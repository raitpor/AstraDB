package com.astradb.core.codec;

import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.util.BitReader;
import com.astradb.core.util.BitWriter;
import com.astradb.core.util.ByteBuf;
import com.astradb.core.util.ByteReader;

/**
 * Gorilla（Facebook VLDB 2015）XOR 差分编码，适用于 DOUBLE 值列。
 * 全量快照流中相邻快照同列值大部分相同 → xor == 0 仅占 1 bit，是压缩率主来源。
 *
 * 块格式：[colType(1B)] [rowCount varint] [first raw 8B] [gorilla bit 流]
 */
public final class GorillaCodec implements ColumnCodec {

    public static final byte TYPE_ID = 2;

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] encode(Column col) {
        if (col.type() != ColumnType.DOUBLE) {
            throw new IllegalArgumentException("GorillaCodec 仅支持 DOUBLE 列");
        }
        double[] values = col.doubles();
        int n = values.length;
        ByteBuf out = new ByteBuf(Math.max(16, n));
        out.writeByte(ColumnType.DOUBLE.ordinal());
        out.writeUInt(n);

        BitWriter bw = new BitWriter();
        long prev = Double.doubleToRawLongBits(values[0]);
        bw.writeBits(prev, 64);
        long prevLeading = 64;
        long prevTrailing = 0;
        for (int i = 1; i < n; i++) {
            long cur = Double.doubleToRawLongBits(values[i]);
            long xor = prev ^ cur;
            if (xor == 0) {
                bw.writeBit(0);
            } else {
                bw.writeBit(1);
                int leading = Long.numberOfLeadingZeros(xor);
                int trailing = Long.numberOfTrailingZeros(xor);
                int sigBits = 64 - leading - trailing;
                if (leading >= prevLeading && trailing >= prevTrailing) {
                    bw.writeBit(0);
                    int winBits = (int) (64 - prevLeading - prevTrailing);
                    bw.writeBits(xor >>> prevTrailing, winBits);
                } else {
                    bw.writeBit(1);
                    bw.writeBits(leading, 5);
                    bw.writeBits(sigBits - 1, 6);
                    bw.writeBits(xor >>> trailing, sigBits);
                    prevLeading = leading;
                    prevTrailing = trailing;
                }
            }
            prev = cur;
        }
        out.writeBytes(bw.toByteArray());
        return out.toArray();
    }

    @Override
    public Column decode(byte[] data) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        if (type != ColumnType.DOUBLE) {
            throw new IllegalStateException("Gorilla 块列类型非法: " + type);
        }
        int n = (int) in.readUInt();
        double[] out = new double[n];

        BitReader br = new BitReader(data, in.position());
        long prev = br.readBits(64);
        out[0] = Double.longBitsToDouble(prev);
        long prevLeading = 64;
        long prevTrailing = 0;
        for (int i = 1; i < n; i++) {
            long xor;
            if (br.readBit() == 0) {
                xor = 0;
            } else {
                int leading;
                int trailing;
                if (br.readBit() == 0) {
                    leading = (int) prevLeading;
                    trailing = (int) prevTrailing;
                    xor = br.readBits((int) (64 - prevLeading - prevTrailing));
                    xor <<= trailing;
                } else {
                    leading = (int) br.readBits(5);
                    int sigBits = (int) br.readBits(6) + 1;
                    trailing = 64 - leading - sigBits;
                    xor = br.readBits(sigBits);
                    xor <<= trailing;
                    prevLeading = leading;
                    prevTrailing = trailing;
                }
            }
            prev ^= xor;
            out[i] = Double.longBitsToDouble(prev);
        }
        return Column.ofDoubles(out);
    }
}
