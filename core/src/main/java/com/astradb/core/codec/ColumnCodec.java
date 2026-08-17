package com.astradb.core.codec;

import com.astradb.core.meta.Column;

/**
 * 列编码器。编码块自足（含列类型与行数），解码无需额外上下文。
 */
public interface ColumnCodec {

    /** 编码器 ID，写入 Chunk 列偏移表。 */
    byte typeId();

    byte[] encode(Column col);

    Column decode(byte[] data);

    /**
     * 取第 rowIndex 行值（不构造整列数组），供单点查询按需解码。
     * 注意：对非随机访问编码（Gorilla 位流/Dictionary varint）为 O(rowIndex) 顺序解码。
     */
    Object valueAt(byte[] data, int rowIndex);

    /**
     * 区间解码：返回 [from, to) 行的子列（不构造整列数组），供分页查询按需解码。
     * 对顺序编码为 O(to) 解码成本。
     */
    Column decodeRange(byte[] data, int from, int to);
}
