package com.astradb.blackbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整性黑盒测试：数据不丢不坏——导入/查询一致（值、null、类型）、单点历史、
 * 重复拒绝、幂等重放、历史回填、删除快照、崩溃恢复（kill -9 后数据完好）、跨表隔离。
 * 依据 docs/design/scenario.md（崩溃不产生半写可见数据、快照不可变）。
 */
public class IntegrityTest extends BlackBoxBase {

    @Test
    @DisplayName("IT-01 导入→全量快照一致：行数、值、null 还原")
    void itSnapshotRoundtrip() throws Exception {
        String t = "it_roundtrip";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.5,华东\n2,,华南\n3,3.25,\n");
        JsonNode full = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(3, full.get("totalRows").asLong());
        JsonNode rows = full.get("rows");
        // 行 1：v=1.5, region=华东
        assertEquals(1.5, rows.get(0).get("values").get(0).asDouble(), 1e-9);
        assertEquals("华东", rows.get(0).get("values").get(1).asText());
        // 行 2：v=null, region=华南
        assertTrue(rows.get(1).get("values").get(0).isNull(), "空值应还原为 null");
        assertEquals("华南", rows.get(1).get("values").get(1).asText());
        // 行 3：region=null
        assertTrue(rows.get(2).get("values").get(1).isNull());
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-02 单点历史：多时间点值序列正确")
    void itPointSeries() throws Exception {
        String t = "it_series";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.0,x\n");
        importCsv(t, T0 + 60_000L, "1,2.0,x\n");
        importCsv(t, T0 + 120_000L, "1,3.0,x\n");
        JsonNode series = post("/api/getPointSeries",
                "{\"table\":\"" + t + "\",\"key\":\"1\",\"from\":0,\"to\":9999999999999,\"limit\":100}");
        assertEquals(3, series.size(), "应返回 3 个时间点");
        assertEquals(T0, series.get(0).get("timestamp").asLong());
        assertEquals(2.0, series.get(1).get("values").get(0).asDouble(), 1e-9);
        // 不存在的点 → 空序列
        JsonNode missing = post("/api/getPointSeries",
                "{\"table\":\"" + t + "\",\"key\":\"999\",\"from\":0,\"to\":9999999999999,\"limit\":100}");
        assertEquals(0, missing.size(), "未知 key 应返回空序列");
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-03 重复时间戳（不同内容）拒绝：400")
    void itDuplicateTimestampRejected() throws Exception {
        String t = "it_dup";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.0,a\n");
        HttpResponse<String> dup = ServerHarness.multipart("/api/importSnapshot", t, String.valueOf(T0),
                csv("1,99.0,b\n"), null);
        assertEquals(400, dup.statusCode(), "同 ts 异内容应 400 拒绝");
        // 原数据未被污染
        JsonNode full = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(1, full.get("totalRows").asLong());
        assertEquals(1.0, full.get("rows").get(0).get("values").get(0).asDouble(), 1e-9);
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-04 幂等重放：同 ts 同内容再次导入不产生重复数据")
    void itIdempotentReplay() throws Exception {
        String t = "it_idem";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.0,a\n2,2.0,b\n");
        // 同内容重放（幂等跳过，仍返回成功）
        HttpResponse<String> replay = ServerHarness.multipart("/api/importSnapshot", t, String.valueOf(T0),
                csv("1,1.0,a\n2,2.0,b\n"), null);
        assertEquals(200, replay.statusCode(), "同内容重放应成功（幂等）: " + replay.body());
        // 快照数不增加、行数不变
        JsonNode list = post("/api/listSnapshots", "{\"table\":\"" + t + "\"}");
        assertEquals(1, list.size(), "重放不应新增快照");
        JsonNode full = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(2, full.get("totalRows").asLong(), "重放不应产生重复行");
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-05 历史回填：向过去时间戳导入后可回溯查询")
    void itBackfillHistory() throws Exception {
        String t = "it_backfill";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0 + DAY_MS, "1,2.0,now\n");
        // 向更早时间回填
        importCsv(t, T0, "1,1.0,past\n");
        JsonNode past = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(1, past.get("totalRows").asLong());
        assertEquals(1.0, past.get("rows").get(0).get("values").get(0).asDouble(), 1e-9);
        assertEquals("past", past.get("rows").get(0).get("values").get(1).asText());
        // 新快照不受影响
        JsonNode now = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + (T0 + DAY_MS) + "}");
        assertEquals(2.0, now.get("rows").get(0).get("values").get(0).asDouble(), 1e-9);
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-06 删除快照：无 confirm 拒绝、confirm 后该时间点不可查")
    void itDeleteSnapshot() throws Exception {
        String t = "it_delete_snap";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.0,a\n");
        importCsv(t, T0 + 60_000L, "1,2.0,a\n");
        // 无 confirm → 400
        HttpResponse<String> noConfirm = ServerHarness.send("POST", "/api/deleteSnapshot",
                "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}", null);
        assertEquals(400, noConfirm.statusCode(), "无 confirm 删除应 400");
        // confirm 删除
        JsonNode del = post("/api/deleteSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + ",\"confirm\":true}");
        assertTrue(del.get("deleted").asBoolean());
        // 该时间点不可查、另一时间点保留
        JsonNode gone = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(0, gone.get("totalRows").asLong(), "删除后该时间点应为空");
        JsonNode kept = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + (T0 + 60_000L) + "}");
        assertEquals(1, kept.get("totalRows").asLong());
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-07 崩溃恢复：kill -9 重启后已提交数据完好可查")
    void itCrashRecovery() throws Exception {
        String t = "it_crash";
        deleteTableQuiet(t);
        createTable(t);
        importCsv(t, T0, "1,1.5,a\n2,2.5,b\n3,3.5,c\n");
        // 杀进程并重启（同一数据目录）
        ServerHarness.killAndRestart();
        // 重启后数据完好
        JsonNode full = post("/api/getFullSnapshot", "{\"table\":\"" + t + "\",\"ts\":" + T0 + "}");
        assertEquals(3, full.get("totalRows").asLong(), "崩溃重启后已提交快照应完好");
        assertEquals(1.5, full.get("rows").get(0).get("values").get(0).asDouble(), 1e-9);
        assertEquals(3, post("/api/getTableStats", "{\"table\":\"" + t + "\"}")
                .get("pointCount").asLong(), "点字典应完好");
        deleteTableQuiet(t);
    }

    @Test
    @DisplayName("IT-08 跨表隔离：A 表数据不影响 B 表")
    void itTableIsolation() throws Exception {
        String ta = "it_iso_a";
        String tb = "it_iso_b";
        deleteTableQuiet(ta);
        deleteTableQuiet(tb);
        createTable(ta);
        createTable(tb);
        importCsv(ta, T0, "1,1.0,a\n");
        importCsv(tb, T0, "7,7.0,b\n");
        JsonNode a = post("/api/getFullSnapshot", "{\"table\":\"" + ta + "\",\"ts\":" + T0 + "}");
        assertEquals(1, a.get("totalRows").asLong());
        assertEquals("a", a.get("rows").get(0).get("values").get(1).asText());
        JsonNode b = post("/api/getFullSnapshot", "{\"table\":\"" + tb + "\",\"ts\":" + T0 + "}");
        assertEquals("b", b.get("rows").get(0).get("values").get(1).asText());
        assertEquals(7.0, b.get("rows").get(0).get("values").get(0).asDouble(), 1e-9);
        deleteTableQuiet(ta);
        deleteTableQuiet(tb);
    }
}
