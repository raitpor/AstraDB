package com.astradb.client;

import com.astradb.client.protocol.BinaryProtocol;
import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
import com.astradb.client.ClientJson.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * AstraDB 客户端 SDK：以专有二进制数据流与 server 交换数据（可集成到其他 Java 应用）。
 * <pre>
 * AstraDbClient client = new AstraDbClient("http://localhost:8080", "admin", "password");
 * int rows = client.ingest("t1", System.currentTimeMillis(), data);   // 行×列数据 → 导入
 * QueryResult r = client.queryFullSnapshot("t1", ts);                  // 列名 + 行数据（对齐）
 * Object[] point = client.queryPointAt("t1", "42", ts);                // 指定时间点单点值
 * </pre>
 */
public final class AstraDbClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final HttpClient http;
    private final String authHeader;

    public AstraDbClient(String baseUrl) {
        this(baseUrl, null, null);
    }

    public AstraDbClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.authHeader = username != null
                ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8))
                : null;
    }

    // ---- 导入 ----

    /**
     * 导入快照：行×列数据（Integer/Long/Double/Float/String，可含 null）→ 列式二进制上传。
     * 返回 rowCount。
     */
    public int ingest(String table, long timestamp, List<List<Object>> data) {
        if (data == null || data.isEmpty()) {
            throw new ClientException("INVALID_ARGUMENT", "数据不能为空");
        }
        // 拉取 schema：校验列数/类型，取得列名（协议帧携带列名）
        Node info = postJson("/api/getTableInfo", "{\"table\":" + ClientJson.quote(table) + "}");
        Node colsNode = info.get("schema").get("columns");
        int schemaCols = colsNode.asArray().size();
        List<String> names = new ArrayList<>(schemaCols);
        List<ColumnType> schemaTypes = new ArrayList<>(schemaCols);
        for (Node c : colsNode.asArray()) {
            names.add(c.get("name").asText());
            schemaTypes.add(columnTypeOf(c.get("type").asText()));
        }
        int dataCols = data.getFirst().size();
        if (dataCols != schemaCols) {
            throw new ClientException("TYPE_MISMATCH", "列数不符：期望 " + schemaCols + "，实际 " + dataCols);
        }
        for (int c = 0; c < schemaCols; c++) {
            ColumnType t = inferType(data, c);
            if (t != null && t != schemaTypes.get(c)) {
                throw new ClientException("TYPE_MISMATCH",
                        "第 " + (c + 1) + " 列期望 " + schemaTypes.get(c) + "，实际 " + t);
            }
        }

        Frame frame = BinaryProtocol.encodeRows(data, names, schemaTypes);
        byte[] body = encodeFrame(frame);
        Node resp = postBinary("/api/importBinary?table=" + encodeQuery(table) + "&timestamp=" + timestamp, body);
        Node rc = resp.get("rowCount");
        return rc == null ? -1 : (int) rc.asLong();
    }

    // ---- 全量快照查询 ----

    /** 查询指定时间点全量快照：列名与数据行（行对齐列名，含主键列；null 值以 null 表示）。 */
    public QueryResult queryFullSnapshot(String table, long timestamp) {
        byte[] body = postJsonBinary("/api/queryFullSnapshotBinary",
                ("{\"table\":" + ClientJson.quote(table) + ",\"ts\":" + timestamp + "}")
                        .getBytes(StandardCharsets.UTF_8));
        Frame frame;
        try {
            frame = BinaryProtocol.decode(new ByteArrayInputStream(body));
        } catch (IOException | RuntimeException e) {
            throw new ClientException("PROTOCOL_ERROR", "二进制响应解析失败: " + e.getMessage());
        }
        String[] columns = new String[frame.columns().size()];
        for (int i = 0; i < frame.columns().size(); i++) {
            columns[i] = frame.columns().get(i).name();
        }
        // 线性组装（每列有效值游标递增）：避免逐行 popcount 的 O(n²) 退化
        int cols = frame.columns().size();
        int[] effIdx = new int[cols];
        List<Object[]> rows = new ArrayList<>(frame.rowCount());
        for (int r = 0; r < frame.rowCount(); r++) {
            Object[] row = new Object[cols];
            for (int c = 0; c < cols; c++) {
                long[] bitmap = frame.data().get(c).nullBitmap();
                if (BinaryProtocol.isNull(bitmap, r)) {
                    row[c] = null;
                } else {
                    row[c] = valueAtEffective(frame, c, effIdx[c]++);
                }
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows);
    }

    // ---- 指定时间点单点数据 ----

    /**
     * 查询某点在指定时间戳的值（值列数组，不含主键列）；该时间点无此点数据返回 null。
     * 复用 getPointSeries（from=to=ts 精确匹配）。
     */
    public Object[] queryPointAt(String table, String key, long timestamp) {
        // SS-10：JSON 字符串统一走 ClientJson.quote（含控制字符转义），
        // 避免 STRING 主键含换行/制表符时生成裸控制字符的非法 JSON
        String body = "{\"table\":" + ClientJson.quote(table) + ",\"key\":" + ClientJson.quote(key)
                + ",\"from\":" + timestamp + ",\"to\":" + timestamp + ",\"limit\":1}";
        Node resp = postJson("/api/getPointSeries", body);
        List<Node> arr = resp.asArray();
        if (arr.isEmpty()) {
            return null;
        }
        List<Node> values = arr.get(0).get("values").asArray();
        Object[] out = new Object[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Node v = values.get(i);
            if (v.isNull()) {
                out[i] = null;
            } else if (v.isNumber()) {
                if (v.isFloatingPoint()) {
                    out[i] = v.asDouble();
                } else {
                    out[i] = v.asLong();
                }
            } else {
                out[i] = v.asText();
            }
        }
        return out;
    }

    // ---- 元数据（JSON） ----

    /** 建表：columns = 列定义（name, type=INT/LONG/DOUBLE/STRING, 可选 nullable）。 */
    public void createTable(String name, List<java.util.Map<String, Object>> columns, String primaryKey) {
        createTable(name, columns, primaryKey, null);
    }

    /** 建表（可指定 zstd 压缩等级 1~22；null 使用服务端默认）。 */
    public void createTable(String name, List<java.util.Map<String, Object>> columns, String primaryKey,
                            Integer compressionLevel) {
        StringBuilder sb = new StringBuilder("{\"name\":");
        sb.append(ClientJson.quote(name)).append(",\"primaryKey\":").append(ClientJson.quote(primaryKey));
        if (compressionLevel != null) {
            sb.append(",\"compressionLevel\":").append(compressionLevel);
        }
        sb.append(",\"columns\":[");
        for (int i = 0; i < columns.size(); i++) {
            java.util.Map<String, Object> c = columns.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(ClientJson.quote(String.valueOf(c.get("name"))))
                    .append(",\"type\":").append(ClientJson.quote(String.valueOf(c.get("type"))));
            Object nullable = c.get("nullable");
            if (nullable != null) {
                sb.append(",\"nullable\":").append(nullable);
            }
            sb.append('}');
        }
        sb.append("]}");
        postJson("/api/createTable", sb.toString());
    }

    /** 表信息（pointCount/segmentCount/totalRows/totalSizeBytes/schema 等）。 */
    public java.util.Map<String, Object> getTableInfo(String table) {
        Node info = postJson("/api/getTableInfo", "{\"table\":" + ClientJson.quote(table) + "}");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : List.of("name", "pointCount", "segmentCount", "totalRows", "totalSizeBytes",
                "retentionDays", "compressionLevel")) {
            Node v = info.get(key);
            out.put(key, v == null ? null : (v.isNumber() ? v.asLong() : v.asText()));
        }
        return out;
    }

    public List<String> listTables() {
        Node resp = postJson("/api/listTables", "{}");
        List<String> out = new ArrayList<>();
        for (Node n : resp.asArray()) {
            out.add(n.asText());
        }
        return out;
    }

    // ---- 内部 ----

    private static ColumnType inferType(List<List<Object>> data, int col) {
        for (List<Object> row : data) {
            Object v = row.get(col);
            if (v != null) {
                return BinaryProtocol.typeOf(v);
            }
        }
        return null; // 该列全 null：按 schema 类型
    }

    /** 按有效值索引取值（组装期游标已换算，O(1)）。 */
    private static Object valueAtEffective(Frame frame, int col, int effIdx) {
        ColumnDef def = frame.columns().get(col);
        Object values = frame.data().get(col).values();
        return switch (def.type()) {
            case INT -> ((int[]) values)[effIdx];
            case LONG -> ((long[]) values)[effIdx];
            case DOUBLE -> ((double[]) values)[effIdx];
            case STRING -> ((String[]) values)[effIdx];
        };
    }

    private static byte[] encodeFrame(Frame frame) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BinaryProtocol.encode(frame, bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ClientException("PROTOCOL_ERROR", "二进制编码失败: " + e.getMessage());
        }
    }

    private Node postJson(String path, String body) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<byte[]> resp = http.send(applyAuth(rb).build(), HttpResponse.BodyHandlers.ofByteArray());
            ensureOk(resp, path);
            try {
                return ClientJson.parse(new String(resp.body(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new ClientException("PROTOCOL_ERROR", "响应解析失败: " + e.getMessage());
            }
        } catch (IOException e) {
            throw new ClientException("NETWORK_ERROR", "请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("NETWORK_ERROR", "请求被中断");
        }
    }

    private Node postBinary(String path, byte[] body) {
        byte[] resp = postBinaryReturn(path, body);
        try {
            return ClientJson.parse(new String(resp, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ClientException("PROTOCOL_ERROR", "响应解析失败: " + e.getMessage());
        }
    }

    private byte[] postBinaryReturn(String path, byte[] body) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> resp = http.send(applyAuth(rb).build(), HttpResponse.BodyHandlers.ofByteArray());
            ensureOk(resp, path);
            return resp.body();
        } catch (IOException e) {
            throw new ClientException("NETWORK_ERROR", "请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("NETWORK_ERROR", "请求被中断");
        }
    }

    /** JSON 请求体、二进制响应（如 queryFullSnapshotBinary）。 */
    private byte[] postJsonBinary(String path, byte[] jsonBody) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody));
            HttpResponse<byte[]> resp = http.send(applyAuth(rb).build(), HttpResponse.BodyHandlers.ofByteArray());
            ensureOk(resp, path);
            return resp.body();
        } catch (IOException e) {
            throw new ClientException("NETWORK_ERROR", "请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("NETWORK_ERROR", "请求被中断");
        }
    }

    private HttpRequest.Builder applyAuth(HttpRequest.Builder rb) {
        return authHeader == null ? rb : rb.header("Authorization", authHeader);
    }

    private void ensureOk(HttpResponse<byte[]> resp, String path) {
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return;
        }
        String code = "HTTP_" + resp.statusCode();
        String message = "HTTP " + resp.statusCode();
        try {
            Node err = ClientJson.parse(new String(resp.body(), StandardCharsets.UTF_8));
            Node c = err.get("code");
            Node m = err.get("message");
            if (c != null) {
                code = c.asText();
            }
            if (m != null) {
                message = m.asText();
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体
        }
        if (resp.statusCode() == 401) {
            code = "UNAUTHORIZED";
        }
        throw new ClientException(code, message + " (" + path + ")");
    }

    private static ColumnType columnTypeOf(String type) {
        return switch (type) {
            case "INT" -> ColumnType.INT;
            case "LONG" -> ColumnType.LONG;
            case "DOUBLE" -> ColumnType.DOUBLE;
            default -> ColumnType.STRING;
        };
    }

    private static String encodeQuery(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
