package com.astradb.client;

import com.astradb.client.protocol.BinaryProtocol;
import com.astradb.client.protocol.BinaryProtocol.ColumnData;
import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * client 契约测试：JDK HttpServer 模拟 server，验证请求构造/认证/响应组装/错误码
 * （ingest 二进制字节、queryFullSnapshot 行对齐列名、queryPointAt 精确时间点、认证 401）。
 */
class ClientContractTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void route(String path, Handler h) {
        server.createContext(path, h::handle);
    }

    interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Test
    void ingestSendsColumnarBinaryWithSchemaTypes() throws Exception {
        route("/api/getTableInfo", ex -> json(ex, 200,
                "{\"schema\":{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},"
                        + "{\"name\":\"v\",\"type\":\"DOUBLE\",\"nullable\":true}]}}"));
        route("/api/importBinary", ex -> {
            assertEquals("t1", queryParam(ex, "table"));
            assertEquals("1000", queryParam(ex, "timestamp"));
            byte[] body = ex.getRequestBody().readAllBytes();
            assertEquals('A', body[0]);
            assertEquals('S', body[1]);
            assertEquals('D', body[2]);
            assertEquals('B', body[3]);
            json(ex, 200, "{\"rowCount\":2,\"newPoints\":2}");
        });
        int rows = new AstraDbClient(baseUrl).ingest("t1", 1000L, List.of(
                java.util.Arrays.asList(1, 1.5),
                java.util.Arrays.asList(2, 2.5)));
        assertEquals(2, rows);
    }

    @Test
    void queryFullSnapshotAssemblesColumnsAndRows() throws Exception {
        route("/api/queryFullSnapshotBinary", ex -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BinaryProtocol.encode(new Frame(List.of(
                            new ColumnDef("id", ColumnType.INT, false),
                            new ColumnDef("v", ColumnType.DOUBLE, true)),
                    2,
                    List.of(new ColumnData(null, new int[]{1, 2}),
                            new ColumnData(new long[]{2}, new double[]{1.5}))),
                    bos);
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            byte[] b = bos.toByteArray();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        QueryResult r = new AstraDbClient(baseUrl).queryFullSnapshot("t1", 1000L);
        assertArrayEquals(new String[]{"id", "v"}, r.columns());
        assertArrayEquals(new Object[]{1, 1.5}, r.rows().get(0));
        assertArrayEquals(new Object[]{2, null}, r.rows().get(1));
    }

    @Test
    void queryPointAtExactTimestampAndNull() throws Exception {
        route("/api/getPointSeries", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"from\":1000") && body.contains("\"to\":1000") && body.contains("\"limit\":1"));
            json(ex, 200, body.contains("nope") ? "{\"pk\":\"id\",\"columns\":[\"id\",\"v\",\"region\"],\"rows\":[],\"timestamps\":[]}"
                    : "{\"pk\":\"id\",\"columns\":[\"id\",\"v\",\"region\"],\"rows\":[[\"42\",10,1.5,\"华东\"]],\"timestamps\":[1000]}");
        });
        AstraDbClient client = new AstraDbClient(baseUrl);
        assertArrayEquals(new Object[]{10L, 1.5, "华东"}, client.queryPointAt("t1", "42", 1000L));
        assertNull(client.queryPointAt("t1", "nope", 1000L));
    }

    @Test
    void queryPointAtEscapesControlAndQuoteInKey() throws Exception {
        route("/api/getPointSeries", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // SS-10：STRING 主键含换行/引号/反斜杠、表名含制表符 → JSON 字符串完整转义
            // （修复前 escape() 只转义 \ 与 "，换行/制表符生成裸控制字符的非法 JSON）
            assertTrue(body.contains("\"table\":\"t\\t1\""), "表名制表符应转义: " + body);
            assertTrue(body.contains("\"key\":\"a\\nb\\\"c\\\\d\""), "key 换行/引号/反斜杠应转义: " + body);
            json(ex, 200, "{\"pk\":\"id\",\"columns\":[\"id\",\"v\"],\"rows\":[[\"a\\nb\\\"c\\\\d\",1.0]],\"timestamps\":[1000]}");
        });
        Object[] r = new AstraDbClient(baseUrl).queryPointAt("t\t1", "a\nb\"c\\d", 1000L);
        assertArrayEquals(new Object[]{1.0}, r);
    }

    @Test
    void basicAuthAndErrorMapping() throws Exception {
        route("/api/getTableInfo", ex -> {
            assertTrue(ex.getRequestHeaders().getFirst("Authorization").startsWith("Basic "));
            json(ex, 401, "{\"code\":\"INVALID_ARGUMENT\",\"message\":\"未认证\"}");
        });
        ClientException e = assertThrows(ClientException.class, () -> new AstraDbClient(baseUrl, "admin", "p").ingest(
                "t", 1L, List.of(java.util.Arrays.asList(1, 1.0))));
        assertEquals("UNAUTHORIZED", e.code());
    }

    @Test
    void typeMismatchRejectedLocally() {
        route("/api/getTableInfo", ex -> {
            try {
                json(ex, 200, "{\"schema\":{\"columns\":[{\"name\":\"id\",\"type\":\"INT\"},{\"name\":\"v\",\"type\":\"DOUBLE\"}]}}");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        ClientException e = assertThrows(ClientException.class, () -> new AstraDbClient(baseUrl).ingest(
                "t", 1L, List.of(java.util.Arrays.asList(1, "x"))));
        assertEquals("TYPE_MISMATCH", e.code());
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }
}
