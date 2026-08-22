package com.astradb.blackbox;

import com.astradb.client.AstraDbClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R-02 交付黑盒测试（server/client should-fix SS-1~SS-10，2026-08-21）：
 * - SS-1 createTable 缺 columns → 400 INVALID_ARGUMENT（原 500 NPE）；
 * - SS-2 损坏二进制帧 → 400 INGEST_REJECTED（原 500 NegativeArraySizeException）；
 * - SS-4 表名含 ' " < > → 400 拒绝（XSS 纵深防御）；
 * - SS-5 CSV 未闭合引号 → 400 格式错误，不产生数据（原静默吞行）；
 * - SS-7 importAsync 坏 CSV：请求 200 返回 taskId，解析错误进任务状态 FAILED（解析移后台）；
 * - SS-8 health 不再泄露 dataDir（仅 dataDirWritable 布尔）；
 * - SS-10 client 含特殊字符（引号/换行/制表符）key 的 JSON 转义 → 导入成功可查；
 * - 常规回归。
 * 依据交付文档 docs/phaseReport/R-02-review-server-shouldfix.md；仅黑盒验证。
 */
public class LatestServerDeliveryTest {

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

    @Test
    @DisplayName("RS-01 SS-1：createTable 缺 columns → 400 INVALID_ARGUMENT（原 500）")
    void createTableMissingColumns400() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable",
                "{\"name\":\"rs_missing_cols\",\"primaryKey\":\"id\"}", null);
        assertEquals(400, r.statusCode(), "缺 columns 应 400，实际 " + r.statusCode() + " body=" + r.body());
        JsonNode err = MAPPER.readTree(r.body());
        assertEquals("INVALID_ARGUMENT", err.get("code").asText(), "错误码应为 INVALID_ARGUMENT: " + r.body());
    }

    @Test
    @DisplayName("RS-02 SS-2：损坏二进制帧 → 400 INGEST_REJECTED（原 500）")
    void corruptBinaryFrame400() throws Exception {
        String t = "rs_bin";
        createTable(t);
        // 先发一个正常帧建表数据？不需要——损坏帧应在解析阶段被拒
        byte[] garbage = new byte[]{0x41, (byte) 0x53, (byte) 0x44, (byte) 0x42, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01, 0x02};
        HttpResponse<String> r = ServerHarness.sendRaw("/api/importBinary?table=" + t, garbage);
        assertEquals(400, r.statusCode(), "损坏帧应 400（原 500），实际 " + r.statusCode() + " body=" + r.body());
        JsonNode err = MAPPER.readTree(r.body());
        assertTrue(err.get("code").asText().contains("REJECTED"), "应为 INGEST_REJECTED: " + r.body());
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("RS-03 SS-8：health 不再泄露 dataDir（仅保留 dataDirWritable 布尔）")
    void healthNoDataDirLeak() throws Exception {
        HttpResponse<String> r = ServerHarness.send("GET", "/api/health", null, null);
        assertEquals(200, r.statusCode());
        JsonNode body = MAPPER.readTree(r.body());
        assertFalse(body.has("dataDir"), "health 不应包含 dataDir 字段: " + body);
        assertTrue(body.has("dataDirWritable"), "应保留 dataDirWritable 布尔");
        assertFalse(r.body().contains("/tmp/astradb-blackbox"), "不应泄露临时数据目录路径");
    }

    @Test
    @DisplayName("RS-04 SS-4：表名含 ' \" < > → 400 拒绝（XSS 纵深防御）")
    void illegalTableNameChars400() throws Exception {
        for (String bad : new String[]{"rs<evil>", "rs'evil", "rs\"evil"}) {
            HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable",
                    "{\"name\":\"" + bad + "\",\"primaryKey\":\"id\","
                            + "\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}]}", null);
            assertEquals(400, r.statusCode(), "表名含特殊字符应 400: " + bad + " -> " + r.body());
        }
    }

    @Test
    @DisplayName("RS-05 SS-5：CSV 未闭合引号 → 400 格式错误，不产生数据")
    void unclosedQuoteCsv400() throws Exception {
        String t = "rs_unclosed";
        createTable(t);
        // 引号未闭合（字段内有引号后无闭合）
        HttpResponse<String> r = ServerHarness.multipart("/api/importSnapshot", t, String.valueOf(T0),
                ("id,v,region\n1,1.0,\"未闭合\n").getBytes(StandardCharsets.UTF_8), null);
        assertEquals(400, r.statusCode(), "未闭合引号应 400: " + r.body());
        // 不产生任何快照（原实现会静默吞行）
        JsonNode list = post("/api/listSnapshots", "{\"table\":\"" + t + "\"}");
        assertEquals(0, list.size(), "未闭合引号不应产生数据");
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("RS-06 SS-7：importAsync 坏 CSV 请求仍 200 返回 taskId，解析错误进任务状态 FAILED")
    void asyncImportParsingMovedToBackground() throws Exception {
        String t = "rs_async_bad";
        createTable(t);
        // 坏 CSV（列数不符/非法数值）→ 请求应快速 200 返回 taskId（解析在后台）
        HttpResponse<String> sub = ServerHarness.multipart("/api/importAsync", t, String.valueOf(T0),
                ("id,v,region\n1,abc,x\n").getBytes(StandardCharsets.UTF_8), null);
        assertEquals(200, sub.statusCode(), "坏 CSV 的 importAsync 请求应 200（解析在后台）: " + sub.body());
        String taskId = MAPPER.readTree(sub.body()).get("taskId").asText();
        // 轮询至终态：解析失败 → FAILED（而非请求 500）
        String status = "RUNNING";
        for (int i = 0; i < 40 && "RUNNING".equals(status); i++) {
            JsonNode st = post("/api/importStatus", "{\"taskId\":\"" + taskId + "\"}");
            status = st.get("status").asText();
            if ("RUNNING".equals(status)) {
                Thread.sleep(200);
            }
        }
        assertEquals("FAILED", status, "坏 CSV 异步任务应 FAILED（错误进任务状态）: " + taskId);
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("RS-07 SS-10：client 含特殊字符（引号/换行/制表符）key 导入成功且可查")
    void clientSpecialCharKeyIngest() throws Exception {
        // STRING 主键表
        String t = "rs_client_esc";
        HttpResponse<String> create = ServerHarness.send("POST", "/api/createTable",
                "{\"name\":\"" + t + "\",\"primaryKey\":\"pk\",\"columns\":["
                        + "{\"name\":\"pk\",\"type\":\"STRING\"},"
                        + "{\"name\":\"v\",\"type\":\"DOUBLE\"}]}", null);
        assertEquals(200, create.statusCode(), "建表应成功: " + create.body());
        // 含引号、换行、制表符的 key（SS-10：client 侧 JSON 转义，否则产生非法 JSON）
        String key1 = "key\"quote";
        String key2 = "line\nbreak";
        String key3 = "tab\tchar";
        AstraDbClient client = new AstraDbClient(ServerHarness.baseUrl());
        int rows = client.ingest(t, T0, List.of(
                List.of(key1, 1.0d),
                List.of(key2, 2.0d),
                List.of(key3, 3.0d)));
        assertEquals(3, rows, "client 导入应成功（含特殊字符 key）");
        // 可查：单点
        Object[] p1 = client.queryPointAt(t, key1, T0);
        assertNotNull(p1, "含引号 key 应可查");
        assertEquals(1.0, ((Number) p1[0]).doubleValue(), 1e-9);
        Object[] p2 = client.queryPointAt(t, key2, T0);
        assertEquals(2.0, ((Number) p2[0]).doubleValue(), 1e-9);
        Object[] p3 = client.queryPointAt(t, key3, T0);
        assertEquals(3.0, ((Number) p3[0]).doubleValue(), 1e-9);
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("RS-08 常规回归：健康/建表/导入/查询全流程可用")
    void basicFlow() throws Exception {
        HttpResponse<String> health = ServerHarness.send("GET", "/api/health", null, null);
        assertEquals(200, health.statusCode());
        String t = "rs_basic";
        deleteTableQuiet(t);
        createTable(t);
        HttpResponse<String> imp = ServerHarness.multipart("/api/importSnapshot", t, String.valueOf(T0),
                ("id,v,region\n1,1.5,x\n").getBytes(StandardCharsets.UTF_8), null);
        assertEquals(200, imp.statusCode(), "导入应成功: " + imp.body());
        JsonNode snap = post("/api/getSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + T0 + ",\"offset\":0,\"limit\":10}");
        assertEquals(1, snap.get("totalRows").asLong());
        assertEquals(1.5, snap.get("rows").get(0).get(1).asDouble(), 1e-9); // 列式：row=[key,v,region]
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

    private static void deleteTableQuiet(String table) {
        try {
            ServerHarness.send("POST", "/api/deleteTable",
                    "{\"table\":\"" + table + "\",\"confirm\":true}", null);
        } catch (Exception ignored) {
        }
    }
}
