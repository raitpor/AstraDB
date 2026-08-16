package com.astradb.core.meta;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 表元数据（内存态）：schema、保留期、压缩等级与数据目录。
 */
public final class TableMeta {

    private final String name;
    private final Schema schema;
    private final int retentionDays;
    private final int compressionLevel;
    private final Path dir;

    public TableMeta(String name, Schema schema, int retentionDays, int compressionLevel, Path dir) {
        this.name = Objects.requireNonNull(name, "name");
        this.schema = Objects.requireNonNull(schema, "schema");
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be > 0");
        }
        if (compressionLevel < 1 || compressionLevel > 22) {
            throw new IllegalArgumentException("compressionLevel must be in [1,22]");
        }
        this.retentionDays = retentionDays;
        this.compressionLevel = compressionLevel;
        this.dir = Objects.requireNonNull(dir, "dir");
    }

    public String name() {
        return name;
    }

    public Schema schema() {
        return schema;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    /** 表数据目录：data/&lt;table&gt;。 */
    public Path dir() {
        return dir;
    }

    @Override
    public String toString() {
        return "Table{" + name + ", " + schema + ", retention=" + retentionDays
                + "d, zstd=" + compressionLevel + "}";
    }
}
