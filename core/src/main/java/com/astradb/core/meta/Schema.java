package com.astradb.core.meta;

import java.util.List;
import java.util.Objects;

/**
 * 表 schema：建表即冻结（列数、列类型、主键列不可修改）。
 * 列序即存储列序，第 0 列为主键列。
 */
public final class Schema {

    /** 列定义。 */
    public record ColumnDef(String name, ColumnType type) {
        public ColumnDef {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    private final int version;
    private final List<ColumnDef> columns;
    private final int primaryKeyIndex;

    public Schema(int version, List<ColumnDef> columns, int primaryKeyIndex) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
        if (primaryKeyIndex < 0 || primaryKeyIndex >= columns.size()) {
            throw new IllegalArgumentException("invalid primaryKeyIndex: " + primaryKeyIndex);
        }
        this.version = version;
        this.columns = List.copyOf(columns);
        this.primaryKeyIndex = primaryKeyIndex;
    }

    public int version() {
        return version;
    }

    public List<ColumnDef> columns() {
        return columns;
    }

    public int columnCount() {
        return columns.size();
    }

    public int primaryKeyIndex() {
        return primaryKeyIndex;
    }

    public ColumnDef primaryKey() {
        return columns.get(primaryKeyIndex);
    }

    public ColumnDef column(int i) {
        return columns.get(i);
    }

    public String columnName(int i) {
        return columns.get(i).name();
    }

    // ---- Jackson 序列化 getter（UI/API 用） ----

    public int getVersion() {
        return version;
    }

    public List<ColumnDef> getColumns() {
        return columns;
    }

    public int getPrimaryKeyIndex() {
        return primaryKeyIndex;
    }

    @Override
    public String toString() {
        return "Schema{v" + version + ", cols=" + columns + ", pk=" + primaryKeyIndex + "}";
    }
}
