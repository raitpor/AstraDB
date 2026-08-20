package com.astradb.client;

import com.astradb.client.ClientJson.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小 JSON 工具测试：解析（对象/数组/嵌套/转义/数值/bool/null）与序列化转义。
 */
class ClientJsonTest {

    @Test
    void parseNestedObjectAndArray() {
        Node n = ClientJson.parse("""
                {"name":"t1","schema":{"columns":[{"name":"id","type":"INT","nullable":false},
                {"name":"v","type":"DOUBLE","nullable":true}]},"count":42,"ok":true,"x":null}
                """);
        assertEquals("t1", n.get("name").asText());
        assertEquals(42L, n.get("count").asLong());
        assertTrue(n.get("ok").asBoolean());
        assertTrue(n.get("x").isNull());
        List<Node> cols = n.get("schema").get("columns").asArray();
        assertEquals(2, cols.size());
        assertEquals("id", cols.get(0).get("name").asText());
        assertEquals("INT", cols.get(0).get("type").asText());
        assertFalse(cols.get(0).get("nullable").asBoolean());
        assertTrue(cols.get(1).get("nullable").asBoolean());
    }

    @Test
    void parseEscapesAndUnicode() {
        Node n = ClientJson.parse("{\"s\":\"中文，\\\"引号\\\"\\\\反斜杠\\n换行\\u4e2d\"}");
        assertEquals("中文，\"引号\"\\反斜杠\n换行中", n.get("s").asText());
    }

    @Test
    void parseNumbersAndEmptyStructures() {
        Node n = ClientJson.parse("{\"i\":-17,\"d\":1.5e3,\"arr\":[],\"obj\":{}}");
        assertEquals(-17L, n.get("i").asLong());
        assertEquals(1500.0, n.get("d").asDouble(), 1e-9);
        assertTrue(n.get("arr").asArray().isEmpty());
        assertNull(n.get("obj").get("missing"));
    }

    @Test
    void parseErrors() {
        assertThrows(IllegalArgumentException.class, () -> ClientJson.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> ClientJson.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> ClientJson.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> ClientJson.parse("{\"a\":1} extra"));
    }

    @Test
    void numberSemantics() {
        // 浮点整值（1.0）保持 Double 语义
        Node n = ClientJson.parse("{\"a\":1.0,\"b\":1,\"c\":9007199254740993,\"d\":1e20}");
        assertTrue(n.get("a").isFloatingPoint());
        assertEquals(1.0, n.get("a").asDouble(), 1e-9);
        assertFalse(n.get("b").isFloatingPoint());
        assertEquals(1L, n.get("b").asLong());
        // LONG 大值 >2^53 精确（rawText 解析）
        assertEquals(9007199254740993L, n.get("c").asLong());
        // 超 long 范围回退 double 截断
        assertEquals((long) 1e20, n.get("d").asLong());
    }

    @Test
    void quoteEscapesAllSpecials() {
        assertEquals("\"a\\\"b\\\\c\"", ClientJson.quote("a\"b\\c"));
        assertEquals("\"\\n\\t\\u0001\"", ClientJson.quote("\n\t\u0001"));
        assertEquals("\"中文\"", ClientJson.quote("中文"));
    }
}
