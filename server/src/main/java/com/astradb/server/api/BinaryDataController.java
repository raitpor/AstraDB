package com.astradb.server.api;

import com.astradb.client.protocol.BinaryProtocol;
import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnData;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.meta.Schema;
import com.astradb.core.query.SnapshotQuery;
import com.astradb.server.ingest.BinaryIngestParser;
import com.astradb.server.service.AstraDbService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 二进制数据流端点（client-design.md 第 5 节）：导入与全量查询走专有列式协议。
 */
@RestController
@RequestMapping("/api")
public class BinaryDataController {

    private final AstraDbService service;

    public BinaryDataController(AstraDbService service) {
        this.service = service;
    }

    /** 二进制导入：请求体 = 列式二进制流（列名/类型/nullable/位图/有效值）。 */
    @PostMapping("/importBinary")
    public Map<String, Object> importBinary(
            @RequestParam("table") String table,
            @RequestParam(value = "timestamp", required = false) Long timestamp,
            @RequestBody byte[] body) throws IOException {
        Schema schema = service.db().tableInfo(table).schema();
        SnapshotData data = BinaryIngestParser.parse(new java.io.ByteArrayInputStream(body), schema);
        SnapshotIngestor.IngestResult r = service.db().ingest(table, data, timestamp);
        return Map.of("rowCount", r.rowCount(), "newPoints", r.newPoints(), "timestamp", r.timestamp());
    }

    /** 二进制全量快照查询：响应 = 列式二进制流（列名/类型/nullable/位图/有效值，含主键列）。 */
    @PostMapping("/queryFullSnapshotBinary")
    public void queryFullSnapshotBinary(@RequestBody SnapshotQueryRequest req, HttpServletResponse resp)
            throws IOException {
        SnapshotQuery.FullSnapshot fs = service.db().fullSnapshot(req.table(), req.ts());
        Schema schema = service.db().tableInfo(req.table()).schema();
        resp.setContentType("application/octet-stream");
        int pkIndex = schema.primaryKeyIndex();
        List<ColumnDef> defs = new ArrayList<>(schema.columnCount());
        List<ColumnData> data = new ArrayList<>(schema.columnCount());
        int valuesIndex = 0;
        for (int c = 0; c < schema.columnCount(); c++) {
            Schema.ColumnDef sdef = schema.columns().get(c);
            boolean isPk = c == pkIndex;
            defs.add(new ColumnDef(sdef.name(), toProtocol(sdef.type()), sdef.nullable()));
            data.add(columnData(sdef, isPk, valuesIndex, fs));
            if (!isPk) {
                valuesIndex++;
            }
        }
        BinaryProtocol.encode(new Frame(defs, (int) fs.totalRows(), data), resp.getOutputStream());
    }

    /** 由行数据（key + values）组装本列列式数据：位图 + 有效值数组。 */
    private ColumnData columnData(Schema.ColumnDef sdef, boolean isPk, int valuesIndex,
                                  SnapshotQuery.FullSnapshot fs) {
        int rows = (int) fs.totalRows();
        boolean nullable = sdef.nullable();
        long[] bitmap = nullable ? new long[(rows + 63) / 64] : null;
        List<Object> eff = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            SnapshotQuery.Row row = fs.rows().get(r);
            Object v;
            if (isPk) {
                v = parseKey(sdef.type(), row.key());
            } else {
                v = valuesIndex < row.values().size() ? row.values().get(valuesIndex) : null;
            }
            if (v == null) {
                if (bitmap != null) {
                    bitmap[r >>> 6] |= 1L << (r & 63);
                }
            } else {
                eff.add(v);
            }
        }
        return new ColumnData(bitmap, toArray(sdef.type(), eff));
    }

    private static Object parseKey(com.astradb.core.meta.ColumnType t, String key) {
        return switch (t) {
            case INT -> Integer.parseInt(key);
            case LONG -> Long.parseLong(key);
            case DOUBLE -> Double.parseDouble(key);
            default -> key;
        };
    }

    private static Object toArray(com.astradb.core.meta.ColumnType t, List<Object> eff) {
        return switch (t) {
            case INT -> {
                int[] a = new int[eff.size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = ((Number) eff.get(i)).intValue();
                }
                yield a;
            }
            case LONG -> {
                long[] a = new long[eff.size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = ((Number) eff.get(i)).longValue();
                }
                yield a;
            }
            case DOUBLE -> {
                double[] a = new double[eff.size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = ((Number) eff.get(i)).doubleValue();
                }
                yield a;
            }
            default -> eff.toArray(new String[0]);
        };
    }

    private static ColumnType toProtocol(com.astradb.core.meta.ColumnType t) {
        return switch (t) {
            case INT -> ColumnType.INT;
            case LONG -> ColumnType.LONG;
            case DOUBLE -> ColumnType.DOUBLE;
            case STRING -> ColumnType.STRING;
        };
    }

    public record SnapshotQueryRequest(String table, long ts) {
    }
}
