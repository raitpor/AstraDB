package com.astradb.server.ingest;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
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
 * 解析结果为 {@link SnapshotData}，可直接交给 core 的 {@link SnapshotIngestor} 导入（core 导入前校验表结构与列类型）。
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
        List<Boolean> nullableFlags = new ArrayList<>(schema.columnCount());
        for (Schema.ColumnDef def : schema.columns()) {
            nullableFlags.add(def.nullable());
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
                } else if (ch == '\n' || ch == '\r') {
                    // SS-5：引号内出现换行 = 未闭合引号。本项目不支持跨行字段，
                    // 报格式错误而非把后续行并入同一字段（列数恰好匹配时静默导入错误数据）
                    throw new SnapshotIngestor.IngestException(
                            "CSV 引号未闭合（第 " + (rowNum + 1) + " 行）");
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
                        throw new com.astradb.core.ingest.SnapshotIngestor.IngestException("CSV 第 " + (rowNum + 1) + " 行列数不符: 期望 " + columnCount
                                + ", 实际 " + fields.size());
                    }
                    for (int i = 0; i < columnCount; i++) {
                        builders.get(i).add(fields.get(i), nullableFlags.get(i));
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
        if (inQuotes) {
            // SS-5：文件在引号内结束 = 未闭合引号，报格式错误
            throw new SnapshotIngestor.IngestException("CSV 引号未闭合（文件结尾）");
        }
        if (rowStart && cur.isEmpty() && fields.isEmpty()) {
            // 空尾行
        } else {
            fields.add(cur.toString());
            if (!(rowNum == 0 && mayHaveHeader && isHeader(fields, schema))) {
                if (fields.size() != columnCount) {
                    throw new com.astradb.core.ingest.SnapshotIngestor.IngestException("CSV 最后一行列数不符: 期望 " + columnCount + ", 实际 " + fields.size());
                }
                for (int i = 0; i < columnCount; i++) {
                    builders.get(i).add(fields.get(i), nullableFlags.get(i));
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
