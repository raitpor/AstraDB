package com.astradb.client.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 二进制流读写原语：小端定长 + varint（无符号 LEB128）。
 * 供 {@link com.astradb.client.protocol.BinaryProtocol} 编解码使用。
 */
public final class BinaryReader {

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
        int len = (int) readVarInt();
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
