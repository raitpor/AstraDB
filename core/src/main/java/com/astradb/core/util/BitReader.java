package com.astradb.core.util;

/**
 * 位读取器（LSB-first），与 {@link BitWriter} 对称。
 */
public final class BitReader {

    private final byte[] data;
    private int bytePos;
    private int bitPos;
    private long current;

    public BitReader(byte[] data) {
        this(data, 0);
    }

    public BitReader(byte[] data, int byteOffset) {
        this.data = data;
        this.bytePos = byteOffset;
    }

    private void fill() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[bytePos + i] & 0xFF);
        }
        current = v;
        bytePos += 8;
    }

    public int readBit() {
        if (bitPos == 0) {
            fill();
        }
        int bit = (int) (current & 1);
        current >>>= 1;
        bitPos = (bitPos + 1) & 63;
        return bit;
    }

    /** 读取低 n 位（n ∈ [0,64]），LSB 在前。 */
    public long readBits(int n) {
        if (n == 0) {
            return 0;
        }
        long result = 0;
        int got = 0;
        while (got < n) {
            if (bitPos == 0) {
                fill();
            }
            int take = Math.min(64 - bitPos, n - got);
            long mask = take == 64 ? -1L : ((1L << take) - 1);
            result |= (current & mask) << got;
            current >>>= take;
            bitPos = (bitPos + take) & 63;
            got += take;
        }
        return result;
    }
}
