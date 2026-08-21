package com.astradb.blackbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全性黑盒测试（鉴权开启实例）：认证控制、路径穿越、confirm 保护、输入校验、
 * 错误信息不泄露内部实现。依据 docs/design/scenario.md（安全部署：鉴权、防误删、防穿越）。
 */
public class SecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long T0 = 1_700_000_000_000L;
    private static final String AUTH = ServerHarness.authHeader();

    @BeforeAll
    static void startServer() throws Exception {
        ServerHarness.start(true);   // 开启鉴权
    }

    @AfterAll
    static void stopServer() {
        ServerHarness.stop();
    }

    // ---- 认证控制 ----

    @Test
    @DisplayName("SE-01 鉴权开启时 /api/health 放行（无需认证）")
    void seHealthPermitted() throws Exception {
        HttpResponse<String> r = ServerHarness.send("GET", "/api/health", null, null);
        assertEquals(200, r.statusCode());
        assertEquals("UP", MAPPER.readTree(r.body()).get("status").asText());
    }

    @Test
    @DisplayName("SE-02 未认证访问受保护 API → 401")
    void seUnauthorized401() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/listTables", "{}", null);
        assertEquals(401, r.statusCode(), "未认证应 401");
    }

    @Test
    @DisplayName("SE-03 错误凭证 → 401")
    void seWrongCredential401() throws Exception {
        String bad = "Basic " + java.util.Base64.getEncoder().encodeToString(
                ("admin:wrong-pass").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = ServerHarness.send("POST", "/api/listTables", "{}", bad);
        assertEquals(401, r.statusCode(), "错误凭证应 401");
    }

    @Test
    @DisplayName("SE-04 正确凭证 → 200")
    void seAuthorized200() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/listTables", "{}", AUTH);
        assertEquals(200, r.statusCode());
        assertNotNull(MAPPER.readTree(r.body()));
    }

    // ---- 路径穿越与误删保护 ----

    @Test
    @DisplayName("SE-05 路径穿越拒绝：listSegmentSnapshots / deleteSegment 的 ../ 返回 400")
    void sePathTraversalRejected() throws Exception {
        // 表存在性无关紧要——穿越校验应在解析路径时拦截
        String traverse = "{\"table\":\"se_x\",\"path\":\"../etc/passwd\"}";
        HttpResponse<String> r1 = ServerHarness.send("POST", "/api/listSegmentSnapshots", traverse, AUTH);
        assertEquals(400, r1.statusCode(), "listSegmentSnapshots 穿越应 400: " + r1.body());
        String traverse2 = "{\"table\":\"se_x\",\"path\":\"segments/../../secret\",\"confirm\":true}";
        HttpResponse<String> r2 = ServerHarness.send("POST", "/api/deleteSegment", traverse2, AUTH);
        assertEquals(400, r2.statusCode(), "deleteSegment 穿越应 400: " + r2.body());
        // 绝对路径同样拒绝
        HttpResponse<String> r3 = ServerHarness.send("POST", "/api/listSegmentSnapshots",
                "{\"table\":\"se_x\",\"path\":\"/etc/passwd\"}", AUTH);
        assertEquals(400, r3.statusCode());
    }

    @Test
    @DisplayName("SE-06 confirm 保护：deleteTable/deleteSegment 无 confirm → 400")
    void seConfirmProtection() throws Exception {
        String t = "se_confirm";
        HttpResponse<String> create = ServerHarness.send("POST", "/api/createTable",
                "{\"name\":\"" + t + "\",\"primaryKey\":\"id\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}]}", AUTH);
        assertEquals(200, create.statusCode(), "建表（鉴权）应成功: " + create.body());
        HttpResponse<String> noConfirm = ServerHarness.send("POST", "/api/deleteTable",
                "{\"table\":\"" + t + "\"}", AUTH);
        assertEquals(400, noConfirm.statusCode(), "无 confirm 删表应 400");
        // 表仍存在
        HttpResponse<String> info = ServerHarness.send("POST", "/api/getTableInfo",
                "{\"table\":\"" + t + "\"}", AUTH);
        assertEquals(200, info.statusCode(), "无 confirm 删除后表应仍在");
        // 带 confirm 正常删除
        HttpResponse<String> ok = ServerHarness.send("POST", "/api/deleteTable",
                "{\"table\":\"" + t + "\",\"confirm\":true}", AUTH);
        assertEquals(200, ok.statusCode());
    }

    // ---- 输入校验 ----

    @Test
    @DisplayName("SE-07 非法表名（含路径分隔符）→ 400")
    void seIllegalTableName() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable",
                "{\"name\":\"se/../evil\",\"primaryKey\":\"id\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}]}", AUTH);
        assertEquals(400, r.statusCode(), "含路径分隔符表名应 400");
        assertFalse(r.body().contains("500"), "不应为 500");
    }

    @Test
    @DisplayName("SE-08 导入类型不符 → 400 INGEST_REJECTED（结构化错误码）")
    void seTypeMismatchRejected() throws Exception {
        String t = "se_type";
        HttpResponse<String> create = ServerHarness.send("POST", "/api/createTable",
                createBody(t), AUTH);
        assertEquals(200, create.statusCode());
        // id 列应为 INT，传 "abc" → 解析失败/类型不符 → 400
        HttpResponse<String> imp = ServerHarness.multipart("/api/importSnapshot", t, String.valueOf(T0),
                ("id,v,region\nabc,1.0,x\n").getBytes(StandardCharsets.UTF_8), AUTH);
        assertEquals(400, imp.statusCode(), "类型不符应 400: " + imp.body());
        JsonNode err = MAPPER.readTree(imp.body());
        assertTrue(err.has("code"), "错误响应应含 code: " + err);
        // 未产生部分数据
        HttpResponse<String> list = ServerHarness.send("POST", "/api/listSnapshots",
                "{\"table\":\"" + t + "\"}", AUTH);
        JsonNode snaps = MAPPER.readTree(list.body());
        assertEquals(0, snaps.size(), "导入失败不应留下快照");
        ServerHarness.send("POST", "/api/deleteTable", "{\"table\":\"" + t + "\",\"confirm\":true}", AUTH);
    }

    @Test
    @DisplayName("SE-09 错误响应不泄露堆栈/内部路径（结构化 {code,message,...}）")
    void seNoStackTraceLeak() throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/getTableInfo",
                "{\"table\":\"se_no_such\"}", AUTH);
        assertEquals(400, r.statusCode());
        String body = r.body();
        assertFalse(body.contains("at com.astradb"), "不应泄露堆栈: " + body);
        assertFalse(body.contains("Caused by"), "不应泄露异常链: " + body);
        JsonNode err = MAPPER.readTree(body);
        assertTrue(err.has("code") && err.has("message"), "错误体应结构化: " + body);
    }

    // ---- 辅助 ----

    private static String createBody(String name) {
        return "{\"name\":\"" + name + "\",\"primaryKey\":\"id\",\"retentionDays\":30,\"compressionLevel\":3,"
                + "\"columns\":["
                + "{\"name\":\"id\",\"type\":\"INT\"},"
                + "{\"name\":\"v\",\"type\":\"DOUBLE\",\"nullable\":true},"
                + "{\"name\":\"region\",\"type\":\"STRING\",\"nullable\":true}]}";
    }
}
