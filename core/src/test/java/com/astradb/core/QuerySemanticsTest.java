package com.astradb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询语义测试：精确时间点匹配、分页边界、全量流式、单点历史
 * （跨段并行归并、limit、未知 key、点消失）——组合断言验证查询契约。
 */
class QuerySemanticsTest {

    @TempDir
    Path tmp;

    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_767_225_600_000L;

    @Test
    void exactTimestampAndPageBoundaries() throws Exception {
        Path dataDir = tmp.resolve("q1");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            String csv = "1,10.0\n2,20.0\n3,30.0\n4,40.0\n5,50.0\n";
            db.ingest("t", TestSupport.csv(csv, TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,11.0\n", TestSupport.simpleColumns(false)), T0 + 300_000);

            // 精确匹配：无该时间点 → 空页（非最近快照）
            assertEquals(0, db.snapshot("t", T0 + 100_000, 0, 10).totalRows());
            assertEquals(5, db.snapshot("t", T0, 0, 10).totalRows());
            assertEquals(1, db.snapshot("t", T0 + 300_000, 0, 10).totalRows());
            // 分页边界：offset 越界 → 空；limit 截断
            assertEquals(0, db.snapshot("t", T0, 5, 10).rows().size());
            assertEquals(2, db.snapshot("t", T0, 0, 2).rows().size());
            assertEquals(3, db.snapshot("t", T0, 2, 3).rows().size());
            // 中间页内容（行对齐）
            var mid = db.snapshot("t", T0, 1, 3);
            assertEquals("2", mid.rows().get(0).key());
            assertEquals("4", mid.rows().get(2).key());
            // 全量快照（不分页）
            var full = db.fullSnapshot("t", T0);
            assertEquals(5, full.rows().size());
        }
    }

    @Test
    void seriesAcrossSegmentsMergedInTimeOrder() throws Exception {
        Path dataDir = tmp.resolve("q2");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            // 4 天 4 段，每天 2 快照
            for (int d = 0; d < 4; d++) {
                db.ingest("t", TestSupport.csv("1," + (10 + d) + ".0\n2," + (11 + d) + ".0\n", TestSupport.simpleColumns(false)),
                        T0 + d * DAY);
                db.ingest("t", TestSupport.csv("1," + (20 + d) + ".0\n2," + (21 + d) + ".0\n", TestSupport.simpleColumns(false)),
                        T0 + d * DAY + 300_000);
            }
            assertEquals(4, db.stats("t").segmentCount());
            var series = db.series("t", "1", 0, Long.MAX_VALUE, 100);
            assertEquals(8, series.size());
            for (int i = 1; i < series.size(); i++) {
                assertTrue(series.get(i).timestamp() > series.get(i - 1).timestamp(), "时间严格升序");
            }
            // limit 截断（跨段合并后取前 N）
            assertEquals(3, db.series("t", "1", 0, Long.MAX_VALUE, 3).size());
            // 时间窗口过滤
            assertEquals(3, db.series("t", "1", T0 + DAY, T0 + 2 * DAY, 100).size()); // to 闭区间
            // 未知 key → 空
            assertTrue(db.series("t", "99999", 0, Long.MAX_VALUE, 100).isEmpty());
        }
    }

    @Test
    void pointDisappearsBetweenSnapshots() throws Exception {
        Path dataDir = tmp.resolve("q3");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n3,3.0\n", TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,1.5\n3,3.5\n", TestSupport.simpleColumns(false)), T0 + 300_000);
            db.ingest("t", TestSupport.csv("1,2.0\n2,2.5\n3,4.0\n", TestSupport.simpleColumns(false)), T0 + 600_000);
            var series = db.series("t", "2", 0, Long.MAX_VALUE, 100);
            // 点 2 在中间快照消失：仅首尾 2 条，且时间不连续
            assertEquals(2, series.size());
            assertEquals(T0, series.get(0).timestamp());
            assertEquals(T0 + 600_000, series.get(1).timestamp());
            // 消失期间该时间点查询为空
            assertEquals(0, db.snapshot("t", T0 + 300_000, 0, 100).rows().stream()
                    .filter(r -> r.key().equals("2")).count());
        }
    }
}
