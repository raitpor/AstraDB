package com.astradb.core;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.ingest.SnapshotIngestor.BatchSnapshot;
import com.astradb.core.ingest.SnapshotIngestor.IngestResult;
import com.astradb.core.manifest.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * review 4.1 should-fix 专项回归：
 * SF-1 删除清理幂等记录 / SF-2 混合批导入 / SF-3 损坏段启动隔离 /
 * SF-4 跨表写真正并行（幂等锁按表拆分）/ SF-6 占位确认精确行数。
 */
class ReviewShouldFixTest {

    @TempDir
    Path tmp;

    private static final long T0 = 1_767_225_600_000L;
    private static final long DAY = 86_400_000L;

    // ---- SF-1：删除后同 ts 同内容重放应真正写入（不再被幂等记录静默跳过） ----

    @Test
    void deleteSnapshotCleansIdemAndReplayWrites() throws Exception {
        Path dataDir = tmp.resolve("sf1a");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.deleteSnapshot("t", T0, true);
            // SF-1 修复前：命中残留正式幂等记录 → 静默跳过，数据缺失；修复后：真正写入
            IngestResult r = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            assertEquals(2, r.rowCount());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
        }
    }

    @Test
    void deleteSegmentCleansIdemAndReplayWrites() throws Exception {
        Path dataDir = tmp.resolve("sf1b");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,3.0\n", TestSupport.simpleColumns(false)), T0 + 600_000);
            String segPath = db.stats("t").segments().get(0).path();
            db.deleteSegment("t", segPath);
            IngestResult r = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            assertEquals(2, r.rowCount());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
        }
    }

    @Test
    void idemCleanSurvivesRestart() throws Exception {
        Path dataDir = tmp.resolve("sf1c");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.deleteSnapshot("t", T0, true);
        }
        try (AstraDB db = AstraDB.open(dataDir)) {
            // 磁盘 idempotency.idx 已被重写剔除 → 重启后重放不命中 → 真正写入
            IngestResult r = db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            assertEquals(2, r.rowCount());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
        }
    }

    // ---- SF-2：混合批（部分重放 + 部分新增）不再抛"时间戳已存在" ----

    @Test
    void mixedBatchReplayAndNewSucceeds() throws Exception {
        Path dataDir = tmp.resolve("sf2");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            List<BatchSnapshot> batch = List.of(
                    new BatchSnapshot(TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0),
                    new BatchSnapshot(TestSupport.csv("1,5.0\n", TestSupport.simpleColumns(false)), T0 + 600_000));
            List<IngestResult> rs = db.ingestBatch("t", batch);
            assertEquals(2, rs.size());
            assertEquals(T0, rs.get(0).timestamp());
            assertEquals(2, rs.get(0).rowCount()); // 重放：原结果
            assertEquals(T0 + 600_000, rs.get(1).timestamp());
            assertEquals(1, rs.get(1).rowCount()); // 新增
            assertEquals(2, db.listSnapshots("t").size());
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
            assertEquals(1, db.snapshot("t", T0 + 600_000, 0, 10).totalRows());
        }
    }

    // ---- SF-3：损坏段启动隔离，库可正常打开 ----

    @Test
    void corruptSegmentQuarantinedOnOpen() throws Exception {
        Path dataDir = tmp.resolve("sf3");
        List<String> segPaths;
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,2.0\n", TestSupport.simpleColumns(false)), T0 + DAY);
            segPaths = db.stats("t").segments().stream().map(Manifest.SegmentInfo::path).toList();
            assertEquals(2, segPaths.size());
        }
        // 破坏第一天段文件头（magic → SegmentFormat.readFileHeader 抛 IOException）
        Path bad = dataDir.resolve("t").resolve(segPaths.get(0));
        try (RandomAccessFile raf = new RandomAccessFile(bad.toFile(), "rw")) {
            raf.seek(0);
            raf.write(0);
        }
        try (AstraDB db = AstraDB.open(dataDir)) {
            // SF-3 修复前：任一损坏段使整个库无法 open；修复后：损坏段被隔离，好段可查询
            assertEquals(1, db.stats("t").segments().size());
            assertEquals(1, db.snapshot("t", T0 + DAY, 0, 10).totalRows());
        }
        // 隔离产物留在 segments/.quarantine（*.corrupt 后缀，启动校验不扫，避免重复隔离）
        try (Stream<Path> walk = Files.walk(dataDir.resolve("t/segments/.quarantine"))) {
            assertTrue(walk.anyMatch(p -> p.getFileName().toString().endsWith(".corrupt")));
        }
    }

    // ---- SF-4：跨表写真正并行（幂等锁按表拆分，不再被全局锁串行化） ----

    @Test
    void crossTableWritesTrulyParallel() throws Exception {
        Path dataDir = tmp.resolve("sf4");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("small", TestSupport.simpleColumns(false), "id", 30, 3);
            db.createTable("big", TestSupport.simpleColumns(false), "id", 30, 3);
            StringBuilder bigCsv = new StringBuilder();
            for (int i = 1; i <= 200_000; i++) {
                bigCsv.append(i).append(',').append(i * 1.0).append('\n');
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch bigDone = new CountDownLatch(1);
            pool.submit(() -> {
                try {
                    start.await();
                    db.ingest("big", TestSupport.csv(bigCsv.toString(), TestSupport.simpleColumns(false)), T0);
                } catch (Exception ignored) {
                } finally {
                    bigDone.countDown();
                }
            });
            start.countDown();
            db.ingest("small", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), T0);
            boolean bigFinished = bigDone.await(0, TimeUnit.MILLISECONDS);
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            // SF-4 修复前：全局幂等锁使 small 等待 big 完成 → bigFinished=true；修复后：真正并行
            assertFalse(bigFinished, "跨表写应真正并行（幂等锁已按表拆分）: small 完成时 big 不应已完成");
            assertEquals(1, db.snapshot("small", T0, 0, 10).totalRows());
            assertEquals(200_000, db.snapshot("big", T0, 0, 300_000).totalRows());
        }
    }

    // ---- SF-6：占位确认返回精确 chunk 行数（不再误用整段行数 / long→int 溢出） ----

    @Test
    void placeholderConfirmReturnsExactRows() throws Exception {
        Path dataDir = tmp.resolve("sf6");
        SnapshotData data = TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false));
        long hash = data.contentHash64();
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", data, T0);
        }
        // 模拟崩溃残留：文件尾追加同 ts 同 hash 的占位记录（rowCount=-1）
        Path idem = dataDir.resolve("t/idempotency.idx");
        try (FileChannel ch = FileChannel.open(idem,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bb = ByteBuffer.allocate(24);
            bb.putLong(T0).putLong(hash).putInt(-1).putInt(-1);
            bb.flip();
            ch.write(bb);
        }
        try (AstraDB db = AstraDB.open(dataDir)) {
            IngestResult r = db.ingest("t",
                    TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            assertEquals(T0, r.timestamp());
            assertEquals(2, r.rowCount());  // SF-6：精确 chunk 行数（而非整段行数）
            assertEquals(0, r.newPoints()); // 占位确认无法恢复 newPoints，置 0（标注语义）
            assertEquals(2, db.snapshot("t", T0, 0, 10).totalRows());
        }
    }
}
