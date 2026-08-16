package com.astradb.core.compress;

/**
 * 块级压缩器。压缩等级表级可配（zstd 1~22）。
 */
public interface Compressor {

    byte[] compress(byte[] data);

    /** @param uncompressedSize 解压后目标长度（由列块头记录） */
    byte[] decompress(byte[] data, int uncompressedSize);

    int level();
}
