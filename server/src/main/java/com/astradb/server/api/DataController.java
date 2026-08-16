package com.astradb.server.api;

import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.query.PointSeriesQuery;
import com.astradb.core.query.SnapshotQuery;
import com.astradb.server.service.AstraDbService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 数据 API：快照导入与查询（全部 POST）。
 */
@RestController
@RequestMapping("/api")
public class DataController {

    private final AstraDbService service;

    public DataController(AstraDbService service) {
        this.service = service;
    }

    public record SnapshotListRequest(String table) {
    }

    public record SnapshotRequest(String table, long ts, int offset, int limit) {
    }

    public record SeriesRequest(String table, String key, long from, long to, int limit) {
    }

    public record SegmentRequest(String table, String path) {
    }

    public record DeleteSegmentRequest(String table, String path, Boolean confirm) {
    }

    @PostMapping("/importSnapshot")
    public SnapshotIngestor.IngestResult importSnapshot(
            @RequestParam("table") String table,
            @RequestParam(value = "timestamp", required = false) Long timestamp,
            @RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return service.db().ingest(table, in, timestamp);
        }
    }

    @PostMapping("/listSnapshots")
    public List<Long> listSnapshots(@RequestBody SnapshotListRequest req) throws IOException {
        return service.db().listSnapshots(req.table());
    }

    @PostMapping("/getSnapshot")
    public SnapshotQuery.SnapshotPage getSnapshot(@RequestBody SnapshotRequest req) throws IOException {
        return service.db().snapshot(req.table(), req.ts(), req.offset(), req.limit());
    }

    @PostMapping("/getPointSeries")
    public List<PointSeriesQuery.PointRecord> getPointSeries(@RequestBody SeriesRequest req) throws IOException {
        return service.db().series(req.table(), req.key(), req.from(), req.to(), req.limit());
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
}
