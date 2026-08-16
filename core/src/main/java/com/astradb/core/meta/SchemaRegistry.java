package com.astradb.core.meta;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * schema 版本历史（schema-registry.json）。当前 schema 冻结，仅 v1。
 */
public final class SchemaRegistry {

    /** 与 Schema.ColumnDef 同构的 JSON 记录。 */
    public record ColumnRecord(@JsonProperty("name") String name, @JsonProperty("type") ColumnType type) {
        public Schema.ColumnDef toDef() {
            return new Schema.ColumnDef(name, type);
        }
    }

    public record VersionRecord(@JsonProperty("version") int version,
                                @JsonProperty("columns") List<ColumnRecord> columns) {
    }

    public record History(@JsonProperty("versions") List<VersionRecord> versions) {
    }

    private final Path file;
    private final List<Schema.ColumnDef> columns;

    private SchemaRegistry(Path file, List<Schema.ColumnDef> columns) {
        this.file = file;
        this.columns = List.copyOf(columns);
    }

    /** 新建（v1 冻结）。 */
    public static SchemaRegistry create(Path file, List<Schema.ColumnDef> columns) throws IOException {
        SchemaRegistry r = new SchemaRegistry(file, columns);
        r.save();
        return r;
    }

    public static SchemaRegistry load(Path file) throws IOException {
        History h = JsonFiles.read(file, History.class);
        if (h == null || h.versions() == null || h.versions().isEmpty()) {
            throw new IOException("schema-registry.json 缺失或为空: " + file);
        }
        VersionRecord v = h.versions().get(h.versions().size() - 1);
        List<Schema.ColumnDef> cols = new ArrayList<>();
        for (ColumnRecord c : v.columns()) {
            cols.add(c.toDef());
        }
        return new SchemaRegistry(file, cols);
    }

    public void save() throws IOException {
        List<ColumnRecord> cols = new ArrayList<>();
        for (Schema.ColumnDef c : columns) {
            cols.add(new ColumnRecord(c.name(), c.type()));
        }
        List<VersionRecord> versions = List.of(new VersionRecord(1, cols));
        JsonFiles.write(file, new History(versions));
    }

    public Schema schema(int primaryKeyIndex) {
        return new Schema(1, columns, primaryKeyIndex);
    }

    public List<Schema.ColumnDef> columns() {
        return columns;
    }
}
