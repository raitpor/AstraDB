package com.astradb.client;

import java.util.List;

/**
 * 快照查询结果：列名与数据行（行数据对齐列名，含主键列；可空列 null 值以 null 表示）。
 */
public record QueryResult(String[] columns, List<Object[]> rows) {

    public QueryResult {
        columns = columns == null ? new String[0] : columns;
        rows = rows == null ? List.of() : rows;
    }

    public int rowCount() {
        return rows.size();
    }
}
