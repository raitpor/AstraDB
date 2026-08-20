package com.astradb.server;

import com.astradb.client.AstraDbClient;
import com.astradb.client.QueryResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * aslpv 数据一致性测试（client 全链路：HTTP + 二进制协议，真实 server）：
 * 表 aslpv（pointId INT 主键 / pointValue DOUBLE 可空，zstd 等级 20），
 * 2020-01-01 每 5 分钟一个快照（288 个），单快照 10 万点、值随机（含 null）。
 * 流程：逐快照导入，每导入一个（除零点）后查询上一个快照全量校对；
 * 全部导入后再逐一查询校对（快照不可变 + 精确时间点匹配）。
 * 数据确定性重生成（seed = 基 + 序号），不驻留 2880 万行内存。
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"astradb.data-dir=target/aslpv-data", "astradb.security.enabled=false"})
class AslpvConsistencyIT {

    @LocalServerPort
    int port;

    private static final String TABLE = "aslpv";
    private static final int POINTS = 100_000;
    private static final int SNAPSHOTS = 288;               // 24h * 12/h
    private static final long INTERVAL_MS = 5 * 60_000L;
    private static final long DAY_START_UTC_MS = 1_577_808_000_000L; // 本地（Asia/Shanghai）2020-01-01 00:00 = UTC 2019-12-31 16:00，保证 288 快照同段
    private static final long BASE_SEED = 20200101L;
    private static final double NULL_DENSITY = 0.3;

    @BeforeAll
    @AfterAll
    static void clean() throws IOException {
        if (Files.exists(Path.of("target/aslpv-data"))) {
            try (var walk = Files.walk(Path.of("target/aslpv-data"))) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static long tsOf(int i) {
        return DAY_START_UTC_MS + i * INTERVAL_MS;
    }

    /** 确定性生成第 i 个快照（10 万点，pointValue 随机或 null）。 */
    private static List<List<Object>> genSnapshot(int i) {
        Random rnd = new Random(BASE_SEED + i * 7919L);
        List<List<Object>> rows = new ArrayList<>(POINTS);
        for (int p = 1; p <= POINTS; p++) {
            rows.add(Arrays.asList(p, rnd.nextDouble() < NULL_DENSITY ? null
                    : Math.round(rnd.nextDouble() * 100_000) / 100.0));
        }
        return rows;
    }

    /** 全量校对：查询结果与重新生成的原始数据逐行一致（key/值/null）。 */
    private static void verify(QueryResult r, int i) {
        List<List<Object>> expected = genSnapshot(i);
        assertEquals(POINTS, r.rowCount(), "快照 " + i + " 行数");
        assertEquals(2, r.columns().length);
        for (int row = 0; row < POINTS; row++) {
            Object[] actual = r.rows().get(row);
            assertEquals(row + 1, ((Number) actual[0]).intValue(), "快照 " + i + " 行 " + row + " key");
            Object exp = expected.get(row).get(1);
            Object act = actual[1];
            if (exp == null) {
                assertNull(act, "快照 " + i + " 行 " + row + " 应为 null");
            } else {
                assertNotNull(act, "快照 " + i + " 行 " + row + " 不应为 null");
                assertEquals((Double) exp, ((Number) act).doubleValue(), 1e-9,
                        "快照 " + i + " 行 " + row + " 值");
            }
        }
    }

    @Test
    void aslpvFullDayConsistency() throws Exception {
        AstraDbClient client = new AstraDbClient("http://localhost:" + port);
        // 建表：zstd 等级 20、pointId 主键、pointValue 可空
        client.createTable(TABLE, List.of(
                Map.of("name", "pointId", "type", "INT"),
                Map.of("name", "pointValue", "type", "DOUBLE", "nullable", true)), "pointId", 20);

        long t0 = System.nanoTime();
        long importMs = 0;
        long queryMs = 0;
        // 逐快照导入；每导入一个（除零点）查询上一个快照全量校对
        for (int i = 0; i < SNAPSHOTS; i++) {
            List<List<Object>> rows = genSnapshot(i);
            long ti = System.nanoTime();
            int rc = client.ingest(TABLE, tsOf(i), rows);
            importMs += (System.nanoTime() - ti) / 1_000_000;
            assertEquals(POINTS, rc, "快照 " + i + " rowCount");
            if (i > 0) {
                long tq = System.nanoTime();
                QueryResult prev = client.queryFullSnapshot(TABLE, tsOf(i - 1));
                queryMs += (System.nanoTime() - tq) / 1_000_000;
                verify(prev, i - 1);
            }
            if (i % 48 == 0 || i == SNAPSHOTS - 1) {
                System.out.printf("[aslpv] 导入第 %d/%d 快照完成（累计 %d ms）%n", i + 1, SNAPSHOTS, importMs);
            }
        }
        long totalImportMs = (System.nanoTime() - t0) / 1_000_000;

        // 全部导入后逐一查询校对
        long tqAll = System.nanoTime();
        long finalQueryMs = 0;
        for (int i = 0; i < SNAPSHOTS; i++) {
            long tq = System.nanoTime();
            QueryResult r = client.queryFullSnapshot(TABLE, tsOf(i));
            finalQueryMs += (System.nanoTime() - tq) / 1_000_000;
            verify(r, i);
        }
        long totalFinalMs = (System.nanoTime() - tqAll) / 1_000_000;

        // 统计断言：单日单段、点集 10 万、总行数 2880 万
        var stats = client.getTableInfo(TABLE);
        System.out.println("[aslpv] pointCount=" + stats.get("pointCount") + " segmentCount=" + stats.get("segmentCount")
                + " totalRows=" + stats.get("totalRows") + " sizeBytes=" + stats.get("totalSizeBytes"));
        assertEquals(100_000, ((Number) stats.get("pointCount")).longValue());
        assertEquals(1, ((Number) stats.get("segmentCount")).longValue());
        assertEquals(28_800_000L, ((Number) stats.get("totalRows")).longValue());

        System.out.printf("[aslpv] 结果：导入 %d 快照总耗时 %d ms（均值 %.0f ms/个）；逐次校对查询 %d ms（均值 %.0f ms/次）；最终 %d 次查询 %d ms（均值 %.0f ms/次）%n",
                SNAPSHOTS, totalImportMs, totalImportMs / (double) SNAPSHOTS,
                queryMs, queryMs / (double) (SNAPSHOTS - 1), SNAPSHOTS, totalFinalMs, totalFinalMs / (double) SNAPSHOTS);
    }
}
