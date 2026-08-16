package com.astradb.core.util;

/**
 * 自增长的字节缓冲 + varint/zigzag 原语，用于列编码输出。
 */
public final class ByteBuf {

    private byte[] buf;
    private int len;

    public ByteBuf() {
        this(64);
    }

    public ByteBuf(int initialCapacity) {
        this.buf = new byte[Math.max(8, initialCapacity)];
    }

    public int length() {
        return len;
    }

    private void ensure(int extra) {
        if (len + extra > buf.length) {
            int cap = buf.length;
            while (cap < len + extra) {
                cap = cap < 1024 ? cap * 2 : cap + (cap >> 1);
            }
            byte[] nb = new byte[cap];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
    }

    public ByteBuf writeByte(int b) {
        ensure(1);
        buf[len++] = (byte) b;
        return this;
    }

    public ByteBuf writeBytes(byte[] src) {
        return writeBytes(src, 0, src.length);
    }

    public ByteBuf writeBytes(byte[] src, int off, int n) {
        ensure(n);
        System.arraycopy(src, off, buf, len, n);
        len += n;
        return this;
    }

    public ByteBuf writeInt(int v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteBuf writeShort(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
        return this;
    }

    public ByteBuf writeLong(long v) {
        ensure(8);
        for (int i = 7; i >= 0; i--) {
            buf[len++] = (byte) (v >>> (i * 8));
        }
        return this;
    }

    /** 无符号 varint。 */
    public ByteBuf writeUInt(long v) {
        while ((v & ~0x7FL) != 0) {
            writeByte((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        writeByte((int) v);
        return this;
    }

    /** zigzag + varint。 */
    public ByteBuf writeVarInt(long v) {
        return writeUInt((v << 1) ^ (v >> 63));
    }

    public byte[] toArray() {
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }
}
