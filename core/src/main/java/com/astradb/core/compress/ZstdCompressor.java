package com.astradb.core.compress;

import com.github.luben.zstd.Zstd;

/**
 * zstd 压缩实现（zstd-jni）。
 */
public final class ZstdCompressor implements Compressor {

    private final int level;

    public ZstdCompressor(int level) {
        if (level < 1 || level > 22) {
            throw new IllegalArgumentException("zstd 压缩等级须在 [1,22]，实际 " + level);
        }
        this.level = level;
    }

    @Override
    public byte[] compress(byte[] data) {
        return Zstd.compress(data, level);
    }

    @Override
    public byte[] decompress(byte[] data, int uncompressedSize) {
        byte[] out = new byte[uncompressedSize];
        long n = Zstd.decompress(out, data);
        if (n != uncompressedSize) {
            throw new IllegalStateException("zstd 解压长度不符: expected=" + uncompressedSize + ", actual=" + n);
        }
        return out;
    }

    @Override
    public int level() {
        return level;
    }
}
