package com.astradb.core;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.ingest.SnapshotIngestor.BatchSnapshot;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入/回填/删除语义测试：校验矩阵（类型/nullable/重复/乱序/主键）、
 * 任意时间戳回填、删除指定快照（窗口收缩/空段移除/重启一致）。
 */
class IngestBackfillDeleteTest {

    @TempDir
    Path tmp;

    private static final long T0 = 1_767_225_600_000L;

    @Test
    void ingestionValidationMatrix() throws Exception {
        Path dataDir = tmp.resolve("v");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(true), "id", 30, 3);
            // 列数不符
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,1.0,9\n", List.of(
                            new Schema.ColumnDef("id", ColumnType.INT, false),
                            new Schema.ColumnDef("v", ColumnType.DOUBLE, true),
                            new Schema.ColumnDef("x", ColumnType.INT, false))), T0));
            // 类型不符（v 应为 DOUBLE，传 INT）
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,5\n", List.of(
                            new Schema.ColumnDef("id", ColumnType.INT, false),
                            new Schema.ColumnDef("v", ColumnType.INT, false))), T0));
            // 非空列出现 null
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,\n", List.of(
                            new Schema.ColumnDef("id", ColumnType.INT, false),
                            new Schema.ColumnDef("v", ColumnType.DOUBLE, false))), T0));
            // 主键空/重复
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv(",\n", TestSupport.simpleColumns(true)), T0));
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,1.0\n1,2.0\n", TestSupport.simpleColumns(true)), T0));
            // 快照为空
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("", TestSupport.simpleColumns(true)), T0));
            // 主键列不允许可空（Schema 校验）
            assertThrows(IllegalArgumentException.class,
                    () -> new Schema(1, List.of(
                            new Schema.ColumnDef("id", ColumnType.INT, true)), 0));
            // 主键必须第一列
            assertThrows(IllegalArgumentException.class,
                    () -> db.createTable("pk", List.of(
                            new Schema.ColumnDef("a", ColumnType.INT, false),
                            new Schema.ColumnDef("id", ColumnType.INT, false)), "id", 30, 3));
        }
    }

    @Test
    void batchAndBackfillSemantics() throws Exception {
        Path dataDir = tmp.resolve("b");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,4.0\n2,4.1\n3,4.2\n", TestSupport.simpleColumns(false)), T0 + 600_000);
            // 批量回填中间两个空洞（批内严格递增）
            List<BatchSnapshot> batch = List.of(
                    new BatchSnapshot(TestSupport.csv("1,3.0\n2,3.1\n", TestSupport.simpleColumns(false)), T0 + 300_000),
                    new BatchSnapshot(TestSupport.csv("1,3.5\n", TestSupport.simpleColumns(false)), T0 + 450_000));
            db.ingestBatch("t", batch);
            assertEquals(List.of(T0, T0 + 300_000, T0 + 450_000, T0 + 600_000), db.listSnapshots("t"));
            // 批量内乱序拒绝
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingestBatch("t", List.of(
                            new BatchSnapshot(TestSupport.csv("1,9.0\n", TestSupport.simpleColumns(false)), T0 + 100),
                            new BatchSnapshot(TestSupport.csv("1,8.0\n", TestSupport.simpleColumns(false)), T0))));
            // 与已有快照重复拒绝（单条与批量）
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingest("t", TestSupport.csv("1,9.0\n", TestSupport.simpleColumns(false)), T0));
            assertThrows(SnapshotIngestor.IngestException.class,
                    () -> db.ingestBatch("t", List.of(
                            new BatchSnapshot(TestSupport.csv("1,9.0\n", TestSupport.simpleColumns(false)), T0))));
        }
    }

    @Test
    void deleteSnapshotShrinksWindowAndRemovesEmptySegment() throws Exception {
        Path dataDir = tmp.resolve("d");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(false), "id", 30, 3);
            db.ingest("t", TestSupport.csv("1,1.0\n2,2.0\n", TestSupport.simpleColumns(false)), T0);
            db.ingest("t", TestSupport.csv("1,2.0\n2,2.1\n3,2.2\n", TestSupport.simpleColumns(false)), T0 + 300_000);
            db.ingest("t", TestSupport.csv("1,3.0\n2,3.1\n3,3.2\n4,3.3\n", TestSupport.simpleColumns(false)), T0 + 600_000);

            // 删除中间快照 → 该时间点空、窗口不变、行数收缩
            db.deleteSnapshot("t", T0 + 300_000, true);
            assertEquals(0, db.snapshot("t", T0 + 300_000, 0, 100).totalRows());
            var seg = db.stats("t").segments().get(0);
            assertEquals(T0 + 600_000, seg.endTime());
            assertEquals(6, seg.rows()); // 2 + 4

            // 删除末快照 → endTime 收缩
            db.deleteSnapshot("t", T0 + 600_000, true);
            assertEquals(T0, db.stats("t").segments().get(0).endTime());

            // 删除最后一个 → 段文件移除
            db.deleteSnapshot("t", T0, true);
            assertEquals(0, db.stats("t").segmentCount());

            // confirm 语义 + 不存在拒绝
            db.ingest("t", TestSupport.csv("1,1.0\n", TestSupport.simpleColumns(false)), T0);
            assertThrows(IllegalArgumentException.class, () -> db.deleteSnapshot("t", T0, false));
            assertThrows(IllegalArgumentException.class, () -> db.deleteSnapshot("t", T0 + 999, true));
        }
    }

    @Test
    void directSnapshotDataIngest() throws Exception {
        Path dataDir = tmp.resolve("s");
        try (AstraDB db = AstraDB.open(dataDir)) {
            db.createTable("t", TestSupport.simpleColumns(true), "id", 30, 3);
            // 直接构造列缓冲（非 CSV 路径）
            SnapshotData data = new SnapshotData(List.of(
                    Column.ofInts(new int[]{1, 2}),
                    Column.ofDoubles(new double[]{1.5, 0}, new long[]{2})), 2);
            db.ingest("t", data, T0);
            var page = db.snapshot("t", T0, 0, 10);
            assertEquals(2, page.totalRows());
            assertTrue(page.rows().get(1).values().get(0) == null);
        }
    }
}
