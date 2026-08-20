package com.astradb.core;

import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.query.PointSeriesQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景驱动测试（依据 scenario.md）：完整业务场景链路组合断言——
 * 非固定间隔周期性快照 → 按时间点回查 → 点消失 → 回填空洞 → 删除快照 → 重启一致。
 */
class ScenarioBasedTest {

    @TempDir
    Path tmp;

    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_767_225_600_000L; // 2026-01-01（本地）

    /**
     * 场景：工厂设备每 5~7 分钟被动上报全量快照，某设备（点 5）中途下线消失、
     * 又恢复；期间一个时间点误导入需订正删除；跨天窗口持续累积。
     */
    @Test
    void factorySnapshotLifecycle() throws Exception {
        Path dataDir = tmp.resolve("factory");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("dev", TestSupport.snapshotColumns(), "id", 30, 3);

            // 三天，每天 3 个非固定间隔快照（间隔 5~7 分钟），点集 1..100 缓增
            long ts = T0;
            int snapshots = 0;
            for (int day = 0; day < 3; day++) {
                long dayStart = T0 + day * DAY;
                for (int s = 0; s < 3; s++) {
                    int points = 100 + day * 5 + s;
                    // 第 2 天起点 5 消失，第 3 天恢复
                    String csv = withPoint5(TestSupport.csvSnapshot(7L, points, snapshots, true),
                            day >= 1 && day < 2);
                    db.ingest("dev", TestSupport.csv(csv, TestSupport.snapshotColumns()), dayStart + s * 300_000L);
                    snapshots++;
                }
            }
            // 9 个快照 + 跨天 → 3 段
            assertEquals(3, db.stats("dev").segmentCount());
            assertEquals(9, db.listSnapshots("dev").size());

            // 按时间点回查：末快照行数与首快照一致
            long lastTs = T0 + 2 * DAY + 2 * 300_000L;
            var page = db.snapshot("dev", lastTs, 0, 1000);
            assertEquals(112, page.totalRows());
            // 单点历史：点 5 第 2 天消失（该段无记录）、第 3 天恢复
            List<PointSeriesQuery.PointRecord> p5 = db.series("dev", "5", 0, Long.MAX_VALUE, 100);
            assertEquals(6, p5.size(), "点 5 第 1/3 天各 3 快照 = 6 条");
            assertTrue(p5.get(2).timestamp() < T0 + DAY, "消失前最后记录在第 1 天");
            assertTrue(p5.get(3).timestamp() >= T0 + 2 * DAY, "恢复后首条在第 3 天");
            // 消失期间精确时间点无点 5
            List<Object> mid = pointAt(db, "dev", "5", T0 + DAY + 300_000L);
            assertTrue(mid.isEmpty());
        }
    }

    /** 场景：误导入订正——回填中间空洞后删除该快照，数据还原；重启后一致。 */
    @Test
    void backfillThenDeleteRestoresState() throws Exception {
        Path dataDir = tmp.resolve("correction");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("corr", TestSupport.simpleColumns(true), "id", 30, 3);
            db.ingest("corr", TestSupport.csv("1,10.0\n2,20.0\n", TestSupport.simpleColumns(true)), T0);
            db.ingest("corr", TestSupport.csv("1,40.0\n2,40.1\n3,40.2\n", TestSupport.simpleColumns(true)), T0 + 600_000);

            // 误回填一个中间快照
            db.ingest("corr", TestSupport.csv("1,30.0\n", TestSupport.simpleColumns(true)), T0 + 300_000);
            assertEquals(List.of(T0, T0 + 300_000, T0 + 600_000), db.listSnapshots("corr"));
            assertEquals(1, db.snapshot("corr", T0 + 300_000, 0, 100).totalRows());

            // 订正：删除该误导入快照 → 还原
            db.deleteSnapshot("corr", T0 + 300_000, true);
            assertEquals(List.of(T0, T0 + 600_000), db.listSnapshots("corr"));
            assertEquals(0, db.snapshot("corr", T0 + 300_000, 0, 100).totalRows());
        }
        // 重启一致
        try (AstraDB db = AstraDB.open(dataDir)) {
            assertEquals(List.of(T0, T0 + 600_000), db.listSnapshots("corr"));
            assertEquals(2, db.snapshot("corr", T0, 0, 100).totalRows());
        }
    }

    /** 场景：可空字段（传感器偶发缺失）在整条查询链路上保持 null 语义。 */
    @Test
    void nullableSensorsAcrossQueries() throws Exception {
        Path dataDir = tmp.resolve("sensors");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("s", TestSupport.snapshotColumns(), "id", 30, 3);
            String csv = "1,1,20.5,华东\n2,2,,华南\n3,1,19.8,\n";
            db.ingest("s", TestSupport.csv(csv, TestSupport.snapshotColumns()), T0);
            var page = db.snapshot("s", T0, 0, 100);
            assertNull(page.rows().get(1).values().get(1)); // 点 2 temp null
            assertNull(page.rows().get(2).values().get(2)); // 点 3 region null
            var full = db.fullSnapshot("s", T0);
            assertNull(full.rows().get(1).values().get(1));
            var series = db.series("s", "2", 0, Long.MAX_VALUE, 10);
            assertNull(series.get(0).values().get(1)); // temp null
            // 分页区间
            var p2 = db.snapshot("s", T0, 1, 2);
            assertNull(p2.rows().get(0).values().get(1));
        }
    }

    /** 场景：压缩收益——重复模式快照压缩率万倍级。 */
    @Test
    void compressionRatioOnPatternData() throws Exception {
        Path dataDir = tmp.resolve("compress");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("c", TestSupport.simpleColumns(false), "id", 30, 3);
            StringBuilder sb = new StringBuilder(100_000 * 20);
            for (int i = 1; i <= 100_000; i++) {
                sb.append(i).append(',').append(19387.53 + (i % 97)).append('\n');
            }
            db.ingest("c", TestSupport.csv(sb.toString(), TestSupport.simpleColumns(false)), T0);
            long csvBytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long stored = db.stats("c").totalSizeBytes();
            assertTrue(csvBytes / (double) stored > 100, "压缩率应百倍级: csv=" + csvBytes + " stored=" + stored);
        }
    }

    // ---- 辅助 ----

    private static String withPoint5(String csv, boolean remove) {
        if (!remove) {
            return csv;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : csv.split("\n")) {
            if (!line.isEmpty() && !line.startsWith("5,")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<Object> pointAt(AstraDB db, String table, String key, long ts) throws Exception {
        var series = db.series(table, key, ts, ts, 10);
        return series.isEmpty() ? List.of() : series.get(0).values();
    }
}
