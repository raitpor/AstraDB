package com.astradb.core;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 测试基座：按场景负载特征构造数据（周期性全量/非固定间隔/点集增长/值漂移/空值密度参数化）。
 * 供场景驱动与属性测试使用。
 */
public final class TestSupport {

    private TestSupport() {
    }

    /** 典型快照 schema：id 主键 + 数值/枚举/文本列（部分可空）。 */
    public static List<Schema.ColumnDef> snapshotColumns() {
        return List.of(
                new Schema.ColumnDef("id", ColumnType.INT, false),
                new Schema.ColumnDef("status", ColumnType.INT, false),
                new Schema.ColumnDef("temp", ColumnType.DOUBLE, true),
                new Schema.ColumnDef("region", ColumnType.STRING, true));
    }

    /** 简单两列 schema（性能/边界场景）。 */
    public static List<Schema.ColumnDef> simpleColumns(boolean nullableValue) {
        return List.of(
                new Schema.ColumnDef("id", ColumnType.INT, false),
                new Schema.ColumnDef("v", ColumnType.DOUBLE, nullableValue));
    }

    /** 生成一个快照的行数据（值随快照序号漂移，点集从 1 到 pointCount）。 */
    public static String csvSnapshot(long seed, int pointCount, int snapshotSeq, boolean withNulls) {
        Random rnd = new Random(seed + snapshotSeq * 31L);
        StringBuilder sb = new StringBuilder(pointCount * 24);
        for (int i = 1; i <= pointCount; i++) {
            boolean nullTemp = withNulls && rnd.nextDouble() < 0.3;
            boolean nullRegion = withNulls && rnd.nextDouble() < 0.2;
            sb.append(i).append(',')
                    .append(rnd.nextInt(500) + 1).append(',')
                    .append(nullTemp ? "" : (20.0 + snapshotSeq * 0.5 + rnd.nextDouble() * 5)).append(',')
                    .append(nullRegion ? "" : ("region-" + (i % 50)))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 将 CSV 文本按 schema 解析为 SnapshotData（空字段 → nullable 列 null）。 */
    public static SnapshotData csv(String csv, List<Schema.ColumnDef> columns) {
        return csv(csv, new Schema(1, columns, 0));
    }

    public static SnapshotData csv(String csv, Schema schema) {
        String[] lines = csv.split("\n", -1);
        int n = lines.length;
        if (n > 0 && lines[n - 1].isEmpty()) {
            n--;
        }
        int cols = schema.columnCount();
        List<Column> out = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            ColumnType type = schema.columns().get(c).type();
            boolean nullable = schema.columns().get(c).nullable();
            long[] bitmap = nullable ? new long[(n + 63) / 64] : null;
            out.add(buildColumn(type, nullable, bitmap, lines, c, n));
        }
        return new SnapshotData(out, n);
    }

    private static Column buildColumn(ColumnType type, boolean nullable, long[] bitmap,
                                      String[] lines, int c, int n) {
        return switch (type) {
            case INT -> {
                int[] v = new int[n];
                for (int i = 0; i < n; i++) {
                    String f = field(lines[i], c);
                    if (f.isEmpty() && nullable) {
                        bitmap[i >>> 6] |= 1L << (i & 63);
                    } else {
                        try {
                            v[i] = Integer.parseInt(f);
                        } catch (NumberFormatException e) {
                            throw new com.astradb.core.ingest.SnapshotIngestor.IngestException(
                                    "第 " + (c + 1) + " 列无法解析为 INT: '" + f + "'");
                        }
                    }
                }
                yield bitmap == null ? Column.ofInts(v) : Column.ofInts(v, bitmap);
            }
            case LONG -> {
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    String f = field(lines[i], c);
                    if (f.isEmpty() && nullable) {
                        bitmap[i >>> 6] |= 1L << (i & 63);
                    } else {
                        try {
                            v[i] = Long.parseLong(f);
                        } catch (NumberFormatException e) {
                            throw new com.astradb.core.ingest.SnapshotIngestor.IngestException(
                                    "第 " + (c + 1) + " 列无法解析为 LONG: '" + f + "'");
                        }
                    }
                }
                yield bitmap == null ? Column.ofLongs(v) : Column.ofLongs(v, bitmap);
            }
            case DOUBLE -> {
                double[] v = new double[n];
                for (int i = 0; i < n; i++) {
                    String f = field(lines[i], c);
                    if (f.isEmpty() && nullable) {
                        bitmap[i >>> 6] |= 1L << (i & 63);
                    } else {
                        try {
                            v[i] = Double.parseDouble(f);
                        } catch (NumberFormatException e) {
                            throw new com.astradb.core.ingest.SnapshotIngestor.IngestException(
                                    "第 " + (c + 1) + " 列无法解析为 DOUBLE: '" + f + "'");
                        }
                    }
                }
                yield bitmap == null ? Column.ofDoubles(v) : Column.ofDoubles(v, bitmap);
            }
            default -> {
                String[] v = new String[n];
                for (int i = 0; i < n; i++) {
                    String f = field(lines[i], c);
                    if (f.isEmpty() && nullable) {
                        bitmap[i >>> 6] |= 1L << (i & 63);
                    } else {
                        v[i] = f;
                    }
                }
                yield bitmap == null ? Column.ofStrings(v) : Column.ofStrings(v, bitmap);
            }
        };
    }

    private static String field(String line, int c) {
        String[] parts = line.split(",", -1);
        if (parts.length <= c) {
            throw new IllegalArgumentException("列数不足: " + line);
        }
        return parts[c];
    }
}
