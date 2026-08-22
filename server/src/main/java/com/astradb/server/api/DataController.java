package com.astradb.server.api;

import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.meta.Schema;
import com.astradb.core.query.PointSeriesQuery;
import com.astradb.core.query.SnapshotQuery;
import com.astradb.server.service.AstraDbService;
import com.astradb.server.service.ImportTaskService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据 API：快照导入与查询（全部 POST）。
 */
@RestController
@RequestMapping("/api")
public class DataController {

    private final AstraDbService service;
    private final ImportTaskService importTaskService;

    public DataController(AstraDbService service, ImportTaskService importTaskService) {
        this.service = service;
        this.importTaskService = importTaskService;
    }

    public record SnapshotListRequest(String table) {
    }

    public record SnapshotRequest(String table, long ts, int offset, int limit) {
    }

    public record FullSnapshotRequest(String table, long ts) {
    }

    public record DeleteSnapshotRequest(String table, long ts, Boolean confirm) {
    }

    public record SeriesRequest(String table, String key, long from, long to, int limit) {
    }

    public record SegmentRequest(String table, String path) {
    }

    public record DeleteSegmentRequest(String table, String path, Boolean confirm) {
    }

    /** 列式分页响应：主键列名 + 列名数组（主键在首位）+ 二维行数据（每行含主键值）。 */
    public record ColumnarPage(String pk, List<String> columns, List<Object[]> rows,
                               long timestamp, long totalRows, int offset, int limit) {
    }

    /** 列式点系列响应：时间戳数组与 rows 一一对齐（columns 不含时间戳元列）。 */
    public record ColumnarSeries(String pk, List<String> columns, List<Object[]> rows,
                                 List<Long> timestamps) {
    }

    @PostMapping("/importSnapshot")
    public SnapshotIngestor.IngestResult importSnapshot(
            @RequestParam("table") String table,
            @RequestParam(value = "timestamp", required = false) Long timestamp,
            @RequestParam("file") MultipartFile file) throws IOException {
        com.astradb.core.meta.Schema schema = service.db().tableInfo(table).schema();
        try (InputStream in = file.getInputStream()) {
            com.astradb.core.ingest.SnapshotData data = com.astradb.server.ingest.CsvParser.parse(in, schema, true);
            return service.db().ingest(table, data, timestamp);
        }
    }

    @PostMapping("/listSnapshots")
    public List<Long> listSnapshots(@RequestBody SnapshotListRequest req) throws IOException {
        return service.db().listSnapshots(req.table());
    }

    /** 异步导入：提交后立即返回 taskId，后台执行（SS-7：CSV 解析与落盘均在后台线程，
     *  请求线程仅读取文件字节，大文件不阻塞 HTTP 请求）。 */
    @PostMapping("/importAsync")
    public Map<String, String> importAsync(
            @RequestParam("table") String table,
            @RequestParam(value = "timestamp", required = false) Long timestamp,
            @RequestParam("file") MultipartFile file) throws IOException {
        service.db().tableInfo(table); // 表存在性前置校验（错误同步返回，而非后台失败）
        byte[] csvBytes = file.getBytes();
        String taskId = importTaskService.submit(table, timestamp, csvBytes);
        return Map.of("taskId", taskId);
    }

    public record TaskStatusRequest(String taskId) {
    }

    /** 查询异步导入任务状态。 */
    @PostMapping("/importStatus")
    public ImportTaskService.TaskState importStatus(@RequestBody TaskStatusRequest req) {
        ImportTaskService.TaskState st = importTaskService.status(req.taskId());
        if (st == null) {
            throw new IllegalArgumentException("任务不存在: " + req.taskId());
        }
        return st;
    }

    @PostMapping("/getSnapshot")
    public ColumnarPage getSnapshot(@RequestBody SnapshotRequest req) throws IOException {
        SnapshotQuery.SnapshotPage page = service.db().snapshot(req.table(), req.ts(), req.offset(), req.limit());
        Schema schema = service.db().tableInfo(req.table()).schema();
        List<String> columns = schema.columns().stream().map(c -> c.name()).toList();
        List<Object[]> rows = page.rows().stream().map(DataController::columnarRow).toList();
        return new ColumnarPage(columns.get(schema.primaryKeyIndex()), columns, rows,
                page.timestamp(), page.totalRows(), page.offset(), page.limit());
    }

