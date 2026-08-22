package com.astradb.blackbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可空列 UI 修复与压缩等级默认值交付黑盒测试（2026-08-22，变更 1/3 + 变更 2 数据来源）：
 * - UI-N1 可空列 CSV 空字段导入 → 查询还原 null；
 * - UI-N2 非空列空字段导入 → 400 拒绝、无数据；
 * - UI-N3 主键列 nullable:true 建表 → 400（主键强制非空）；
 * - UI-N4 可空列混合数据（部分空/部分非空）跨快照正确；
 * - UI-N5 getTableInfo 返回 schema.nullable / primaryKeyIndex（表结构展示数据来源）；
 * - UI-N6 建表显式 compressionLevel → TableInfo 一致（表级冻结）。
 * 依据交付文档 docs/phaseReport/2026-08-22-可空列与压缩等级UI修复交付文档.md。
 */
public class NullableColumnDeliveryTest extends BlackBoxBase {

    private static final long TS1 = T0;
    private static final long TS2 = T0 + 60_000L;

    // ---- UI-N1：可空列空字段导入 → null 还原 ----

    @Test
    @DisplayName("UI-N1 可空列 CSV 空字段导入后查询还原 null")
    void nullableColumnRoundtrip() throws Exception {
        String t = "n1_" + System.nanoTime();
        try {
            createTable(t); // schema: id INT 主键 / v DOUBLE 可空 / region STRING 可空
            importCsv(t, TS1, "1,,east\n2,1.5,\n3,2.5,north");

            JsonNode page = post("/api/getSnapshot",
                    "{\"table\":\"" + t + "\",\"ts\":" + TS1 + ",\"offset\":0,\"limit\":100}");
            assertEquals(3, page.get("totalRows").asInt(), "行数");

            JsonNode rows = page.get("rows");
            // 列式：rows 每行 = [key, v, region]（主键在首位）
            JsonNode r0 = rows.get(0);
            assertEquals("1", r0.get(0).asText());
            assertTrue(r0.get(1).isNull(), "行1 v 应为 null");
            assertEquals("east", r0.get(2).asText());

            JsonNode r1 = rows.get(1);
            assertEquals("2", r1.get(0).asText());
            assertEquals(1.5, r1.get(1).asDouble(), 0.0, "行2 v=1.5");
            assertTrue(r1.get(2).isNull(), "行2 region 应为 null");

            assertEquals("north", rows.get(2).get(2).asText(), "行3 region=north");
            assertEquals("id", page.get("pk").asText(), "pk=主键列名");
            assertEquals(3, page.get("columns").size(), "columns 列名数组");
        } finally {
            deleteTableQuiet(t);
        }
    }

    // ---- UI-N2：非空列空字段导入 → 400、无数据 ----

    @Test
    @DisplayName("UI-N2 非空列空字段导入 400 拒绝且不产生数据")
    void nonNullableColumnRejectsEmpty() throws Exception {
        String t = "n2_" + System.nanoTime();
        try {
            // v DOUBLE 非空（不声明 nullable）
            HttpResponse<String> create = ServerHarness.send("POST", "/api/createTable",
                    "{\"name\":\"" + t + "\",\"primaryKey\":\"id\",\"retentionDays\":30,"
                            + "\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"v\",\"type\":\"DOUBLE\"}]}",
                    null);
            assertTrue(create.statusCode() < 300, "建表应成功: " + create.statusCode() + " " + create.body());

            HttpResponse<String> imp = ServerHarness.multipart("/api/importSnapshot", t,
                    String.valueOf(TS1), csv("1,\n2,3.0"), null);
            assertEquals(400, imp.statusCode(), "非空列空字段应 400 拒绝");
            assertTrue(imp.body().contains("INGEST_REJECTED"), "错误体含结构化错误码");

            // 该 ts 无快照产生（导入被整体拒绝）
            JsonNode stats = post("/api/getTableStats", "{\"table\":\"" + t + "\"}");
            assertEquals(0, stats.get("totalRows").asInt(), "不应产生任何数据");
        } finally {
            deleteTableQuiet(t);
        }
    }

    // ---- UI-N3：主键列 nullable:true → 400 ----

