package com.benesquivelmusic.daw.app.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON parser shared by {@code DockLayoutJson},
 * {@code WorkspaceJson}, and {@code LayoutManager} so the project stays
 * free of a JSON library dependency at the UI layer.
 *
 * <p>Understands the subset of JSON actually produced by the DAW's own
 * serialisers: objects, arrays, strings, numbers, booleans, and null.
 * Unknown tokens throw {@link IllegalArgumentException}.</p>
 */
public final class TinyJsonParser {

    private final String src;
    private int i;

    /**
     * Creates a parser for the given JSON source string.
     *
     * @param src the JSON text to parse (must not be {@code null})
     */
    public TinyJsonParser(String src) {
        this.src = src;
        this.i = 0;
    }

    /**
     * Convenience method: parses the given string as a JSON object and
     * returns the resulting map.  Returns an empty map for non-object
     * root values.
     */
    public static Map<String, Object> parseObjectString(String src) {
        Object v = new TinyJsonParser(src).parseValue();
        if (!(v instanceof Map<?, ?> m)) return new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    /** Parses the next JSON value from the source. */
    public Object parseValue() {
        skipWs();
        if (i >= src.length()) throw new IllegalArgumentException("unexpected end");
        char c = src.charAt(i);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    /** Parses a JSON object. */
    public Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { i++; return out; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            out.put(key, parseValue());
            skipWs();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return out; }
            throw new IllegalArgumentException("expected , or } at " + i);
        }
    }

    /** Parses a JSON array. */
    public List<Object> parseArray() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWs();
        if (peek() == ']') { i++; return out; }
        while (true) {
            out.add(parseValue());
            skipWs();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return out; }
            throw new IllegalArgumentException("expected , or ] at " + i);
        }
    }

    /** Parses a JSON string (opening quote must be the current character). */
    public String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < src.length()) {
            char c = src.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\' && i < src.length()) {
                char n = src.charAt(i++);
                sb.append(switch (n) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    /** Parses a JSON boolean literal. */
    public Boolean parseBoolean() {
        if (src.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (src.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw new IllegalArgumentException("expected boolean at " + i);
    }

    /** Parses a JSON null literal. */
    public Object parseNull() {
        if (src.startsWith("null", i)) { i += 4; return null; }
        throw new IllegalArgumentException("expected null at " + i);
    }

    /** Parses a JSON number (integer or floating point). */
    public Number parseNumber() {
        int start = i;
        if (peek() == '-' || peek() == '+') i++;
        boolean isFloat = false;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c >= '0' && c <= '9') { i++; }
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isFloat = true;
                i++;
            } else break;
        }
        String tok = src.substring(start, i);
        if (tok.isEmpty()) throw new IllegalArgumentException("empty number at " + start);
        return isFloat ? (Number) Double.valueOf(tok) : (Number) Long.valueOf(tok);
    }

    /** Skips whitespace in the source. */
    public void skipWs() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
    }

    private void expect(char c) {
        skipWs();
        if (i >= src.length() || src.charAt(i) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + i);
        }
        i++;
    }

    private char peek() {
        skipWs();
        if (i >= src.length()) throw new IllegalArgumentException("unexpected end at " + i);
        return src.charAt(i);
    }
}
