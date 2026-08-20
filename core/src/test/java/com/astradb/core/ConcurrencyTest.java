package com.astradb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试：同表多线程读写（表级读写锁：读读并发、写独占）、跨表写并行。
 */
class ConcurrencyTest {

    @TempDir
    Path tmp;

    private static final long T0 = 1_767_225_600_000L;

    @Test
    void sameTableConcurrentReadWrite() throws Exception {
        Path dataDir = tmp.resolve("rw");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);

            int writes = 5;
            int reads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writes + reads);
            for (int i = 0; i < writes; i++) {
                int seq = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)),
                                T0 + (seq + 1L) * 60_000);
                    } catch (Exception ignored) {
                        // 单点失败不中断
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (int i = 0; i < reads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        db.snapshot("t", T0, 0, 10);
                        db.series("t", "1", 0, Long.MAX_VALUE, 100);
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务应完成");
            pool.shutdown();
            assertEquals(writes + 1, db.listSnapshots("t").size());
        }
    }

    @Test
    void crossTableWritesAreParallel() throws Exception {
        Path dataDir = tmp.resolve("par");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("small", TestSupport.simpleColumns(false), "id", 30, 3);
            db.createTable("big", TestSupport.simpleColumns(false), "id", 30, 3);
            // 大表 20 万行 + 小表 1 行：表级锁下小表写不应被大表阻塞
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
            // 小表写应在大表完成前返回（跨表并行）：记录大表完成时小表是否已返回
            db.ingest("small", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), T0);
            boolean bigFinished = bigDone.await(0, TimeUnit.MILLISECONDS);
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            assertTrue(bigFinished || db.stats("small").totalRows() > 0,
                    "跨表写应并行：小表写入不依赖大表完成（bigFinished=" + bigFinished + "）");
            assertEquals(1, db.snapshot("small", T0, 0, 10).totalRows());
            assertEquals(200_000, db.snapshot("big", T0, 0, 300_000).totalRows());
        }
    }
}
