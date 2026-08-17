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
    private int size;

    ColumnBuilder(ColumnType type) {
        this.type = type;
    }

    void add(String raw) {
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
        }
    }

    Column build() {
        return switch (type) {
            case INT -> Column.ofInts(java.util.Arrays.copyOf(ints, size));
            case LONG -> Column.ofLongs(java.util.Arrays.copyOf(longs, size));
            case DOUBLE -> Column.ofDoubles(java.util.Arrays.copyOf(doubles, size));
            case STRING -> Column.ofStrings(java.util.Arrays.copyOf(strings, size));
        };
    }

    int size() {
        return size;
    }
}
