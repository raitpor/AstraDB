package com.astradb.core;

import com.astradb.core.compress.Compressor;
import com.astradb.core.compress.ZstdCompressor;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.JsonFiles;
import com.astradb.core.meta.Schema;
import com.astradb.core.meta.SchemaRegistry;
import com.astradb.core.meta.TableMeta;
import com.astradb.core.meta.TablesStore;
import com.astradb.core.points.PointDictionary;
import com.astradb.core.query.ChunkCache;
import com.astradb.core.query.PointSeriesQuery;
import com.astradb.core.query.SnapshotQuery;
import com.astradb.core.retention.RetentionCleaner;
import com.astradb.core.segment.SegmentPaths;
import com.astradb.core.segment.SegmentReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * AstraDB 门面：表管理 / 快照导入 / 查询 / 保留期清理。
 * 写操作（建删表、导入、清理）互斥，查询与写并发（读写锁）。
 */
public final class AstraDB implements AutoCloseable {

    public static final String VERSION = "0.1.0";
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;
    public static final int DEFAULT_RETENTION_DAYS = 1825; // 5 年
    /** 查询列解压缓存默认上限（64MB，0 禁用）。 */
    public static final long DEFAULT_QUERY_CACHE_BYTES = 64L * 1024 * 1024;
    public static final int MAX_NAME_LENGTH = 128;

    /** 表概览（server/UI 用）。 */
    public record TableInfo(String name, Schema schema, int retentionDays, int compressionLevel,
                            int pointCount, int segmentCount, long totalRows, long totalSizeBytes) {
    }

    /** 表统计。 */
    public record TableStats(String name, int pointCount, int segmentCount, long totalRows, long totalSizeBytes,
                             List<Manifest.SegmentInfo> segments) {
    }

    /** 段内单个快照：时间戳 + 行数。 */
    public record SegmentSnapshotInfo(long timestamp, int rows) {
    }

    private static final class TableState {
        final TableMeta meta;
        final PointDictionary dict;
        final Manifest manifest;
        final Compressor compressor;
        /** 表级读写锁：同表写串行、查询与写并发、跨表写并行（K-02）。 */
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        TableState(TableMeta meta, PointDictionary dict, Manifest manifest, Compressor compressor) {
            this.meta = meta;
            this.dict = dict;
            this.manifest = manifest;
            this.compressor = compressor;
        }
    }

    private final Path dataDir;
    private final int defaultCompressionLevel;
    private final java.time.ZoneId zone;
    private final TablesStore store;
    private final Map<String, TableState> states = new TreeMap<>();
    /** 全局锁：仅保护表集合（建/删表）与表查找；数据操作走表级锁。 */
    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();
    /** 查询列解压 LRU 缓存（跨查询复用，缓解反复解压；0 = 禁用）。 */
    private final ChunkCache cache;

    private AstraDB(Path dataDir, int defaultCompressionLevel, java.time.ZoneId zone, TablesStore store,
                    long cacheBytes) {
        this.dataDir = dataDir;
        this.defaultCompressionLevel = defaultCompressionLevel;
        this.zone = zone;
        this.store = store;
        this.cache = new ChunkCache(cacheBytes);
    }

    /** 打开（或初始化）数据库目录，按天分片使用系统默认时区。 */
    public static AstraDB open(Path dataDir) throws IOException {
        return open(dataDir, DEFAULT_COMPRESSION_LEVEL);
    }

    public static AstraDB open(Path dataDir, int defaultCompressionLevel) throws IOException {
        return open(dataDir, defaultCompressionLevel, java.time.ZoneId.systemDefault());
    }

    /** 打开；指定按天分片时区（与页面/数据时间戳保持一致）。 */
    public static AstraDB open(Path dataDir, int defaultCompressionLevel, java.time.ZoneId zone) throws IOException {
        return open(dataDir, defaultCompressionLevel, zone, DEFAULT_QUERY_CACHE_BYTES);
    }

    /** 打开；指定时区与查询缓存上限（字节；0 禁用）。 */
    public static AstraDB open(Path dataDir, int defaultCompressionLevel, java.time.ZoneId zone, long cacheBytes)
            throws IOException {
        Files.createDirectories(dataDir);
        TablesStore store = TablesStore.load(dataDir);
        AstraDB db = new AstraDB(dataDir, defaultCompressionLevel, zone, store, cacheBytes);
        for (TableMeta meta : store.all()) {
            db.loadTable(meta);
        }
        return db;
    }

    public java.time.ZoneId zone() {
        return zone;
    }

    /** 查询缓存条目数（测试/监控用）。 */
    public int queryCacheSize() {
        return cache.size();
    }

