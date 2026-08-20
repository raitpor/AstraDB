package com.astradb.core;

import com.astradb.core.compress.ZstdCompressor;
import com.astradb.core.meta.Column;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;
import com.astradb.core.segment.Chunk;
import com.astradb.core.segment.ChunkCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 属性测试：随机规模 × 类型 × null 密度下，列式编码 roundtrip 保持不变量
 * （整列解码 / valueAt / decodeRange 与原始数据一致；位图↔有效值索引换算正确）。
 * 固定种子保证可复现。
 */
class EncodingPropertyTest {

    private static final ZstdCompressor ZSTD = new ZstdCompressor(3);
    private static final int[] SIZES = {1, 2, 7, 63, 64, 65, 1000};
    private static final double[] NULL_DENSITY = {0.0, 0.3, 0.7, 1.0};
    private static final long SEED = 42L;

    @Test
    void roundtripPreservesValuesForAllTypesAndNullDensities() throws Exception {
        for (int size : SIZES) {
            for (double density : NULL_DENSITY) {
                Random rnd = new Random(SEED ^ size ^ Double.doubleToLongBits(density));
                for (ColumnType type : new ColumnType[]{ColumnType.INT, ColumnType.LONG, ColumnType.DOUBLE, ColumnType.STRING}) {
                    Column col = randomColumn(rnd, type, size, density);
                    Chunk chunk = new Chunk(1_700_000_000_000L, 1, List.of(
                            Column.ofInts(range(size)), col));
                    byte[] encoded = ChunkCodec.encode(chunk, ZSTD);
                    Chunk decoded = ChunkCodec.decode(encoded, ZSTD);
                    Column out = decoded.column(1);
                    assertColumnsEqual(col, out, size, "size=" + size + " type=" + type + " density=" + density);

                    // valueAt 与整列一致
                    ChunkCodec.RawColumn rc = ChunkCodec.rawColumnAt(encoded, 1, ZSTD);
                    for (int i = 0; i < size; i++) {
                        Object v = ChunkCodec.valueColumnAt(encoded, 1, i, ZSTD);
                        if (col.isNull(i)) {
                            assertNull(v);
                        } else {
                            assertEquals(col.valueAt(i), v);
                        }
                    }
                    // 区间解码与整列一致
                    if (size > 2) {
                        Column range = ChunkCodec.decodeColumnRange(encoded, 1, 1, size - 1, ZSTD);
                        for (int i = 1; i < size - 1; i++) {
                            if (col.isNull(i)) {
                                assertNull(range.valueAt(i - 1));
                            } else {
                                assertEquals(col.valueAt(i), range.valueAt(i - 1));
                            }
                        }
                    }
                }
            }
        }
    }

    /** 全 null 列：无有效值编码，解码还原全 null。 */
    @Test
    void allNullColumnDecodesToAllNull() throws Exception {
        for (ColumnType type : new ColumnType[]{ColumnType.INT, ColumnType.LONG, ColumnType.DOUBLE, ColumnType.STRING}) {
            int size = 100;
            long[] bitmap = new long[(size + 63) / 64];
            java.util.Arrays.fill(bitmap, -1L);
            Column allNull = switch (type) {
                case INT -> Column.ofInts(new int[size], bitmap);
                case LONG -> Column.ofLongs(new long[size], bitmap);
                case DOUBLE -> Column.ofDoubles(new double[size], bitmap);
                default -> Column.ofStrings(new String[size], bitmap);
            };
            Chunk chunk = new Chunk(1_700_000_000_000L, 1, List.of(Column.ofInts(range(size)), allNull));
            byte[] encoded = ChunkCodec.encode(chunk, ZSTD);
            Chunk decoded = ChunkCodec.decode(encoded, ZSTD);
            Column out = decoded.column(1);
            for (int i = 0; i < size; i++) {
                assertNull(out.valueAt(i), "type=" + type + " row=" + i);
            }
        }
    }

    /** 非 8 对齐行数（位图边界）属性。 */
    @Test
    void bitmapBoundarySizes() throws Exception {
        for (int size : new int[]{1, 7, 8, 9, 63, 64, 65, 127, 128, 129}) {
            Random rnd = new Random(SEED + size);
            Column col = randomColumn(rnd, ColumnType.DOUBLE, size, 0.5);
            Chunk chunk = new Chunk(1_700_000_000_000L, 1, List.of(Column.ofInts(range(size)), col));
            Chunk decoded = ChunkCodec.decode(ChunkCodec.encode(chunk, ZSTD), ZSTD);
            assertColumnsEqual(col, decoded.column(1), size, "size=" + size);
        }
    }

    private static Column randomColumn(Random rnd, ColumnType type, int size, double nullDensity) {
        long[] bitmap = nullDensity > 0 ? new long[(size + 63) / 64] : null;
        if (bitmap != null) {
            for (int i = 0; i < size; i++) {
                if (rnd.nextDouble() < nullDensity) {
                    bitmap[i >>> 6] |= 1L << (i & 63);
                }
            }
        }
        return switch (type) {
            case INT -> {
                int[] v = new int[size];
                for (int i = 0; i < size; i++) {
                    v[i] = rnd.nextInt(1_000_000) - 500_000;
                }
                yield bitmap == null ? Column.ofInts(v) : Column.ofInts(v, bitmap);
            }
            case LONG -> {
                long[] v = new long[size];
                for (int i = 0; i < size; i++) {
                    v[i] = rnd.nextLong();
                }
                yield bitmap == null ? Column.ofLongs(v) : Column.ofLongs(v, bitmap);
            }
            case DOUBLE -> {
                double[] v = new double[size];
                for (int i = 0; i < size; i++) {
                    v[i] = 100.0 + rnd.nextDouble() * 1000;
                }
                yield bitmap == null ? Column.ofDoubles(v) : Column.ofDoubles(v, bitmap);
            }
            default -> {
                String[] v = new String[size];
                for (int i = 0; i < size; i++) {
                    v[i] = "key-" + rnd.nextInt(size * 3);
                }
                yield bitmap == null ? Column.ofStrings(v) : Column.ofStrings(v, bitmap);
            }
        };
    }

    private static void assertColumnsEqual(Column expected, Column actual, int size, String ctx) {
        assertEquals(size, actual.rowCount(), ctx);
        for (int i = 0; i < size; i++) {
            if (expected.isNull(i)) {
                assertNull(actual.valueAt(i), ctx + " row=" + i);
            } else {
                Object e = expected.valueAt(i);
                Object a = actual.valueAt(i);
                if (e instanceof Double d) {
                    assertEquals(d, (Double) a, 1e-9, ctx + " row=" + i);
                } else {
                    assertEquals(e, a, ctx + " row=" + i);
                }
            }
        }
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }
}
