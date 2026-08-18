package com.astradb.server.ingest;

import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;

/**
 * 按列类型构建原始类型数组的缓冲（禁止装箱驻留）。
 */
final class ColumnBuilder {

    private final ColumnType type;
    private int[] ints = new int[4096];
    private long[] longs = new long[4096];
    private double[] doubles = new double[4096];
    private String[] strings = new String[4096];
    private long[] nullBitmap;
    private int size;

    ColumnBuilder(ColumnType type) {
        this.type = type;
    }

    /** 追加字段值；nullable 且字段为空 → 写入 null。 */
    void add(String raw, boolean nullable) {
        if (raw.isEmpty() && nullable) {
            addNull();
            return;
        }
        try {
            switch (type) {
                case INT -> addInt(Integer.parseInt(raw.trim()));
                case LONG -> addLong(Long.parseLong(raw.trim()));
                case DOUBLE -> addDouble(Double.parseDouble(raw.trim()));
                case STRING -> addString(raw);
            }
        } catch (NumberFormatException e) {
            throw new com.astradb.core.ingest.SnapshotIngestor.IngestException(
                    "无法解析为 " + type + ": '" + raw + "'");
        }
    }

    /** 追加一个 null 行（占位值 0/0.0/null 由数组默认值承担）。 */
    void addNull() {
        ensureCapacity();
        if (nullBitmap == null) {
            nullBitmap = new long[(ints.length + 63) / 64]; // 与当前容量对齐（ensureCapacity 已先调用）
        }
        nullBitmap[size >>> 6] |= 1L << (size & 63);
        size++;
    }

    void addInt(int v) {
        ensureCapacity();
        ints[size++] = v;
    }

    void addLong(long v) {
        ensureCapacity();
        longs[size++] = v;
    }

    void addDouble(double v) {
        ensureCapacity();
        doubles[size++] = v;
    }

    void addString(String v) {
        ensureCapacity();
        strings[size++] = v;
    }

    private void ensureCapacity() {
        if (size == ints.length) {
            int cap = ints.length + (ints.length >> 1);
            ints = java.util.Arrays.copyOf(ints, cap);
            longs = java.util.Arrays.copyOf(longs, cap);
            doubles = java.util.Arrays.copyOf(doubles, cap);
            strings = java.util.Arrays.copyOf(strings, cap);
            if (nullBitmap != null) {
                nullBitmap = java.util.Arrays.copyOf(nullBitmap, (cap + 63) / 64);
            }
        }
    }

    Column build() {
        long[] bm = nullBitmap == null ? null : java.util.Arrays.copyOf(nullBitmap, (size + 63) / 64);
        return switch (type) {
            case INT -> Column.ofInts(java.util.Arrays.copyOf(ints, size), bm);
            case LONG -> Column.ofLongs(java.util.Arrays.copyOf(longs, size), bm);
            case DOUBLE -> Column.ofDoubles(java.util.Arrays.copyOf(doubles, size), bm);
            case STRING -> Column.ofStrings(java.util.Arrays.copyOf(strings, size), bm);
        };
    }

    int size() {
        return size;
    }
}
