package com.astradb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据生命周期测试：manifest 重建/漂移纠正、保留期边界与清理、时区分片、
 * 崩溃截断恢复（Footer 缺失回退扫描）。
 */
class StorageLifecycleTest {

    @TempDir
    Path tmp;

    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_767_225_600_000L;

    @Test
    void manifestRebuildAfterDeletionAndDrift() throws Exception {
        Path dataDir = tmp.resolve("m");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,2.0\n2,2.1\n3,2.2\n", TestSupport.simpleColumns(false)), T0 + DAY);
            // 删除 manifest → 启动重建（磁盘段扫描，窗口精确）
            Files.deleteIfExists(dataDir.resolve("t/manifest.json"));
        }
        try (AstraDB db = AstraDB.open(dataDir)) {
            assertEquals(2, db.stats("t").segmentCount());
            assertEquals(2, db.snapshot("t", T0, 0, 100).totalRows());
            assertEquals(3, db.snapshot("t", T0 + DAY, 0, 100).totalRows());
            // 重建后窗口精确（minKey/maxKey 非保守）
            var seg = db.stats("t").segments().get(0);
            assertEquals(1, seg.minKey());
            assertEquals(2, seg.maxKey());
        }
    }

    @Test
    void manifestDriftCorrectedOnRestart() throws Exception {
        Path dataDir = tmp.resolve("drift");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            // 篡改 manifest：段 rows 改为错误值（模拟漂移）
            Path mf = dataDir.resolve("t/manifest.json");
            String content = Files.readString(mf);
            content = content.replace("\"rows\":2", "\"rows\":999");
            Files.writeString(mf, content);
        }
        try (AstraDB db = AstraDB.open(dataDir)) {
            // 启动两级校验：轻量描述与 manifest 不一致 → 精确重建
            assertEquals(2, db.stats("t").totalRows());
            assertEquals(2, db.snapshot("t", T0, 0, 100).totalRows());
        }
    }

    @Test
    void retentionBoundaryAndCleanup() throws Exception {
        Path dataDir = tmp.resolve("r");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 2, 3); // 保留 2 天
            long now = System.currentTimeMillis();
            db.ingest("t", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), now);
            db.ingest("t", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), now - DAY);
            db.ingest("t", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), now - 2 * DAY - 1000); // 超期（明确超 1 秒）
            int removed = db.cleanRetention("t", System.currentTimeMillis());
            assertEquals(1, removed);
            assertEquals(2, db.stats("t").segmentCount());
        }
    }

    @Test
    void timezoneSharding() throws Exception {
        Path dataDir = tmp.resolve("tz");
        // Asia/Shanghai：本地凌晨时间戳应落本地当天（而非 UTC 前一天）
        try (AstraDB db = AstraDB.open(dataDir, 3, ZoneId.of("Asia/Shanghai"))) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), 1_786_811_400_000L);
            String seg = db.stats("t").segments().get(0).path();
            assertTrue(seg.contains("2026-08-16"), "本地凌晨落本地当天: " + seg);
        }
    }

    @Test
    void crashRecoveryTruncatesPartialChunk() throws Exception {
        Path dataDir = tmp.resolve("c");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            // 模拟崩溃：段文件追加半截字节（Footer 破坏）
            Path seg = dataDir.resolve("t").resolve(db.stats("t").segments().get(0).path());
            Files.write(seg, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        }
        try (AstraDB db = AstraDB.open(dataDir)) {
            // Footer 校验失败 → 顺序扫描按 CRC 截断到完整 chunk
            assertEquals(1, db.stats("t").segmentCount());
            assertEquals(2, db.snapshot("t", T0, 0, 100).totalRows());
        }
    }
}
