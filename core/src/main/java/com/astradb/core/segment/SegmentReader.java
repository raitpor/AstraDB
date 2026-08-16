package com.astradb.core.segment;

import com.astradb.core.compress.Compressor;
import com.astradb.core.meta.Column;
import com.astradb.core.util.Crc64;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import static com.astradb.core.segment.SegmentFormat.ChunkIndexEntry;

/**
 * 段只读访问：校验头尾、加载 ChunkIndex，支持按时间二分定位与按需解列。
 * Footer 缺失/损坏时回退到顺序扫描（崩溃现场也可安全读取完整 chunk）。
 */
public final class SegmentReader implements AutoCloseable {

    private final Path path;
    private final RandomAccessFile raf;
    private final long segmentStartTime;
    private final int schemaVersion;
    private final int columnCount;
    private final ChunkIndexEntry[] index;

    private SegmentReader(Path path, RandomAccessFile raf, SegmentFormat.FileHeader header,
                          List<ChunkIndexEntry> entries) {
        this.path = path;
        this.raf = raf;
        this.segmentStartTime = header.segmentStartTime();
        this.schemaVersion = header.schemaVersion();
        this.columnCount = header.columnCount();
        this.index = entries.toArray(new ChunkIndexEntry[0]);
    }

    public static SegmentReader open(Path path) throws IOException {
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
            return new SegmentReader(path, raf, header, entries);
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
        raf.seek(e.offset());
        raf.readFully(chunk);
        return chunk;
    }

    public Chunk decodeChunk(int i, Compressor compressor) throws IOException {
        return ChunkCodec.decode(readChunk(i), compressor);
    }

    public Column decodeColumn(int i, int columnIndex, Compressor compressor) throws IOException {
        return ChunkCodec.decodeColumn(readChunk(i), columnIndex, compressor);
    }

    public long sizeBytes() throws IOException {
        return raf.length();
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
