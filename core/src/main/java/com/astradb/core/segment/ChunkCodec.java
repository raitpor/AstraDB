package com.astradb.core.segment;

import com.astradb.core.codec.CodecRegistry;
import com.astradb.core.codec.ColumnCodec;
import com.astradb.core.codec.DeltaVarintCodec;
import com.astradb.core.compress.Compressor;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.util.ByteBuf;
import com.astradb.core.util.ByteReader;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Chunk 编解码（design.md 6.2）。
 * <p>
 * 布局（格式 v2，生产读写）：
 * <pre>
 *   [0]    timestamp(8B)
 *   [8]    rowCount(4B)
 *   [12]   schemaVersion(2B)
 *   [14]   columnCount(2B)
 *   [16]   列偏移表: n × [offset(4B) length(4B) codecId(1B) flags(1B)]  // offset 相对 chunk 起点
 *   列数据区: 每列 = [uncompressedLen varint][zstd payload]
 *     payload = [nullBitmap(ceil(n/8) 字节，仅 flags bit0=1)] + [有效值编码]
 *     可空列编码"有效值序列"（跳过 null 行），位图标记 null 位置，压缩不因占位退化；
 *     全非空列 flags=0、不写位图（零空间开销）。
 *   尾部:   CRC32C(4B)，覆盖 [0, len-4)
 * </pre>
 */
public final class ChunkCodec {

    public static final int HEADER_FIXED = 16;
    public static final int COL_TABLE_ENTRY = 10;
    public static final int CRC_BYTES = 4;
    public static final int FLAG_HAS_NULL_BITMAP = 1;

    private ChunkCodec() {
    }

    /** 编码（格式 v2：列块 = [列类型 1B][uncompressedLen][zstd(位图(可选) + 有效值编码)]）。 */
    public static byte[] encode(Chunk chunk, Compressor compressor) {
        int entrySize = COL_TABLE_ENTRY;
        int n = chunk.columnCount();
        int headerSize = HEADER_FIXED + entrySize * n;

        byte[][] colBlocks = new byte[n][];
        byte[] flags = new byte[n];
        int dataSize = 0;
        for (int i = 0; i < n; i++) {
            Column col = chunk.column(i);
            ColumnCodec codec = CodecRegistry.of(CodecRegistry.idOf(col.type()));
            // 类型字节放在 zstd 流之外：避免破坏 zstd 块内压缩（type 进流会触发跨块边界、压缩率暴跌）
            ByteBuf payloadBuf = new ByteBuf(16);
            if (col.hasNullBitmap()) {
                // 可空列：位图 + 有效值序列
                byte[] bitmapBytes = bitmapToBytes(col.nullBitmap(), chunk.rowCount());
                Column compact = col.compact();
                byte[] encoded;
                if (compact == null || compact.rowCount() == 0) {
                    encoded = new byte[0]; // 全 null：无有效值
                } else {
                    encoded = codec.encode(compact);
                }
                payloadBuf.writeBytes(bitmapBytes);
                payloadBuf.writeBytes(encoded);
                flags[i] = FLAG_HAS_NULL_BITMAP;
            } else {
                payloadBuf.writeBytes(codec.encode(col));
            }
            byte[] payload = payloadBuf.toArray();
            byte[] compressed = compressor.compress(payload);
            ByteBuf block = new ByteBuf(16 + compressed.length);
            block.writeByte(col.type().ordinal());
            block.writeUInt(payload.length);
            block.writeBytes(compressed);
            colBlocks[i] = block.toArray();
            dataSize += colBlocks[i].length;
        }

        ByteBuf out = new ByteBuf(headerSize + dataSize + CRC_BYTES);
        out.writeLong(chunk.timestamp());
        out.writeInt(chunk.rowCount());
        out.writeShort(chunk.schemaVersion());
        out.writeShort(n);
        int offset = headerSize;
        for (int i = 0; i < n; i++) {
            out.writeInt(offset);
            out.writeInt(colBlocks[i].length);
            out.writeByte(CodecRegistry.idOf(chunk.column(i).type()));
            out.writeByte(flags[i]);
            offset += colBlocks[i].length;
        }
        for (byte[] b : colBlocks) {
            out.writeBytes(b);
        }

        byte[] body = out.toArray();
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        byte[] result = new byte[body.length + CRC_BYTES];
        System.arraycopy(body, 0, result, 0, body.length);
        int crcValue = (int) crc.getValue();
        result[body.length] = (byte) (crcValue >>> 24);
        result[body.length + 1] = (byte) (crcValue >>> 16);
        result[body.length + 2] = (byte) (crcValue >>> 8);
        result[body.length + 3] = (byte) crcValue;
        return result;
    }

