package com.astradb.core.meta;

import java.util.List;
import java.util.Objects;

/**
 * 表 schema：建表即冻结（列数、列类型、主键列不可修改）。
 * 列序即存储列序，第 0 列为主键列。
 */
public final class Schema {

    /** 列定义。nullable=true 表示允许该列存在空值（null）；主键列强制非空。 */
    public record ColumnDef(String name, ColumnType type, boolean nullable) {
        public ColumnDef {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        public ColumnDef(String name, ColumnType type) {
            this(name, type, false);
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
        // 主键列强制非空（design 7.1）
        if (columns.get(primaryKeyIndex).nullable()) {
            throw new IllegalArgumentException("主键列不允许为可空: " + columns.get(primaryKeyIndex).name());
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
