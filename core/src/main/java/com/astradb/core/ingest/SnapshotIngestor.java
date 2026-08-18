package com.astradb.core.ingest;

import com.astradb.core.compress.Compressor;
import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;
import com.astradb.core.meta.TableMeta;
import com.astradb.core.points.PointDictionary;
import com.astradb.core.segment.Chunk;
import com.astradb.core.segment.ChunkCodec;
import com.astradb.core.segment.SegmentPaths;
import com.astradb.core.segment.SegmentReader;
import com.astradb.core.segment.SegmentRewriter;
import com.astradb.core.segment.SegmentWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快照导入编排（design.md 7.2）：
 * 解析 CSV → 校验 → 点分配 → 按 pointId 排序 → 编码压缩 → 追加当天 .seg → 更新 manifest。
 * 顺序保证崩溃安全：points.dict 先落盘，再写 chunk（孤儿点无害，半写 chunk 由恢复截断）。
 */
public final class SnapshotIngestor {

    /** 导入结果。 */
    public record IngestResult(long timestamp, int rowCount, int newPoints) {
    }

    private SnapshotIngestor() {
    }

    /**
     * 以 {@link SnapshotData}（列缓冲）导入：CSV/JSON/Java API 等任何导入方式
     * 解析为 SnapshotData 后调用本入口；core 在导入前校验表结构与列类型与数据一致。
     */
    public static IngestResult ingest(TableMeta table, PointDictionary dict, Manifest manifest,
                                      Compressor compressor, SnapshotData data, Long timestamp,
                                      java.time.ZoneId zone, java.util.function.Consumer<java.nio.file.Path> evict)
            throws IOException {
        long ts = timestamp != null ? timestamp : System.currentTimeMillis();
        PreparedChunk p = prepare(table, dict, compressor, data, ts, zone);
        dict.flush(); // 先落盘点字典（fsync），再写 chunk
        writeSegment(table, manifest, compressor, p, zone, evict);
        return new IngestResult(p.ts(), p.rowCount(), p.newPoints());
    }

    /** 批量导入的单个快照（显式时间戳，须严格递增）。 */
    public record BatchSnapshot(SnapshotData data, Long timestamp) {
    }

    /**
     * 批量导入：多个快照一次完成校验/点分配（dict 落盘一次）、同段共享写入器
     * （每段 fsync 一次）、manifest 末尾保存一次——大幅减少 fsync 次数。
     * 崩溃语义与单快照一致：dict 先落盘，未完成段由恢复截断丢弃。
     */
    public static List<IngestResult> ingestBatch(TableMeta table, PointDictionary dict, Manifest manifest,
                                                 Compressor compressor, List<BatchSnapshot> snapshots,
                                                 java.time.ZoneId zone,
                                                 java.util.function.Consumer<java.nio.file.Path> evict)
            throws IOException {
        if (snapshots == null || snapshots.isEmpty()) {
            throw new IngestException("批量导入为空");
        }
        List<PreparedChunk> prepared = new ArrayList<>(snapshots.size());
        List<IngestResult> results = new ArrayList<>(snapshots.size());
        long prevTs = Long.MIN_VALUE;
        for (int i = 0; i < snapshots.size(); i++) {
            BatchSnapshot bs = snapshots.get(i);
            if (bs.timestamp() == null) {
                throw new IngestException("批量导入须显式提供每个快照的时间戳（第 " + (i + 1) + " 个）");
            }
            long ts = bs.timestamp();
            if (ts <= prevTs) {
                throw new IngestException("批量导入时间戳须严格递增（第 " + (i + 1) + " 个）");
            }
            prevTs = ts;
            PreparedChunk p = prepare(table, dict, compressor, bs.data(), ts, zone);
            prepared.add(p);
            results.add(new IngestResult(ts, p.rowCount(), p.newPoints()));
        }
        dict.flush(); // 全部新点一次落盘
        writeSegmentsBatch(table, manifest, compressor, prepared, zone, evict);
        return results;
    }

