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
import com.astradb.core.segment.SegmentWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    /** 从 CSV 解析并导入。 */
    public static IngestResult ingest(TableMeta table, PointDictionary dict, Manifest manifest,
                                      Compressor compressor, InputStream csv, Long timestamp,
                                      java.time.ZoneId zone)
            throws IOException {
        SnapshotData data = CsvParser.parse(csv, table.schema(), true);
        return ingest(table, dict, manifest, compressor, data, timestamp, zone);
    }

    /**
     * 以 {@link SnapshotData}（列缓冲）导入：适用于 CSV 之外的任何导入方式
     * （JSON、Java API 等），解析为 SnapshotData 后即可复用本入口。
     */
    public static IngestResult ingest(TableMeta table, PointDictionary dict, Manifest manifest,
                                      Compressor compressor, SnapshotData data, Long timestamp,
                                      java.time.ZoneId zone)
            throws IOException {
        Schema schema = table.schema();
        if (data.columns().size() != schema.columnCount()) {
            throw new IngestException("列数不符：期望 " + schema.columnCount() + "，实际 " + data.columns().size());
        }
        long ts = timestamp != null ? timestamp : System.currentTimeMillis();

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
        dict.flush(); // 先落盘点字典（fsync），再写 chunk

        // 4. 按 pointId 排序重排所有列；主键列替换为 pointId 列
        int[] order = sortIndex(pointIds);
        Column[] sorted = new Column[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            sorted[i] = columns.get(i).permute(order);
        }
        int[] sortedIds = new int[n];
        for (int i = 0; i < n; i++) {
            sortedIds[i] = pointIds[order[i]];
        }
        sorted[pkIndex] = Column.ofInts(sortedIds);

        // 5. 编码 + 压缩 → chunk 字节
        Chunk chunk = new Chunk(ts, schema.version(), List.of(sorted));
        byte[] chunkBytes = ChunkCodec.encode(chunk, compressor);

        // 6. 追加写当天段
        Path segPath = SegmentPaths.pathFor(table.dir(), ts, zone);
        SegmentWriter writer = Files.exists(segPath)
                ? SegmentWriter.openAppend(segPath, schema.version(), schema.columnCount())
                : SegmentWriter.create(segPath, SegmentPaths.dayStart(ts, zone), schema.version(), schema.columnCount());
        try (SegmentWriter w = writer) {
            w.append(chunkBytes, ts, n);
        }

        // 7. manifest 更新（段级信息合并）
        String rel = SegmentPaths.relative(segPath, table.dir());
        Manifest.SegmentInfo old = null;
        for (Manifest.SegmentInfo s : manifest.segments()) {
            if (s.path().equals(rel)) {
                old = s;
                break;
            }
        }
        int minKey = sortedIds[0];
        int maxKey = sortedIds[n - 1];
        Manifest.SegmentInfo si = new Manifest.SegmentInfo(
                rel, SegmentPaths.dayStart(ts, zone), ts,
                (old == null ? 0 : old.chunkCount()) + 1,
                (old == null ? 0 : old.rows()) + n,
                Math.min(old == null ? Integer.MAX_VALUE : old.minKey(), minKey),
                Math.max(old == null ? -1 : old.maxKey(), maxKey),
                Files.size(segPath));
        manifest.addOrMerge(si);
        manifest.save();

        return new IngestResult(ts, n, newPoints);
    }

    private static String primaryKeyString(Column pk, int i) {
        return switch (pk.type()) {
            case INT -> Integer.toString(pk.ints()[i]);
            case LONG -> Long.toString(pk.longs()[i]);
            case DOUBLE -> Double.toString(pk.doubles()[i]);
            case STRING -> pk.strings()[i];
        };
    }

    /** 按 keys 升序返回索引排列（临时装箱可接受，非驻留对象）。 */
    private static int[] sortIndex(int[] keys) {
        Integer[] idx = new Integer[keys.length];
        for (int i = 0; i < keys.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Integer.compare(keys[a], keys[b]));
        int[] out = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /** 导入失败（主键重复/列不匹配/空快照等）。 */
    public static final class IngestException extends RuntimeException {
        public IngestException(String message) {
            super(message);
        }
    }
}
