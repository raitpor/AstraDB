package com.astradb.client.protocol;

import com.astradb.client.io.BinaryReader;
import com.astradb.client.io.BinaryWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 列式二进制数据流协议（client-design.md 第 4 节）。
 * <pre>
 * magic('ASDB' 4B) + version(1B=1) + flags(1B, 预留压缩位) + columnCount(2B LE)
 * 每列定义: columnName(varint len + UTF-8) + columnType(1B: 1=INT,2=LONG,3=DOUBLE,4=STRING) + nullable(1B)
 * rowCount(varint)
 * 每列数据（与存储列缓冲对齐）:
 *   nullable 列: nullBitmap(ceil(rowCount/8) 字节, bit i = 行 i 是否 null)
 *   有效值序列: INT int32 / LONG int64 / DOUBLE float64 / STRING (varint len + UTF-8)，逐值
 * </pre>
 * 用于 client ↔ server 数据路径（导入 / 全量查询），元数据 API 保持 JSON。
 */
public final class BinaryProtocol {

    public static final byte[] MAGIC = "ASDB".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;

    public enum ColumnType {
        INT(1), LONG(2), DOUBLE(3), STRING(4);

        private final int id;

        ColumnType(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static ColumnType of(int id) {
            return switch (id) {
                case 1 -> INT;
                case 2 -> LONG;
                case 3 -> DOUBLE;
                case 4 -> STRING;
                default -> throw new IllegalArgumentException("未知列类型 ID: " + id);
            };
        }
    }

    public record ColumnDef(String name, ColumnType type, boolean nullable) {
    }

    /** 列数据：nullBitmap（可为 null=全非空）+ 有效值数组（int[]/long[]/double[]/String[]，不含 null 行）。 */
    public record ColumnData(long[] nullBitmap, Object values) {
    }

    public record Frame(List<ColumnDef> columns, int rowCount, List<ColumnData> data) {
    }

    private BinaryProtocol() {
    }

    // ---- 编码 ----

    public static void encode(Frame frame, OutputStream out) throws IOException {
        BinaryWriter w = new BinaryWriter(out);
        w.writeBytes(MAGIC);
        w.writeByte(VERSION);
        w.writeByte(0); // flags：压缩预留（默认无）
        w.writeShort(frame.columns().size());
        for (ColumnDef def : frame.columns()) {
            w.writeString(def.name());
            w.writeByte(def.type().id());
            w.writeByte(def.nullable() ? 1 : 0);
        }
        w.writeVarInt(frame.rowCount());
        for (int c = 0; c < frame.columns().size(); c++) {
            ColumnDef def = frame.columns().get(c);
            ColumnData data = frame.data().get(c);
            if (def.nullable()) {
                w.writeBytes(bitmapToBytes(data.nullBitmap(), frame.rowCount()));
            }
            writeValues(w, def.type(), data.values());
        }
        out.flush();
    }

    private static void writeValues(BinaryWriter w, ColumnType type, Object values) throws IOException {
        switch (type) {
            case INT -> {
                int[] a = (int[]) values;
                for (int v : a) {
                    w.writeInt(v);
                }
            }
            case LONG -> {
                long[] a = (long[]) values;
                for (long v : a) {
                    w.writeLong(v);
                }
            }
            case DOUBLE -> {
                double[] a = (double[]) values;
                for (double v : a) {
                    w.writeLong(Double.doubleToRawLongBits(v));
                }
            }
            case STRING -> {
                String[] a = (String[]) values;
                for (String v : a) {
                    w.writeString(v);
                }
            }
        }
    }

    // ---- 解码 ----

