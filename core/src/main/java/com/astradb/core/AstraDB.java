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
import com.astradb.core.segment.SegmentChannelCache;
import com.astradb.core.segment.SegmentPaths;
import com.astradb.core.segment.SegmentReader;
import com.astradb.core.segment.SegmentRewriter;
import com.astradb.core.util.FsUtil;

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

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(AstraDB.class.getName());

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
        /** 本表幂等记录（SF-4：per-table，跨表导入不再共享全局锁；上限 IDEMPOTENCY_MAX/表）。 */
        final java.util.Map<String, IdemEntry> idempotency = new java.util.LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, IdemEntry> eldest) {
                return size() > IDEMPOTENCY_MAX;
            }
        };
        /** 本表幂等锁：快速路径（表锁外检查）与慢路径（表写锁内）共用；锁序 global read → table write → idem。 */
        final Object idemLock = new Object();

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
    /** 段文件句柄空闲池（复用 FileChannel，减少查询反复打开/关闭）。 */
    private final SegmentChannelCache segmentChannels;
    /** dataDir 排他文件锁（防多进程同目录互写，O-03）。 */
    private java.nio.channels.FileLock dataDirLock;

    private static final int IDEMPOTENCY_MAX = 100_000;

    private record IdemEntry(long hash, int rowCount, int newPoints, long timestamp) {
    }

    private static String idemKey(String table, long ts) {
        return table + "\u0000" + ts;
    }

    /** S-3：段内是否已含该时间戳（轻量，仅占位命中时调用；表写锁内）。
     *  @return 该 ts 所在 chunk 的精确行数；不存在返回 -1（SF-6：占位确认路径不再误用整段行数）。 */
    private int timestampRowCount(TableState st, long ts) {
        Manifest.SegmentInfo si = st.manifest.lastAtOrBefore(ts);
        if (si == null) {
            return -1;
        }
        try (SegmentReader r = SegmentReader.open(st.meta.dir().resolve(si.path()), null)) {
            int idx = r.findChunkAtOrBefore(ts);
            if (idx >= 0 && r.timestampAt(idx) == ts) {
                return r.entry(idx).rowCount();
            }
            return -1;
        } catch (IOException e) {
            return -1; // 段读取失败 → 视为未提交（导入时重复 ts 会拒绝，安全方向）
        }
    }

    private static final int IDEM_ENTRY_BYTES = 24;      // ts(8) + hash64(8) + rowCount(4) + newPoints(4)
    private static final long IDEM_FILE_MAX_ENTRIES = 200_000;

    private static Path idemFile(Path tableDir) {
        return tableDir.resolve("idempotency.idx");
    }

    /** 追加一条幂等记录并 fsync（崩溃重启后重放仍可判定）。
     *  若文件尾为同 ts 占位记录（rowCount<0）则截断该条再写正式记录，避免占位+正式双条残留。 */
    private static void appendIdem(Path tableDir, IdemEntry e) {
        try {
            Path f = idemFile(tableDir);
            long entries = Files.exists(f) ? Files.size(f) / IDEM_ENTRY_BYTES : 0;
            if (entries >= IDEM_FILE_MAX_ENTRIES) {
                rewriteIdem(tableDir);
            }
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE)) {
                long size = ch.size();
                if (size >= IDEM_ENTRY_BYTES) {
                    java.nio.ByteBuffer tail = java.nio.ByteBuffer.allocate(IDEM_ENTRY_BYTES);
                    ch.read(tail, size - IDEM_ENTRY_BYTES);
                    tail.flip();
                    long tailTs = tail.getLong();
                    tail.getLong();
                    int tailRc = tail.getInt();
                    if (tailTs == e.timestamp() && tailRc < 0) {
                        ch.truncate(size - IDEM_ENTRY_BYTES); // 移除尾部占位
                        size -= IDEM_ENTRY_BYTES;
                    }
                }
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(IDEM_ENTRY_BYTES);
                bb.putLong(e.timestamp()).putLong(e.hash()).putInt(e.rowCount()).putInt(e.newPoints());
                bb.flip();
                ch.position(size); // 追加语义（JDK 禁止 READ+APPEND 组合）
                ch.write(bb);
                ch.force(true);
            }
        } catch (IOException ex) {
            LOG.warning("幂等记录写入失败（降级为进程内幂等）: " + ex);
        }
    }

    /** 批量追加幂等记录：一次 open + 写 + fsync（S-2，避免每快照一次 fsync）。 */
    private static void appendIdemBatch(Path tableDir, java.util.List<IdemEntry> es) {
        try {
            Path f = idemFile(tableDir);
            long entries = Files.exists(f) ? Files.size(f) / IDEM_ENTRY_BYTES : 0;
            if (entries + es.size() >= IDEM_FILE_MAX_ENTRIES) {
                rewriteIdem(tableDir);
            }
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(IDEM_ENTRY_BYTES * es.size());
                for (IdemEntry e : es) {
                    bb.putLong(e.timestamp()).putLong(e.hash()).putInt(e.rowCount()).putInt(e.newPoints());
                }
                bb.flip();
                ch.write(bb);
                ch.force(true);
            }
        } catch (IOException ex) {
            LOG.warning("幂等记录批量写入失败（降级为进程内幂等）: " + ex);
        }
    }

    /** 幂等文件超限：保留尾部记录后重写。 */
    private static void rewriteIdem(Path tableDir) {
        try {
            Path f = idemFile(tableDir);
            long total = Files.exists(f) ? Files.size(f) / IDEM_ENTRY_BYTES : 0;
            long keep = Math.min(total, IDEMPOTENCY_MAX);
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f,
                    java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
                long start = (total - keep) * IDEM_ENTRY_BYTES;
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) (keep * IDEM_ENTRY_BYTES));
                ch.read(bb, start);
                bb.flip();
                ch.truncate(0);
                ch.write(bb, 0);
                ch.force(true);
            }
        } catch (IOException ex) {
            LOG.warning("幂等文件超限重写失败（保留原文件）: " + ex);
        }
    }

    /** SF-1：删除快照/段后清理对应 ts 的幂等记录：内存 map 移除 + 磁盘幂等文件原子重写（表写锁内调用）。 */
    private void removeIdem(TableState st, java.util.Set<Long> tsSet) {
        if (tsSet == null || tsSet.isEmpty()) {
            return;
        }
        synchronized (st.idemLock) {
            for (long ts : tsSet) {
                st.idempotency.remove(idemKey(st.meta.name(), ts));
            }
        }
        rewriteIdemExcluding(st.meta.dir(), tsSet);
    }

    /** 重写幂等文件，剔除指定 ts（SF-1 删除路径）：临时文件 + fsync + 原子替换 + 目录 fsync；
     *  全部剔除时直接删除文件。失败仅告警（进程内已移除；磁盘残留会在重启后重新加载，
     *  同 ts 同内容重放仍可能被跳过——与幂等写入失败的降级语义一致）。 */
    private static void rewriteIdemExcluding(Path tableDir, java.util.Set<Long> exclude) {
        try {
            Path f = idemFile(tableDir);
            if (!Files.exists(f) || Files.size(f) == 0) {
                return;
            }
            java.util.List<IdemEntry> keep = new java.util.ArrayList<>();
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f,
                    java.nio.file.StandardOpenOption.READ)) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) Files.size(f));
                ch.read(bb, 0);
                bb.flip();
                while (bb.remaining() >= IDEM_ENTRY_BYTES) {
                    long ts = bb.getLong();
                    long hash = bb.getLong();
                    int rc = bb.getInt();
                    int np = bb.getInt();
                    if (!exclude.contains(ts)) {
                        keep.add(new IdemEntry(hash, rc, np, ts));
                    }
                }
            }
            if (keep.isEmpty()) {
                Files.deleteIfExists(f);
                FsUtil.fsyncDir(tableDir);
                return;
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(IDEM_ENTRY_BYTES * keep.size());
                for (IdemEntry e : keep) {
                    bb.putLong(e.timestamp()).putLong(e.hash()).putInt(e.rowCount()).putInt(e.newPoints());
                }
                bb.flip();
                ch.write(bb);
                ch.force(true);
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            FsUtil.fsyncDir(tableDir);
        } catch (IOException ex) {
            LOG.warning("幂等记录删除重写失败（进程内已移除，磁盘残留重启后可能重新生效）: " + ex);
        }
    }

    /** 启动加载表幂等记录（文件损坏 → 忽略并降级为空）。 */
    private void loadIdem(String table, Path tableDir) {
        TableState st = states.get(table);
        if (st == null) {
            return;
        }
        try {
            Path f = idemFile(tableDir);
            if (!Files.exists(f)) {
                return;
            }
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f,
                    java.nio.file.StandardOpenOption.READ)) {
                long total = ch.size() / IDEM_ENTRY_BYTES;
                long start = Math.max(0, (total - IDEMPOTENCY_MAX) * IDEM_ENTRY_BYTES);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) (ch.size() - start));
                ch.read(bb, start);
                bb.flip();
                synchronized (st.idemLock) {
                    while (bb.remaining() >= IDEM_ENTRY_BYTES) {
                        long ts = bb.getLong();
                        long hash = bb.getLong();
                        int rc = bb.getInt();
                        int np = bb.getInt();
                        st.idempotency.put(idemKey(table, ts), new IdemEntry(hash, rc, np, ts));
                    }
                }
            }
        } catch (IOException ex) {
            LOG.warning("幂等文件加载失败（降级为进程内幂等）: " + ex);
        }
    }

    private static final int DEFAULT_MAX_CHANNELS = 64;

    private AstraDB(Path dataDir, int defaultCompressionLevel, java.time.ZoneId zone, TablesStore store,
                    long cacheBytes) {
        this.dataDir = dataDir;
        this.defaultCompressionLevel = defaultCompressionLevel;
        this.zone = zone;
        this.store = store;
        this.cache = new ChunkCache(cacheBytes);
        this.segmentChannels = new SegmentChannelCache(DEFAULT_MAX_CHANNELS);
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
        // O-03 dataDir 排他锁：防多进程同目录互写（tables.json rename / 段追加竞争）
        Path lockFile = dataDir.resolve(".lock");
        java.nio.channels.FileChannel lc = java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock = null;
        try {
            try {
                lock = lc.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                throw new IOException("数据目录已被其他进程锁定（已有 AstraDB 实例运行）: " + dataDir);
            }
            TablesStore store = TablesStore.load(dataDir);
            AstraDB db = new AstraDB(dataDir, defaultCompressionLevel, zone, store, cacheBytes);
            db.dataDirLock = lock;
            for (TableMeta meta : store.all()) {
                db.loadTable(meta);
                db.loadIdem(meta.name(), meta.dir());
            }
            return db;
        } catch (IOException | RuntimeException e) {
            // S-1：加载失败时释放锁与句柄（同 JVM 重试 open 不再误报"已被其他进程锁定"）
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException ignored) {
                }
            }
            try {
                lc.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
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
        validateManifest(meta, dict, manifest, new ZstdCompressor(meta.compressionLevel()), segmentChannels);
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
                                         Compressor compressor, SegmentChannelCache channels)
            throws IOException {
        Path segRoot = meta.dir().resolve("segments");
        Set<String> disk = new HashSet<>();
        List<Manifest.SegmentInfo> diskInfos = new ArrayList<>();
        if (Files.isDirectory(segRoot)) {
            // 清理重写中断残留的临时文件（*.tmp，启动校验只扫 .seg）
            try (Stream<Path> walk = Files.walk(segRoot)) {
                for (Path p : walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()) {
                    Files.deleteIfExists(p);
                }
            }
            try (Stream<Path> walk = Files.walk(segRoot)) {
                for (Path p : walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".seg")).toList()) {
                    String rel = SegmentPaths.relative(p, meta.dir());
                    Manifest.SegmentInfo light;
                    try {
                        light = describeSegmentLight(meta, p, rel, channels);
                    } catch (IOException | RuntimeException e) {
                        // SF-3：损坏段启动隔离，不阻塞整个库打开
                        quarantineCorruptSegment(meta, p, rel, e);
                        continue;
                    }
                    disk.add(rel);
                    Manifest.SegmentInfo old = findSegment(manifest, rel);
                    if (old == null || !sameSegmentInfo(old, light)) {
                        // 缺失或信息漂移 → 精确重建（解码主键列计算 minKey/maxKey）
                        try {
                            diskInfos.add(describeSegmentPrecise(meta, p, rel, compressor, channels));
                        } catch (IOException | RuntimeException e) {
                            // 轻量描述通过但解码损坏（bit rot 在数据区）→ 同样隔离
                            quarantineCorruptSegment(meta, p, rel, e);
                        }
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

    /** SF-3：启动隔离损坏段：移至 segments/.quarantine/（*.corrupt 后缀，启动校验不扫）并告警，
     *  保证单个损坏段不阻塞整个库打开；隔离失败（如权限）则删除并告警（数据已不可读，
     *  避免每次启动重复隔离）；隔离与删除均失败则抛错（维持原"库打不开"语义并留痕）。 */
    private static void quarantineCorruptSegment(TableMeta meta, Path p, String rel, Exception cause) {
        Path qDir = meta.dir().resolve("segments/.quarantine");
        Path qTarget = qDir.resolve(p.getFileName() + ".corrupt");
        try {
            Files.createDirectories(qDir);
            int seq = 1;
            while (Files.exists(qTarget)) {
                qTarget = qDir.resolve(p.getFileName() + ".corrupt." + seq++);
            }
            Files.move(p, qTarget);
            LOG.warning("启动隔离损坏段 " + rel + " → " + qTarget + "（" + cause + "）");
            FsUtil.fsyncDir(qDir); // 隔离目录 rename 目录项落盘
            FsUtil.fsyncDir(qDir.getParent()); // segments 目录 delete 目录项落盘
        } catch (IOException moveFailed) {
            try {
                Files.deleteIfExists(p);
                LOG.warning("损坏段隔离失败，已删除（数据不可读）: " + rel + "（" + cause + "）");
                FsUtil.fsyncDir(p.getParent());
            } catch (IOException delFailed) {
                LOG.severe("损坏段既无法隔离也无法删除，启动失败: " + rel + "（" + cause + "）");
                throw new IllegalStateException("损坏段处理失败: " + rel, cause);
            }
        }
    }

    /** 轻量描述：仅读段头与 ChunkIndex（不解码数据列），min/max 占位。 */
    private static Manifest.SegmentInfo describeSegmentLight(TableMeta meta, Path p, String rel,
                                                                 SegmentChannelCache channels)
            throws IOException {
        try (SegmentReader reader = SegmentReader.open(p, channels)) {
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
    private static Manifest.SegmentInfo describeSegmentPrecise(TableMeta meta, Path p, String rel,
                                                                      Compressor compressor, SegmentChannelCache channels)
            throws IOException {
        try (SegmentReader reader = SegmentReader.open(p, channels)) {
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
            if (pkIndex != 0) {
                throw new IllegalArgumentException("主键列必须为第一列（当前第 " + (pkIndex + 1) + " 列）");
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

    /** 以 SnapshotData（列缓冲）导入：唯一单快照入口（core 导入前校验表结构与列类型；O-02 幂等）。 */
    public SnapshotIngestor.IngestResult ingest(String name, com.astradb.core.ingest.SnapshotData data, Long timestamp)
            throws IOException {
        long ts = timestamp != null ? timestamp : System.currentTimeMillis();
        long hash = data.contentHash64();
        String key = idemKey(name, ts);
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            synchronized (st.idemLock) {
                IdemEntry prev = st.idempotency.get(key);
                if (prev != null && prev.hash() == hash && prev.rowCount() >= 0) {
                    // 正式记录命中：同内容重放，跳过（幂等返回原结果）
                    return new SnapshotIngestor.IngestResult(prev.timestamp(), prev.rowCount(), prev.newPoints());
                }
                if (prev != null && prev.hash() == hash && prev.rowCount() < 0) {
                    // S-3 占位记录（段提交后、正式记录前崩溃的残留）：确认 ts 是否已提交
                    int rows = timestampRowCount(st, ts);
                    if (rows >= 0) {
                        // SF-6：返回精确 chunk 行数；newPoints 无法从已合并点字典恢复，置 0（标注语义）
                        return new SnapshotIngestor.IngestResult(ts, rows, 0);
                    }
                    st.idempotency.remove(key); // 未提交：占位作废，正常导入
                }
                // S-3 预写占位（段提交前先落盘；崩溃后由占位命中路径确认）
                IdemEntry placeholder = new IdemEntry(hash, -1, -1, ts);
                st.idempotency.put(key, placeholder);
                appendIdem(st.meta.dir(), placeholder);
                SnapshotIngestor.IngestResult r = SnapshotIngestor.ingest(
                        st.meta, st.dict, st.manifest, st.compressor, data, timestamp, zone, segmentChannels::evict);
                IdemEntry e = new IdemEntry(hash, r.rowCount(), r.newPoints(), r.timestamp());
                st.idempotency.put(key, e);
                appendIdem(st.meta.dir(), e); // 追加正式记录（覆盖文件尾同 ts 占位，见 appendIdem）
                return r;
            }
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    /** 批量导入：多个快照一次落盘（点字典/段 fsync 次数大幅减少，时间戳须严格递增）。 */
    public List<SnapshotIngestor.IngestResult> ingestBatch(String name,
                                                           List<SnapshotIngestor.BatchSnapshot> snapshots)
            throws IOException {
        // O-02 幂等：整批重放（全部命中正式记录且同内容）→ 直接返回，不写盘（快速路径）
        TableState st = stateOf(name);
        java.util.List<SnapshotIngestor.IngestResult> replayed = new java.util.ArrayList<>(snapshots.size());
        boolean allHit;
        synchronized (st.idemLock) {
            allHit = true;
            for (SnapshotIngestor.BatchSnapshot bs : snapshots) {
                long ts = bs.timestamp();
                IdemEntry prev = st.idempotency.get(idemKey(name, ts));
                if (prev == null || prev.hash() != bs.data().contentHash64() || prev.rowCount() < 0) {
                    allHit = false;
                    break;
                }
                replayed.add(new SnapshotIngestor.IngestResult(prev.timestamp(), prev.rowCount(), prev.newPoints()));
            }
        }
        if (allHit) {
            return replayed;
        }
        st.lock.writeLock().lock();
        try {
            // SF-2 混合批：批内部分快照可能已命中正式幂等记录 → 重放返回；仅未命中者进入 ingestBatch。
            // 占位记录（rowCount<0）先确认 ts 是否已提交：已提交 → 精确返回；未提交 → 作废并重新导入。
            java.util.List<SnapshotIngestor.BatchSnapshot> toIngest = new java.util.ArrayList<>(snapshots.size());
            java.util.List<IdemEntry> placeholders = new java.util.ArrayList<>(snapshots.size());
            java.util.List<SnapshotIngestor.IngestResult> confirmed = new java.util.ArrayList<>(snapshots.size());
            int[] ingestPos = new int[snapshots.size()]; // 原序 → toIngest 序；-1 表示该位置已确认（重放/占位确认）
            java.util.Arrays.fill(ingestPos, -1);
            synchronized (st.idemLock) {
                for (int i = 0; i < snapshots.size(); i++) {
                    SnapshotIngestor.BatchSnapshot bs = snapshots.get(i);
                    long ts = bs.timestamp();
                    long hash = bs.data().contentHash64();
                    String key = idemKey(name, ts);
                    IdemEntry prev = st.idempotency.get(key);
                    if (prev != null && prev.hash() == hash && prev.rowCount() >= 0) {
                        // 正式记录命中：同内容重放（幂等返回原结果）
                        confirmed.add(new SnapshotIngestor.IngestResult(prev.timestamp(), prev.rowCount(), prev.newPoints()));
                        continue;
                    }
                    if (prev != null && prev.hash() == hash && prev.rowCount() < 0) {
                        // 占位记录残留：确认 ts 是否已提交（S-3，崩溃现场）
                        int rows = timestampRowCount(st, ts);
                        if (rows >= 0) {
                            confirmed.add(new SnapshotIngestor.IngestResult(ts, rows, 0)); // SF-6 语义
                            continue;
                        }
                        st.idempotency.remove(key); // 未提交：占位作废，正常导入
                    }
                    ingestPos[i] = toIngest.size();
                    toIngest.add(bs);
                    IdemEntry placeholder = new IdemEntry(hash, -1, -1, ts);
                    st.idempotency.put(key, placeholder); // S-3 预写占位
                    placeholders.add(placeholder);
                }
            }
            if (!placeholders.isEmpty()) {
                appendIdemBatch(st.meta.dir(), placeholders); // S-2 批内一次 fsync
            }
            java.util.List<SnapshotIngestor.IngestResult> rs = toIngest.isEmpty()
                    ? java.util.List.of()
                    : SnapshotIngestor.ingestBatch(st.meta, st.dict, st.manifest, st.compressor,
                            toIngest, zone, segmentChannels::evict);
            java.util.List<IdemEntry> finals = new java.util.ArrayList<>(toIngest.size());
            if (!toIngest.isEmpty()) {
                for (int i = 0; i < toIngest.size(); i++) {
                    SnapshotIngestor.BatchSnapshot bs = toIngest.get(i);
                    SnapshotIngestor.IngestResult r = rs.get(i);
                    finals.add(new IdemEntry(bs.data().contentHash64(), r.rowCount(), r.newPoints(), r.timestamp()));
                }
                synchronized (st.idemLock) {
                    for (int i = 0; i < toIngest.size(); i++) {
                        st.idempotency.put(idemKey(name, toIngest.get(i).timestamp()), finals.get(i));
                    }
                }
                appendIdemBatch(st.meta.dir(), finals); // S-2 批内一次 fsync
            }
            // 按原顺序合并结果：重放/占位确认 + 本次导入
            java.util.List<SnapshotIngestor.IngestResult> out = new java.util.ArrayList<>(snapshots.size());
            int confirmedIdx = 0;
            for (int i = 0; i < snapshots.size(); i++) {
                if (ingestPos[i] >= 0) {
                    out.add(rs.get(ingestPos[i]));
                } else {
                    out.add(confirmed.get(confirmedIdx++));
                }
            }
            return out;
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    // ---- 查询 ----

    public SnapshotQuery.SnapshotPage snapshot(String name, long ts, int offset, int limit) throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return SnapshotQuery.getSnapshot(st.meta, st.dict, st.manifest, st.compressor, cache, segmentChannels, ts, offset, limit);
        } finally {
            st.lock.readLock().unlock();
        }
    }

    /** 全量快照（不分页）：精确时间点匹配，一次返回该快照全部行。 */
    public SnapshotQuery.FullSnapshot fullSnapshot(String name, long ts) throws IOException {
        TableState st = stateOf(name);
        st.lock.readLock().lock();
        try {
            return SnapshotQuery.getFullSnapshot(st.meta, st.dict, st.manifest, st.compressor, cache, segmentChannels, ts);
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
            return PointSeriesQuery.getSeries(st.meta, st.dict, st.manifest, st.compressor, cache, segmentChannels, key, from, to, limit);
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
            return RetentionCleaner.clean(st.meta, st.manifest, now, segmentChannels,
                    tsSet -> removeIdem(st, tsSet)); // SF-1：清理被删段的幂等记录
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
                try (SegmentReader r = SegmentReader.open(p, segmentChannels)) {
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
            try (SegmentReader r = SegmentReader.open(seg, segmentChannels)) {
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
            // SF-1：删除段前枚举段内全部时间戳，供删除后清理幂等记录（防同 ts 同内容重放被静默跳过）
            java.util.Set<Long> tsSet = new java.util.HashSet<>();
            try (SegmentReader r = SegmentReader.open(seg, segmentChannels)) {
                for (int i = 0; i < r.chunkCount(); i++) {
                    tsSet.add(r.timestampAt(i));
                }
            }
            segmentChannels.evict(seg);
            Files.deleteIfExists(seg);
            FsUtil.fsyncDir(seg.getParent()); // SF-7：delete 目录项落盘
            st.manifest.remove(relativePath);
            st.manifest.save();
            removeIdem(st, tsSet); // SF-1：删除后同步清理幂等记录
        } finally {
            st.lock.writeLock().unlock();
        }
    }

    /** 删除指定时间点的快照（不可恢复，需 confirm=true）：段重写过滤该 chunk，段内时间戳保持有序。 */
    public void deleteSnapshot(String name, long ts, boolean confirm) throws IOException {
        if (!confirm) {
            throw new IllegalArgumentException("删除快照不可恢复，需携带 confirm=true");
        }
        TableState st = stateOf(name);
        st.lock.writeLock().lock();
        try {
            Manifest.SegmentInfo seg = null;
            for (Manifest.SegmentInfo s : st.manifest.segments()) {
                if (s.startTime() <= ts && ts <= s.endTime()) {
                    seg = s;
                    break;
                }
            }
            if (seg == null) {
                throw new IllegalArgumentException("快照不存在: " + ts);
            }
            Path segPath = st.meta.dir().resolve(seg.path());
            Schema schema = st.meta.schema();
            // 定位并确认目标时间戳在段内
            boolean found = false;
            try (SegmentReader r = SegmentReader.open(segPath, segmentChannels)) {
                int idx = r.findChunkAtOrBefore(ts);
                found = idx >= 0 && r.timestampAt(idx) == ts;
            }
            if (!found) {
                throw new IllegalArgumentException("快照不存在: " + ts);
            }
            SegmentRewriter.RewriteResult res = SegmentRewriter.rewrite(
                    segPath, SegmentPaths.dayStart(ts, zone), schema.version(), schema.columnCount(),
                    java.util.Set.of(ts), List.of());
            segmentChannels.evict(segPath);
            if (res.isEmpty()) {
                Files.deleteIfExists(segPath);
                FsUtil.fsyncDir(segPath.getParent()); // SF-7：delete 目录项落盘
                st.manifest.remove(seg.path());
            } else {
                // 删除后窗口精确重算（minKey/maxKey/endTime 可能收缩）
                Manifest.SegmentInfo precise = describeSegmentPrecise(st.meta, segPath, seg.path(),
                        st.compressor, segmentChannels);
                st.manifest.addOrMerge(precise);
            }
            st.manifest.save();
            removeIdem(st, java.util.Set.of(ts)); // SF-1：删除快照后清理幂等记录（防同 ts 同内容重放被跳过）
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
        // 所有写入均即时落盘（fsync），无内存态需要冲刷；释放段句柄池与 dataDir 锁
        segmentChannels.close();
        try {
            if (dataDirLock != null) {
                dataDirLock.release();
                dataDirLock.channel().close();
                dataDirLock = null;
            }
        } catch (IOException ignored) {
            // 锁释放失败不影响关闭
        }
    }

    /** 供 JSON 序列化统计引用。 */
    public static String json(Object value) throws IOException {
        return JsonFiles.toJson(value);
    }
}
