package com.astradb.core.query;

import com.astradb.core.codec.CodecRegistry;
import com.astradb.core.codec.DeltaVarintCodec;
import com.astradb.core.compress.Compressor;
import com.astradb.core.manifest.Manifest;
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
 * 单点历史序列查询：按段窗口（主键 min/max）跳过 → 候选 chunk 解主键列二分 → 按需取列。
 */
public final class PointSeriesQuery {

    public record PointRecord(long timestamp, List<Object> values) {
    }

    private PointSeriesQuery() {
    }

    public static List<PointRecord> getSeries(TableMeta table, PointDictionary dict, Manifest manifest,
                                              Compressor compressor, ChunkCache cache, String key,
                                              long from, long to, int limit)
            throws IOException {
        if (limit <= 0) {
            return List.of();
        }
        int pointId = dict.idOf(key);
        if (pointId < 0) {
            return List.of();
        }
        List<PointRecord> out = new ArrayList<>();
        for (Manifest.SegmentInfo seg : manifest.segments()) {
            if (out.size() >= limit) {
                break;
            }
            if (seg.maxKey() < pointId || seg.minKey() > pointId) {
                continue;
            }
            Path segPath = table.dir().resolve(seg.path());
            if (!Files.exists(segPath)) {
                continue;
            }
            try (SegmentReader reader = SegmentReader.open(segPath)) {
                int columnCount = reader.columnCount();
                for (int i = 0; i < reader.chunkCount(); i++) {
                    long ts = reader.timestampAt(i);
                    if (ts > to) {
                        break; // 时间升序，后续更大
                    }
                    if (ts < from) {
                        continue;
                    }
                    // 按需解码：主键列 delta 顺序查找行号 + 值列解压至目标行即停（不构造整列数组）
                    byte[] chunk = reader.readChunk(i);
                    ChunkCodec.RawColumn pkRaw = SnapshotQuery.rawColumn(cache, table.name(),
                            seg.path(), i, 0, chunk, compressor);
                    int row = new DeltaVarintCodec().findRow(pkRaw.raw(), pointId);
                    if (row < 0) {
                        continue; // 该快照不含此点（点集增长前/消失后）
                    }
                    List<Object> vals = new ArrayList<>(columnCount - 1);
                    for (int c = 1; c < columnCount; c++) {
                        ChunkCodec.RawColumn rc = SnapshotQuery.rawColumn(cache, table.name(),
                                seg.path(), i, c, chunk, compressor);
                        vals.add(CodecRegistry.of(rc.codecId()).valueAt(rc.raw(), row));
                    }
                    out.add(new PointRecord(ts, vals));
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }
}
