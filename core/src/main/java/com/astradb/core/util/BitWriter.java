package com.astradb.core.util;

/**
 * 位写入器（LSB-first），供 Gorilla 编码使用。
 */
public final class BitWriter {

    private final ByteBuf out = new ByteBuf(256);
    private long current;
    private int bitCount;

    /** 写入 1 个 bit（0/1）。 */
    public void writeBit(int bit) {
        current |= ((long) (bit & 1)) << bitCount;
        if (++bitCount == 64) {
            flushWord();
        }
    }

    /** 写入低 n 位（n ∈ [0,64]），LSB 在前。 */
    public void writeBits(long value, int n) {
        if (n == 0) {
            return;
        }
        if (n == 64) {
            int done = bitCount;
            if (done > 0) {
                // current 剩余 64-done 位：先写 value 低位，剩余高位继续
                int fit = 64 - done;
                current |= (value & ((1L << fit) - 1)) << bitCount;
                bitCount = 64;
                flushWord();
                value >>>= fit;
                n = 64 - fit;
            } else {
                out.writeLong(value);
                return;
            }
        }
        while (n >= 64 - bitCount) {
            int take = 64 - bitCount;
            long mask = take == 64 ? -1L : ((1L << take) - 1);
            current |= (value & mask) << bitCount;
            bitCount += take;
            value >>>= take;
            n -= take;
            flushWord();
        }
        if (n > 0) {
            long mask = (1L << n) - 1;
            current |= (value & mask) << bitCount;
            bitCount += n;
        }
    }

    private void flushWord() {
        out.writeLong(current);
        current = 0;
        bitCount = 0;
    }

    public byte[] toByteArray() {
        if (bitCount > 0) {
            flushWord();
        }
        return out.toArray();
    }
}
