package com.astradb.server;

import com.astradb.client.protocol.BinaryProtocol;
import com.astradb.client.protocol.BinaryProtocol.ColumnData;
import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 二进制端点契约测试：importBinary（含 null/全 null 列/损坏帧 400）与
 * queryFullSnapshotBinary（列名/类型/位图/有效值，行对齐含主键列）。
 */
@SpringBootTest(properties = {"astradb.data-dir=target/bin-contract-data", "astradb.security.enabled=false"})
@AutoConfigureMockMvc
class BinaryEndpointTest {

    @Autowired
    MockMvc mvc;

    private static final long T0 = 1_767_225_600_000L;

    @BeforeAll
    static void clean() throws IOException {
        if (Files.exists(Path.of("target/bin-contract-data"))) {
            try (var walk = Files.walk(Path.of("target/bin-contract-data"))) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static byte[] encode(List<List<Object>> rows, List<String> names) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        BinaryProtocol.encode(BinaryProtocol.encodeRows(rows, names), bos);
        return bos.toByteArray();
    }

    @Test
    void importAndQueryFullSnapshotBinaryWithNulls() throws Exception {
        mvc.perform(post("/api/createTable").contentType("application/json").content("""
                {"name":"bt","primaryKey":"id","columns":[{"name":"id","type":"INT"},
                            {"name":"a","type":"INT","nullable":true},
                            {"name":"b","type":"DOUBLE","nullable":true},
                            {"name":"c","type":"STRING","nullable":true}]}"""))
                .andExpect(status().isOk());

        byte[] body = encode(java.util.Arrays.asList(
                java.util.Arrays.asList(1, 10, 1.5, "华东"),
                java.util.Arrays.asList(2, null, null, null),
                java.util.Arrays.asList(3, 30, null, "华北")), List.of("id", "a", "b", "c"));
        mvc.perform(post("/api/importBinary").param("table", "bt").param("timestamp", String.valueOf(T0))
                        .contentType("application/octet-stream").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(3));

        MvcResult r = mvc.perform(post("/api/queryFullSnapshotBinary")
                        .contentType("application/json")
                        .content("{\"table\":\"bt\",\"ts\":" + T0 + "}"))
                .andExpect(status().isOk()).andReturn();
        Frame frame = BinaryProtocol.decode(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        assertEquals(4, frame.columns().size());
        assertEquals("id", frame.columns().get(0).name());
        assertEquals(3, frame.rowCount());
        assertEquals(1, valueAt(frame, 0, 0));          // 主键列
        assertNull(valueAt(frame, 1, 1));                // a 行 2 null
        assertNull(valueAt(frame, 2, 2));                // b 行 3 null
        assertEquals("华北", valueAt(frame, 3, 2));
    }

    @Test
    void allNullColumnAndCorruptFrame() throws Exception {
        mvc.perform(post("/api/createTable").contentType("application/json").content("""
                {"name":"an","primaryKey":"id","columns":[{"name":"id","type":"INT"},
                            {"name":"x","type":"INT","nullable":true}]}"""))
                .andExpect(status().isOk());
        // 全 null 列（forcedTypes 按 schema）
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        BinaryProtocol.encode(BinaryProtocol.encodeRows(
                java.util.Arrays.asList(java.util.Arrays.asList(1, null), java.util.Arrays.asList(2, null)),
                List.of("id", "x"), List.of(ColumnType.INT, ColumnType.INT)), bos);
        mvc.perform(post("/api/importBinary").param("table", "an").param("timestamp", String.valueOf(T0))
                        .contentType("application/octet-stream").content(bos.toByteArray()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2));
        MvcResult r = mvc.perform(post("/api/queryFullSnapshotBinary")
                        .contentType("application/json")
                        .content("{\"table\":\"an\",\"ts\":" + T0 + "}"))
                .andExpect(status().isOk()).andReturn();
        Frame frame = BinaryProtocol.decode(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        assertNull(valueAt(frame, 1, 0));
        assertNull(valueAt(frame, 1, 1));

        // 损坏帧 → 400 INGEST_REJECTED（非 500）
        mvc.perform(post("/api/importBinary").param("table", "an").param("timestamp", String.valueOf(T0 + 1000))
                        .contentType("application/octet-stream").content(new byte[]{1, 2, 3, 4, 0}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGEST_REJECTED"));
    }

    @Test
    void corruptVarintLenFrameRejectedAs400() throws Exception {
        // 独立建表（方法执行顺序不确定，不依赖其他测试的表）
        mvc.perform(post("/api/createTable").contentType("application/json").content(
                "{\"name\":\"vn\",\"primaryKey\":\"id\",\"columns\":[{\"name\":\"id\",\"type\":\"INT\"}]}"))
                .andExpect(status().isOk());
        // SS-2：列名字符串长度 varint = 0xFFFFFFFF。旧实现 (int) 强转 -1 → NegativeArraySizeException
        // → 500；修复后长度超限以受控 IOException 拒绝 → 400 INGEST_REJECTED。
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{'A', 'S', 'D', 'B'});   // magic
        bos.write(1);                                 // version
        bos.write(0);                                 // flags
        bos.write(new byte[]{1, 0});                  // columnCount=1
        bos.write(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}); // 长度 varint
        mvc.perform(post("/api/importBinary").param("table", "vn").param("timestamp", String.valueOf(T0 + 2000))
                        .contentType("application/octet-stream").content(bos.toByteArray()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INGEST_REJECTED"));
    }

    private static Object valueAt(Frame f, int col, int row) {
        ColumnDef def = f.columns().get(col);
        long[] bitmap = f.data().get(col).nullBitmap();
        Object values = f.data().get(col).values();
        if (BinaryProtocol.isNull(bitmap, row)) {
            return null;
        }
        int idx = row - (bitmap == null ? 0 : BinaryProtocol.popcount(bitmap, row));
        return switch (def.type()) {
            case INT -> ((int[]) values)[idx];
            case LONG -> ((long[]) values)[idx];
            case DOUBLE -> ((double[]) values)[idx];
            case STRING -> ((String[]) values)[idx];
        };
    }
}
