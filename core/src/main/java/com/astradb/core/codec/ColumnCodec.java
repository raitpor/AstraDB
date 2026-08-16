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
}