    /** 解码整块（格式 v2）。 */
    public static Chunk decode(byte[] data, Compressor compressor) {
        ByteReader in = new ByteReader(data);
        long timestamp = in.readLong();
        int rowCount = in.readInt();
        int schemaVersion = in.readShort();
        int n = in.readShort();
        int[] offsets = new int[n];
        int[] lengths = new int[n];
        byte[] codecIds = new byte[n];
        byte[] flags = new byte[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = in.readInt();
            lengths[i] = in.readInt();
            codecIds[i] = (byte) in.readByte();
            flags[i] = (byte) in.readByte();
        }
        List<Column> columns = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ColumnType type = ColumnType.values()[data[offsets[i]] & 0xFF];
            byte[] payload = decodeColumnBlockRaw(data, offsets[i], lengths[i], compressor);
            columns.add(decodeColumnPayload(payload, codecIds[i], flags[i], rowCount, 0, rowCount, type));
        }
        return new Chunk(timestamp, schemaVersion, columns);
    }

    /** 只解码指定列（单点查询按需取列）。 */
    public static Column decodeColumn(byte[] data, int columnIndex, Compressor compressor) {
        RawColumn rc = decodeRawColumn(data, columnIndex, compressor);
        if (rc.nullBitmap() == null) {
            return CodecRegistry.of(rc.codecId()).decode(rc.raw());
        }
        if (rc.raw().length == 0) {
            return allNullColumn(rc.columnType(), rc.totalRows());
        }
        return expand(CodecRegistry.of(rc.codecId()).decode(rc.raw()), rc.nullBitmap(), rc.totalRows());
    }

    /** 区间解码指定列 [from, to) 行（分页查询按需解码，不构造整列数组）。 */
    public static Column decodeColumnRange(byte[] data, int columnIndex, int from, int to,
                                           Compressor compressor) {
        RawColumn rc = decodeRawColumn(data, columnIndex, compressor);
        if (rc.nullBitmap() == null) {
            return CodecRegistry.of(rc.codecId()).decodeRange(rc.raw(), from, to);
        }
        // 原始行号 → 有效值索引（剔除 [0, from) 的 null）
        if (rc.raw().length == 0) {
            // 全 null 列：直接构造目标区间全 null 子列
            return allNullColumn(rc.columnType(), to - from);
        }
        int p1 = prefixNull(rc.nullBitmap(), from);
        int p2 = prefixNull(rc.nullBitmap(), to);
        Column eff = CodecRegistry.of(rc.codecId()).decodeRange(rc.raw(), from - p1, to - p2);
        return expandRange(eff, rc.nullBitmap(), from, to);
    }

    /** 公开：按列索引取 zstd 解压后的原始字节（供 ChunkCache 复用；raw 为有效值编码，位图独立）。 */
    public static RawColumn rawColumnAt(byte[] data, int columnIndex, Compressor compressor) {
        return decodeRawColumn(data, columnIndex, compressor);
    }

    /** 取指定列第 row 行值（zstd 解压该列块后按需解码，不构造整列数组）；null 行返回 null。 */
    public static Object valueColumnAt(byte[] data, int columnIndex, int row, Compressor compressor) {
        RawColumn rc = decodeRawColumn(data, columnIndex, compressor);
        if (rc.nullBitmap() == null) {
            return CodecRegistry.of(rc.codecId()).valueAt(rc.raw(), row);
        }
        if (isNull(rc.nullBitmap(), row)) {
            return null;
        }
        return CodecRegistry.of(rc.codecId()).valueAt(rc.raw(), row - prefixNull(rc.nullBitmap(), row));
    }

    /** 已解压 RawColumn 上取第 row 行值（位图感知，null 行返回 null；供已缓存 rc 复用）。 */
    public static Object valueAtRaw(RawColumn rc, int row) {
        if (rc.nullBitmap() == null) {
            return CodecRegistry.of(rc.codecId()).valueAt(rc.raw(), row);
        }
        if (isNull(rc.nullBitmap(), row)) {
            return null;
        }
        return CodecRegistry.of(rc.codecId()).valueAt(rc.raw(), row - prefixNull(rc.nullBitmap(), row));
    }

    /** 主键列（第 0 列，DeltaVarint 有序，非空）查找目标 pointId 行号；不存在返回 -1。 */
    public static int findPrimaryKeyRow(byte[] data, int pointId, Compressor compressor) {
        RawColumn rc = decodeRawColumn(data, 0, compressor);
        return new DeltaVarintCodec().findRow(rc.raw(), pointId);
    }

    public record RawColumn(byte[] raw, byte codecId, ColumnType columnType, long[] nullBitmap, int totalRows) {
    }

    // ---- 内部：列块解析与 null 位图展开 ----

    private static RawColumn decodeRawColumn(byte[] data, int columnIndex, Compressor compressor) {
        ByteReader in = new ByteReader(data);
        in.readLong();      // timestamp
        int rowCount = in.readInt();
        in.readShort();     // schemaVersion
        int n = in.readShort();
        if (columnIndex < 0 || columnIndex >= n) {
            throw new IllegalArgumentException("列索引越界: " + columnIndex);
        }
        int[] offsets = new int[n];
        int[] lengths = new int[n];
        byte[] codecIds = new byte[n];
        byte[] flags = new byte[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = in.readInt();
            lengths[i] = in.readInt();
            codecIds[i] = (byte) in.readByte();
            flags[i] = (byte) in.readByte();
        }
        int typeOrdinal = data[offsets[columnIndex]] & 0xFF; // 列块首字节 = 列类型（zstd 流外）
        byte[] payload = decodeColumnBlockRaw(data, offsets[columnIndex], lengths[columnIndex], compressor);
        byte[] raw;
        long[] bitmap = null;
        if ((flags[columnIndex] & FLAG_HAS_NULL_BITMAP) != 0) {
            int bmLen = (rowCount + 7) / 8;
            bitmap = bitmapFromBytes(payload, bmLen, rowCount);
            raw = new byte[payload.length - bmLen];
            System.arraycopy(payload, bmLen, raw, 0, raw.length);
        } else {
            raw = payload;
        }
        return new RawColumn(raw, codecIds[columnIndex], ColumnType.values()[typeOrdinal], bitmap, rowCount);
    }

    private static Column decodeColumnPayload(byte[] payload, byte codecId, byte flags, int totalRows,
                                              int from, int to, ColumnType type) {
        long[] bitmap = null;
        byte[] raw;
        if ((flags & FLAG_HAS_NULL_BITMAP) != 0) {
            int bmLen = (totalRows + 7) / 8;
            bitmap = bitmapFromBytes(payload, bmLen, totalRows);
            raw = new byte[payload.length - bmLen];
            System.arraycopy(payload, bmLen, raw, 0, raw.length);
        } else {
            raw = payload;
        }
        ColumnCodec codec = CodecRegistry.of(codecId);
        if (bitmap == null) {
            return codec.decodeRange(raw, from, to);
        }
        if (raw.length == 0) {
            // 全 null 列：无有效值编码
            return allNullColumn(type, totalRows);
        }
        Column eff = codec.decode(raw);
        if (from == 0 && to == totalRows) {
            return expand(eff, bitmap, totalRows);
        }
        return expandRange(eff, bitmap, from, to);
    }

    /** 有效值子列 → 完整列（null 行占位 + 位图）。 */
    public static Column expand(Column eff, long[] bitmap, int totalRows) {
        ColumnType t = eff.type();
        int j = 0;
        switch (t) {
            case INT -> {
                int[] full = new int[totalRows];
                for (int i = 0; i < totalRows; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i] = eff.ints()[j++];
                    }
                }
                return Column.ofInts(full, bitmap);
            }
            case LONG -> {
                long[] full = new long[totalRows];
                for (int i = 0; i < totalRows; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i] = eff.longs()[j++];
                    }
                }
                return Column.ofLongs(full, bitmap);
            }
            case DOUBLE -> {
                double[] full = new double[totalRows];
                for (int i = 0; i < totalRows; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i] = eff.doubles()[j++];
                    }
                }
                return Column.ofDoubles(full, bitmap);
            }
            default -> {
                String[] full = new String[totalRows];
                for (int i = 0; i < totalRows; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i] = eff.strings()[j++];
                    }
                }
                return Column.ofStrings(full, bitmap);
            }
        }
    }

    /** 区间展开：[from, to) 目标区间，null 行占位，有效值按序填入。 */
    public static Column expandRange(Column eff, long[] bitmap, int from, int to) {
        int len = to - from;
        ColumnType t = eff.type();
        int j = 0;
        switch (t) {
            case INT -> {
                int[] full = new int[len];
                for (int i = from; i < to; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i - from] = eff.ints()[j++];
                    }
                }
                return Column.ofInts(full, sliceBitmap(bitmap, from, to));
            }
            case LONG -> {
                long[] full = new long[len];
                for (int i = from; i < to; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i - from] = eff.longs()[j++];
                    }
                }
                return Column.ofLongs(full, sliceBitmap(bitmap, from, to));
            }
            case DOUBLE -> {
                double[] full = new double[len];
                for (int i = from; i < to; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i - from] = eff.doubles()[j++];
                    }
                }
                return Column.ofDoubles(full, sliceBitmap(bitmap, from, to));
            }
            default -> {
                String[] full = new String[len];
                for (int i = from; i < to; i++) {
                    if (!isNull(bitmap, i)) {
                        full[i - from] = eff.strings()[j++];
                    }
                }
                return Column.ofStrings(full, sliceBitmap(bitmap, from, to));
            }
        }
    }

    /** 全 null 列：占位数组 + 全 1 位图（每行都 null）。 */
    public static Column allNullColumnPublic(ColumnType t, int totalRows) {
        return allNullColumn(t, totalRows);
    }

    private static Column allNullColumn(ColumnType t, int totalRows) {
        long[] fullOne = new long[(totalRows + 63) / 64];
        java.util.Arrays.fill(fullOne, -1L); // 全 1
        return switch (t) {
            case INT -> Column.ofInts(new int[totalRows], fullOne);
            case LONG -> Column.ofLongs(new long[totalRows], fullOne);
            case DOUBLE -> Column.ofDoubles(new double[totalRows], fullOne);
            case STRING -> Column.ofStrings(new String[totalRows], fullOne);
        };
    }

    // ---- null 位图工具（long[]，bit i = 行 i） ----

    public static boolean isNull(long[] bitmap, int row) {
        return bitmap != null && ((bitmap[row >>> 6] >>> (row & 63)) & 1L) != 0;
    }

    /** [0, end) 行中 null 行数（原始行 → 有效值索引换算）。 */
    public static int prefixNull(long[] bitmap, int end) {
        if (bitmap == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < end; i++) {
            if (((bitmap[i >>> 6] >>> (i & 63)) & 1L) != 0) {
                count++;
            }
        }
        return count;
    }

    /** 位图切片（行 [from, to) → 新位图，行 0 = 原 from）。 */
    public static long[] sliceBitmap(long[] bitmap, int from, int to) {
        int len = to - from;
        long[] out = new long[(len + 63) / 64];
        for (int i = 0; i < len; i++) {
            if (isNull(bitmap, from + i)) {
                out[i >>> 6] |= 1L << (i & 63);
            }
        }
        return out;
    }

    public static byte[] bitmapToBytes(long[] bitmap, int rows) {
        byte[] b = new byte[(rows + 7) / 8];
        for (int i = 0; i < rows; i++) {
            if (isNull(bitmap, i)) {
                b[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return b;
    }

    public static long[] bitmapFromBytes(byte[] b, int bmLen, int rows) {
        long[] out = new long[(rows + 63) / 64];
        for (int i = 0; i < rows; i++) {
            if ((b[i >>> 3] & (1 << (i & 7))) != 0) {
                out[i >>> 6] |= 1L << (i & 63);
            }
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] decodeColumnBlockRaw(byte[] data, int offset, int length, Compressor compressor) {
        ByteReader in = new ByteReader(data, offset);
        in.readByte(); // 列块首字节 = 列类型（v2；v1 对比编码无此字节，但生产仅读 v2）
        int uncompressedSize = (int) in.readUInt();
        byte[] compressed = new byte[length - (in.position() - offset)];
        System.arraycopy(data, in.position(), compressed, 0, compressed.length);
        return compressor.decompress(compressed, uncompressedSize);
    }

    /** 读取 chunk 时间戳（不解压）。 */
    public static long timestampOf(byte[] data) {
        return ((data[0] & 0xFFL) << 56) | ((data[1] & 0xFFL) << 48)
                | ((data[2] & 0xFFL) << 40) | ((data[3] & 0xFFL) << 32)
                | ((data[4] & 0xFFL) << 24) | ((data[5] & 0xFFL) << 16)
                | ((data[6] & 0xFFL) << 8) | (data[7] & 0xFFL);
    }

    /** 读取 chunk 行数。 */
    public static int rowCountOf(byte[] data) {
        return ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16)
                | ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
    }

    /** 读取 chunk 列数。 */
    public static int columnCountOf(byte[] data) {
        return ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);
    }

    /** 由列偏移表计算 chunk 总长（含 CRC），崩溃恢复定位用。仅需头 + 列偏移表。 */
    public static int chunkLength(byte[] data, int available) {
        if (available < HEADER_FIXED + COL_TABLE_ENTRY) {
            return -1;
        }
        int n = ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);
        int tableSize = COL_TABLE_ENTRY * n;
        if (available < HEADER_FIXED + tableSize) {
            return -1;
        }
        int base = HEADER_FIXED + tableSize;
        long lastOffset = 0;
        long lastLength = 0;
        for (int i = 0; i < n; i++) {
            int p = HEADER_FIXED + i * COL_TABLE_ENTRY;
            lastOffset = ((data[p] & 0xFFL) << 24) | ((data[p + 1] & 0xFFL) << 16)
                    | ((data[p + 2] & 0xFFL) << 8) | (data[p + 3] & 0xFFL);
            lastLength = ((data[p + 4] & 0xFFL) << 24) | ((data[p + 5] & 0xFFL) << 16)
                    | ((data[p + 6] & 0xFFL) << 8) | (data[p + 7] & 0xFFL);
        }
        long end = lastOffset + lastLength + CRC_BYTES;
        if (end < base || end > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) end;
    }

    /** 校验 chunk 尾部 CRC32C；返回 true 表示 chunk 数据完整。 */
    public static boolean checkCrc(byte[] data, int len) {
        if (len < HEADER_FIXED + CRC_BYTES || len > data.length) {
            return false;
        }
        CRC32C crc = new CRC32C();
        crc.update(data, 0, len - CRC_BYTES);
        int expected = (int) crc.getValue();
        int stored = ((data[len - 4] & 0xFF) << 24) | ((data[len - 3] & 0xFF) << 16)
                | ((data[len - 2] & 0xFF) << 8) | (data[len - 1] & 0xFF);
        return expected == stored;
    }
}
