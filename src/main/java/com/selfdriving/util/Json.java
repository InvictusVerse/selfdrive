package com.selfdriving.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader (RFC 8259). Objects become {@code Map<String, Object>}, arrays
 * {@code List<Object>}, numbers {@code Double}, plus {@code String}, {@code Boolean} and null.
 *
 * <p>Used for glTF model files and map data, so the app needs no JSON library.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a complete JSON document. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("Unexpected text after the end of the document");
        }
        return value;
    }

    // ---- typed access helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    public static Map<String, Object> object(Map<String, Object> parent, String key) {
        return object(parent.get(key));
    }

    public static List<Object> array(Map<String, Object> parent, String key) {
        return array(parent.get(key));
    }

    public static double number(Map<String, Object> parent, String key, double fallback) {
        Object v = parent.get(key);
        return v instanceof Double d ? d : fallback;
    }

    public static int integer(Map<String, Object> parent, String key, int fallback) {
        Object v = parent.get(key);
        return v instanceof Double d ? (int) Math.round(d) : fallback;
    }

    public static String string(Map<String, Object> parent, String key, String fallback) {
        Object v = parent.get(key);
        return v instanceof String s ? s : fallback;
    }

    /** A numeric array as doubles, or the fallback if missing. */
    public static double[] numbers(Map<String, Object> parent, String key, double[] fallback) {
        Object v = parent.get(key);
        if (!(v instanceof List<?> list)) {
            return fallback;
        }
        double[] result = new double[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (Double) list.get(i);
        }
        return result;
    }

    // ---- parser ---------------------------------------------------------------------------

    private Object value() {
        if (pos >= text.length()) {
            throw error("Unexpected end of document");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a property name");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = null;
        int start = pos;
        while (true) {
            if (pos >= text.length()) {
                throw error("Unterminated string");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                String result = sb == null ? text.substring(start, pos) : sb.append(text, start, pos).toString();
                pos++;
                return result;
            }
            if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(text, start, pos);
                pos++;
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("Bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("Bad escape");
                }
                start = pos;
            } else {
                pos++;
            }
        }
    }

    private Double number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("Unexpected character '" + text.charAt(pos) + "'");
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("Bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("Unexpected text");
        }
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("Unexpected end of document");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("Expected '" + c + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at character " + pos);
    }
}
