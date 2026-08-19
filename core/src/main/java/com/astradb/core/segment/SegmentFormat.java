package com.astradb.core.segment;

import com.astradb.core.util.ByteReader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * .seg 文件格式常量与头尾编解码（design.md 6.1）。
 *
 * FileHeader(21B): magic "ASEG"(4) formatVersion(2) layout(1)
 *                  segmentStartTime(8) schemaVersion(2) columnCount(2)
 * ChunkIndexEntry(30B): offset(8) length(4) timestamp(8) rowCount(4) schemaVersion(2) crc32c(4)
 * Footer(28B): magic "SEGF"(4) indexOffset(8) indexCount(4) fileChecksum(8) endMagic "SEGE"(4)
 */
public final class SegmentFormat {

    public static final byte[] FILE_MAGIC = "ASEG".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] FOOTER_MAGIC = "SEGF".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] FOOTER_END_MAGIC = "SEGE".getBytes(StandardCharsets.US_ASCII);

    /** 当前格式版本（v2：列偏移表含 flags，可空列带 null 位图）。 */
    public static final int FORMAT_VERSION = 2;
    public static final int LAYOUT = 1;

    public static final int FILE_HEADER_SIZE = 21;
    public static final int INDEX_ENTRY_SIZE = 30;
    public static final int FOOTER_SIZE = 28;

    private SegmentFormat() {
    }

    /** 单个 chunk 的索引条目。 */
    public record ChunkIndexEntry(long offset, int length, long timestamp, int rowCount,
                                  int schemaVersion, int crc) {
        public byte[] toBytes() {
            byte[] b = new byte[INDEX_ENTRY_SIZE];
            putLong(b, 0, offset);
            putInt(b, 8, length);
            putLong(b, 12, timestamp);
            putInt(b, 20, rowCount);
            putShort(b, 24, schemaVersion);
            putInt(b, 26, crc);
            return b;
        }

        public static ChunkIndexEntry read(ByteReader in) {
            return new ChunkIndexEntry(
                    in.readLong(), in.readInt(), in.readLong(), in.readInt(),
                    in.readShort(), in.readInt());
        }
    }

    public static byte[] buildFileHeader(long segmentStartTime, int schemaVersion, int columnCount) {
        byte[] h = new byte[FILE_HEADER_SIZE];
        System.arraycopy(FILE_MAGIC, 0, h, 0, 4);
        putShort(h, 4, FORMAT_VERSION);
        h[6] = (byte) LAYOUT;
        putLong(h, 7, segmentStartTime);
        putShort(h, 15, schemaVersion);
        putShort(h, 17, columnCount);
        return h;
    }

    public static byte[] buildFooter(long indexOffset, int indexCount, long fileChecksum) {
        byte[] f = new byte[FOOTER_SIZE];
        System.arraycopy(FOOTER_MAGIC, 0, f, 0, 4);
        putLong(f, 4, indexOffset);
        putInt(f, 12, indexCount);
        putLong(f, 16, fileChecksum);
        System.arraycopy(FOOTER_END_MAGIC, 0, f, 24, 4);
        return f;
    }

    /** 读取文件头并校验 magic/版本。 */
    public static FileHeader readFileHeader(RandomAccessFile raf) throws IOException {
        if (raf.length() < FILE_HEADER_SIZE) {
            throw new IOException("文件过小，非有效 .seg");
        }
        byte[] h = new byte[FILE_HEADER_SIZE];
        raf.seek(0);
        raf.readFully(h);
        for (int i = 0; i < 4; i++) {
            if (h[i] != FILE_MAGIC[i]) {
                throw new IOException("文件头 magic 不匹配，非有效 .seg");
            }
        }
        int version = readShort(h, 4);
        if (version != FORMAT_VERSION) {
            throw new IOException("数据文件格式不兼容（v" + version + "，当前仅支持 v" + FORMAT_VERSION
                    + "）；旧格式数据需重新导入");
        }
        long segmentStartTime = readLong(h, 7);
        int schemaVersion = readShort(h, 15);
        int columnCount = readShort(h, 17);
        return new FileHeader(segmentStartTime, schemaVersion, columnCount);
    }

    /** 读取文件尾 footer；文件完整时返回，否则 null（需恢复）。 */
    public static Footer tryReadFooter(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        if (len < FILE_HEADER_SIZE + FOOTER_SIZE) {
            return null;
        }
        byte[] f = new byte[FOOTER_SIZE];
        raf.seek(len - FOOTER_SIZE);
        raf.readFully(f);
        for (int i = 0; i < 4; i++) {
            if (f[i] != FOOTER_MAGIC[i] || f[24 + i] != FOOTER_END_MAGIC[i]) {
                return null;
            }
        }
        long indexOffset = readLong(f, 4);
        int indexCount = readInt(f, 12);
        long fileChecksum = readLong(f, 16);
        if (indexOffset < FILE_HEADER_SIZE || indexCount < 0
                || indexOffset + (long) indexCount * INDEX_ENTRY_SIZE + FOOTER_SIZE != len) {
            return null;
        }
        return new Footer(indexOffset, indexCount, fileChecksum);
    }

    /** 读取 ChunkIndex 区全部条目。 */
    public static List<ChunkIndexEntry> readIndex(RandomAccessFile raf, Footer footer) throws IOException {
        List<ChunkIndexEntry> list = new ArrayList<>(footer.indexCount());
        raf.seek(footer.indexOffset());
        byte[] buf = new byte[INDEX_ENTRY_SIZE];
        for (int i = 0; i < footer.indexCount(); i++) {
            raf.readFully(buf);
            list.add(ChunkIndexEntry.read(new ByteReader(buf)));
        }
        return list;
    }

    public record FileHeader(long segmentStartTime, int schemaVersion, int columnCount) {
    }

    public record Footer(long indexOffset, int indexCount, long fileChecksum) {
    }

    // ---- 小端写 / 读 ----

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void putLong(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
    }

    static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    static int readShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
