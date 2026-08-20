package com.astradb.core;

import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.ingest.SnapshotIngestor.BatchSnapshot;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 可靠性测试（optimization.md O-01/O-02/O-03）：
 * 批量导入原子化、幂等导入语义、dataDir 文件锁。
 */
class P0ReliabilityTest {

    @TempDir
    Path tmp;

    private static final long T0 = 1_577_808_000_000L; // 本地 2020-01-01 00:00

    private static List<Schema.ColumnDef> cols() {
        return List.of(
                new Schema.ColumnDef("pointId", ColumnType.INT, false),
                new Schema.ColumnDef("pointValue", ColumnType.DOUBLE, true));
    }

    // ---- O-03 dataDir 文件锁 ----

    @Test
    void dataDirLockRejectsSecondInstance() throws Exception {
        Path dir = tmp.resolve("lock");
        try (AstraDB db1 = AstraDB.open(dir)) {
            // 同 JVM 第二个 open 同目录 → 拒绝
            IOException e = assertThrows(IOException.class, () -> AstraDB.open(dir));
            assertTrue(e.getMessage().contains("已被其他进程锁定"), e.getMessage());
            db1.createTable("t", cols(), "pointId", 30, 3);
            db1.ingest("t", TestSupport.csv("1,1.0\n", cols()), T0);
        }
        // close 后释放 → 可重新打开且数据保留
        try (AstraDB db2 = AstraDB.open(dir)) {
            assertEquals(1, db2.snapshot("t", T0, 0, 10).totalRows());
        }
    }

    // ---- O-02 幂等导入 ----

