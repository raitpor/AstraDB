package com.astradb.blackbox;

import com.astradb.client.AstraDbClient;
import com.astradb.client.QueryResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定时导入黑盒测试（依据测试计划）：
 * 1. 启动服务端，建表 asltest（主键 pointId INT，数据列 pointValue DOUBLE，压缩等级 20）；
 * 2. 以 spring 实现定时任务：每 1 分钟用 astradb-client 导入 1000 条，共 10 次（约 10 分钟）；
 * 3. 逐个快照做全量查询与单点查询，确认可查询且数据正确。
 *
 * 两个运行模式（同一定时链路）：
 * - smokeScheduledImportChain：interval=2s × 3 次，快速验证 spring 定时 + client 导入 + 验证逻辑；
 * - fullTenMinuteScheduledImport：interval=60s × 10 次（真实十分钟），表 asltest。
 * 参数经系统属性注入（scheduled.interval.ms / scheduled.count / scheduled.table）。
 */
public class ScheduledImportTest {

    private static final String TABLE = "asltest";
    private static final long BASE_TS = 1_700_000_000_000L;

    /** spring 配置：开启调度 + 注册定时导入任务。 */
    @Configuration
    @EnableScheduling
    public static class ScheduledImportConfig {
        @Bean
        public AstraDbClient astraDbClient() {
            return new AstraDbClient(ServerHarness.baseUrl());
        }

        @Bean
        public ScheduledImportTask scheduledImportTask(AstraDbClient client) {
            return new ScheduledImportTask(client);
        }
    }

    /** 定时导入任务：每次 1000 条（pointId 递增段、pointValue = id * 0.5），达 count 次置 done。 */
    public static class ScheduledImportTask {
        private final AstraDbClient client;
        private final int count = Integer.getInteger("scheduled.count", 10);
        private final long intervalMs = Long.getLong("scheduled.interval.ms", 60_000L);
        private final String table = System.getProperty("scheduled.table", TABLE);
        private final AtomicInteger counter = new AtomicInteger();
        volatile boolean done;

        public ScheduledImportTask(AstraDbClient client) {
            this.client = client;
        }

        @Scheduled(fixedDelayString = "${scheduled.interval.ms:60000}")
        public void importBatch() {
            int batch = counter.getAndIncrement();
            if (batch >= count) {
                return; // 已达标，忽略后续触发
            }
            long ts = BASE_TS + batch * 60_000L;
            // 固定 1000 个点（id=1..1000，点集稳定，符合"周期性全量快照"语义）；
            // 值随批次微变（pointValue = id*0.5 + batch*0.1）→ 可验证跨快照不串数据
            List<List<Object>> rows = new ArrayList<>(1000);
            for (int id = 1; id <= 1000; id++) {
                rows.add(List.of(id, id * 0.5d + batch * 0.1d));
            }
            int rowCount = client.ingest(table, ts, rows);
            if (rowCount != 1000) {
                throw new IllegalStateException("第 " + (batch + 1) + " 次导入期望 1000 行，实际 " + rowCount);
            }
            System.out.println("[ScheduledImportTask] 第 " + (batch + 1) + "/" + count
                    + " 次导入完成 ts=" + ts + " rows=" + rowCount);
            if (batch + 1 >= count) {
                done = true;
            }
        }
    }

