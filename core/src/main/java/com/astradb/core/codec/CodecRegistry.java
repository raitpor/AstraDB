package com.astradb.core.codec;

import com.astradb.core.meta.ColumnType;

import java.util.HashMap;
import java.util.Map;

/**
 * 编码器注册表：类型 ID ↔ 编码器。
 */
public final class CodecRegistry {

    private static final Map<Byte, ColumnCodec> BY_ID = new HashMap<>();

    static {
        register(new DeltaVarintCodec());
        register(new GorillaCodec());
        register(new DictionaryCodec());
    }

    private CodecRegistry() {
    }

    private static void register(ColumnCodec codec) {
        BY_ID.put(codec.typeId(), codec);
    }

    public static ColumnCodec of(byte typeId) {
        ColumnCodec codec = BY_ID.get(typeId);
        if (codec == null) {
            throw new IllegalArgumentException("未知编码器 ID: " + typeId);
        }
        return codec;
    }

    /** 根据列类型返回默认编码器 ID。 */
    public static byte idOf(ColumnType type) {
        return switch (type) {
            case INT, LONG -> DeltaVarintCodec.TYPE_ID;
            case DOUBLE -> GorillaCodec.TYPE_ID;
            case STRING -> DictionaryCodec.TYPE_ID;
        };
    }
}
