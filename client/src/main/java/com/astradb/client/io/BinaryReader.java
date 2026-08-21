package com.astradb.client.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 二进制流读写原语：小端定长 + varint（无符号 LEB128）。
 * 供 {@link com.astradb.client.protocol.BinaryProtocol} 编解码使用。
 */
public final class BinaryReader {

    /** 单字符串长度上限（64MB；SS-2/SO-4：防恶意帧超大分配，负数 varint 强转 int 变负/超大 → 拒收）。 */
    private static final long MAX_STRING_BYTES = 1L << 26;

    private final InputStream in;

    public BinaryReader(InputStream in) {
        this.in = in;
    }

    public int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("数据流提前结束");
        }
        return b;
    }

    public int readShort() throws IOException {
        return readByte() | (readByte() << 8);
    }

    public int readInt() throws IOException {
        return readByte() | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
    }

    public long readLong() throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) readByte()) << (8 * i);
        }
        return v;
    }

    /** 无符号 varint（LEB128）。 */
    public long readVarInt() throws IOException {
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
                throw new IOException("varint 溢出");
            }
        }
    }

    public String readString() throws IOException {
        long lenL = readVarInt();
        // SS-2：varint 为无符号（0..2^63-1），强转 int 可为负 → NegativeArraySizeException；
        // 统一校验长度区间，非法帧以受控 IOException 拒绝（上层映射 400）
        if (lenL < 0 || lenL > MAX_STRING_BYTES) {
            throw new IOException("字符串长度非法（超上限）: " + lenL);
        }
        int len = (int) lenL; // 已校验 ≤ 64MB，收窄安全
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new IOException("数据流提前结束");
            }
            off += n;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    public byte[] readBytes(int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new IOException("数据流提前结束");
            }
            off += n;
        }
        return buf;
    }
}
