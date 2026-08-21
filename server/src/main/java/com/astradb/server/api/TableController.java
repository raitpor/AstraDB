package com.astradb.server.api;

import com.astradb.core.AstraDB;
import com.astradb.core.manifest.Manifest;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;
import com.astradb.server.service.AstraDbService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 表管理与统计 API（全部 POST，路径为明确操作）。
 */
@RestController
@RequestMapping("/api")
public class TableController {

    private final AstraDbService service;

    public TableController(AstraDbService service) {
        this.service = service;
    }

    public record ColumnDef(String name, ColumnType type, Boolean nullable) {
        public ColumnDef(String name, ColumnType type) {
            this(name, type, null);
        }
    }

    public record CreateTableRequest(String name, List<ColumnDef> columns, String primaryKey,
                                     Integer retentionDays, Integer compressionLevel) {
    }

    public record TableRequest(String table) {
    }

    public record DeleteTableRequest(String table, Boolean confirm) {
    }

    @PostMapping("/createTable")
    public AstraDB.TableInfo createTable(@RequestBody CreateTableRequest req) throws IOException {
        if (req.columns() == null || req.columns().isEmpty()) {
            // SS-1：缺 columns 字段 → 400（而非 NPE → 500）
            throw new IllegalArgumentException("列定义不能为空（需提供 columns 字段）");
        }
        List<Schema.ColumnDef> defs = req.columns().stream()
                .map(c -> new Schema.ColumnDef(c.name(), c.type(), Boolean.TRUE.equals(c.nullable())))
                .toList();
        return service.db().createTable(req.name(), defs, req.primaryKey(),
                req.retentionDays() != null ? req.retentionDays() : AstraDB.DEFAULT_RETENTION_DAYS,
                req.compressionLevel());
    }

    @PostMapping("/listTables")
    public List<String> listTables() {
        return service.db().listTables();
    }

    @PostMapping("/getTableInfo")
    public AstraDB.TableInfo tableInfo(@RequestBody TableRequest req) {
        return service.db().tableInfo(req.table());
    }

    @PostMapping("/deleteTable")
    public java.util.Map<String, Object> deleteTable(@RequestBody DeleteTableRequest req) throws IOException {
        if (req.confirm() == null || !req.confirm()) {
            throw new IllegalArgumentException("删除表不可恢复，需携带 confirm=true");
        }
        service.db().dropTable(req.table());
        return java.util.Map.of("deleted", true, "table", req.table());
    }

    @PostMapping("/getTableStats")
    public AstraDB.TableStats tableStats(@RequestBody TableRequest req) {
        return service.db().stats(req.table());
    }

    /** 段文件列表（表详情页用）。 */
    @PostMapping("/listSegments")
    public List<Manifest.SegmentInfo> listSegments(@RequestBody TableRequest req) {
        return service.db().stats(req.table()).segments();
    }
}
