package com.astradb.server;

import com.astradb.client.protocol.BinaryProtocol;
import com.astradb.client.protocol.BinaryProtocol.ColumnData;
import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 契约测试（MockMvc）：表/数据/二进制端点契约 + 结构化错误。
 */
@SpringBootTest(properties = {"astradb.data-dir=target/contract-data", "astradb.security.enabled=false"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiContractTest {

    @Autowired
    MockMvc mvc;

    private static final long T0 = 1_767_225_600_000L;

    @BeforeAll
    @AfterAll
    static void clean() throws IOException {
        if (Files.exists(Path.of("target/contract-data"))) {
            try (var walk = Files.walk(Path.of("target/contract-data"))) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Order(1)
    void tableAndSnapshotLifecycle() throws Exception {
        mvc.perform(post("/api/createTable").contentType("application/json").content("""
                {"name":"ct","primaryKey":"id","columns":[{"name":"id","type":"INT"},
                            {"name":"a","type":"INT","nullable":true},
                            {"name":"b","type":"DOUBLE"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ct"))
                .andExpect(jsonPath("$.schema.columns[1].nullable").value(true));

        // 导入（含 null）→ 快照列表 → 分页（null 还原）→ 全量
        mvc.perform(multipart("/api/importSnapshot").file(csv("s.csv", "1,10,1.5\n2,,2.5\n"))
                        .param("table", "ct").param("timestamp", String.valueOf(T0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2));
        mvc.perform(post("/api/listSnapshots").contentType("application/json")
                        .content("{\"table\":\"ct\"}"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/getSnapshot").contentType("application/json")
                        .content("{\"table\":\"ct\",\"ts\":" + T0 + ",\"offset\":0,\"limit\":10}"))
                .andExpect(jsonPath("$.pk").value("id"))
                .andExpect(jsonPath("$.columns[0]").value("id"))
                .andExpect(jsonPath("$.columns[1]").value("a"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.rows[1][1]").isEmpty()); // null（行2 的 a 列）
        mvc.perform(post("/api/getFullSnapshot").contentType("application/json")
                        .content("{\"table\":\"ct\",\"ts\":" + T0 + "}"))
                .andExpect(jsonPath("$.pk").value("id"))
                .andExpect(jsonPath("$.columns[0]").value("id"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.rows[0][0]").value(1)); // 行1 主键值

        // 回填中间空洞 + 重复拒绝 + 删除快照
        mvc.perform(multipart("/api/importSnapshot").file(csv("s2.csv", "1,20,2.5\n"))
                        .param("table", "ct").param("timestamp", String.valueOf(T0 + 300_000)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/listSnapshots").contentType("application/json")
                        .content("{\"table\":\"ct\"}"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(multipart("/api/importSnapshot").file(csv("s3.csv", "1,30,3.5\n"))
                        .param("table", "ct").param("timestamp", String.valueOf(T0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGEST_REJECTED"));
        mvc.perform(post("/api/deleteSnapshot").contentType("application/json")
                        .content("{\"table\":\"ct\",\"ts\":" + (T0 + 300_000) + ",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(post("/api/listSnapshots").contentType("application/json")
                        .content("{\"table\":\"ct\"}"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @Order(2)
    void structuredErrorsAndValidation() throws Exception {
        // 未知表 → 结构化 400
        mvc.perform(post("/api/getSnapshot").contentType("application/json")
                        .content("{\"table\":\"nope\",\"ts\":1,\"offset\":0,\"limit\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.timestamp").isNumber());
        // 非法表名（路径分隔符）
        mvc.perform(post("/api/createTable").contentType("application/json")
                        .content("{\"name\":\"../x\",\"primaryKey\":\"id\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}]}"))
                .andExpect(status().isBadRequest());
        // SS-1：缺 columns 字段 → 400（而非 NPE → 500）
        mvc.perform(post("/api/createTable").contentType("application/json")
                        .content("{\"name\":\"noc\",\"primaryKey\":\"id\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 主键非第一列
        mvc.perform(post("/api/createTable").contentType("application/json")
                        .content("{\"name\":\"pk\",\"primaryKey\":\"v\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"v\",\"type\":\"INT\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("第一列")));
        // 路径穿越（自建表避免依赖其他测试执行顺序）
        mvc.perform(post("/api/createTable").contentType("application/json").content(
                "{\"name\":\"st\",\"primaryKey\":\"id\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"v\",\"type\":\"DOUBLE\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/listSegmentSnapshots").contentType("application/json")
                        .content("{\"table\":\"st\",\"path\":\"../tables.json\"}"))
                .andExpect(status().isBadRequest());
        // 未知任务
        mvc.perform(post("/api/importStatus").contentType("application/json")
                        .content("{\"taskId\":\"import-999999\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    void batchAndAsyncImport() throws Exception {
        mvc.perform(post("/api/createTable").contentType("application/json").content("""
                {"name":"ba","primaryKey":"id","columns":[{"name":"id","type":"INT"},
                            {"name":"v","type":"DOUBLE"}]}"""))
                .andExpect(status().isOk());
        // 批量（含中间回填）
        mvc.perform(multipart("/api/importSnapshots")
                        .file(csv("a.csv", "1,1.0\n2,2.0\n")).file(csv("b.csv", "1,1.5\n2,2.5\n3,3.5\n"))
                        .param("table", "ba").param("timestamps", String.valueOf(T0), String.valueOf(T0 + 300_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        // 向已有段中间批量回填（批内时间戳早于段内已有但不存在）
        mvc.perform(multipart("/api/importSnapshots")
                        .file(csv("e.csv", "1,0.5\n2,0.6\n"))
                        .param("table", "ba").param("timestamps", String.valueOf(T0 + 150_000)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/listSnapshots").contentType("application/json")
                        .content("{\"table\":\"ba\"}"))
                .andExpect(jsonPath("$.length()").value(3));
        // 批内乱序 → 400
        mvc.perform(multipart("/api/importSnapshots")
                        .file(csv("c.csv", "1,9.9\n"))
                        .param("table", "ba").param("timestamps", String.valueOf(T0 + 100), String.valueOf(T0)))
                .andExpect(status().isBadRequest());
        // 异步导入 → 轮询 SUCCESS
        MvcResult r = mvc.perform(multipart("/api/importAsync").file(csv("d.csv", "1,7.7\n"))
                        .param("table", "ba").param("timestamp", String.valueOf(T0 + 600_000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isNotEmpty()).andReturn();
        String taskId = r.getResponse().getContentAsString().replaceAll(".*\"taskId\":\"([^\"]*)\".*", "$1");
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult st = mvc.perform(post("/api/importStatus").contentType("application/json")
                            .content("{\"taskId\":\"" + taskId + "\"}"))
                    .andExpect(status().isOk()).andReturn();
            if (st.getResponse().getContentAsString().contains("\"SUCCESS\"")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("异步导入超时未 SUCCESS");
    }
}
