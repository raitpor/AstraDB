package com.astradb.core.query;

import com.astradb.core.compress.Compressor;
import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.TableMeta;
import com.astradb.core.points.PointDictionary;
import com.astradb.core.segment.Chunk;
import com.astradb.core.segment.SegmentReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 全量快照查询：定位段 → ChunkIndex 二分（最后 timestamp ≤ ts）→ 解码 → 行区间分页。
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

    public static SnapshotPage getSnapshot(TableMeta table, PointDictionary dict, Manifest manifest,
                                           Compressor compressor, long ts, int offset, int limit)
            throws IOException {
        if (limit <= 0) {
            return SnapshotPage.empty(ts);
        }
        Manifest.SegmentInfo seg = manifest.lastAtOrBefore(ts);
        if (seg == null) {
            return SnapshotPage.empty(ts);
        }
        Path segPath = table.dir().resolve(seg.path());
        if (!Files.exists(segPath)) {
            throw new IOException("段文件缺失: " + segPath);
        }
        try (SegmentReader reader = SegmentReader.open(segPath)) {
            int idx = reader.findChunkAtOrBefore(ts);
            if (idx < 0) {
                return SnapshotPage.empty(ts);
            }
            Chunk chunk = reader.decodeChunk(idx, compressor);
            int total = chunk.rowCount();
            int from = Math.max(0, offset);
            int to = Math.min(total, offset + limit);
            List<Row> rows = new ArrayList<>(Math.max(0, to - from));
            int[] ids = chunk.column(0).ints();
            for (int r = from; r < to; r++) {
                String key = dict.keyOf(ids[r]);
                List<Object> vals = new ArrayList<>(chunk.columnCount() - 1);
                for (int c = 1; c < chunk.columnCount(); c++) {
                    vals.add(chunk.column(c).valueAt(r));
                }
                rows.add(new Row(key == null ? "?" : key, vals));
            }
            return new SnapshotPage(chunk.timestamp(), total, from, to - from, rows);
        }
    }
}
