package com.astradb.core.util;

/**
 * 字节数组 + 游标的只读读取原语，用于列编码解码。
 */
public final class ByteReader {

    private final byte[] data;
    private int pos;

    public ByteReader(byte[] data) {
        this(data, 0);
    }

    public ByteReader(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return data.length - pos;
    }

    public int readByte() {
        return data[pos++] & 0xFF;
    }

    public long readLong() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[pos++] & 0xFF);
        }
        return v;
    }

    public int readInt() {
        return (int) (((data[pos++] & 0xFFL) << 24)
                | ((data[pos++] & 0xFFL) << 16)
                | ((data[pos++] & 0xFFL) << 8)
                | (data[pos++] & 0xFFL));
    }

    public int readShort() {
        return (int) (((data[pos++] & 0xFFL) << 8) | (data[pos++] & 0xFFL));
    }

    /** 无符号 varint。 */
    public long readUInt() {
        long v = 0;
        int shift = 0;
        while (true) {
            int b = readByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalStateException("varint too long");
            }
        }
    }

    /** zigzag + varint。 */
    public long readVarInt() {
        long u = readUInt();
        return (u >>> 1) ^ -(u & 1);
    }
}
