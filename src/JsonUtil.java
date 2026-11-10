import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：零第三方依赖。
 * 支持对象、数组、字符串、数字、布尔值、null，以及 pretty 缩进输出。
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    // ---------- 序列化 ----------

    public static String stringify(Object value) {
        return stringify(value, false);
    }

    public static String stringify(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, pretty ? 0 : -1);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            writeNumber((Number) value, sb);
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value, sb, indent);
        } else if (value instanceof List) {
            writeList((List<?>) value, sb, indent);
        } else {
            writeString(String.valueOf(value), sb);
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb, int indent) {
        sb.append('{');
        if (!map.isEmpty()) {
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newlineIndent(sb, childIndent(indent));
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                if (indent >= 0) sb.append(' ');
                write(e.getValue(), sb, childIndent(indent));
            }
            newlineIndent(sb, indent);
        }
        sb.append('}');
    }

    private static void writeList(List<?> list, StringBuilder sb, int indent) {
        sb.append('[');
        if (!list.isEmpty()) {
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                newlineIndent(sb, childIndent(indent));
                write(item, sb, childIndent(indent));
            }
            newlineIndent(sb, indent);
        }
        sb.append(']');
    }

    private static int childIndent(int indent) {
        return indent >= 0 ? indent + 1 : -1;
    }

    private static void newlineIndent(StringBuilder sb, int indent) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    private static void writeNumber(Number value, StringBuilder sb) {
        if (value instanceof Float || value instanceof Double) {
            double d = value.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else {
            sb.append(value);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------- 解析 ----------

    public static Object parse(String json) {
        Parser p = new Parser(json);
        Object result = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("JSON 存在多余内容");
        }
        return result;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) throw new IllegalArgumentException("JSON 意外结束");
            char c = s.charAt(i);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        void expect(String word) {
            if (i + word.length() > s.length() || !s.startsWith(word, i)) {
                throw new IllegalArgumentException("JSON 语法错误");
            }
            i += word.length();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // 跳过 {
            skipWhitespace();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (atEnd() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("JSON 对象缺少冒号");
                }
                i++;
                map.put(key, parseValue());
                skipWhitespace();
                if (atEnd()) throw new IllegalArgumentException("JSON 对象未闭合");
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return map;
                }
                throw new IllegalArgumentException("JSON 对象语法错误");
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // 跳过 [
            skipWhitespace();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) throw new IllegalArgumentException("JSON 数组未闭合");
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return list;
                }
                throw new IllegalArgumentException("JSON 数组语法错误");
            }
        }

        String parseString() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("JSON 字符串缺失");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("非法 Unicode 转义");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("非法转义字符");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }

        Object parseNumber() {
            int start = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalArgumentException("JSON 数字非法");
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }
    }
}
