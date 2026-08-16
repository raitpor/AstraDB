package com.astradb.core.ingest;

import com.astradb.core.meta.Column;
import com.astradb.core.meta.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式 CSV 解析（RFC 4180 风格）：UTF-8、逗号分隔、引号转义（""），
 * 列序与 schema 一致；首行若与列名完全一致则视为表头跳过。
 * 解析结果为 {@link SnapshotData}，可直接交给 {@link SnapshotIngestor} 导入。
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static SnapshotData parse(InputStream in, Schema schema, boolean mayHaveHeader) throws IOException {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        List<ColumnBuilder> builders = new ArrayList<>(schema.columnCount());
        for (Schema.ColumnDef def : schema.columns()) {
            builders.add(new ColumnBuilder(def.type()));
        }

        int columnCount = schema.columnCount();
        List<String> fields = new ArrayList<>(columnCount);
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean afterQuote = false;
        boolean rowStart = true;
        int rowNum = 0;

        int c;
        while ((c = reader.read()) != -1) {
            char ch = (char) c;
            if (inQuotes) {
                if (ch == '"') {
                    afterQuote = true;
                    inQuotes = false;
                } else {
                    cur.append(ch);
                }
                continue;
            }
            if (afterQuote) {
                afterQuote = false;
                if (ch == '"') {
                    cur.append('"');
                    inQuotes = true;
                    continue;
                }
                // 引号后必须是分隔符或换行，否则格式非法（宽松处理：按普通字符继续）
            }
            switch (ch) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    fields.add(cur.toString());
                    cur.setLength(0);
                    rowStart = false;
                }
                case '\n' -> {
                    fields.add(cur.toString());
                    cur.setLength(0);
                    if (rowNum == 0 && mayHaveHeader && isHeader(fields, schema)) {
                        fields.clear();
                        rowStart = true;
                        continue;
                    }
                    if (fields.size() != columnCount) {
                        throw new IOException("CSV 第 " + (rowNum + 1) + " 行列数不符: 期望 " + columnCount
                                + ", 实际 " + fields.size());
                    }
                    for (int i = 0; i < columnCount; i++) {
                        builders.get(i).add(fields.get(i));
                    }
                    rowNum++;
                    fields.clear();
                    rowStart = true;
                }
                case '\r' -> {
                    // 忽略；\r\n 由 \n 处理
                }
                default -> {
                    cur.append(ch);
                    rowStart = false;
                }
            }
        }
        // 最后一行无换行符
        if (rowStart && cur.isEmpty() && fields.isEmpty()) {
            // 空尾行
        } else {
            fields.add(cur.toString());
            if (!(rowNum == 0 && mayHaveHeader && isHeader(fields, schema))) {
                if (fields.size() != columnCount) {
                    throw new IOException("CSV 最后一行列数不符: 期望 " + columnCount + ", 实际 " + fields.size());
                }
                for (int i = 0; i < columnCount; i++) {
                    builders.get(i).add(fields.get(i));
                }
                rowNum++;
            }
        }

        List<Column> columns = new ArrayList<>(columnCount);
        for (ColumnBuilder bld : builders) {
            columns.add(bld.build());
        }
        return new SnapshotData(columns, rowNum);
    }

    private static boolean isHeader(List<String> fields, Schema schema) {
        if (fields.size() != schema.columnCount()) {
            return false;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).equals(schema.columnName(i))) {
                return false;
            }
        }
        return true;
    }
}