    /** 单快照：校验/分配/排序/编码为 chunk 字节（不落盘点字典）。 */
    private static PreparedChunk prepare(TableMeta table, PointDictionary dict, Compressor compressor,
                                         SnapshotData data, long ts, java.time.ZoneId zone) {
        Schema schema = table.schema();
        if (data.columns().size() != schema.columnCount()) {
            throw new IngestException("列数不符：期望 " + schema.columnCount() + "，实际 " + data.columns().size());
        }
        // 表结构与列类型校验：快照数据每列类型须与 schema 冻结的列类型一致（导入前拦截）
        for (int i = 0; i < schema.columnCount(); i++) {
            ColumnType expected = schema.columns().get(i).type();
            ColumnType actual = data.columns().get(i).type();
            if (expected != actual) {
                throw new IngestException("第 " + (i + 1) + " 列类型不符：期望 " + expected + "，实际 " + actual);
            }
            // nullable 约束：非空列出现 null → 拒绝（含主键列）
            if (!schema.columns().get(i).nullable()) {
                Column c = data.columns().get(i);
                if (c.hasNullBitmap()) {
                    for (int r = 0; r < data.rowCount(); r++) {
                        if (c.isNull(r)) {
                            throw new IngestException("第 " + (i + 1) + " 列不允许为空（第 " + (r + 1) + " 行）");
                        }
                    }
                }
            }
        }
        List<Column> columns = data.columns();
        int n = data.rowCount();
        if (n == 0) {
            throw new IngestException("快照为空：无数据行");
        }

        // 1. 主键 key 数组（按解析后的值字符串化，INT/LONG/DOUBLE 规范化）
        int pkIndex = schema.primaryKeyIndex();
        Column pkCol = columns.get(pkIndex);
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) {
            keys[i] = primaryKeyString(pkCol, i);
        }

        // 2. 主键非空校验（design 7.1）+ 快照内唯一性
        Set<String> seen = new HashSet<>(n);
        for (int i = 0; i < n; i++) {
            String k = keys[i];
            if (k == null || k.isBlank()) {
                throw new IngestException("主键不能为空（第 " + (i + 1) + " 行）");
            }
            if (!seen.add(k)) {
                throw new IngestException("快照内主键重复: " + k);
            }
        }

        // 3. 点分配
        int[] pointIds = new int[n];
        int newPoints = 0;
        for (int i = 0; i < n; i++) {
            int id = dict.idOf(keys[i]);
            if (id < 0) {
                id = dict.assign(keys[i]);
                newPoints++;
            }
            pointIds[i] = id;
        }

        // 4. 按 pointId 排序重排所有列；主键列替换为 pointId 列（跳过主键列 permute 避免重复复制）
        int[] order = sortIndex(pointIds);
        Column[] sorted = new Column[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            if (i != pkIndex) {
                sorted[i] = columns.get(i).permute(order);
            }
        }
        int[] sortedIds = new int[n];
        for (int i = 0; i < n; i++) {
            sortedIds[i] = pointIds[order[i]];
        }
        sorted[pkIndex] = Column.ofInts(sortedIds);

        // 5. 编码 + 压缩 → chunk 字节
        Chunk chunk = new Chunk(ts, schema.version(), List.of(sorted));
        byte[] chunkBytes = ChunkCodec.encode(chunk, compressor);

