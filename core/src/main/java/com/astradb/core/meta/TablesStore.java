package com.astradb.core.meta;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 表元数据仓库（tables.json）：表名 → TableMeta。
 */
public final class TablesStore {

    public record ColumnRecord(@JsonProperty("name") String name, @JsonProperty("type") ColumnType type) {
    }

    public record TableRecord(@JsonProperty("name") String name,
                              @JsonProperty("schemaVersion") int schemaVersion,
                              @JsonProperty("primaryKey") String primaryKey,
                              @JsonProperty("retentionDays") int retentionDays,
                              @JsonProperty("compressionLevel") int compressionLevel,
                              @JsonProperty("columns") List<ColumnRecord> columns) {
    }

    private record Store(@JsonProperty("tables") List<TableRecord> tables) {
    }

    private final Path dataDir;
    private final Map<String, TableMeta> tables = new TreeMap<>();

    private TablesStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    public static TablesStore load(Path dataDir) throws IOException {
        TablesStore store = new TablesStore(dataDir);
        Store s = JsonFiles.read(dataDir.resolve("tables.json"), Store.class);
        if (s != null && s.tables() != null) {
            for (TableRecord r : s.tables()) {
                store.tables.put(r.name(), toMeta(dataDir, r));
            }
        }
        return store;
    }

    private static TableMeta toMeta(Path dataDir, TableRecord r) {
        List<Schema.ColumnDef> defs = new ArrayList<>();
        for (ColumnRecord c : r.columns()) {
            defs.add(new Schema.ColumnDef(c.name(), c.type()));
        }
        int pkIndex = -1;
        for (int i = 0; i < defs.size(); i++) {
            if (defs.get(i).name().equals(r.primaryKey())) {
                pkIndex = i;
                break;
            }
        }
        if (pkIndex < 0) {
            throw new IllegalArgumentException("主键列不存在: " + r.primaryKey() + " in table " + r.name());
        }
        Schema schema = new Schema(r.schemaVersion(), defs, pkIndex);
        return new TableMeta(r.name(), schema, r.retentionDays(), r.compressionLevel(),
                dataDir.resolve(r.name()));
    }

    public void save() throws IOException {
        List<TableRecord> list = new ArrayList<>();
        for (TableMeta t : tables.values()) {
            Schema s = t.schema();
            List<ColumnRecord> cols = new ArrayList<>();
            for (Schema.ColumnDef c : s.columns()) {
                cols.add(new ColumnRecord(c.name(), c.type()));
            }
            list.add(new TableRecord(t.name(), s.version(), s.primaryKey().name(),
                    t.retentionDays(), t.compressionLevel(), cols));
        }
        JsonFiles.write(dataDir.resolve("tables.json"), new Store(list));
    }

    public void put(TableMeta meta) {
        tables.put(meta.name(), meta);
    }

    public TableMeta get(String name) {
        return tables.get(name);
    }

    public TableMeta remove(String name) {
        return tables.remove(name);
    }

    public List<TableMeta> all() {
        return List.copyOf(tables.values());
    }

    public boolean contains(String name) {
        return tables.containsKey(name);
    }
}
