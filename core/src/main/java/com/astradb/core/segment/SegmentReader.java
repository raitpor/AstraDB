package com.astradb.core.segment;

import com.astradb.core.compress.Compressor;
import com.astradb.core.meta.Column;
import com.astradb.core.util.Crc64;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static com.astradb.core.segment.SegmentFormat.ChunkIndexEntry;

/**
 * 段只读访问：校验头尾、加载 ChunkIndex，支持按时间二分定位与按需解列。
 * Footer 缺失/损坏时回退到顺序扫描（崩溃现场也可安全读取完整 chunk）。
 */
public final class SegmentReader implements AutoCloseable {

    private final Path path;
    private final FileChannel ch;
    private final SegmentChannelCache cache;
    private final long segmentStartTime;
    private final int schemaVersion;
    private final int columnCount;
    private final ChunkIndexEntry[] index;

    private SegmentReader(Path path, FileChannel ch, SegmentChannelCache cache,
                          SegmentFormat.FileHeader header, List<ChunkIndexEntry> entries) {
        this.path = path;
        this.ch = ch;
        this.cache = cache;
        this.segmentStartTime = header.segmentStartTime();
        this.schemaVersion = header.schemaVersion();
        this.columnCount = header.columnCount();
        this.index = entries.toArray(new ChunkIndexEntry[0]);
    }

    /** 打开段（无句柄池：每次独立打开，close 时关闭）。 */
    public static SegmentReader open(Path path) throws IOException {
        return open(path, null);
    }

    /** 打开段（复用 {@link SegmentChannelCache} 句柄：close 时归还池，不关闭句柄）。 */
    public static SegmentReader open(Path path, SegmentChannelCache cache) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
        try {
            SegmentFormat.FileHeader header = SegmentFormat.readFileHeader(raf);
            List<ChunkIndexEntry> entries;
            SegmentFormat.Footer footer = SegmentFormat.tryReadFooter(raf);
            if (footer != null) {
                long bodyLen = footer.indexOffset() + (long) footer.indexCount() * SegmentFormat.INDEX_ENTRY_SIZE;
                byte[] body = new byte[(int) bodyLen];
                raf.seek(0);
                raf.readFully(body);
                if (Crc64.of(body) == footer.fileChecksum()) {
                    entries = SegmentFormat.readIndex(raf, footer);
                } else {
                    entries = SegmentRecovery.scan(raf).entries();
                }
            } else {
                entries = SegmentRecovery.scan(raf).entries();
            }
            raf.close();
            // 头部/索引读取完毕：后续读 chunk 走池化（或独立）FileChannel，positional read 并发安全
            FileChannel ch = cache != null ? cache.acquire(path) : FileChannel.open(path, StandardOpenOption.READ);
            return new SegmentReader(path, ch, cache, header, entries);
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    public Path path() {
        return path;
    }

    public long segmentStartTime() {
        return segmentStartTime;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public int columnCount() {
        return columnCount;
    }

    public int chunkCount() {
        return index.length;
    }

    public ChunkIndexEntry entry(int i) {
        return index[i];
    }

    public long timestampAt(int i) {
        return index[i].timestamp();
    }

    /** 最后一个 timestamp &lt;= target 的 chunk 索引；无则 -1（timestamps 升序二分）。 */
    public int findChunkAtOrBefore(long target) {
        int lo = 0;
        int hi = index.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (index[mid].timestamp() <= target) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    public byte[] readChunk(int i) throws IOException {
        ChunkIndexEntry e = index[i];
        byte[] chunk = new byte[e.length()];
        readFully(ch, chunk, e.offset());
        return chunk;
    }

    public Chunk decodeChunk(int i, Compressor compressor) throws IOException {
        return ChunkCodec.decode(readChunk(i), compressor);
    }

    public Column decodeColumn(int i, int columnIndex, Compressor compressor) throws IOException {
        return ChunkCodec.decodeColumn(readChunk(i), columnIndex, compressor);
    }

    public long sizeBytes() throws IOException {
        return ch.size();
    }

    /** positional read：并发安全（不共享 position）。 */
    private static void readFully(FileChannel ch, byte[] buf, long pos) throws IOException {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
        long p = pos;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, p);
            if (n < 0) {
                throw new java.io.EOFException("段文件提前结束: " + p);
            }
            p += n;
        }
    }

    @Override
    public void close() throws IOException {
        if (cache != null) {
            cache.release(path, ch); // 归还空闲池
        } else {
            ch.close();
        }
    }
}
