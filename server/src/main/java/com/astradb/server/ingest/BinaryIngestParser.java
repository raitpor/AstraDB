package com.astradb.server.ingest;

import com.astradb.client.protocol.BinaryProtocol;
import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 二进制数据流解析（client-design.md 第 5 节）：列式帧 → 列缓冲 → SnapshotData，
 * 类型/nullable 与 schema 对照，交给 core ingest（列数/列类型/非空列 null 校验兜底）。
 */
public final class BinaryIngestParser {

    private BinaryIngestParser() {
    }

    public static SnapshotData parse(InputStream in, Schema schema) throws IOException {
        Frame frame;
        try {
            frame = BinaryProtocol.decode(in);
        } catch (IOException | RuntimeException e) {
            // SS-2：损坏/恶意帧（含 varint 溢出、长度非法、越界等运行时异常）
            // → 400（而非穿透为 500 存储错误）
            throw new SnapshotIngestor.IngestException("二进制数据解析失败: " + e.getMessage());
        }
        int cols = schema.columnCount();
        if (frame.columns().size() != cols) {
            throw new SnapshotIngestor.IngestException(
                    "列数不符：期望 " + cols + "，实际 " + frame.columns().size());
        }
        List<Column> columns = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            ColumnDef def = frame.columns().get(c);
            com.astradb.core.meta.ColumnType t = schema.columns().get(c).type();
            if (toCore(def.type()) != t) {
                throw new SnapshotIngestor.IngestException(
                        "第 " + (c + 1) + " 列类型不符：期望 " + t + "，实际 " + def.type());
            }
            columns.add(buildColumn(t, frame, c));
        }
        return new SnapshotData(columns, frame.rowCount());
    }

    private static Column buildColumn(com.astradb.core.meta.ColumnType type, Frame frame, int col) {
        ColumnDef def = frame.columns().get(col);
        long[] bitmap = frame.data().get(col).nullBitmap();
        Object values = frame.data().get(col).values();
        int rows = frame.rowCount();
        if (def.nullable() && bitmap == null) {
            // 协议声明可空但未写位图：全非空（按列数据行数对齐）
            bitmap = null;
        }
        int eff = values == null ? 0
                : switch (def.type()) {
                    case INT -> ((int[]) values).length;
                    case LONG -> ((long[]) values).length;
                    case DOUBLE -> ((double[]) values).length;
                    case STRING -> ((String[]) values).length;
                };
        boolean allNull = def.nullable() && eff == 0;
        long[] outBitmap = bitmap;
        if (allNull) {
            outBitmap = new long[(rows + 63) / 64];
            java.util.Arrays.fill(outBitmap, -1L); // 全 1
        }
        return switch (type) {
            case INT -> {
                int[] full = new int[rows];
                int[] a = (int[]) values;
                int j = 0;
                for (int i = 0; i < rows; i++) {
                    if (!allNull && !isNull(bitmap, i)) {
                        full[i] = a[j++];
                    }
                }
                yield outBitmap == null ? Column.ofInts(full) : Column.ofInts(full, outBitmap);
            }
            case LONG -> {
                long[] full = new long[rows];
                long[] a = (long[]) values;
                int j = 0;
                for (int i = 0; i < rows; i++) {
                    if (!allNull && !isNull(bitmap, i)) {
                        full[i] = a[j++];
                    }
                }
                yield outBitmap == null ? Column.ofLongs(full) : Column.ofLongs(full, outBitmap);
            }
            case DOUBLE -> {
                double[] full = new double[rows];
                double[] a = (double[]) values;
                int j = 0;
                for (int i = 0; i < rows; i++) {
                    if (!allNull && !isNull(bitmap, i)) {
                        full[i] = a[j++];
                    }
                }
                yield outBitmap == null ? Column.ofDoubles(full) : Column.ofDoubles(full, outBitmap);
            }
            default -> {
                String[] full = new String[rows];
                String[] a = (String[]) values;
                int j = 0;
                for (int i = 0; i < rows; i++) {
                    if (!allNull && !isNull(bitmap, i)) {
                        full[i] = a[j++];
                    }
                }
                yield outBitmap == null ? Column.ofStrings(full) : Column.ofStrings(full, outBitmap);
            }
        };
    }

    private static boolean isNull(long[] bitmap, int row) {
        return bitmap != null && ((bitmap[row >>> 6] >>> (row & 63)) & 1L) != 0;
    }

    private static com.astradb.core.meta.ColumnType toCore(ColumnType t) {
        return switch (t) {
            case INT -> com.astradb.core.meta.ColumnType.INT;
            case LONG -> com.astradb.core.meta.ColumnType.LONG;
            case DOUBLE -> com.astradb.core.meta.ColumnType.DOUBLE;
            case STRING -> com.astradb.core.meta.ColumnType.STRING;
        };
    }
}
