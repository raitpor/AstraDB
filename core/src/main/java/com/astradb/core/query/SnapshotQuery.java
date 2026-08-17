package com.astradb.core.query;

import com.astradb.core.codec.CodecRegistry;
import com.astradb.core.compress.Compressor;
import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.TableMeta;
import com.astradb.core.points.PointDictionary;
import com.astradb.core.segment.ChunkCodec;
import com.astradb.core.segment.SegmentReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 全量快照查询：定位段 → ChunkIndex 精确匹配（timestamp == ts）→ 区间解码/缓存 → 分页。
 */
public final class SnapshotQuery {

    /** 一行：主键 + 值列（不含主键列本身）。 */
    public record Row(String key, List<Object> values) {
    }

    public record SnapshotPage(long timestamp, long totalRows, int offset, int limit, List<Row> rows) {
        public static SnapshotPage empty(long ts) {
            return new SnapshotPage(ts, 0, 0, 0, List.of());
        }
    }

    private SnapshotQuery() {
    }

    /** 分页查询：精确时间点匹配；仅解码 [offset, offset+limit) 行区间（可选 LRU 缓存复用）。 */
    public static SnapshotPage getSnapshot(TableMeta table, PointDictionary dict, Manifest manifest,
                                           Compressor compressor, ChunkCache cache, long ts,
                                           int offset, int limit)
            throws IOException {
        if (limit <= 0) {
            return SnapshotPage.empty(ts);
        }
        Located loc = locateBytes(table, manifest, compressor, ts);
        if (loc == null) {
            return SnapshotPage.empty(ts);
        }
        int total = ChunkCodec.rowCountOf(loc.chunk());
        int from = Math.max(0, offset);
        int to = Math.min(total, offset + limit);
        return decodeRows(table, dict, compressor, cache, loc, from, to, false);
    }

    /** 全量快照（不分页）：精确时间点匹配，一次返回该快照全部行（可选 LRU 缓存复用）。 */
    public record FullSnapshot(long timestamp, long totalRows, List<Row> rows) {
        public static FullSnapshot empty(long ts) {
            return new FullSnapshot(ts, 0, List.of());
        }
    }

    public static FullSnapshot getFullSnapshot(TableMeta table, PointDictionary dict, Manifest manifest,
                                               Compressor compressor, ChunkCache cache, long ts)
            throws IOException {
        Located loc = locateBytes(table, manifest, compressor, ts);
        if (loc == null) {
            return FullSnapshot.empty(ts);
        }
        int total = ChunkCodec.rowCountOf(loc.chunk());
        SnapshotPage page = decodeRows(table, dict, compressor, cache, loc, 0, total, true);
        return new FullSnapshot(loc.chunk() == null ? ts : ChunkCodec.timestampOf(loc.chunk()),
                total, page.rows());
    }

    /** 解码 [from, to) 行并组装 Row。full=true 时整列解码，否则区间解码。 */
    private static SnapshotPage decodeRows(TableMeta table, PointDictionary dict, Compressor compressor,
                                           ChunkCache cache, Located loc, int from, int to, boolean full) {
        long ts = ChunkCodec.timestampOf(loc.chunk());
        int total = ChunkCodec.rowCountOf(loc.chunk());
        if (from >= to) {
            // 分页区间为空（如 offset 越界）→ 空页，totalRows 仍正确
            return new SnapshotPage(ts, total, from, 0, List.of());
        }
        int columnCount = ChunkCodec.columnCountOf(loc.chunk());
        Column pk = decodeColumn(table, dict, compressor, cache, loc, 0, from, to, full);
        List<Column> valueCols = new ArrayList<>(columnCount - 1);
        for (int c = 1; c < columnCount; c++) {
            valueCols.add(decodeColumn(table, dict, compressor, cache, loc, c, from, to, full));
        }
        int len = to - from;
        List<Row> rows = new ArrayList<>(Math.max(0, len));
        int[] ids = pk.ints();
        for (int r = 0; r < len; r++) {
            String key = dict.keyOf(ids[r]);
            List<Object> vals = new ArrayList<>(valueCols.size());
            for (Column col : valueCols) {
                vals.add(col.valueAt(r));
            }
            rows.add(new Row(key == null ? "?" : key, vals));
        }
        return new SnapshotPage(ts, total, from, len, rows);
    }

    private static Column decodeColumn(TableMeta table, PointDictionary dict, Compressor compressor,
                                       ChunkCache cache, Located loc, int col, int from, int to, boolean full) {
        ChunkCodec.RawColumn rc = rawColumn(cache, table.name(), loc.segRel(), loc.chunkIdx(), col,
                loc.chunk(), compressor);
        return full
                ? CodecRegistry.of(rc.codecId()).decode(rc.raw())
                : CodecRegistry.of(rc.codecId()).decodeRange(rc.raw(), from, to);
    }

    /** 取列原始字节：优先命中 LRU 缓存，否则 zstd 解压并入缓存。 */
    public static ChunkCodec.RawColumn rawColumn(ChunkCache cache, String table, String segRel, int chunkIdx,
                                                 int col, byte[] chunk, Compressor compressor) {
        if (cache != null) {
            ChunkCodec.RawColumn hit = cache.get(table, segRel, chunkIdx, col);
            if (hit != null) {
                return hit;
            }
        }
        ChunkCodec.RawColumn rc = ChunkCodec.rawColumnAt(chunk, col, compressor);
        if (cache != null) {
            cache.put(table, segRel, chunkIdx, col, rc);
        }
        return rc;
    }

    /** 定位并读取精确时间点的 chunk 字节（timestamp == ts）；无匹配返回 null。 */
    private static Located locateBytes(TableMeta table, Manifest manifest, Compressor compressor, long ts)
            throws IOException {
        Manifest.SegmentInfo seg = manifest.lastAtOrBefore(ts);
        if (seg == null) {
            return null;
        }
        Path segPath = table.dir().resolve(seg.path());
        if (!Files.exists(segPath)) {
            throw new IOException("段文件缺失: " + segPath);
        }
        try (SegmentReader reader = SegmentReader.open(segPath)) {
            int idx = reader.findChunkAtOrBefore(ts);
            if (idx < 0 || reader.timestampAt(idx) != ts) {
                return null;
            }
            return new Located(reader.readChunk(idx), seg.path(), idx);
        }
    }

    private record Located(byte[] chunk, String segRel, int chunkIdx) {
    }
}