    /** 查询缓存当前字节数（测试/监控用）。 */
    public long queryCacheBytes() {
        return cache.currentBytes();
    }

    private void loadTable(TableMeta meta) throws IOException {
        Path dir = meta.dir();
        SchemaRegistry registry = SchemaRegistry.load(dir.resolve("schema-registry.json"));
        if (!sameColumns(registry.columns(), meta.schema().columns())) {
            throw new IOException("表 " + meta.name() + " 的 tables.json 与 schema-registry 不一致");
        }
        PointDictionary dict = PointDictionary.load(dir.resolve("points.dict"));
        Manifest manifest = Manifest.load(dir.resolve("manifest.json"), meta.name());
        validateManifest(meta, dict, manifest, new ZstdCompressor(meta.compressionLevel()));
        states.put(meta.name(), new TableState(meta, dict, manifest,
                new ZstdCompressor(meta.compressionLevel())));
    }

    private static boolean sameColumns(List<Schema.ColumnDef> a, List<Schema.ColumnDef> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name()) || a.get(i).type() != b.get(i).type()) {
                return false;
            }
        }
        return true;
    }

    /** 启动校验：manifest 与磁盘段一致，不一致则校正。正常启动走轻量描述（不解码），仅漂移/缺失段精确重建窗口。 */
    private static void validateManifest(TableMeta meta, PointDictionary dict, Manifest manifest,
                                         Compressor compressor)
            throws IOException {
        Path segRoot = meta.dir().resolve("segments");
        Set<String> disk = new HashSet<>();
        List<Manifest.SegmentInfo> diskInfos = new ArrayList<>();
        if (Files.isDirectory(segRoot)) {
            try (Stream<Path> walk = Files.walk(segRoot)) {
                for (Path p : walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".seg")).toList()) {
                    String rel = SegmentPaths.relative(p, meta.dir());
                    disk.add(rel);
                    Manifest.SegmentInfo light = describeSegmentLight(meta, p, rel);
                    Manifest.SegmentInfo old = findSegment(manifest, rel);
                    if (old == null || !sameSegmentInfo(old, light)) {
                        // 缺失或信息漂移 → 精确重建（解码主键列计算 minKey/maxKey）
                        diskInfos.add(describeSegmentPrecise(meta, p, rel, compressor));
                    }
                }
            }
        }
        boolean dirty = false;
        // 磁盘有、manifest 无或信息不一致 → 合并（精确信息）
        for (Manifest.SegmentInfo info : diskInfos) {
            manifest.addOrMerge(info);
            dirty = true;
        }
        // manifest 有、磁盘无 → 移除
        for (Manifest.SegmentInfo s : manifest.segments()) {
            if (!disk.contains(s.path())) {
                manifest.remove(s.path());
                dirty = true;
            }
        }
        if (dirty) {
            manifest.save();
        }
    }

    private static boolean sameSegmentInfo(Manifest.SegmentInfo a, Manifest.SegmentInfo b) {
        return a.chunkCount() == b.chunkCount() && a.rows() == b.rows()
                && a.sizeBytes() == b.sizeBytes() && a.endTime() == b.endTime();
    }

    /** 轻量描述：仅读段头与 ChunkIndex（不解码数据列），min/max 占位。 */
    private static Manifest.SegmentInfo describeSegmentLight(TableMeta meta, Path p, String rel)
            throws IOException {
        try (SegmentReader reader = SegmentReader.open(p)) {
            long rows = 0;
            long endTime = reader.segmentStartTime();
            for (int i = 0; i < reader.chunkCount(); i++) {
                rows += reader.entry(i).rowCount();
                endTime = reader.timestampAt(i);
            }
            return new Manifest.SegmentInfo(rel, reader.segmentStartTime(), endTime,
                    reader.chunkCount(), rows, 1, 1, Files.size(p));
        }
    }

    private static Manifest.SegmentInfo findSegment(Manifest manifest, String path) {
        for (Manifest.SegmentInfo s : manifest.segments()) {
            if (s.path().equals(path)) {
                return s;
            }
        }
        return null;
    }

    /** 由磁盘段读取段信息；minKey/maxKey 精确计算（解码主键列首尾，K-01）。 */
    private static Manifest.SegmentInfo describeSegmentPrecise(TableMeta meta, Path p, String rel, Compressor compressor)
            throws IOException {
        try (SegmentReader reader = SegmentReader.open(p)) {
            long rows = 0;
            long endTime = reader.segmentStartTime();
            int minKey = Integer.MAX_VALUE;
            int maxKey = Integer.MIN_VALUE;
            for (int i = 0; i < reader.chunkCount(); i++) {
                rows += reader.entry(i).rowCount();
                endTime = reader.timestampAt(i);
                // 主键列按 pointId 排序，首尾元素即该 chunk 的 min/max
                Column ids = reader.decodeColumn(i, 0, compressor);
                int[] arr = ids.ints();
                if (arr.length > 0) {
                    if (arr[0] < minKey) {
                        minKey = arr[0];
                    }
                    if (arr[arr.length - 1] > maxKey) {
                        maxKey = arr[arr.length - 1];
                    }
                }
            }
            if (minKey == Integer.MAX_VALUE) {
                minKey = 1;
                maxKey = 1;
            }
            return new Manifest.SegmentInfo(rel, reader.segmentStartTime(), endTime,
                    reader.chunkCount(), rows, minKey, maxKey, Files.size(p));
        }
    }

    // ---- 表管理 ----

    public TableInfo createTable(String name, List<Schema.ColumnDef> columns, String primaryKey,
                                 int retentionDays, Integer compressionLevel) throws IOException {
        globalLock.writeLock().lock();
        try {
            validateName(name);
            if (states.containsKey(name)) {
                throw new IllegalArgumentException("表已存在: " + name);
            }
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("列定义不能为空");
            }
            int pkIndex = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(primaryKey)) {
                    pkIndex = i;
                    break;
                }
            }
            if (pkIndex < 0) {
                throw new IllegalArgumentException("主键列不存在: " + primaryKey);
            }
            int level = compressionLevel != null ? compressionLevel : defaultCompressionLevel;
            if (level < 1 || level > 22) {
                throw new IllegalArgumentException("压缩等级须在 [1,22]");
            }
            Schema schema = new Schema(1, columns, pkIndex);
            Path dir = dataDir.resolve(name);
            Files.createDirectories(dir.resolve("segments"));
            SchemaRegistry.create(dir.resolve("schema-registry.json"), columns);
            PointDictionary dict = PointDictionary.load(dir.resolve("points.dict"));
            Manifest manifest = Manifest.empty(dir.resolve("manifest.json"), name);
            manifest.save();
            TableMeta meta = new TableMeta(name, schema, retentionDays, level, dir);
            store.put(meta);
            store.save();
            states.put(name, new TableState(meta, dict, manifest, new ZstdCompressor(level)));
            return tableInfo(name);
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    public void dropTable(String name) throws IOException {
        globalLock.writeLock().lock();
        try {
            TableState st = states.remove(name);
            if (st == null) {
                throw new IllegalArgumentException("表不存在: " + name);
            }
            store.remove(name);
            store.save();
            // 等待该表进行中的写操作完成后再删目录（锁序：global → table，无死锁）
            st.lock.writeLock().lock();
            try {
                deleteRecursively(st.meta.dir());
            } finally {
                st.lock.writeLock().unlock();
            }
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    public List<String> listTables() {
        globalLock.readLock().lock();
        try {
            return List.copyOf(states.keySet());
        } finally {
            globalLock.readLock().unlock();
        }
    }

    public TableInfo tableInfo(String name) {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return new TableInfo(st.meta.name(), st.meta.schema(), st.meta.retentionDays(),
                    st.meta.compressionLevel(), st.dict.size(), st.manifest.segments().size(),
                    st.manifest.totalRows(), st.manifest.totalSizeBytes());
        } finally {
            st.lock.readLock().unlock();
        }
    }

    // ---- 数据写入 ----

    public SnapshotIngestor.IngestResult ingest(String name, InputStream csv, Long timestamp) throws IOException {
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            return SnapshotIngestor.ingest(st.meta, st.dict, st.manifest, st.compressor, csv, timestamp, zone);
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    /** 以 SnapshotData（列缓冲）导入：供 CSV 之外的导入方式复用同一落盘入口。 */
    public SnapshotIngestor.IngestResult ingest(String name, com.astradb.core.ingest.SnapshotData data, Long timestamp)
            throws IOException {
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            return SnapshotIngestor.ingest(st.meta, st.dict, st.manifest, st.compressor, data, timestamp, zone);
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    /** 批量导入：多个快照一次落盘（点字典/段 fsync 次数大幅减少，时间戳须严格递增）。 */
    public List<SnapshotIngestor.IngestResult> ingestBatch(String name,
                                                           List<SnapshotIngestor.BatchSnapshot> snapshots)
            throws IOException {
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            return SnapshotIngestor.ingestBatch(st.meta, st.dict, st.manifest, st.compressor, snapshots, zone);
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    // ---- 查询 ----

    public SnapshotQuery.SnapshotPage snapshot(String name, long ts, int offset, int limit) throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return SnapshotQuery.getSnapshot(st.meta, st.dict, st.manifest, st.compressor, cache, ts, offset, limit);
        } finally {
            st.lock.readLock().unlock();
        }
    }

    /** 全量快照（不分页）：精确时间点匹配，一次返回该快照全部行。 */
    public SnapshotQuery.FullSnapshot fullSnapshot(String name, long ts) throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return SnapshotQuery.getFullSnapshot(st.meta, st.dict, st.manifest, st.compressor, cache, ts);
        } finally {
            st.lock.readLock().unlock();
        }
    }

    /** 单点历史序列。 */
    public List<PointSeriesQuery.PointRecord> series(String name, String key, long from, long to, int limit)
            throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return PointSeriesQuery.getSeries(st.meta, st.dict, st.manifest, st.compressor, cache, key, from, to, limit);
        } finally {
            st.lock.readLock().unlock();
        }
    }

    // ---- 维护 ----

    public int cleanRetention(String name) throws IOException {
        return cleanRetention(name, System.currentTimeMillis());
    }

    public int cleanRetention(String name, long now) throws IOException {
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            return RetentionCleaner.clean(st.meta, st.manifest, now);
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    public TableStats stats(String name) {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return new TableStats(st.meta.name(), st.dict.size(), st.manifest.segments().size(),
                    st.manifest.totalRows(), st.manifest.totalSizeBytes(), st.manifest.segments());
        } finally {
            st.lock.readLock().unlock();
        }
    }

    /** 全部快照时间戳（升序，遍历段 ChunkIndex）。 */
    public List<Long> listSnapshots(String name) throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            List<Long> out = new ArrayList<>();
            for (Manifest.SegmentInfo seg : st.manifest.segments()) {
                Path p = st.meta.dir().resolve(seg.path());
                try (SegmentReader r = SegmentReader.open(p)) {
                    for (int i = 0; i < r.chunkCount(); i++) {
                        out.add(r.timestampAt(i));
                    }
                }
            }
            return out;
        } finally {
            st.lock.readLock().unlock();
        }
    }

    /** 指定段内每个快照的时间戳与行数（数据文件查看用）。 */
    public List<SegmentSnapshotInfo> listSegmentSnapshots(String name, String relativePath) throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            Path seg = resolveSegment(st.meta, relativePath);
            if (!Files.exists(seg)) {
                throw new IllegalArgumentException("段文件不存在: " + relativePath);
            }
            List<SegmentSnapshotInfo> out = new ArrayList<>();
            try (SegmentReader r = SegmentReader.open(seg)) {
                for (int i = 0; i < r.chunkCount(); i++) {
                    out.add(new SegmentSnapshotInfo(r.timestampAt(i), r.entry(i).rowCount()));
                }
            }
            return out;
        } finally {
            st.lock.readLock().unlock();
        }
    }

    /** 删除指定段文件（不可恢复），同步更新 manifest。 */
    public void deleteSegment(String name, String relativePath) throws IOException {
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            Path seg = resolveSegment(st.meta, relativePath);
            Manifest.SegmentInfo info = null;
            for (Manifest.SegmentInfo s : st.manifest.segments()) {
                if (s.path().equals(relativePath)) {
                    info = s;
                    break;
                }
            }
            if (info == null) {
                throw new IllegalArgumentException("段不存在（manifest 未记录）: " + relativePath);
            }
            Files.deleteIfExists(seg);
            st.manifest.remove(relativePath);
            st.manifest.save();
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    /** 段路径解析与穿越校验：仅允许 segments/ 目录内的相对路径。 */
    private static Path resolveSegment(TableMeta meta, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("段路径不能为空");
        }
        Path root = meta.dir().resolve("segments").normalize();
        Path seg = meta.dir().resolve(relativePath).normalize();
        if (!seg.startsWith(root)) {
            throw new IllegalArgumentException("非法段路径（越出 segments 目录）: " + relativePath);
        }
        return seg;
    }

    public Path dataDir() {
        return dataDir;
    }

    /** 取表状态：全局读锁保护 map 查找（锁序：global → table）。 */
    private TableState stateOf(String name) {
        globalLock.readLock().lock();
        try {
            TableState st = states.get(name);
            if (st == null) {
                throw new IllegalArgumentException("表不存在: " + name);
            }
            return st;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    static void validateName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("非法表名（空或超长）");
        }
        for (char c : name.toCharArray()) {
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("非法表名（含路径分隔符或控制字符）: " + name);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public void close() {
        // 所有写入均即时落盘（fsync），无内存态需要冲刷
    }

    /** 供 JSON 序列化统计引用。 */
    public static String json(Object value) throws IOException {
        return JsonFiles.toJson(value);
    }
}
