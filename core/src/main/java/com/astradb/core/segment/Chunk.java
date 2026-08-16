package com.astradb.core.segment;

import com.astradb.core.meta.Column;

import java.util.List;

/**
 * 内存态快照 Chunk：时间戳 + 按 schema 列序排列的列数据（第 0 列为主键 pointId 列）。
 */
public final class Chunk {

    private final long timestamp;
    private final int schemaVersion;
    private final List<Column> columns;

    public Chunk(long timestamp, int schemaVersion, List<Column> columns) {
        this.timestamp = timestamp;
        this.schemaVersion = schemaVersion;
        this.columns = List.copyOf(columns);
    }

    public long timestamp() {
        return timestamp;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public int rowCount() {
        return columns.isEmpty() ? 0 : columns.get(0).rowCount();
    }

    public int columnCount() {
        return columns.size();
    }

    public Column column(int i) {
        return columns.get(i);
    }

    public List<Column> columns() {
        return columns;
    }

    @Override
    public String toString() {
        return "Chunk{ts=" + timestamp + ", rows=" + rowCount() + ", cols=" + columnCount() + "}";
    }
}
