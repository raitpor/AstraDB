package com.astradb.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析/序列化工具（JDK-only，无第三方依赖）。
 * <p>
 * 供 AstraDbClient 处理元数据 API（建表/表信息/统计/错误体）的 JSON 请求与响应，
 * 避免引入 Jackson 造成与宿主 Spring Boot 应用（Jackson 3/tools.jackson）的版本冲突。
 */
public final class ClientJson {

    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL }

    /** 通用 JSON 节点。 */
    public static final class Node {
        public final Kind kind;
        final Map<String, Node> object;
        final List<Node> array;
        final String text;
        final double number;
        /** 数字原始文本（asLong 精确解析用）；浮点表示标记（如 1.0/1e3）。 */
        final String rawNumber;
        final boolean floating;
        final boolean bool;

        private Node(Kind kind, Map<String, Node> object, List<Node> array, String text,
                     double number, String rawNumber, boolean floating, boolean bool) {
            this.kind = kind;
            this.object = object;
            this.array = array;
            this.text = text;
            this.number = number;
            this.rawNumber = rawNumber;
            this.floating = floating;
            this.bool = bool;
        }

        public static Node obj(Map<String, Node> m) {
            return new Node(Kind.OBJECT, m, null, null, 0, null, false, false);
        }

        public static Node arr(List<Node> a) {
            return new Node(Kind.ARRAY, null, a, null, 0, null, false, false);
        }

        public static Node str(String s) {
            return new Node(Kind.STRING, null, null, s, 0, null, false, false);
        }

        public static Node num(double d, String raw, boolean floating) {
            return new Node(Kind.NUMBER, null, null, null, d, raw, floating, false);
        }

        public static Node bool(boolean b) {
            return new Node(Kind.BOOL, null, null, null, 0, null, false, b);
        }

        public static final Node NULL = new Node(Kind.NULL, null, null, null, 0, null, false, false);

        public boolean isNull() {
            return kind == Kind.NULL;
        }

        public boolean isNumber() {
            return kind == Kind.NUMBER;
        }

        public boolean isFloatingPoint() {
            return kind == Kind.NUMBER && floating;
        }

        public boolean isString() {
            return kind == Kind.STRING;
        }

        public Node get(String key) {
            return kind == Kind.OBJECT && object != null ? object.get(key) : null;
        }

        public List<Node> asArray() {
            return kind == Kind.ARRAY && array != null ? array : List.of();
        }

        public String asText() {
            return switch (kind) {
                case STRING -> text;
                case NUMBER -> number == Math.floor(number) && Math.abs(number) < 1e15
                        ? String.valueOf((long) number) : String.valueOf(number);
                case BOOL -> String.valueOf(bool);
                case NULL -> null;
                default -> null;
            };
        }

        public long asLong() {
            if (kind != Kind.NUMBER || rawNumber == null) {
                return 0;
            }
            if (!floating) {
                try {
                    return Long.parseLong(rawNumber);
                } catch (NumberFormatException ignored) {
                    // 超 long 范围（如 1e20）回退 double 截断
                }
            }
            return (long) number;
        }

        public double asDouble() {
            return kind == Kind.NUMBER ? number : 0;
        }

        public boolean asBoolean() {
            return kind == Kind.BOOL && bool;
        }
    }

    private ClientJson() {
    }

    // ---- 解析 ----

    public static Node parse(String json) {
        Parser p = new Parser(json);
        Node n = p.parseValue();
        p.skipWs();
        if (p.pos != json.length()) {
            throw new IllegalArgumentException("JSON 解析失败：尾部多余内容 @" + p.pos);
        }
        return n;
    }

    private static final class Parser {
        private final String s;
        private int pos;
        private String lastRawNumber;
        private boolean lastFloating;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Node parseValue() {
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("JSON 解析失败：意外结束");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> Node.obj(parseObject());
                case '[' -> Node.arr(parseArray());
                case '"' -> Node.str(parseString());
                case 't' -> {
                    expect("true");
                    yield Node.bool(true);
                }
                case 'f' -> {
                    expect("false");
                    yield Node.bool(false);
                }
                case 'n' -> {
                    expect("null");
                    yield Node.NULL;
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        double d = parseNumber();
                        yield Node.num(d, lastRawNumber, lastFloating);
                    }
                    throw new IllegalArgumentException("JSON 解析失败：非法字符 '" + c + "' @" + pos);
                }
            };
        }

        private Map<String, Node> parseObject() {
            pos++; // {
            Map<String, Node> m = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("JSON 解析失败：期望键名 @" + pos);
                }
                String key = parseString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("JSON 解析失败：期望 ':' @" + pos);
                }
                pos++;
                m.put(key, parseValue());
                skipWs();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("JSON 解析失败：对象未闭合");
                }
                char c = s.charAt(pos);
                pos++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 解析失败：期望 ',' 或 '}' @" + (pos - 1));
                }
            }
        }

        private List<Node> parseArray() {
            pos++; // [
            List<Node> a = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return a;
            }
            while (true) {
                a.add(parseValue());
                skipWs();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("JSON 解析失败：数组未闭合");
                }
                char c = s.charAt(pos);
                pos++;
                if (c == ']') {
                    return a;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 解析失败：期望 ',' 或 ']' @" + (pos - 1));
                }
            }
        }

        private String parseString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        break;
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("JSON 解析失败：\\u 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("JSON 解析失败：非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 解析失败：字符串未闭合");
        }

        private double parseNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            boolean floating = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                floating = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            lastRawNumber = s.substring(start, pos);
            lastFloating = floating;
            return Double.parseDouble(lastRawNumber);
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("JSON 解析失败：期望 " + lit + " @" + pos);
            }
            pos += lit.length();
        }
    }

    // ---- 序列化 ----

    /** JSON 字符串转义（含控制字符与引号/反斜杠）。 */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
