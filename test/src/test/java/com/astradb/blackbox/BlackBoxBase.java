package com.astradb.blackbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.net.http.HttpResponse;

/**
 * 黑盒测试基类：管理 server 生命周期（真实进程）与常用辅助（建表/导入/JSON 解析）。
 */
public abstract class BlackBoxBase {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected static final long T0 = 1_700_000_000_000L;      // 基准时间戳（本地 2023-11）
    protected static final long DAY_MS = 86_400_000L;

    /** 测试用 schema：id(INT 主键) / v(DOUBLE 可空) / region(STRING 可空)。 */
    protected static String createTableBody(String name) {
        return "{\"name\":\"" + name + "\",\"primaryKey\":\"id\",\"retentionDays\":30,\"compressionLevel\":3,"
                + "\"columns\":["
                + "{\"name\":\"id\",\"type\":\"INT\"},"
                + "{\"name\":\"v\",\"type\":\"DOUBLE\",\"nullable\":true},"
                + "{\"name\":\"region\",\"type\":\"STRING\",\"nullable\":true}]}";
    }

    /** 标准 CSV（含表头，行序 = id,v,region；空字段 → null）。 */
    protected static byte[] csv(String lines) {
        return ("id,v,region\n" + lines).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- 生命周期：默认实例无鉴权；SecurityTest 覆盖为开启鉴权 ----

    @BeforeAll
    static void startServer() throws Exception {
        ServerHarness.start(false);
    }

    @AfterAll
    static void stopServer() {
        ServerHarness.stop();
    }

    // ---- 辅助：返回 JSON 节点，非 2xx 抛断言错误 ----

    protected static JsonNode post(String path, String jsonBody) throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", path, jsonBody, null);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new AssertionError("POST " + path + " 期望 2xx，实际 " + r.statusCode() + " body=" + r.body());
        }
        return MAPPER.readTree(r.body());
    }

    protected static JsonNode post(String path, String jsonBody, String auth) throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", path, jsonBody, auth);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new AssertionError("POST " + path + " 期望 2xx，实际 " + r.statusCode() + " body=" + r.body());
        }
        return MAPPER.readTree(r.body());
    }

    protected static JsonNode importCsv(String table, long ts, String csvLines) throws Exception {
        HttpResponse<String> r = ServerHarness.multipart("/api/importSnapshot", table, String.valueOf(ts),
                csv(csvLines), null);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new AssertionError("导入失败 table=" + table + " ts=" + ts + " "
                    + r.statusCode() + " body=" + r.body());
        }
        return MAPPER.readTree(r.body());
    }

    protected static void createTable(String name) throws Exception {
        HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable", createTableBody(name), null);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new AssertionError("建表失败 " + name + " " + r.statusCode() + " body=" + r.body());
        }
    }

    protected static void deleteTableQuiet(String name) {
        try {
            ServerHarness.send("POST", "/api/deleteTable",
                    "{\"table\":\"" + name + "\",\"confirm\":true}", null);
        } catch (Exception ignored) {
        }
    }
}
