package com.astradb.client.protocol;

import com.astradb.client.protocol.BinaryProtocol.ColumnDef;
import com.astradb.client.protocol.BinaryProtocol.ColumnType;
import com.astradb.client.protocol.BinaryProtocol.Frame;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 二进制协议属性测试：随机行数据（类型/空值密度/边界值/中文）编码 → 解码后
 * 保持逐值不变量；全 null 列 forcedTypes 覆盖；类型推断与不一致拒绝。
 */
class BinaryProtocolPropertyTest {

    private static final long SEED = 2026L;

    @Test
    void randomRowsRoundtripPreservesValues() throws Exception {
        for (double nullDensity : new double[]{0.0, 0.3, 0.8}) {
            for (int rows : new int[]{1, 17, 1000}) {
                Random rnd = new Random(SEED ^ Double.doubleToLongBits(nullDensity) ^ rows);
                List<List<Object>> data = new ArrayList<>();
                for (int r = 0; r < rows; r++) {
                    data.add(java.util.Arrays.asList(
                            rnd.nextDouble() < nullDensity ? null : rnd.nextInt(1_000_000),
                            rnd.nextDouble() < nullDensity ? null : rnd.nextLong(),
                            rnd.nextDouble() < nullDensity ? null : Math.round(rnd.nextDouble() * 10000) / 100.0,
                            rnd.nextDouble() < nullDensity ? null : ("中文-数据-" + rnd.nextInt(100))));
                }
                Frame f = BinaryProtocol.encodeRows(data, List.of("a", "b", "c", "d"));
                byte[] bytes;
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    BinaryProtocol.encode(f, bos);
                    bytes = bos.toByteArray();
                }
                Frame out = BinaryProtocol.decode(new ByteArrayInputStream(bytes));
                assertEquals(rows, out.rowCount());
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < 4; c++) {
                        Object expected = data.get(r).get(c);
                        Object actual = valueAt(out, c, r);
                        if (expected == null) {
                            assertNull(actual);
                        } else if (expected instanceof Double d) {
                            assertEquals(d, (Double) actual, 1e-9);
                        } else {
                            assertEquals(expected, actual);
                        }
                    }
                }
            }
        }
    }

    @Test
    void boundaryValuesSurvive() throws Exception {
        List<List<Object>> data = List.of(
                java.util.Arrays.asList(Integer.MAX_VALUE, Long.MAX_VALUE, Double.MAX_VALUE, "边界\"引号\",逗号"),
                java.util.Arrays.asList(Integer.MIN_VALUE, Long.MIN_VALUE, -0.0001, "中文\n换行"),
                java.util.Arrays.asList(0, 0L, 0.0, ""));
        Frame f = BinaryProtocol.encodeRows(data, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        BinaryProtocol.encode(f, bos);
        Frame out = BinaryProtocol.decode(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(Integer.MAX_VALUE, valueAt(out, 0, 0));
        assertEquals(Long.MIN_VALUE, valueAt(out, 1, 1));
        assertEquals(Double.MAX_VALUE, valueAt(out, 2, 0));
        assertEquals("边界\"引号\",逗号", valueAt(out, 3, 0));
        assertEquals("中文\n换行", valueAt(out, 3, 1));
        assertEquals("", valueAt(out, 3, 2));
    }

    @Test
    void allNullColumnForcedType() throws Exception {
        List<List<Object>> data = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            data.add(java.util.Arrays.asList(i, null));
        }
        // 全 null 第二列：forcedTypes 按 schema 指定 DOUBLE
        Frame f = BinaryProtocol.encodeRows(data, List.of("id", "v"),
                List.of(ColumnType.INT, ColumnType.DOUBLE));
        assertEquals(ColumnType.DOUBLE, f.columns().get(1).type());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        BinaryProtocol.encode(f, bos);
        Frame out = BinaryProtocol.decode(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(ColumnType.DOUBLE, out.columns().get(1).type());
        for (int i = 0; i < 5; i++) {
            assertNull(valueAt(out, 1, i));
        }
    }

    @Test
    void typeInferenceAndMismatch() throws Exception {
        // Float 提升 DOUBLE
        assertEquals(ColumnType.DOUBLE,
                BinaryProtocol.encodeRows(List.of(java.util.Arrays.asList(1.5f)), null).columns().get(0).type());
        // 列数不一致拒绝
        assertThrows(IllegalArgumentException.class,
                () -> BinaryProtocol.encodeRows(List.of(java.util.Arrays.asList(1, 2), java.util.Arrays.asList(3)), null));
        // 类型不一致拒绝
        assertThrows(IllegalArgumentException.class,
                () -> BinaryProtocol.encodeRows(List.of(java.util.Arrays.asList(1, 2), java.util.Arrays.asList(3, "x")), null));
        // 非法类型 ID 拒绝（解码侧）：完整帧头 + 列定义（type=99）+ 行数
        ByteArrayOutputStream badOut = new ByteArrayOutputStream();
        badOut.write(new byte[]{'A', 'S', 'D', 'B', 1, 0, 0, 1}); // magic+version+flags+columnCount=1
        badOut.write(new byte[]{0});      // 列名（空，len=0）
        badOut.write(99);                 // 非法类型 ID
        badOut.write(0);                  // nullable=0
        badOut.write(1);                  // rowCount=1
        assertThrows(IllegalArgumentException.class,
                () -> BinaryProtocol.decode(new ByteArrayInputStream(badOut.toByteArray())));
    }

    private static Object valueAt(Frame f, int col, int row) {
        ColumnDef def = f.columns().get(col);
        long[] bitmap = f.data().get(col).nullBitmap();
        Object values = f.data().get(col).values();
        if (BinaryProtocol.isNull(bitmap, row)) {
            return null;
        }
        int idx = row - (bitmap == null ? 0 : BinaryProtocol.popcount(bitmap, row));
        return switch (def.type()) {
            case INT -> ((int[]) values)[idx];
            case LONG -> ((long[]) values)[idx];
            case DOUBLE -> ((double[]) values)[idx];
            case STRING -> ((String[]) values)[idx];
        };
    }
}
