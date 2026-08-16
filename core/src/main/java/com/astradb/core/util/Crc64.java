package com.astradb.core.util;

/**
 * CRC-64/ECMA-182（多项式 0xC96C5795D7870F42），增量式，用于 .seg 文件校验和（8B）。
 */
public final class Crc64 {

    private static final long POLY = 0xC96C5795D7870F42L;
    private static final long[] TABLE = new long[256];

    static {
        for (int i = 0; i < 256; i++) {
            long crc = i;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLY : crc >>> 1;
            }
            TABLE[i] = crc;
        }
    }

    private long crc = ~0L;

    public void update(byte[] b, int off, int n) {
        for (int i = off; i < off + n; i++) {
            crc = TABLE[(int) ((crc ^ b[i]) & 0xFF)] ^ (crc >>> 8);
        }
    }

    public void update(byte[] b) {
        update(b, 0, b.length);
    }

    public long digest() {
        return ~crc;
    }

    public static long of(byte[] b, int off, int n) {
        Crc64 c = new Crc64();
        c.update(b, off, n);
        return c.digest();
    }

    public static long of(byte[] b) {
        return of(b, 0, b.length);
    }
}
