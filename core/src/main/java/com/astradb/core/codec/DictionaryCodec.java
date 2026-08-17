package com.astradb.core.codec;

import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.util.ByteBuf;
import com.astradb.core.util.ByteReader;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * 字典编码：适用于 STRING 列（低基数）。
 * M1 使用 chunk 级字典（自足、可独立解码），字典按值排序保证跨快照稳定；
 * 后续可升级为表级字典以进一步提升压缩率。
 *
 * 块格式：[colType(1B)] [rowCount varint] [dictSize varint]
 *         [dict: (len varint + utf8 bytes) ...]
 *         [id varint per row]
 */
public final class DictionaryCodec implements ColumnCodec {

    public static final byte TYPE_ID = 3;

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] encode(Column col) {
        if (col.type() != ColumnType.STRING) {
            throw new IllegalArgumentException("DictionaryCodec 仅支持 STRING 列");
        }
        String[] values = col.strings();
        int n = values.length;

        TreeMap<String, Integer> dict = new TreeMap<>();
        for (String v : values) {
            dict.putIfAbsent(v, 0);
        }
        int dictSize = dict.size();
        int i = 0;
        for (Map.Entry<String, Integer> e : dict.entrySet()) {
            e.setValue(i++);
        }

        ByteBuf out = new ByteBuf(Math.max(32, n));
        out.writeByte(ColumnType.STRING.ordinal());
        out.writeUInt(n);
        out.writeUInt(dictSize);
        for (Map.Entry<String, Integer> e : dict.entrySet()) {
            byte[] utf8 = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeUInt(utf8.length);
            out.writeBytes(utf8);
        }
        for (String v : values) {
            out.writeUInt(dict.get(v));
        }
        return out.toArray();
    }

    @Override
    public Column decode(byte[] data) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        if (type != ColumnType.STRING) {
            throw new IllegalStateException("Dictionary 块列类型非法: " + type);
        }
        int n = (int) in.readUInt();
        int dictSize = (int) in.readUInt();
        String[] dict = new String[dictSize];
        for (int i = 0; i < dictSize; i++) {
            int len = (int) in.readUInt();
            byte[] utf8 = new byte[len];
            for (int j = 0; j < len; j++) {
                utf8[j] = (byte) in.readByte();
            }
            dict[i] = new String(utf8, StandardCharsets.UTF_8);
        }
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = dict[(int) in.readUInt()];
        }
        return Column.ofStrings(out);
    }

    @Override
    public Object valueAt(byte[] data, int rowIndex) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        if (type != ColumnType.STRING) {
            throw new IllegalStateException("Dictionary 块列类型非法: " + type);
        }
        int n = (int) in.readUInt();
        if (rowIndex < 0 || rowIndex >= n) {
            throw new IllegalArgumentException("行号越界: " + rowIndex + " (rows=" + n + ")");
        }
        int dictSize = (int) in.readUInt();
        String[] dict = new String[dictSize];
        for (int i = 0; i < dictSize; i++) {
            int len = (int) in.readUInt();
            byte[] utf8 = new byte[len];
            for (int j = 0; j < len; j++) {
                utf8[j] = (byte) in.readByte();
            }
            dict[i] = new String(utf8, StandardCharsets.UTF_8);
        }
        long id = 0;
        for (int i = 0; i <= rowIndex; i++) {
            id = in.readUInt();
        }
        return dict[(int) id];
    }

    @Override
    public Column decodeRange(byte[] data, int from, int to) {
        ByteReader in = new ByteReader(data);
        ColumnType type = ColumnType.values()[in.readByte()];
        if (type != ColumnType.STRING) {
            throw new IllegalStateException("Dictionary 块列类型非法: " + type);
        }
        int n = (int) in.readUInt();
        if (from < 0 || to < from || to > n) {
            throw new IllegalArgumentException("区间越界: [" + from + ", " + to + ") rows=" + n);
        }
        int dictSize = (int) in.readUInt();
        String[] dict = new String[dictSize];
        for (int i = 0; i < dictSize; i++) {
            int len = (int) in.readUInt();
            byte[] utf8 = new byte[len];
            for (int j = 0; j < len; j++) {
                utf8[j] = (byte) in.readByte();
            }
            dict[i] = new String(utf8, StandardCharsets.UTF_8);
        }
        long id = 0;
        for (int i = 0; i < from; i++) {
            id = in.readUInt(); // 跳过 from 行
        }
        int len = to - from;
        String[] out = new String[len];
        for (int i = 0; i < len; i++) {
            id = in.readUInt();
            out[i] = dict[(int) id];
        }
        return Column.ofStrings(out);
    }
}