        return new PreparedChunk(chunkBytes, ts, n, sortedIds[0], sortedIds[n - 1], newPoints,
                SegmentPaths.pathFor(table.dir(), ts, zone));
    }

    /** 已编码快照：待写段。 */
    private record PreparedChunk(byte[] chunkBytes, long ts, int rowCount, int minKey, int maxKey,
                                 int newPoints, Path segPath) {
    }

    /** 单快照写段 + manifest 更新。段存在时支持任意时间戳插入（尾部 append 快路径 / 中间重写合并）。 */
    private static void writeSegment(TableMeta table, Manifest manifest, Compressor compressor,
                                     PreparedChunk p, java.time.ZoneId zone,
                                     java.util.function.Consumer<java.nio.file.Path> evict)
            throws IOException {
        Schema schema = table.schema();
        Path segPath = p.segPath();
        if (!Files.exists(segPath)) {
            try (SegmentWriter w = SegmentWriter.create(segPath, SegmentPaths.dayStart(p.ts(), zone),
                    schema.version(), schema.columnCount())) {
                w.append(p.chunkBytes(), p.ts(), p.rowCount());
            }
            mergeSegmentInfo(manifest, table, segPath, SegmentPaths.dayStart(p.ts(), zone), p.ts(),
                    1, p.rowCount(), p.minKey(), p.maxKey(), Files.size(segPath));
            manifest.save();
            return;
        }
        // 段存在：定位目标时间戳
        try (SegmentReader r = SegmentReader.open(segPath, null)) {
            int idx = r.findChunkAtOrBefore(p.ts());
            if (idx >= 0 && r.timestampAt(idx) == p.ts()) {
                throw new IngestException("时间戳已存在（重复快照）: " + p.ts());
            }
            if (r.chunkCount() == 0 || r.timestampAt(r.chunkCount() - 1) < p.ts()) {
                // 尾部追加：快路径（仅 fsync 一次）
                try (SegmentWriter w = SegmentWriter.openAppend(segPath, schema.version(), schema.columnCount())) {
                    w.append(p.chunkBytes(), p.ts(), p.rowCount());
                }
                mergeSegmentInfo(manifest, table, segPath, SegmentPaths.dayStart(p.ts(), zone), p.ts(),
                        1, p.rowCount(), p.minKey(), p.maxKey(), Files.size(segPath));
                manifest.save();
                return;
            }
        }
        // 中间空洞：重写合并（reader 已关闭；低频回填，接受 O(段大小) 开销）
        SegmentRewriter.rewrite(
                segPath, SegmentPaths.dayStart(p.ts(), zone), schema.version(), schema.columnCount(),
                java.util.Set.of(), List.of(new SegmentRewriter.NewChunk(p.chunkBytes(), p.ts(), p.rowCount())));
        evict.accept(segPath);
        // 新增量（mergeSegmentInfo 按追加语义累加到旧值）
        mergeSegmentInfo(manifest, table, segPath, SegmentPaths.dayStart(p.ts(), zone), p.ts(),
                1, p.rowCount(), p.minKey(), p.maxKey(), Files.size(segPath));
        manifest.save();
    }

    /** 批量写段：同段共享写入器（close/fsync 一次），manifest 汇总保存一次。 */
    private static void writeSegmentsBatch(TableMeta table, Manifest manifest, Compressor compressor,
                                           List<PreparedChunk> prepared, java.time.ZoneId zone,
                                           java.util.function.Consumer<java.nio.file.Path> evict)
            throws IOException {
        Schema schema = table.schema();
        Map<Path, List<PreparedChunk>> bySeg = new LinkedHashMap<>();
        for (PreparedChunk p : prepared) {
            bySeg.computeIfAbsent(p.segPath(), k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<Path, List<PreparedChunk>> e : bySeg.entrySet()) {
            Path segPath = e.getKey();
            List<PreparedChunk> chunks = e.getValue();
            int minKey = Integer.MAX_VALUE;
            int maxKey = Integer.MIN_VALUE;
            long rows = 0;
            for (PreparedChunk p : chunks) {
                rows += p.rowCount();
                minKey = Math.min(minKey, p.minKey());
                maxKey = Math.max(maxKey, p.maxKey());
            }
            if (!Files.exists(segPath)) {
                try (SegmentWriter w = SegmentWriter.create(segPath,
                        SegmentPaths.dayStart(chunks.get(0).ts(), zone), schema.version(), schema.columnCount())) {
                    for (PreparedChunk p : chunks) {
                        w.append(p.chunkBytes(), p.ts(), p.rowCount());
                    }
                }
                mergeSegmentInfo(manifest, table, segPath,
                        SegmentPaths.dayStart(chunks.get(0).ts(), zone), chunks.get(chunks.size() - 1).ts(),
                        chunks.size(), rows, minKey, maxKey, Files.size(segPath));
                continue;
            }
            // 段存在：校验批内与旧段时间戳重复，再决定 append 快路径或重写合并
            boolean needRewrite;
            try (SegmentReader r = SegmentReader.open(segPath, null)) {
                for (PreparedChunk p : chunks) {
                    int idx = r.findChunkAtOrBefore(p.ts());
                    if (idx >= 0 && r.timestampAt(idx) == p.ts()) {
                        throw new IngestException("时间戳已存在（重复快照）: " + p.ts());
                    }
                }
                needRewrite = r.chunkCount() > 0 && r.timestampAt(r.chunkCount() - 1) >= chunks.get(0).ts();
                if (!needRewrite) {
                    // 全部落在段尾：追加快路径
                    try (SegmentWriter w = SegmentWriter.openAppend(segPath, schema.version(), schema.columnCount())) {
                        for (PreparedChunk p : chunks) {
                            w.append(p.chunkBytes(), p.ts(), p.rowCount());
                        }
                    }
                    mergeSegmentInfo(manifest, table, segPath,
                            SegmentPaths.dayStart(chunks.get(0).ts(), zone), chunks.get(chunks.size() - 1).ts(),
                            chunks.size(), rows, minKey, maxKey, Files.size(segPath));
                    continue;
                }
            }
            // 落入段中间：reader 已关闭，该段一次性重写合并
            List<SegmentRewriter.NewChunk> newChunks = new ArrayList<>(chunks.size());
            for (PreparedChunk p : chunks) {
                newChunks.add(new SegmentRewriter.NewChunk(p.chunkBytes(), p.ts(), p.rowCount()));
            }
            SegmentRewriter.rewrite(segPath, SegmentPaths.dayStart(chunks.get(0).ts(), zone),
                    schema.version(), schema.columnCount(), java.util.Set.of(), newChunks);
            evict.accept(segPath);
            // 新增量（mergeSegmentInfo 按追加语义累加）
            mergeSegmentInfo(manifest, table, segPath,
                    SegmentPaths.dayStart(chunks.get(0).ts(), zone), chunks.get(chunks.size() - 1).ts(),
                    chunks.size(), rows, minKey, maxKey, Files.size(segPath));
        }
        manifest.save();
    }

    /** manifest 段信息合并（含旧值累计：chunkCount/rows 累加，min/max 并集，endTime 取 max，startTime 取 min）。 */
    private static void mergeSegmentInfo(Manifest manifest, TableMeta table, Path segPath,
                                         long startTime, long endTime, int chunkCount, long rows,
                                         int minKey, int maxKey, long sizeBytes) {
        String rel = SegmentPaths.relative(segPath, table.dir());
        Manifest.SegmentInfo old = null;
        for (Manifest.SegmentInfo s : manifest.segments()) {
            if (s.path().equals(rel)) {
                old = s;
                break;
            }
        }
        long newStart = old == null ? startTime : Math.min(old.startTime(), startTime);
        long newEnd = old == null ? endTime : Math.max(old.endTime(), endTime);
        Manifest.SegmentInfo si = new Manifest.SegmentInfo(
                rel, newStart, newEnd,
                (old == null ? 0 : old.chunkCount()) + chunkCount,
                (old == null ? 0 : old.rows()) + rows,
                Math.min(old == null ? Integer.MAX_VALUE : old.minKey(), minKey),
                Math.max(old == null ? -1 : old.maxKey(), maxKey),
                sizeBytes);
        manifest.addOrMerge(si);
    }

    private static String primaryKeyString(Column pk, int i) {
        return switch (pk.type()) {
            case INT -> Integer.toString(pk.ints()[i]);
            case LONG -> Long.toString(pk.longs()[i]);
            case DOUBLE -> Double.toString(pk.doubles()[i]);
            case STRING -> pk.strings()[i];
        };
    }

    /**
     * 按 keys 升序返回索引排列（原始类型快排，三数取中 + 尾递归优化，
     * 替代 Integer[] 装箱排序，避免 20 万行临时装箱对象）。
     */
    private static int[] sortIndex(int[] keys) {
        int n = keys.length;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        quickSort(keys, idx, 0, n - 1);
        return idx;
    }

    private static void quickSort(int[] keys, int[] idx, int lo, int hi) {
        while (lo < hi) {
            int p = partition(keys, idx, lo, hi);
            // 递归较小段，迭代较大段，控制递归栈深
            if (p - lo < hi - p) {
                quickSort(keys, idx, lo, p - 1);
                lo = p + 1;
            } else {
                quickSort(keys, idx, p + 1, hi);
                hi = p - 1;
            }
        }
    }

    private static int partition(int[] keys, int[] idx, int lo, int hi) {
        int mid = (lo + hi) >>> 1;
        // 三数取中选主元
        int a = keys[idx[lo]];
        int b = keys[idx[mid]];
        int c = keys[idx[hi]];
        int pivotPos;
        if (a < b) {
            pivotPos = b < c ? mid : (a < c ? hi : lo);
        } else {
            pivotPos = a < c ? lo : (b < c ? hi : mid);
        }
        int pivot = keys[idx[pivotPos]];
        swap(idx, pivotPos, hi);
        int i = lo;
        for (int j = lo; j < hi; j++) {
            if (keys[idx[j]] < pivot) {
                swap(idx, i, j);
                i++;
            }
        }
        swap(idx, i, hi);
        return i;
    }

    private static void swap(int[] idx, int a, int b) {
        if (a != b) {
            int t = idx[a];
            idx[a] = idx[b];
            idx[b] = t;
        }
    }

    /** 导入失败（主键重复/列不匹配/空快照等）。 */
    public static final class IngestException extends RuntimeException {
        public IngestException(String message) {
            super(message);
        }
    }
}