    @Test
    void idempotentReplaySkipsSameContent() throws Exception {
        Path dir = tmp.resolve("idem");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            var r1 = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", cols()), T0);
            // 同内容重放 → 跳过（返回原结果，不产生新数据）
            var r2 = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", cols()), T0);
            assertEquals(r1.rowCount(), r2.rowCount());
            assertEquals(1, db.listSnapshots("t").size());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
            // 异内容 → 拒绝（重复时间戳）
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,9.9\n2,2.0\n", cols()), T0));
        }
    }

    @Test
    void idempotentBatchReplaySkipsWholeBatch() throws Exception {
        Path dir = tmp.resolve("idemb");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            List<BatchSnapshot> batch = List.of(
                    new BatchSnapshot(TestSupport.csv("1,1.0\n", cols()), T0),
                    new BatchSnapshot(TestSupport.csv("1,2.0\n", cols()), T0 + 300_000));
            var rs1 = db.ingestBatch("t", batch);
            assertEquals(2, rs1.size());
            // 整批重放（同内容）→ 跳过不写盘
            var rs2 = db.ingestBatch("t", batch);
            assertEquals(2, rs2.size());
            assertEquals(rs1.get(0).rowCount(), rs2.get(0).rowCount());
            assertEquals(2, db.listSnapshots("t").size());
        }
    }

    // ---- O-02 增强：跨重启幂等 / 64 位哈希 / 损坏降级 ----

    @Test
    void idempotencySurvivesRestart() throws Exception {
        Path dir = tmp.resolve("idemr");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            var r1 = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", cols()), T0);
            assertEquals(2, r1.rowCount());
        }
        // 重启后同内容重放 → 幂等跳过（不产生新数据、不报重复时间戳）
        try (AstraDB db = AstraDB.open(dir)) {
            var r2 = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", cols()), T0);
            assertEquals(2, r2.rowCount());
            assertEquals(1, db.listSnapshots("t").size());
            // 异内容 → 重复时间戳拒绝
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,9.9\n2,2.0\n", cols()), T0));
        }
    }

    @Test
    void corruptedIdemFileDegradesGracefully() throws Exception {
        Path dir = tmp.resolve("idemc");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n", cols()), T0);
        }
        // 损坏幂等文件（半条记录）
        java.nio.file.Files.write(dir.resolve("t/idempotency.idx"), new byte[]{1, 2, 3, 4});
        try (AstraDB db = AstraDB.open(dir)) {
            // 降级为空幂等表：正常启动、数据完整；重放同内容 → 重复时间戳拒绝（非幂等跳过，可接受降级）
            assertEquals(1, db.snapshot("t", T0, 0, 10).totalRows());
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,1.0\n", cols()), T0));
        }
    }

    @Test
    void hash64DistinguishesDifferentContents() {
        // 属性式：不同内容（值/位图/类型）哈希互异（抽样验证）
        var schema = new Schema(1, cols(), 0);
        long h1 = TestSupport.csv("1,1.0\n2,2.0\n", schema).contentHash64();
        long h2 = TestSupport.csv("1,1.5\n2,2.0\n", schema).contentHash64();
        long h3 = TestSupport.csv("1,,2.0\n2,2.0\n", schema).contentHash64(); // null 位图差异
        long h4 = TestSupport.csv("1,1.0\n", schema).contentHash64();
        assertTrue(h1 != h2 && h1 != h3 && h1 != h4 && h2 != h3 && h2 != h4 && h3 != h4,
                "64 位哈希应区分不同内容");
    }

    // ---- 评审修复回归（B-1 并发死锁 / B-2 STRING 碰撞 / S-1 锁异常路径） ----

    @Test
    void concurrentIngestAndIngestBatchNoDeadlock() throws Exception {
        Path dir = tmp.resolve("lockrace");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
            // 线程 A：连续单快照导入（不同 ts）
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        db.ingest("t", TestSupport.csv("1,1.0\n", cols()), T0 + i * 1000L);
                    }
                } catch (Throwable t) {
                    err.set(t);
                }
            });
            // 线程 B：连续批量导入（不同 ts 区间，与 A 无重叠避免重复 ts 干扰）
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        long base = T0 + 1_000_000L + i * 2000L;
                        db.ingestBatch("t", List.of(
                                new BatchSnapshot(TestSupport.csv("1,2.0\n", cols()), base),
                                new BatchSnapshot(TestSupport.csv("1,2.5\n", cols()), base + 1000L)));
                    }
                } catch (Throwable t) {
                    err.set(t);
                }
            });
            start.countDown();
            pool.shutdown();
            // 限时 20s：超时 = 死锁（B-1 场景）
            assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS), "并发导入疑似死锁（B-1）");
            pool.shutdownNow();
            assertEquals(null, err.get());
        }
    }

    @Test
    void stringHashDistinguishesHashCodeCollisions() {
        // B-2："Aa" 与 "BB" 是 String.hashCode() 已知冲突（同为 2112），contentHash64 必须区分
        var schema = new Schema(1, List.of(
                new Schema.ColumnDef("id", ColumnType.INT, false),
                new Schema.ColumnDef("s", ColumnType.STRING, false)), 0);
        long h1 = TestSupport.csv("1,Aa\n", schema).contentHash64();
        long h2 = TestSupport.csv("1,BB\n", schema).contentHash64();
        assertTrue(h1 != h2, "STRING 内容哈希应区分 String.hashCode 冲突对");
    }

    @Test
    void openFailureReleasesLockAndRetrySucceeds() throws Exception {
        Path dir = tmp.resolve("lockfail");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n", cols()), T0);
        }
        // 破坏 tables.json → open 失败
        java.nio.file.Files.writeString(dir.resolve("tables.json"), "{ broken json");
        assertThrows(java.io.IOException.class, () -> AstraDB.open(dir));
        // 修复后同 JVM 重试 open 必须成功（S-1：异常路径已释放锁）
        java.nio.file.Files.delete(dir.resolve("tables.json"));
        try (AstraDB db2 = AstraDB.open(dir)) {
            // 表目录残留（tables.json 空）→ 无表可查，验证可正常打开即可
            assertEquals(0, db2.listTables().size());
        }
    }

    // ---- O-01 批量导入原子化（新段 staging） ----

    @Test
    void batchAtomicNewSegmentsAndStagingCleanup() throws Exception {
        Path dir = tmp.resolve("atomic");
        try (AstraDB db = AstraDB.open(dir)) {
            db.createTable("t", cols(), "pointId", 30, 3);
            // 批量导入两个新段（跨天）
            List<BatchSnapshot> batch = List.of(
                    new BatchSnapshot(TestSupport.csv("1,1.0\n2,2.0\n", cols()), T0),
                    new BatchSnapshot(TestSupport.csv("1,3.0\n2,3.1\n", cols()), T0 + 86_400_000L));
            var rs = db.ingestBatch("t", batch);
            assertEquals(2, rs.size());
            // 两段均可见
            assertEquals(2, db.stats("t").segmentCount());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
            assertEquals(2, db.snapshot("t", T0 + 86_400_000L, 0, 10).totalRows());
        }
        // staging 残留模拟：重启前放置 .staging/*.tmp → 启动清理且不影响正式段
        Path stagingDir = dir.resolve("t/segments/.staging");
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("stale.seg.tmp"), new byte[]{1, 2, 3});
        try (AstraDB db = AstraDB.open(dir)) {
            assertTrue(!Files.exists(stagingDir.resolve("stale.seg.tmp")), "启动应清理 staging 残留");
            assertEquals(2, db.stats("t").segmentCount());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
        }
    }
}