    private static final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    /** 启动 server + 建表 + 启动 spring 定时任务，等待全部导入完成。 */
    private static void runScheduledImports(String table, long intervalMs, int count) throws Exception {
        // 系统属性 → @Scheduled 占位符与任务配置
        System.setProperty("scheduled.interval.ms", String.valueOf(intervalMs));
        System.setProperty("scheduled.count", String.valueOf(count));
        System.setProperty("scheduled.table", table);
        ServerHarness.start(false);
        // 建表：pointId INT 主键（第 0 列）、pointValue DOUBLE、压缩等级 20
        String body = "{\"name\":\"" + table + "\",\"primaryKey\":\"pointId\",\"retentionDays\":30,"
                + "\"compressionLevel\":20,\"columns\":["
                + "{\"name\":\"pointId\",\"type\":\"INT\"},"
                + "{\"name\":\"pointValue\",\"type\":\"DOUBLE\"}]}";
        var r = ServerHarness.send("POST", "/api/createTable", body, null);
        assertEquals(200, r.statusCode(), "建表应成功: " + r.body());
        // spring 定时任务
        AstraDbClient client = new AstraDbClient(ServerHarness.baseUrl());
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(ScheduledImportConfig.class);
        contexts.add(ctx);
        ScheduledImportTask task = ctx.getBean(ScheduledImportTask.class);
        // 等待 count 次导入完成（超时 = 间隔跨度 + 180s 余量）
        long deadline = System.currentTimeMillis() + (count - 1) * intervalMs + 180_000L;
        while (!task.done && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        assertTrue(task.done, "定时任务应在时限内完成 " + count + " 次导入");
        ctx.close();
    }

    @Test
    @DisplayName("冒烟：spring 定时 + client 导入 + 快照验证链路（3 次 × 2s）")
    void smokeScheduledImportChain() throws Exception {
        String table = "asltest_smoke";
        try {
            runScheduledImports(table, 2_000L, 3);
            verifySnapshots(table, 3, 2_000L);
        } finally {
            deleteTableQuiet(table);
            ServerHarness.stop();
        }
    }

    @Test
    @DisplayName("正式：每 1 分钟 client 导入 1000 条 ×10（约 10 分钟），逐快照全量+单点验证")
    void fullTenMinuteScheduledImport() throws Exception {
        try {
            runScheduledImports(TABLE, 60_000L, 10);
            verifySnapshots(TABLE, 10, 60_000L);
        } finally {
            deleteTableQuiet(TABLE);
            ServerHarness.stop();
        }
    }

    // ---- 验证：逐个快照全量查询 + 单点查询 ----

    private static void verifySnapshots(String table, int count, long intervalMs) throws Exception {
        AstraDbClient client = new AstraDbClient(ServerHarness.baseUrl());
        // 快照列表：count 个时间点，升序
        com.fasterxml.jackson.databind.JsonNode list = BlackBoxBase.post(
                "/api/listSnapshots", "{\"table\":\"" + table + "\"}");
        assertEquals(count, list.size(), "应存在 " + count + " 个快照: " + list);
        long[] tsArr = new long[count];
        for (int i = 0; i < count; i++) {
            tsArr[i] = list.get(i).asLong();
            if (i > 0) {
                assertTrue(tsArr[i] > tsArr[i - 1], "快照时间戳应严格递增");
            }
        }
        // 逐个快照：全量 + 单点
        for (int i = 0; i < count; i++) {
            long ts = tsArr[i];
            // 全量查询（client）：行数 = 1000，抽查首/中/尾行值正确（值 = id*0.5 + 批次*0.1）
            QueryResult full = client.queryFullSnapshot(table, ts);
            assertEquals(1000, full.rows().size(), "快照 " + ts + " 全量应 1000 行");
            for (int pick : new int[]{0, 500, 999}) {
                Object[] row = full.rows().get(pick);
                int expectId = pick + 1;
                double expectVal = expectId * 0.5d + i * 0.1d;
                // 行对齐列名：row[0]=pointId, row[1]=pointValue
                assertEquals(expectId, ((Number) row[0]).intValue(), "快照 " + ts + " 行 " + pick + " 主键");
                assertEquals(expectVal, ((Number) row[1]).doubleValue(), 1e-9,
                        "快照 " + ts + " 行 " + pick + " 值");
            }
            // 单点查询（client queryPointAt）：抽查 3 个点，值正确
            for (int pick : new int[]{1, 250, 1000}) {
                double expectVal = pick * 0.5d + i * 0.1d;
                Object[] point = client.queryPointAt(table, String.valueOf(pick), ts);
                assertNotNull(point, "单点 " + pick + " @ " + ts + " 应存在");
                assertEquals(expectVal, ((Number) point[0]).doubleValue(), 1e-9,
                        "单点 " + pick + " @ " + ts + " 值");
            }
            System.out.println("[Verify] 快照 " + (i + 1) + "/" + count + " ts=" + ts
                    + " 全量 1000 行 + 3 单点 校验通过");
        }
        // 单点历史：点 1 在全部 count 个快照的值序列（值随批次递增，验证跨快照不串数据）
        com.fasterxml.jackson.databind.JsonNode series = BlackBoxBase.post("/api/getPointSeries",
                "{\"table\":\"" + table + "\",\"key\":\"1\",\"from\":0,\"to\":9999999999999,\"limit\":1000}");
        assertEquals(count, series.get("rows").size(), "单点历史应覆盖全部快照: " + series);
        for (int i = 0; i < count; i++) {
            assertEquals(BASE_TS + i * 60_000L, series.get("timestamps").get(i).asLong());
            double expect = 0.5d + i * 0.1d;
            assertEquals(expect, series.get("rows").get(i).get(1).asDouble(), 1e-9,
                    "点 1 在快照 " + i + " 的值");
        }
        System.out.println("[Verify] 单点历史：点 1 在 " + count + " 个快照值序列全部正确");
    }

    private static void deleteTableQuiet(String table) {
        try {
            ServerHarness.send("POST", "/api/deleteTable",
                    "{\"table\":\"" + table + "\",\"confirm\":true}", null);
        } catch (Exception ignored) {
        }
    }

    @AfterAll
    static void cleanup() {
        for (AnnotationConfigApplicationContext ctx : contexts) {
            try {
                ctx.close();
            } catch (Exception ignored) {
            }
        }
        contexts.clear();
        ServerHarness.stop();
    }
}
