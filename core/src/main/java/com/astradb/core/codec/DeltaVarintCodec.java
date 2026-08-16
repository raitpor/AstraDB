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
}
