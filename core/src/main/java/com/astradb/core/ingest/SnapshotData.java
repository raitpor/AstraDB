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

    /** 内容哈希（64 位 FNV-1a）：幂等导入判定用（列类型/数组内容/null 位图），
     *  64 位显著降低不同内容哈希碰撞被误判幂等跳过的风险。 */
    public long contentHash64() {
        long h = 0xcbf29ce484222325L;
        h = fnv(h, rowCount);
        for (Column c : columns) {
            h = fnv(h, c.type().ordinal());
            long[] bm = c.nullBitmap();
            if (bm != null) {
                for (long w : bm) {
                    h = fnv(h, w);
                }
            }
            switch (c.type()) {
                case INT -> {
                    for (int v : c.ints()) {
                        h = fnv(h, v);
                    }
                }
                case LONG -> {
                    for (long v : c.longs()) {
                        h = fnv(h, v);
                    }
                }
                case DOUBLE -> {
                    for (double v : c.doubles()) {
                        h = fnv(h, Double.doubleToRawLongBits(v));
                    }
                }
                case STRING -> {
                    for (String v : c.strings()) {
                        if (v == null) {
                            h = fnv(h, 0L);
                        } else {
                            // 逐 char 直接喂 FNV-1a（64 位区分度），弃用 String.hashCode()（32 位域）
                            for (int i = 0; i < v.length(); i++) {
                                h = fnv(h, v.charAt(i));
                            }
                        }
                    }
                }
            }
        }
        return h;
    }

    private static long fnv(long h, long v) {
        return (h ^ v) * 0x100000001b3L;
    }
}
