package com.astradb.core.codec;

import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.util.ByteBuf;
import com.astradb.core.util.ByteReader;

/**
 * Delta + zigzag varint 编码：适用于排序/序列化后的 INT / LONG 列（增量小则紧凑）。
 * 也用于主键列（pointId 递增序列）。
 *
 * 块格式：[colType(1B)] [rowCount varint] [first zigzag varint] [delta zigzag varint ...]
 */
public final class DeltaVarintCodec implements ColumnCodec {

    public static final byte TYPE_ID = 1;

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] encode(Column col) {
        ByteBuf out = new ByteBuf(Math.max(16, col.rowCount()));
        out.writeByte(col.type().ordinal());
        out.writeUInt(col.rowCount());
        long prev;
        if (col.type() == ColumnType.LONG) {
            long[] v = col.longs();
            prev = v[0];
            out.writeVarInt(prev);
            for (int i = 1; i < v.length; i++) {
                long cur = v[i];
                out.writeVarInt(cur - prev);
                prev = cur;
            }
        } else {
            int[] v = col.ints();
            prev = v[0];
            out.writeVarInt(prev);
            for (int i = 1; i < v.length; i++) {
                long cur = v[i];
                out.writeVarInt(cur - prev);
                prev = cur;
            }
        }
        return out.toArray();
    }

    @Override
    public Column decode(byte[] data) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        int n = (int) in.readUInt();
        long prev = in.readVarInt();
        if (type == ColumnType.LONG) {
            long[] out = new long[n];
            out[0] = prev;
            for (int i = 1; i < n; i++) {
                prev += in.readVarInt();
                out[i] = prev;
            }
            return Column.ofLongs(out);
        }
        int[] out = new int[n];
        out[0] = (int) prev;
        for (int i = 1; i < n; i++) {
            prev += in.readVarInt();
            out[i] = (int) prev;
        }
        return Column.ofInts(out);
    }

    @Override
    public Object valueAt(byte[] data, int rowIndex) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        int n = (int) in.readUInt();
        if (rowIndex < 0 || rowIndex >= n) {
            throw new IllegalArgumentException("行号越界: " + rowIndex + " (rows=" + n + ")");
        }
        long v = in.readVarInt();
        for (int i = 1; i <= rowIndex; i++) {
            v += in.readVarInt();
        }
        // 注意：三元表达式会使 int 装箱为 Long，需显式分支保证 INT→Integer
        if (type == ColumnType.LONG) {
            return v;
        }
        return (int) v;
    }

    @Override
    public Column decodeRange(byte[] data, int from, int to) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        int n = (int) in.readUInt();
        checkRange(from, to, n);
        int len = to - from;
        long v = in.readVarInt();
        for (int i = 1; i <= from; i++) {
            v += in.readVarInt(); // 跳过 from 行
        }
        if (type == ColumnType.LONG) {
            long[] out = new long[len];
            if (len > 0) {
                out[0] = v;
                for (int i = 1; i < len; i++) {
                    v += in.readVarInt();
                    out[i] = v;
                }
            }
            return Column.ofLongs(out);
        }
        int[] out = new int[len];
        if (len > 0) {
            out[0] = (int) v;
            for (int i = 1; i < len; i++) {
                v += in.readVarInt();
                out[i] = (int) v;
            }
        }
        return Column.ofInts(out);
    }

    private static void checkRange(int from, int to, int n) {
        if (from < 0 || to < from || to > n) {
            throw new IllegalArgumentException("区间越界: [" + from + ", " + to + ") rows=" + n);
        }
    }

    /**
     * 顺序解码查找目标值所在行号（列值须升序，主键 pointId 列适用）。
     * 命中返回行号；不存在返回 -1。一次 pass、不构造数组。
     */
    public int findRow(byte[] data, long target) {
        ByteReader in = new ByteReader(data);
        in.readByte(); // type
        int n = (int) in.readUInt();
        long prev = in.readVarInt();
        if (prev > target) {
            return -1;
        }
        if (prev == target) {
            return 0;
        }
        for (int i = 1; i < n; i++) {
            prev += in.readVarInt();
            if (prev >= target) {
                return prev == target ? i : -1;
            }
        }
        return -1;
    }
}
