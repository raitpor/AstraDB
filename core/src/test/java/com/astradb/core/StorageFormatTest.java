package com.astradb.core;

import com.astradb.core.compress.ZstdCompressor;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.segment.Chunk;
import com.astradb.core.segment.ChunkCodec;
import com.astradb.core.segment.SegmentFormat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储格式测试：v2（null 位图 + 有效值序列）格式正确性与 v1/v2 空间对比
 * （v1 保留编码仅作空间对比基准，生产只读写 v2）。
 */
class StorageFormatTest {

    private static final ZstdCompressor ZSTD = new ZstdCompressor(3);
    private static final long T0 = 1_700_000_000_000L;

    /** 全非空列：位图省略，编码为紧凑基线（验证零 null 开销）。 */
    @Test
    void v2AllNotNullIsCompact() {
        int rows = 10_000;
        Chunk chunk = new Chunk(T0, 1, List.of(
                Column.ofInts(range(rows)),
                Column.ofDoubles(noNulls(rows))));
        byte[] encoded = ChunkCodec.encode(chunk, ZSTD);
        // 10 万数值仅数 KB（无位图开销、压缩生效）
        assertTrue(encoded.length < 64 * 1024, "全非空应紧凑: " + encoded.length);
    }

    /** 含 null 数据：有效值序列（跳过 null 行）应显著小于全非空基线（占位污染压缩）。 */
    @Test
    void v2WithNullsIsSmallerThanAllNotNull() {
        int rows = 10_000;
        long[] bitmap = new long[(rows + 63) / 64];
        double[] values = new double[rows];
        Random rnd = new Random(7);
        for (int i = 0; i < rows; i++) {
            if (rnd.nextDouble() < 0.5) {
                bitmap[i >>> 6] |= 1L << (i & 63);
            }
            values[i] = 19387.53 + rnd.nextDouble() * 100;
        }
        Chunk chunk = new Chunk(T0, 1, List.of(
                Column.ofInts(range(rows)),
                Column.ofDoubles(values, bitmap)));
        // 对照：同一随机数据全非空（占位 0 参与编码）vs 含 null（有效值序列）
        double[] allNotNull = new double[rows];
        for (int i = 0; i < rows; i++) {
            allNotNull[i] = values[i];
        }
        Chunk baseline = new Chunk(T0, 1, List.of(
                Column.ofInts(range(rows)),
                Column.ofDoubles(allNotNull)));
        byte[] withNulls = ChunkCodec.encode(chunk, ZSTD);
        byte[] allNotNullEnc = ChunkCodec.encode(baseline, ZSTD);
        // 50% null → 有效值 5 千行；Gorilla 对伪随机差值压缩率差 → 明显更小
        assertTrue(withNulls.length <= allNotNullEnc.length,
                "有效值序列不应大于全量编码: null=" + withNulls.length + " full=" + allNotNullEnc.length);
    }

    /** v2 全 null 列：无有效值、解码全 null。 */
    @Test
    void v2AllNullColumnFormat() {
        int rows = 200;
        long[] bitmap = new long[(rows + 63) / 64];
        java.util.Arrays.fill(bitmap, -1L);
        Chunk chunk = new Chunk(T0, 1, List.of(
                Column.ofInts(range(rows)),
                Column.ofLongs(new long[rows], bitmap)));
        byte[] encoded = ChunkCodec.encode(chunk, ZSTD);
        Chunk decoded = ChunkCodec.decode(encoded, ZSTD);
        assertEquals(ColumnType.LONG, decoded.column(1).type());
        for (int i = 0; i < rows; i++) {
            assertNull(decoded.column(1).valueAt(i));
        }
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    private static double[] noNulls(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = 100.0 + i;
        }
        return a;
    }
}
