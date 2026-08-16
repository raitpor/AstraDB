package com.astradb.core.ingest;

import com.astradb.core.meta.Column;

import java.util.List;

/**
 * 快照数据载体：按 schema 列序排列的列缓冲 + 行数。
 * 由 CsvParser 解析产出，也可由其他导入方式（JSON、Java API 等）构造，
 * 直接传给 {@link SnapshotIngestor} 即可导入。
 */
public record SnapshotData(List<Column> columns, int rowCount) {

    public SnapshotData {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0");
        }
    }

    public Column column(int i) {
        return columns.get(i);
    }
}