    public static Frame decode(InputStream in) throws IOException {
        BinaryReader r = new BinaryReader(in);
        byte[] magic = r.readBytes(4);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("非法二进制协议头（magic 不符）");
        }
        int version = r.readByte();
        if (version != VERSION) {
            throw new IOException("二进制协议版本不兼容: " + version);
        }
        r.readByte(); // flags（预留）
        int columnCount = r.readShort();
        if (columnCount <= 0 || columnCount > 1024) {
            throw new IOException("非法列数: " + columnCount);
        }
        List<ColumnDef> columns = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            String name = r.readString();
            ColumnType type = ColumnType.of(r.readByte());
            boolean nullable = r.readByte() == 1;
            columns.add(new ColumnDef(name, type, nullable));
        }
        long rowCountLong = r.readVarInt();
        if (rowCountLong < 0 || rowCountLong > Integer.MAX_VALUE) {
            throw new IOException("非法行数: " + rowCountLong);
        }
        int rowCount = (int) rowCountLong;
        List<ColumnData> data = new ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) {
            ColumnDef def = columns.get(c);
            long[] bitmap = null;
            if (def.nullable()) {
                bitmap = readBitmap(r, rowCount);
            }
            data.add(new ColumnData(bitmap, readValues(r, def.type(), rowCount, bitmap)));
        }
        return new Frame(columns, rowCount, data);
    }

    private static Object readValues(BinaryReader r, ColumnType type, int rowCount, long[] bitmap)
            throws IOException {
        int eff = rowCount - (bitmap == null ? 0 : popcount(bitmap, rowCount));
        return switch (type) {
            case INT -> {
                int[] a = new int[eff];
                for (int i = 0; i < eff; i++) {
                    a[i] = r.readInt();
                }
                yield a;
            }
            case LONG -> {
                long[] a = new long[eff];
                for (int i = 0; i < eff; i++) {
                    a[i] = r.readLong();
                }
                yield a;
            }
            case DOUBLE -> {
                double[] a = new double[eff];
                for (int i = 0; i < eff; i++) {
                    a[i] = Double.longBitsToDouble(r.readLong());
                }
                yield a;
            }
            case STRING -> {
                String[] a = new String[eff];
                for (int i = 0; i < eff; i++) {
                    a[i] = r.readString();
                }
                yield a;
            }
        };
    }

    // ---- 行 → 列式转换（client 导入侧） ----

    /**
     * 从行数据推断列定义：列类型取该列首个非 null 元素（Integer→INT 等），
     * 含 null 元素 → nullable=true。
     */
    public static List<ColumnDef> inferColumns(List<List<Object>> rows, List<String> names) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }
        int cols = rows.getFirst().size();
        List<ColumnDef> defs = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            String name = names != null && c < names.size() ? names.get(c) : "";
            ColumnType type = null;
            boolean nullable = false;
            for (List<Object> row : rows) {
                if (row.size() != cols) {
                    throw new IllegalArgumentException("行数据列数不一致: " + row.size() + " != " + cols);
                }
                Object v = row.get(c);
                if (v == null) {
                    nullable = true;
                    continue;
                }
                ColumnType t = typeOf(v);
                if (type == null) {
                    type = t;
                } else if (type != t) {
                    throw new IllegalArgumentException("第 " + (c + 1) + " 列类型不一致: " + type + " vs " + t);
                }
            }
            if (type == null) {
                type = ColumnType.STRING; // 全 null 列兜底（调用方可经 forcedTypes 按 schema 覆盖）
            }
            defs.add(new ColumnDef(name, type, nullable));
        }
        return defs;
    }

    public static ColumnType typeOf(Object v) {
        if (v instanceof Integer) {
            return ColumnType.INT;
        }
        if (v instanceof Long) {
            return ColumnType.LONG;
        }
        if (v instanceof Double || v instanceof Float) {
            return ColumnType.DOUBLE;
        }
        if (v instanceof String) {
            return ColumnType.STRING;
        }
        throw new IllegalArgumentException("不支持的数据类型: " + (v == null ? "null" : v.getClass().getName()));
    }

    /** 行数据 → 帧（含推断类型/nullable 与列式有效值 + 位图）。 */
    public static Frame encodeRows(List<List<Object>> rows, List<String> columnNames) {
        return encodeRows(rows, columnNames, null);
    }

    /**
     * 行数据 → 帧。forcedTypes 非 null 时覆盖每列类型（用于全 null 列：类型无法从数据推断，
     * 由调用方按 schema 类型指定），长度须与列数一致或为 null。
     */
    public static Frame encodeRows(List<List<Object>> rows, List<String> columnNames,
                                   List<ColumnType> forcedTypes) {
        List<ColumnDef> defs = inferColumns(rows, columnNames);
        if (forcedTypes != null) {
            if (forcedTypes.size() != defs.size()) {
                throw new IllegalArgumentException("forcedTypes 与列数不一致");
            }
            for (int c = 0; c < defs.size(); c++) {
                ColumnType t = defs.get(c).type();
                if (t == null || forcedTypes.get(c) != null && forcedTypes.get(c) != t) {
                    defs.set(c, new ColumnDef(defs.get(c).name(), forcedTypes.get(c), defs.get(c).nullable()));
                }
            }
        }
        int rowCount = rows.size();
        int cols = defs.size();
        List<ColumnData> data = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            ColumnDef def = defs.get(c);
            long[] bitmap = def.nullable() ? new long[(rowCount + 63) / 64] : null;
            int eff = 0;
            for (int i = 0; i < rowCount; i++) {
                Object v = rows.get(i).get(c);
                if (v == null) {
                    bitmap[i >>> 6] |= 1L << (i & 63);
                } else {
                    eff++;
                }
            }
            data.add(new ColumnData(bitmap, buildValues(def.type(), rows, c, eff)));
        }
        return new Frame(defs, rowCount, data);
    }

    private static Object buildValues(ColumnType type, List<List<Object>> rows, int col, int eff) {
        return switch (type) {
            case INT -> {
                int[] a = new int[eff];
                int j = 0;
                for (List<Object> row : rows) {
                    Object v = row.get(col);
                    if (v != null) {
                        a[j++] = ((Integer) v);
                    }
                }
                yield a;
            }
            case LONG -> {
                long[] a = new long[eff];
                int j = 0;
                for (List<Object> row : rows) {
                    Object v = row.get(col);
                    if (v != null) {
                        a[j++] = ((Number) v).longValue();
                    }
                }
                yield a;
            }
            case DOUBLE -> {
                double[] a = new double[eff];
                int j = 0;
                for (List<Object> row : rows) {
                    Object v = row.get(col);
                    if (v != null) {
                        a[j++] = ((Number) v).doubleValue();
                    }
                }
                yield a;
            }
            default -> {
                String[] a = new String[eff];
                int j = 0;
                for (List<Object> row : rows) {
                    Object v = row.get(col);
                    if (v != null) {
                        a[j++] = (String) v;
                    }
                }
                yield a;
            }
        };
    }

    // ---- null 位图工具 ----

    public static byte[] bitmapToBytes(long[] bitmap, int rows) {
        byte[] b = new byte[(rows + 7) / 8];
        for (int i = 0; i < rows; i++) {
            if (((bitmap[i >>> 6] >>> (i & 63)) & 1L) != 0) {
                b[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return b;
    }

    public static long[] readBitmap(BinaryReader r, int rows) throws IOException {
        byte[] b = r.readBytes((rows + 7) / 8);
        long[] out = new long[(rows + 63) / 64];
        for (int i = 0; i < rows; i++) {
            if ((b[i >>> 3] & (1 << (i & 7))) != 0) {
                out[i >>> 6] |= 1L << (i & 63);
            }
        }
        return out;
    }

    public static boolean isNull(long[] bitmap, int row) {
        return bitmap != null && ((bitmap[row >>> 6] >>> (row & 63)) & 1L) != 0;
    }

    public static int popcount(long[] bitmap, int rows) {
        int n = 0;
        for (int i = 0; i < rows; i++) {
            if (isNull(bitmap, i)) {
                n++;
            }
        }
        return n;
    }
}
