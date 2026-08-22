package com.astradb.blackbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 最新交付可用性黑盒测试（2026-08-21）：
 * 针对交付文档——D-12（未知端点 404 / 方法 405 错误语义）与 R-01（SF-1~SF-8）：
 * - D-12：未知端点 404 + 结构化错误码 NOT_FOUND；方法不支持 405 + METHOD_NOT_ALLOWED；
 * - SF-3：损坏段隔离（.quarantine/*.corrupt）后库仍可启动、好表数据可查（关键可用性）；
 * - SF-1：删除快照后同内容重放真正写入（幂等记录随删除清理）；
 * - SF-2：混合批导入（部分命中正式记录 → 重放 + 其余新增，不再抛"时间戳已存在"）；
 * - SF-4：跨表并发导入互不阻塞（均成功、数据正确）；
 * - 常规回归：健康/建表/导入/查询/删表全流程可用。
 * 仅经外部接口（HTTP + 文件系统行为）黑盒验证，不触碰内部实现。
 */
public class LatestDeliveryAvailabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long T0 = 1_700_000_000_000L;

    @BeforeAll
    static void startServer() throws Exception {
        ServerHarness.start(false);
    }

    @AfterAll
    static void stopServer() {
        ServerHarness.stop();
    }

    // ---- D-12 错误语义 ----

    @Test
    @DisplayName("LD-01 未知端点返回 404 + 结构化错误码 NOT_FOUND")
    void unknownEndpoint404() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/noSuchEndpoint123", "{}", null);
        assertEquals(404, r.statusCode(), "未知端点应 404，实际 " + r.statusCode());
        JsonNode err = MAPPER.readTree(r.body());
        assertEquals("NOT_FOUND", err.get("code").asText(), "错误码应为 NOT_FOUND: " + r.body());
        assertTrue(err.has("message") && err.has("path"), "错误体应结构化");
        assertFalse(r.body().contains("at com.astradb"), "不应泄露堆栈");
    }

    @Test
    @DisplayName("LD-02 方法不支持返回 405 + 结构化错误码 METHOD_NOT_ALLOWED（GET 打 POST 端点）")
    void methodNotAllowed405() throws Exception {
        HttpResponse<String> r = ServerHarness.send("GET", "/api/createTable", null, null);
        assertEquals(405, r.statusCode(), "GET 打 POST 端点应 405，实际 " + r.statusCode());
        JsonNode err = MAPPER.readTree(r.body());
        assertEquals("METHOD_NOT_ALLOWED", err.get("code").asText(), "错误码应为 METHOD_NOT_ALLOWED: " + r.body());
    }

    // ---- SF-3 损坏段隔离可用性（核心） ----

    @Test
    @DisplayName("LD-03 损坏段隔离：篡改段文件后重启，库仍启动、好表可查、坏段进 .quarantine")
    void corruptSegmentIsolatedAndDbUsable() throws Exception {
        String good = "ld_good";
        String bad = "ld_bad";
        deleteTableQuiet(good);
        deleteTableQuiet(bad);
        createTable(good);
        createTable(bad);
        importCsv(good, T0, "1,1.0,g\n");
        importCsv(bad, T0, "1,1.0,b\n");
        // 定位 bad 表的段文件并篡改（写入垃圾字节破坏 Footer/数据）
        Path badSeg = findSegment(ServerHarness.dataDir().resolve(bad));
        assertNotNull(badSeg, "bad 表应有段文件");
        Files.write(badSeg, "CORRUPTED-DATA-NOT-A-SEG".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        // 重启（同一数据目录）
        ServerHarness.killAndRestart();
        // 库仍可用：health UP
        HttpResponse<String> health = ServerHarness.send("GET", "/api/health", null, null);
        assertEquals(200, health.statusCode(), "损坏段不应阻塞库启动");
        // 好表数据完好可查
        JsonNode goodFull = post("/api/getFullSnapshot", "{\"table\":\"" + good + "\",\"ts\":" + T0 + "}");
        assertEquals(1, goodFull.get("totalRows").asLong(), "好表数据应完好");
        assertEquals("g", goodFull.get("rows").get(0).get(2).asText()); // 列式 row=[key,v,region]
        // 坏表段被隔离：manifest 中段计数为 0（黑盒可见）
        JsonNode badStats = post("/api/getTableStats", "{\"table\":\"" + bad + "\"}");
        assertEquals(0, badStats.get("segmentCount").asLong(), "坏段应从 manifest 剔除: " + badStats);
        // 隔离产物落在 .quarantine（黑盒：文件系统行为可见）
        Path quarantineDir = ServerHarness.dataDir().resolve(bad).resolve("segments/.quarantine");
        try (var walk = Files.walk(quarantineDir)) {
            boolean found = walk.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".corrupt"));
            assertTrue(found, "损坏段应被隔离到 .quarantine/*.corrupt");
        }
        deleteTableQuiet(good);
        deleteTableQuiet(bad);
    }

    // ---- SF-1 删除后幂等清理 ----

    @Test
    @DisplayName("LD-04 删除快照后同内容重放真正写入（幂等记录随删除清理）")
    void replayAfterDeleteSnapshotActuallyWrites() throws Exception {
        String t = "ld_replay_after_delete";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.0,a\n2,2.0,b\n");
        // 删除快照
        JsonNode del = post("/api/deleteSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + ",\"confirm\":true}");
        assertTrue(del.get("deleted").asBoolean());
        assertEquals(0, post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}")
                .get("totalRows").asLong(), "删除后该时间点应为空");
        // 同内容重放 → SF-1 后应真正写入（快照恢复），而非被幂等跳过
        HttpResponse<String> replay = ServerHarness.multipart("/api/importSnapshot", t, String.valueOf(T0),
                csv("1,1.0,a\n2,2.0,b\n"), null);
        assertEquals(200, replay.statusCode(), "重放应成功: " + replay.body());
        JsonNode restored = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(2, restored.get("totalRows").asLong(), "删除后重放应真正写入（快照恢复）");
        assertEquals(2.0, restored.get("rows").get(1).get(1).asDouble(), 1e-9);
        deleteTableQuiet(t);
    }

    // ---- SF-2 混合批导入 ----

    @Test
    @DisplayName("LD-05 混合批导入：部分命中正式记录重放 + 其余新增，不抛时间戳已存在")
    void mixedBatchReplayAndNew() throws Exception {
        String t = "ld_mixed_batch";
        deleteTableQuiet(t);
        createTable(t);
        // 首批：ts1 + ts2
        HttpResponse<String> first = ServerHarness.multipartFiles("/api/importSnapshots", t,
                List.of(T0, T0 + 60_000L),
                List.of(csv("1,1.0,a\n"), csv("1,2.0,b\n")), null);
        assertEquals(200, first.statusCode(), "首批批量导入应成功: " + first.body());
        // 二次批量：[ts1 同内容(命中) + ts3 新] → 不应 400
        HttpResponse<String> mixed = ServerHarness.multipartFiles("/api/importSnapshots", t,
                List.of(T0, T0 + 120_000L),
                List.of(csv("1,1.0,a\n"), csv("1,3.0,c\n")), null);
        assertEquals(200, mixed.statusCode(), "混合批不应抛时间戳已存在: " + mixed.body());
        // 三个快照均存在；ts1 数据未变；ts3 新数据正确
        JsonNode list = post("/api/listSnapshots", "{\"table\":\"" + t + "\"}");
        assertEquals(3, list.size(), "应有 3 个快照: " + list);
        JsonNode ts1 = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(1.0, ts1.get("rows").get(0).get(1).asDouble(), 1e-9, "ts1 数据不应变化");
        JsonNode ts3 = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + (T0 + 120_000L) + "}");
        assertEquals(3.0, ts3.get("rows").get(0).get(1).asDouble(), 1e-9, "ts3 新数据应正确");
        deleteTableQuiet(t);
    }

    // ---- SF-4 跨表并发 ----

    @Test
    @DisplayName("LD-06 跨表并发导入：两表同时导入均成功、数据正确（互不阻塞）")
    void concurrentImportsAcrossTables() throws Exception {
        String ta = "ld_conc_a";
        String tb = "ld_conc_b";
        deleteTableQuiet(ta);
        deleteTableQuiet(tb);
        createTable(ta);
        createTable(tb);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread taThread = new Thread(() -> {
            try {
                start.await();
                importCsv(ta, T0, "1,1.0,aa\n2,2.0,ab\n");
            } catch (Throwable e) {
                err.compareAndSet(null, e);
            }
        });
        Thread tbThread = new Thread(() -> {
            try {
                start.await();
                importCsv(tb, T0, "3,3.0,ba\n");
            } catch (Throwable e) {
                err.compareAndSet(null, e);
            }
        });
        taThread.start();
        tbThread.start();
        start.countDown();
        taThread.join(30_000);
        tbThread.join(30_000);
        assertNull(err.get(), "并发导入不应出错: " + (err.get() == null ? "" : err.get().getMessage()));
        // 两表数据均正确
        JsonNode a = post("/api/getFullSnapshot", "{\"table\":\"" + ta + "\",\"ts\":" + T0 + "}");
        assertEquals(2, a.get("totalRows").asLong());
        JsonNode b = post("/api/getFullSnapshot", "{\"table\":\"" + tb + "\",\"ts\":" + T0 + "}");
        assertEquals(1, b.get("totalRows").asLong());
        assertEquals(3.0, b.get("rows").get(0).get(1).asDouble(), 1e-9);
        deleteTableQuiet(ta);
        deleteTableQuiet(tb);
    }

    // ---- 常规回归 ----

    @Test
    @DisplayName("LD-07 常规回归：健康/建表/导入/查询/删表全流程可用")
    void basicFlowStillUsable() throws Exception {
        HttpResponse<String> health = ServerHarness.send("GET", "/api/health", null, null);
        assertEquals(200, health.statusCode());
        String t = "ld_basic";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.5,x\n");
        JsonNode snap = post("/api/getSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + T0 + ",\"offset\":0,\"limit\":10}");
        assertEquals(1, snap.get("totalRows").asLong());
        assertEquals(1.5, snap.get("rows").get(0).get(1).asDouble(), 1e-9);
        deleteTableQuiet(t);
    }

    // ---- 辅助 ----

    private static JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", path, body, null);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new AssertionError("POST " + path + " 期望 2xx，实际 " + r.statusCode() + " body=" + r.body());
        }
        return MAPPER.readTree(r.body());
    }

    private static void createTable(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"primaryKey\":\"id\",\"retentionDays\":30,\"compressionLevel\":3,"
                + "\"columns\":["
                + "{\"name\":\"id\",\"type\":\"INT\"},"
                + "{\"name\":\"v\",\"type\":\"DOUBLE\",\"nullable\":true},"
                + "{\"name\":\"region\",\"type\":\"STRING\",\"nullable\":true}]}";
        HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable", body, null);
        assertEquals(200, r.statusCode(), "建表失败 " + name + ": " + r.body());
    }

    private static JsonNode importCsv(String table, long ts, String lines) throws Exception {
        HttpResponse<String> r = ServerHarness.multipart("/api/importSnapshot", table, String.valueOf(ts),
                csv(lines), null);
        assertEquals(200, r.statusCode(), "导入失败: " + r.body());
        return MAPPER.readTree(r.body());
    }

    /** 标准 CSV（含表头，行序 = id,v,region）。 */
    private static byte[] csv(String lines) {
        return ("id,v,region\n" + lines).getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteTableQuiet(String table) {
        try {
            ServerHarness.send("POST", "/api/deleteTable",
                    "{\"table\":\"" + table + "\",\"confirm\":true}", null);
        } catch (Exception ignored) {
        }
    }

    /** 递归找表目录下第一个 .seg 文件。 */
    private static Path findSegment(Path tableDir) throws Exception {
        if (!Files.isDirectory(tableDir)) {
            return null;
        }
        try (var walk = Files.walk(tableDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".seg"))
                    .findFirst().orElse(null);
        }
    }
}
