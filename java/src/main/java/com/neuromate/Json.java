package com.neuromate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A tiny JSON reader/writer so the SDK has no dependencies. Objects are Map, arrays List, numbers Long or Double. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing characters at " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    public static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    public static double dbl(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    public static boolean bool(Object o) {
        return o instanceof Boolean && (Boolean) o;
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            case '"':
                return string();
            case 't':
                lit("true");
                return Boolean.TRUE;
            case 'f':
                lit("false");
                return Boolean.FALSE;
            case 'n':
                lit("null");
                return null;
            default: {
                int st = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                String n = s.substring(st, i);
                if (n.isEmpty()) throw new IllegalArgumentException("unexpected '" + c + "' at " + st);
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            }
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
        i++;
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad literal at " + i);
        i += w.length();
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    public static String stringify(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    private static void write(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            b.append('"');
            for (char c : ((String) o).toCharArray()) {
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object x : (Iterable<?>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, x);
            }
            b.append(']');
        } else {
            b.append(o.toString());
        }
    }
}
