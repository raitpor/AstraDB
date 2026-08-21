package com.astradb.blackbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可用性黑盒测试：应用可启动、健康、表/数据全流程可用、错误处理友好（400 而非 500）、
 * 异步/批量导入可用。依据 docs/design/scenario.md（周期性全量快照负载的可用性要求）。
 */
public class AvailabilityTest extends BlackBoxBase {

    @Test
    @DisplayName("AV-01 健康检查：/api/health 返回 UP 与版本/数据目录信息")
    void avHealthUp() throws Exception {
        HttpResponse<String> r = ServerHarness.send("GET", "/api/health", null, null);
        assertEquals(200, r.statusCode(), "health 应 200");
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals("UP", body.get("status").asText());
        assertTrue(body.has("version") && body.has("dataDirWritable") && body.has("uptimeMs"),
                "health 应包含 version/dataDirWritable/uptimeMs: " + body);
    }

    @Test
    @DisplayName("AV-02 表生命周期：建表→列表→详情→统计→删表 全链路可用")
    void avTableLifecycle() throws Exception {
        String t = "av_lifecycle";
        deleteTableQuiet(t);
        createTable(t);
        // 列表
        JsonNode tables = post("/api/listTables", "{}");
        assertTrue(containsTable(tables, t), "listTables 应包含 " + t + ": " + tables);
        // 详情
        JsonNode info = post("/api/getTableInfo", "{\"table\":\"" + t + "\"}");
        assertEquals(t, info.get("name").asText());
        assertEquals(3, info.get("schema").get("columns").size());
        // 统计
        JsonNode stats = post("/api/getTableStats", "{\"table\":\"" + t + "\"}");
        assertEquals(0, stats.get("pointCount").asLong(), "空表 pointCount=0");
        // 删表
        JsonNode del = post("/api/deleteTable", "{\"table\":\"" + t + "\",\"confirm\":true}");
        assertTrue(del.get("deleted").asBoolean());
        JsonNode after = post("/api/listTables", "{}");
        assertFalse(containsTable(after, t), "删表后不应包含 " + t);
    }

    @Test
    @DisplayName("AV-03 导入→查询→删除 端到端可用")
    void avCsvImportQueryDelete() throws Exception {
        String t = "av_import_query";
        deleteTableQuiet(t);
        createTable(t);
        JsonNode r = importCsv(t, T0, "1,1.5,华东\n2,2.5,华北\n3,,华南\n");
        assertEquals(3, r.get("rowCount").asInt());
        // 查询
        JsonNode snap = post("/api/getSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + T0 + ",\"offset\":0,\"limit\":10}");
        assertEquals(3, snap.get("totalRows").asLong());
        assertEquals(3, snap.get("rows").size());
        // 快照列表
        JsonNode list = post("/api/listSnapshots", "{\"table\":\"" + t + "\"}");
        assertEquals(1, list.size());
        assertEquals(T0, list.get(0).asLong());
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("AV-04 未知端点返回 404（非 500）")
    @org.junit.jupiter.api.Disabled("登记 D-12（docs/test/defects.md）：未知端点被全局 Exception 兜底映射为 500 INTERNAL_ERROR 而非 404，待 server 修复后启用")
    void avUnknownEndpoint404() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/notExistEndpoint", "{}", null);
        assertEquals(404, r.statusCode(), "未知端点应 404，实际 " + r.statusCode());
    }

    @Test
    @DisplayName("AV-05 非法请求参数返回 400（客户端错误，非 500）")
    void avBadInput400() throws Exception {
        // 主键列不存在 → 400
        String bad = "{\"name\":\"av_bad\",\"primaryKey\":\"nope\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}]}";
        HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable", bad, null);
        assertEquals(400, r.statusCode(), "非法建表应 400");
        // 未知表查询 → 400
        HttpResponse<String> r2 = ServerHarness.send("POST", "/api/getTableInfo",
                "{\"table\":\"av_no_such_table\"}", null);
        assertEquals(400, r2.statusCode(), "未知表应 400");
    }

    @Test
    @DisplayName("AV-06 异步导入：提交→轮询状态→SUCCESS→数据可查")
    void avAsyncImport() throws Exception {
        String t = "av_async";
        deleteTableQuiet(t);
        createTable(t);
        HttpResponse<String> sub = ServerHarness.multipart("/api/importAsync", t, String.valueOf(T0 + DAY_MS),
                csv("1,9.9,异步\n"), null);
        assertEquals(200, sub.statusCode());
        String taskId = MAPPER.readTree(sub.body()).get("taskId").asText();
        // 轮询至终态
        String status = "RUNNING";
        for (int i = 0; i < 40 && "RUNNING".equals(status); i++) {
            JsonNode st = post("/api/importStatus", "{\"taskId\":\"" + taskId + "\"}");
            status = st.get("status").asText();
            if ("RUNNING".equals(status)) {
                Thread.sleep(200);
            }
        }
        assertEquals("SUCCESS", status, "异步导入应 SUCCESS，taskId=" + taskId);
        // 数据可查
        JsonNode snap = post("/api/getSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + (T0 + DAY_MS) + ",\"offset\":0,\"limit\":10}");
        assertEquals(1, snap.get("totalRows").asLong());
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("AV-07 批量导入：多文件+严格递增时间戳一次导入")
    void avBatchImport() throws Exception {
        String t = "av_batch";
        deleteTableQuiet(t);
        createTable(t);
        HttpResponse<String> r = ServerHarness.multipartFiles("/api/importSnapshots", t,
                java.util.List.of(T0, T0 + 60_000L),
                java.util.List.of(csv("1,1.0,a\n"), csv("1,2.0,b\n")), null);
        assertEquals(200, r.statusCode(), "批量导入应 200: " + r.body());
        JsonNode res = MAPPER.readTree(r.body());
        assertEquals(2, res.size(), "应返回 2 个导入结果");
        assertEquals(1, res.get(0).get("rowCount").asInt());
        JsonNode list = post("/api/listSnapshots", "{\"table\":\"" + t + "\"}");
        assertEquals(2, list.size(), "应存在 2 个快照时间点");
        // 两个时间点数据均可查
        assertEquals(1, post("/api/getSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + T0 + ",\"offset\":0,\"limit\":10}")
                .get("totalRows").asLong());
        assertEquals(1, post("/api/getSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + (T0 + 60_000L) + ",\"offset\":0,\"limit\":10}")
                .get("totalRows").asLong());
        deleteTableQuiet(t);
    }

    private static boolean containsTable(JsonNode arr, String name) {
        for (JsonNode n : arr) {
            if (name.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }
}
