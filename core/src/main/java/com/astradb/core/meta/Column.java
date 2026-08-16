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

    private Column(ColumnType type, int rowCount,
                   int[] intValues, long[] longValues, double[] doubleValues, String[] stringValues) {
        this.type = type;
        this.rowCount = rowCount;
        this.intValues = intValues;
        this.longValues = longValues;
        this.doubleValues = doubleValues;
        this.stringValues = stringValues;
    }

    public static Column ofInts(int[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.INT, values.length, values, null, null, null);
    }

    public static Column ofLongs(long[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.LONG, values.length, null, values, null, null);
    }

    public static Column ofDoubles(double[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.DOUBLE, values.length, null, null, values, null);
    }

    public static Column ofStrings(String[] values) {
        Objects.requireNonNull(values, "values");
        return new Column(ColumnType.STRING, values.length, null, null, null, values);
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

    /** 按行号取任意值（解码/查询层用）。 */
    public Object valueAt(int i) {
        return switch (type) {
            case INT -> intValues[i];
            case LONG -> longValues[i];
            case DOUBLE -> doubleValues[i];
            case STRING -> stringValues[i];
        };
    }

    /** 按给定行号排列重排本列（导入排序用）。 */
    public Column permute(int[] order) {
        ColumnType t = type;
        if (t == ColumnType.INT) {
            int[] out = new int[rowCount];
            for (int i = 0; i < rowCount; i++) {
                out[i] = intValues[order[i]];
            }
            return Column.ofInts(out);
        }
        if (t == ColumnType.LONG) {
            long[] out = new long[rowCount];
            for (int i = 0; i < rowCount; i++) {
                out[i] = longValues[order[i]];
            }
            return Column.ofLongs(out);
        }
        if (t == ColumnType.DOUBLE) {
            double[] out = new double[rowCount];
            for (int i = 0; i < rowCount; i++) {
                out[i] = doubleValues[order[i]];
            }
            return Column.ofDoubles(out);
        }
        String[] out = new String[rowCount];
        for (int i = 0; i < rowCount; i++) {
            out[i] = stringValues[order[i]];
        }
        return Column.ofStrings(out);
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
        return switch (type) {
            case INT -> Arrays.equals(intValues, other.intValues);
            case LONG -> Arrays.equals(longValues, other.longValues);
            case DOUBLE -> Arrays.equals(doubleValues, other.doubleValues);
            case STRING -> Arrays.equals(stringValues, other.stringValues);
        };
    }
}
