package com.astradb.core.query;

import com.astradb.core.codec.CodecRegistry;
import com.astradb.core.codec.DeltaVarintCodec;
import com.astradb.core.compress.Compressor;
import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.TableMeta;
import com.astradb.core.points.PointDictionary;
import com.astradb.core.segment.ChunkCodec;
import com.astradb.core.segment.SegmentChannelCache;
import com.astradb.core.segment.SegmentReader;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
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

    /** 跨段并行解码线程池（守护线程，随 JVM 退出）。 */
    private static final ForkJoinPool POOL = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);

    private PointSeriesQuery() {
    }

    public static List<PointRecord> getSeries(TableMeta table, PointDictionary dict, Manifest manifest,
                                              Compressor compressor, ChunkCache cache,
                                              SegmentChannelCache channels, String key,
                                              long from, long to, int limit)
            throws IOException {
        if (limit <= 0) {
            return List.of();
        }
        int pointId = dict.idOf(key);
        if (pointId < 0) {
            return List.of();
        }
        List<Manifest.SegmentInfo> candidates = new ArrayList<>();
        for (Manifest.SegmentInfo seg : manifest.segments()) {
            if (seg.maxKey() < pointId || seg.minKey() > pointId) {
                continue;
            }
            if (!Files.exists(table.dir().resolve(seg.path()))) {
                continue;
            }
            candidates.add(seg);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 多段时并行解码（各段独立），结果按时间 k-way 归并，与串行结果一致
        if (candidates.size() > 1) {
            List<List<PointRecord>> perSeg = new ArrayList<>(candidates.size());
            List<Future<List<PointRecord>>> futures = new ArrayList<>(candidates.size());
            for (Manifest.SegmentInfo seg : candidates) {
                futures.add(POOL.submit(() -> decodeSegment(table, dict, manifest, compressor, cache,
                        channels, seg, pointId, from, to, limit)));
            }
            for (Future<List<PointRecord>> f : futures) {
                try {
                    perSeg.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("单点历史查询被中断", e);
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof IOException ioe) {
                        throw ioe;
                    }
                    if (c instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IOException("单点历史查询失败", c);
                }
            }
            return mergeSorted(perSeg, limit);
        }
        return decodeSegment(table, dict, manifest, compressor, cache, channels,
                candidates.get(0), pointId, from, to, limit);
    }

    /** 解码单个段内 [from, to] 时间窗口的点记录（段内按时间升序）。 */
    private static List<PointRecord> decodeSegment(TableMeta table, PointDictionary dict, Manifest manifest,
                                                   Compressor compressor, ChunkCache cache,
                                                   SegmentChannelCache channels, Manifest.SegmentInfo seg,
                                                   int pointId, long from, long to, int limit)
            throws IOException {
        List<PointRecord> out = new ArrayList<>();
        Path segPath = table.dir().resolve(seg.path());
        try (SegmentReader reader = SegmentReader.open(segPath, channels)) {
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
                    vals.add(ChunkCodec.valueAtRaw(rc, row));
                }
                out.add(new PointRecord(ts, vals));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** 多路归并（各段结果按时间升序），取前 limit 条。 */
    private static List<PointRecord> mergeSorted(List<List<PointRecord>> perSeg, int limit) {
        int k = perSeg.size();
        int[] idx = new int[k];
        List<PointRecord> merged = new ArrayList<>();
        while (merged.size() < limit) {
            int best = -1;
            long bestTs = Long.MAX_VALUE;
            for (int s = 0; s < k; s++) {
                List<PointRecord> l = perSeg.get(s);
                if (idx[s] < l.size() && l.get(idx[s]).timestamp() < bestTs) {
                    bestTs = l.get(idx[s]).timestamp();
                    best = s;
                }
            }
            if (best < 0) {
                break;
            }
            merged.add(perSeg.get(best).get(idx[best]));
            idx[best]++;
        }
        return merged;
    }
}