    /** 全量快照（不分页，流式 JSON 输出避免整页内存缓冲）。 */
    @PostMapping("/getFullSnapshot")
    public void getFullSnapshot(@RequestBody FullSnapshotRequest req, HttpServletResponse resp) throws IOException {
        SnapshotQuery.FullSnapshot fs = service.db().fullSnapshot(req.table(), req.ts());
        Schema schema = service.db().tableInfo(req.table()).schema();
        List<String> columns = schema.columns().stream().map(c -> c.name()).toList();
        String pk = columns.get(schema.primaryKeyIndex());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try (JsonGenerator g = new JsonFactory().createGenerator(resp.getOutputStream())) {
            g.writeStartObject();
            g.writeStringField("pk", pk);
            g.writeArrayFieldStart("columns");
            for (String c : columns) {
                g.writeString(c);
            }
            g.writeEndArray();
            g.writeNumberField("timestamp", fs.timestamp());
            g.writeNumberField("totalRows", fs.totalRows());
            g.writeArrayFieldStart("rows");
            for (SnapshotQuery.Row row : fs.rows()) {
                g.writeStartArray();
                g.writeObject(row.key());
                for (Object v : row.values()) {
                    g.writeObject(v);
                }
                g.writeEndArray();
            }
            g.writeEndArray();
            g.writeEndObject();
            g.flush();
        }
    }

    @PostMapping("/getPointSeries")
    public ColumnarSeries getPointSeries(@RequestBody SeriesRequest req) throws IOException {
        List<PointSeriesQuery.PointRecord> records =
                service.db().series(req.table(), req.key(), req.from(), req.to(), req.limit());
        Schema schema = service.db().tableInfo(req.table()).schema();
        List<String> columns = schema.columns().stream().map(c -> c.name()).toList();
        List<Object[]> rows = records.stream().map(r -> {
            Object[] arr = new Object[r.values().size() + 1];
            arr[0] = req.key();
            for (int i = 0; i < r.values().size(); i++) {
                arr[i + 1] = r.values().get(i);
            }
            return arr;
        }).toList();
        List<Long> timestamps = records.stream().map(PointSeriesQuery.PointRecord::timestamp).toList();
        return new ColumnarSeries(columns.get(schema.primaryKeyIndex()), columns, rows, timestamps);
    }

    /** 构造列式行：主键值在首位 + 各数据列值（与 columns 顺序一致）。 */
    private static Object[] columnarRow(SnapshotQuery.Row row) {
        Object[] arr = new Object[row.values().size() + 1];
        arr[0] = row.key();
        for (int i = 0; i < row.values().size(); i++) {
            arr[i + 1] = row.values().get(i);
        }
        return arr;
    }

    /** 删除指定时间点的快照（不可恢复，需 confirm=true）。 */
    @PostMapping("/deleteSnapshot")
    public Map<String, Object> deleteSnapshot(@RequestBody DeleteSnapshotRequest req) throws IOException {
        service.db().deleteSnapshot(req.table(), req.ts(), Boolean.TRUE.equals(req.confirm()));
        return Map.of("deleted", true, "table", req.table(), "ts", req.ts());
    }

    /** 段内快照时间戳与行数（数据文件查看）。 */
    @PostMapping("/listSegmentSnapshots")
    public List<com.astradb.core.AstraDB.SegmentSnapshotInfo> listSegmentSnapshots(@RequestBody SegmentRequest req)
            throws IOException {
        return service.db().listSegmentSnapshots(req.table(), req.path());
    }

    /** 删除段文件（不可恢复，需 confirm）。 */
    @PostMapping("/deleteSegment")
    public java.util.Map<String, Object> deleteSegment(@RequestBody DeleteSegmentRequest req) throws IOException {
        if (req.confirm() == null || !req.confirm()) {
            throw new IllegalArgumentException("删除数据文件不可恢复，需携带 confirm=true");
        }
        service.db().deleteSegment(req.table(), req.path());
        return java.util.Map.of("deleted", true, "table", req.table(), "path", req.path());
    }

    /** 批量导入：多个 CSV + 对应时间戳（严格递增），一次落盘减少 fsync。 */
    @PostMapping("/importSnapshots")
    public List<SnapshotIngestor.IngestResult> importSnapshots(
            @RequestParam("table") String table,
            @RequestParam("file") List<org.springframework.web.multipart.MultipartFile> files,
            @RequestParam(value = "timestamps", required = false) List<Long> timestamps) throws IOException {
        if (timestamps == null || timestamps.size() != files.size()) {
            throw new IllegalArgumentException(
                    "批量导入需为每个文件提供对应时间戳（timestamps 列表，与 file 一一对应且严格递增）");
        }
        com.astradb.core.meta.Schema schema = service.db().tableInfo(table).schema();
        List<SnapshotIngestor.BatchSnapshot> snapshots = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            com.astradb.core.ingest.SnapshotData data;
            try (InputStream in = files.get(i).getInputStream()) {
                data = com.astradb.server.ingest.CsvParser.parse(in, schema, true);
            }
            snapshots.add(new SnapshotIngestor.BatchSnapshot(data, timestamps.get(i)));
        }
        return service.db().ingestBatch(table, snapshots);
    }
}
