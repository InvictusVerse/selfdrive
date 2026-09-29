package com.selfdriving.tools;

import java.util.List;
import java.util.Map;

/** Writes maps, lists, strings, numbers and booleans as compact JSON (one road or building per line). */
final class JsonWriter {

    private JsonWriter() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            string(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(n);
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                if (depth == 0) {
                    sb.append("\n");
                }
                string(sb, e.getKey().toString());
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            if (depth == 0) {
                sb.append('\n');
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object item = list.get(i);
                if (depth == 1 && item instanceof Map) {
                    sb.append('\n');
                }
                write(sb, item, depth + 1);
            }
            sb.append(']');
        } else {
            string(sb, value.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
