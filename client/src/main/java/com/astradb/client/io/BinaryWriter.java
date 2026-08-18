package com.astradb.client.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 二进制流写入原语：小端定长 + varint（无符号 LEB128）。
 * 供 {@link com.astradb.client.protocol.BinaryProtocol} 编解码使用。
 */
public final class BinaryWriter {

    private final OutputStream out;

    public BinaryWriter(OutputStream out) {
        this.out = out;
    }

    public void writeByte(int b) throws IOException {
        out.write(b & 0xFF);
    }

    public void writeShort(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    public void writeInt(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    public void writeLong(long v) throws IOException {
        for (int i = 0; i < 8; i++) {
            out.write((int) (v >>> (8 * i)) & 0xFF);
        }
    }

    /** 无符号 varint（LEB128）。 */
    public void writeVarInt(long v) throws IOException {
        long value = v;
        while (true) {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value == 0) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    public void writeString(String s) throws IOException {
        byte[] buf = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf.length);
        out.write(buf);
    }

    public void writeBytes(byte[] buf) throws IOException {
        out.write(buf);
    }
}