    @Test
    @DisplayName("UI-N3 主键列声明 nullable=true 建表被 400 拒绝")
    void primaryKeyNullableRejected() throws Exception {
        String t = "n3_" + System.nanoTime();
        HttpResponse<String> r = ServerHarness.send("POST", "/api/createTable",
                "{\"name\":\"" + t + "\",\"primaryKey\":\"id\",\"retentionDays\":30,"
                        + "\"columns\":[{\"name\":\"id\",\"type\":\"INT\",\"nullable\":true},"
                        + "{\"name\":\"v\",\"type\":\"DOUBLE\"}]}",
                null);
        assertEquals(400, r.statusCode(), "主键可空应 400");
        assertTrue(r.body().contains("主键列不允许为可空"), "错误消息: " + r.body());

        // 表未创建
        JsonNode tables = post("/api/listTables", "{}");
        boolean exists = false;
        for (JsonNode n : tables) {
            if (n.asText().equals(t)) exists = true;
        }
        assertFalse(exists, "主键可空建表失败后不应残留表");
    }

    // ---- UI-N4：可空列混合数据跨快照正确 ----

    @Test
    @DisplayName("UI-N4 可空列部分空/部分非空跨快照查询不串数据")
    void nullableMixedAcrossSnapshots() throws Exception {
        String t = "n4_" + System.nanoTime();
        try {
            createTable(t);
            importCsv(t, TS1, "1,,a\n2,1.0,b");
            importCsv(t, TS2, "1,9.0,\n2,,b");

            // 单点历史：点 1 = [null(TS1), 9.0(TS2)]；列式 rows 每行=[key,v,region]，timestamps 对齐
            JsonNode series = post("/api/getPointSeries",
                    "{\"table\":\"" + t + "\",\"key\":\"1\",\"from\":0,\"to\":" + TS2 + ",\"limit\":100}");
            JsonNode sRows = series.get("rows");
            assertEquals(2, sRows.size(), "点1 两个快照");
            assertTrue(sRows.get(0).get(1).isNull(), "TS1 v=null");
            assertEquals(9.0, sRows.get(1).get(1).asDouble(), 0.0, "TS2 v=9.0");
            assertEquals(2, series.get("timestamps").size(), "timestamps 与 rows 对齐");
            assertEquals(TS1, series.get("timestamps").get(0).asLong());
            assertEquals(TS2, series.get("timestamps").get(1).asLong());

            // 点 2 历史 = [1.0(TS1), null(TS2)]
            JsonNode s2 = post("/api/getPointSeries",
                    "{\"table\":\"" + t + "\",\"key\":\"2\",\"from\":0,\"to\":" + TS2 + ",\"limit\":100}");
            assertEquals(1.0, s2.get("rows").get(0).get(1).asDouble(), 0.0, "TS1 v=1.0");
            assertTrue(s2.get("rows").get(1).get(1).isNull(), "TS2 v=null");
        } finally {
            deleteTableQuiet(t);
        }
    }

    // ---- UI-N5：getTableInfo 表结构数据来源 ----

    @Test
    @DisplayName("UI-N5 getTableInfo 返回 nullable 与 primaryKeyIndex（表结构展示数据来源）")
    void tableInfoSchemaForStructureDisplay() throws Exception {
        String t = "n5_" + System.nanoTime();
        try {
            createTable(t);
            JsonNode info = post("/api/getTableInfo", "{\"table\":\"" + t + "\"}");
            JsonNode schema = info.get("schema");
            assertEquals(0, schema.get("primaryKeyIndex").asInt(), "主键列下标=0");
            JsonNode cols = schema.get("columns");
            assertEquals(3, cols.size());
            assertFalse(cols.get(0).get("nullable").asBoolean(), "主键列非空");
            assertTrue(cols.get(1).get("nullable").asBoolean(), "v 可空");
            assertTrue(cols.get(2).get("nullable").asBoolean(), "region 可空");
            assertEquals("v", cols.get(1).get("name").asText());
            assertEquals("DOUBLE", cols.get(1).get("type").asText());
        } finally {
            deleteTableQuiet(t);
        }
    }

    // ---- UI-N6：压缩等级表级冻结 ----

    @Test
    @DisplayName("UI-N6 建表显式 compressionLevel 与 TableInfo 一致（表级冻结）")
    void compressionLevelPersistsPerTable() throws Exception {
        String t = "n6_" + System.nanoTime();
        try {
            HttpResponse<String> create = ServerHarness.send("POST", "/api/createTable",
                    "{\"name\":\"" + t + "\",\"primaryKey\":\"id\",\"retentionDays\":30,\"compressionLevel\":10,"
                            + "\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"v\",\"type\":\"DOUBLE\",\"nullable\":true}]}",
                    null);
            assertTrue(create.statusCode() < 300, "建表应成功: " + create.statusCode() + " " + create.body());
            JsonNode info = post("/api/getTableInfo", "{\"table\":\"" + t + "\"}");
            assertEquals(10, info.get("compressionLevel").asInt(), "表级压缩等级=10");
        } finally {
            deleteTableQuiet(t);
        }
    }
}
