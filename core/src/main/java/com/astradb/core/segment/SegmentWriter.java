package com.astradb.core.segment;

import com.astradb.core.util.ByteReader;
import com.astradb.core.util.Crc64;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import static com.astradb.core.segment.SegmentFormat.ChunkIndexEntry;

/**
 * .seg 追加写器（单写者，同表写串行由上层保证）。
 *
 * 写入顺序：chunk 数据 → close 时 ChunkIndex + Footer + fsync。
 * 崩溃（close 前）时文件无有效 Footer，打开时触发恢复截断（见 SegmentRecovery）。
 */
public final class SegmentWriter implements AutoCloseable {

    private final Path path;
    private final RandomAccessFile raf;
    private final int schemaVersion;
    private final int columnCount;
    private final List<ChunkIndexEntry> entries = new ArrayList<>();
    private final Crc64 crc64;
    private long dataEnd;
    private boolean closed;

    private SegmentWriter(Path path, RandomAccessFile raf, int schemaVersion, int columnCount,
                          long dataEnd, List<ChunkIndexEntry> existing, Crc64 crc64) {
        this.path = path;
        this.raf = raf;
        this.schemaVersion = schemaVersion;
        this.columnCount = columnCount;
        this.dataEnd = dataEnd;
        this.entries.addAll(existing);
        this.crc64 = crc64;
    }

    /** 新建段（当天首个快照）。 */
    public static SegmentWriter create(Path path, long segmentStartTime, int schemaVersion, int columnCount)
            throws IOException {
        Files.createDirectories(path.getParent());
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        raf.setLength(0);
        byte[] header = SegmentFormat.buildFileHeader(segmentStartTime, schemaVersion, columnCount);
        raf.write(header);
        Crc64 crc = new Crc64();
        crc.update(header); // 文件校验和覆盖 FileHeader + chunk 区 + 索引区
        long dataEnd = SegmentFormat.FILE_HEADER_SIZE;
        raf.seek(dataEnd);
        return new SegmentWriter(path, raf, schemaVersion, columnCount, dataEnd, List.of(), crc);
    }

    /**
     * 打开已有段继续追加。Footer 有效则沿用其索引；否则先恢复截断（崩溃现场）。
     * 校验段头 schemaVersion/columnCount 与期望一致。
     */
    public static SegmentWriter openAppend(Path path, int schemaVersion, int columnCount) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        try {
            SegmentFormat.FileHeader header = SegmentFormat.readFileHeader(raf);
            if (header.schemaVersion() != schemaVersion || header.columnCount() != columnCount) {
                throw new IOException("段 schema 不匹配: file=" + header.schemaVersion() + "/" + header.columnCount()
                        + ", expect=" + schemaVersion + "/" + columnCount);
            }
            SegmentFormat.Footer footer = SegmentFormat.tryReadFooter(raf);
            List<ChunkIndexEntry> existing;
            long dataEnd;
            if (footer != null) {
                long bodyLen = footer.indexOffset() + (long) footer.indexCount() * SegmentFormat.INDEX_ENTRY_SIZE;
                byte[] body = readBytes(raf, 0, (int) bodyLen);
                if (Crc64.of(body) != footer.fileChecksum()) {
                    throw new IOException("段文件校验和不匹配，文件损坏: " + path);
                }
                existing = SegmentFormat.readIndex(raf, footer);
                dataEnd = footer.indexOffset();
            } else {
                SegmentRecovery.RecoveryResult r = SegmentRecovery.recover(path);
                existing = r.entries();
                dataEnd = r.dataEnd();
            }
            Crc64 crc = new Crc64();
            crc.update(readBytes(raf, 0, (int) dataEnd));
            return new SegmentWriter(path, raf, schemaVersion, columnCount, dataEnd, existing, crc);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    private static byte[] readBytes(RandomAccessFile raf, long off, int len) throws IOException {
        byte[] b = new byte[len];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    /** 追加一个已编码的 chunk（时间戳必须单调递增，保证 ChunkIndex 二分正确）。 */
    public void append(byte[] chunk, long timestamp, int rowCount) throws IOException {
        if (closed) {
            throw new IllegalStateException("writer 已关闭");
        }
        if (!entries.isEmpty()) {
            long last = entries.get(entries.size() - 1).timestamp();
            if (timestamp <= last) {
                throw new IllegalArgumentException("快照时间戳必须单调递增：段内最后快照 " + last
                        + "，本次 " + timestamp + "（乱序或重复的时间戳会破坏 ChunkIndex 二分）");
            }
        }
        raf.write(chunk);
        crc64.update(chunk);
        CRC32C crc = new CRC32C();
        crc.update(chunk, 0, chunk.length);
        entries.add(new ChunkIndexEntry(dataEnd, chunk.length, timestamp, rowCount, schemaVersion, (int) crc.getValue()));
        dataEnd += chunk.length;
    }

    public int chunkCount() {
        return entries.size();
    }

    public Path path() {
        return path;
    }

    public long dataEnd() {
        return dataEnd;
    }

    /** 写 ChunkIndex + Footer 并 fsync，标记段完整。 */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        long indexOffset = dataEnd;
        raf.seek(indexOffset);
        for (ChunkIndexEntry e : entries) {
            byte[] b = e.toBytes();
            raf.write(b);
            crc64.update(b); // 校验和覆盖 chunk 区 + 索引区
        }
        long checksum = crc64.digest();
        raf.write(SegmentFormat.buildFooter(indexOffset, entries.size(), checksum));
        raf.getFD().sync();
        closed = true;
        raf.close();
    }
}
