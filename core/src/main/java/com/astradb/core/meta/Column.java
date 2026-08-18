package com.astradb.core.meta;

import java.util.Arrays;
import java.util.Objects;

/**
 * 列数据缓冲：按类型使用原始类型数组存储，禁止装箱对象驻留。
 * 每个实例仅持有与 {@link ColumnType} 对应的一个数组。
 */
public final class Column {

    private final ColumnType type;
    private final int rowCount;
    private final int[] intValues;
    private final long[] longValues;
    private final double[] doubleValues;
    private final String[] stringValues;
    /** null 位图：bit i = 行 i 是否 null（null 表示全非空）。 */
    private final long[] nullBitmap;

    private Column(ColumnType type, int rowCount,
                   int[] intValues, long[] longValues, double[] doubleValues, String[] stringValues,
                   long[] nullBitmap) {
        this.type = type;
        this.rowCount = rowCount;
        this.intValues = intValues;
        this.longValues = longValues;
        this.doubleValues = doubleValues;
        this.stringValues = stringValues;
        this.nullBitmap = nullBitmap;
    }

    public static Column ofInts(int[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.INT, values.length, values, null, null, null, null);
    }

    public static Column ofInts(int[] values, long[] nullBitmap) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.INT, values.length, values, null, null, null, normalize(values.length, nullBitmap));
    }

    public static Column ofLongs(long[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.LONG, values.length, null, values, null, null, null);
    }

    public static Column ofLongs(long[] values, long[] nullBitmap) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.LONG, values.length, null, values, null, null, normalize(values.length, nullBitmap));
    }

    public static Column ofDoubles(double[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.DOUBLE, values.length, null, null, values, null, null);
    }

    public static Column ofDoubles(double[] values, long[] nullBitmap) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.DOUBLE, values.length, null, null, values, null, normalize(values.length, nullBitmap));
    }

    public static Column ofStrings(String[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.STRING, values.length, null, null, null, values, null);
    }

    public static Column ofStrings(String[] values, long[] nullBitmap) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.STRING, values.length, null, null, null, values, normalize(values.length, nullBitmap));
    }

    /** 全 0 位图（无实际 null）规范化为 null，避免白写位图与 equals 不一致。 */
    private static long[] normalize(int rows, long[] bitmap) {
        if (bitmap == null) {
            return null;
        }
        for (long w : bitmap) {
            if (w != 0) {
                return bitmap;
            }
        }
        return null;
    }

    public ColumnType type() {
        return type;
    }

    public int rowCount() {
        return rowCount;
    }

    public int intAt(int i) {
        return intValues[i];
    }

    public long longAt(int i) {
        return longValues[i];
    }

    public double doubleAt(int i) {
        return doubleValues[i];
    }

    public String stringAt(int i) {
        return stringValues[i];
    }

    /** 原始数组访问（仅当类型匹配时非 null），供编码器内部循环使用。 */
    public int[] ints() {
        return intValues;
    }

    public long[] longs() {
        return longValues;
    }

    public double[] doubles() {
        return doubleValues;
    }

    public String[] strings() {
        return stringValues;
    }

    /** 行 i 是否为 null。 */
    public boolean isNull(int i) {
        return nullBitmap != null && ((nullBitmap[i >>> 6] >>> (i & 63)) & 1L) != 0;
    }

    /** 是否声明了 null 位图（可能全 0；仅 nullable 列可能有）。 */
    public boolean hasNullBitmap() {
        return nullBitmap != null;
    }

    public long[] nullBitmap() {
        return nullBitmap;
    }

    /** 按行号取任意值（解码/查询层用）；null 行返回 null。 */
    public Object valueAt(int i) {
        if (isNull(i)) {
            return null;
        }
        return switch (type) {
            case INT -> intValues[i];
            case LONG -> longValues[i];
            case DOUBLE -> doubleValues[i];
            case STRING -> stringValues[i];
        };
    }

    /** 提取非 null 行子列（有效值序列，供 nullable 列编码）；全 null 返回 null；全非空返回自身。 */
    public Column compact() {
        if (nullBitmap == null) {
            return this;
        }
        int k = 0;
        for (int i = 0; i < rowCount; i++) {
            if (!isNull(i)) {
                k++;
            }
        }
        if (k == rowCount) {
            return this;
        }
        if (k == 0) {
            return null;
        }
        return switch (type) {
            case INT -> {
                int[] out = new int[k];
                int j = 0;
                for (int i = 0; i < rowCount; i++) {
                    if (!isNull(i)) {
                        out[j++] = intValues[i];
                    }
                }
                yield Column.ofInts(out);
            }
            case LONG -> {
                long[] out = new long[k];
                int j = 0;
                for (int i = 0; i < rowCount; i++) {
                    if (!isNull(i)) {
                        out[j++] = longValues[i];
                    }
                }
                yield Column.ofLongs(out);
            }
            case DOUBLE -> {
                double[] out = new double[k];
                int j = 0;
                for (int i = 0; i < rowCount; i++) {
                    if (!isNull(i)) {
                        out[j++] = doubleValues[i];
                    }
                }
                yield Column.ofDoubles(out);
            }
            case STRING -> {
                String[] out = new String[k];
                int j = 0;
                for (int i = 0; i < rowCount; i++) {
                    if (!isNull(i)) {
                        out[j++] = stringValues[i];
                    }
                }
                yield Column.ofStrings(out);
            }
        };
    }

    /** 按给定行号排列重排本列（导入排序用）。 */
    public Column permute(int[] order) {
        ColumnType t = type;
        long[] outBitmap = nullBitmap == null ? null : new long[nullBitmap.length];
        if (t == ColumnType.INT) {
            int[] out = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                out[i] = intValues[order[i]];
                if (outBitmap != null && isNull(order[i])) {
                    outBitmap[i >>> 6] |= 1L << (i & 63);
                }
            }
            return outBitmap == null ? Column.ofInts(out) : Column.ofInts(out, outBitmap);
        }
        if (t == ColumnType.LONG) {
            long[] out = new long[rowCount];
            for (int i = 0; i < rowCount; i++) {
                out[i] = longValues[order[i]];
                if (outBitmap != null && isNull(order[i])) {
                    outBitmap[i >>> 6] |= 1L << (i & 63);
                }
            }
            return outBitmap == null ? Column.ofLongs(out) : Column.ofLongs(out, outBitmap);
        }
        if (t == ColumnType.DOUBLE) {
            double[] out = new double[rowCount];
            for (int i = 0; i < rowCount; i++) {
                out[i] = doubleValues[order[i]];
                if (outBitmap != null && isNull(order[i])) {
                    outBitmap[i >>> 6] |= 1L << (i & 63);
                }
            }
            return outBitmap == null ? Column.ofDoubles(out) : Column.ofDoubles(out, outBitmap);
        }
        String[] out = new String[rowCount];
        for (int i = 0; i < rowCount; i++) {
            out[i] = stringValues[order[i]];
            if (outBitmap != null && isNull(order[i])) {
                outBitmap[i >>> 6] |= 1L << (i & 63);
            }
        }
        return outBitmap == null ? Column.ofStrings(out) : Column.ofStrings(out, outBitmap);
    }

    @Override
    public String toString() {
        return "Column{" + type + ", rows=" + rowCount
                + (rowCount > 0 ? ", first=" + valueAt(0) : "") + "}";
    }

    // ---- 测试辅助 ----

    public boolean equalsValues(Column other) {
        if (type != other.type || rowCount != other.rowCount) {
            return false;
        }
        if (!java.util.Arrays.equals(nullBitmap, other.nullBitmap)) {
            return false;
        }
        return switch (type) {
            case INT -> Arrays.equals(intValues, other.intValues);
            case LONG -> Arrays.equals(longValues, other.longValues);
            case DOUBLE -> Arrays.equals(doubleValues, other.doubleValues);
            case STRING -> Arrays.equals(stringValues, other.stringValues);
        };
    }
}
