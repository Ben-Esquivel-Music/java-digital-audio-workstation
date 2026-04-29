package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled tolerant JSON parser for {@link Workspace} files written by
 * {@link WorkspaceStore}. Returns {@code null} (rather than throwing) for
 * malformed input — callers treat that as "not a workspace" and skip the
 * file.
 *
 * <p>The parser only understands the subset of JSON actually emitted by
 * {@link WorkspaceStore#toJson}: objects, arrays, strings, numbers, and
 * booleans. It deliberately avoids a Jackson dependency to match the
 * dependency-free style used elsewhere in {@code daw-app}.</p>
 */
final class WorkspaceJson {

    private WorkspaceJson() { }

    static Workspace parse(String json) {
        if (json == null) return null;
        Parser p = new Parser(json);
        try {
            p.skipWs();
            Object root = p.parseValue();
            if (!(root instanceof Map<?, ?> m)) return null;
            String name = stringField(m, "name");
            if (name == null) return null;
            Map<String, PanelState> panelStates = parsePanelStates(m.get("panelStates"));
            List<String> openDialogs = parseStringArray(m.get("openDialogs"));
            Map<String, Rectangle2D> panelBounds = parsePanelBounds(m.get("panelBounds"));
            return new Workspace(name, panelStates, openDialogs, panelBounds);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stringField(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PanelState> parsePanelStates(Object obj) {
        Map<String, PanelState> out = new LinkedHashMap<>();
        if (obj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof Map<?, ?> v) {
                    boolean visible = v.get("visible") instanceof Boolean b ? b : true;
                    double zoom = finiteOr(numberOr(v.get("zoom"), 1.0), 1.0);
                    double scrollX = finiteOr(numberOr(v.get("scrollX"), 0.0), 0.0);
                    double scrollY = finiteOr(numberOr(v.get("scrollY"), 0.0), 0.0);
                    if (zoom <= 0) zoom = 1.0;
                    out.put(k, new PanelState(visible, zoom, scrollX, scrollY));
                }
            }
        }
        return out;
    }

    private static Map<String, Rectangle2D> parsePanelBounds(Object obj) {
        Map<String, Rectangle2D> out = new LinkedHashMap<>();
        if (obj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof Map<?, ?> v) {
                    double x = finiteOr(numberOr(v.get("x"), 0.0), 0.0);
                    double y = finiteOr(numberOr(v.get("y"), 0.0), 0.0);
                    double width = Math.max(0, finiteOr(numberOr(v.get("width"), 0.0), 0.0));
                    double height = Math.max(0, finiteOr(numberOr(v.get("height"), 0.0), 0.0));
                    out.put(k, new Rectangle2D(x, y, width, height));
                }
            }
        }
        return out;
    }

    private static List<String> parseStringArray(Object obj) {
        List<String> out = new ArrayList<>();
        if (obj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) out.add(s);
            }
        }
        return out;
    }

    private static double numberOr(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        return fallback;
    }

    /** Returns {@code value} if finite, otherwise {@code fallback}. */
    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    // ── Tiny recursive-descent JSON parser ──────────────────────────────────
    private static final class Parser {
        private final String src;
        private int i;

        Parser(String src) {
            this.src = src;
            this.i = 0;
        }

        void skipWs() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        }

        Object parseValue() {
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

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return out; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return out; }
                throw new IllegalArgumentException("expected , or } at " + i);
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { i++; return out; }
            while (true) {
                Object v = parseValue();
                out.add(v);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return out; }
                throw new IllegalArgumentException("expected , or ] at " + i);
            }
        }

        String parseString() {
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

        Boolean parseBoolean() {
            if (src.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (src.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("expected boolean at " + i);
        }

        Object parseNull() {
            if (src.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("expected null at " + i);
        }

        Number parseNumber() {
            int start = i;
            if (peek() == '-' || peek() == '+') i++;
            boolean isFloat = false;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c >= '0' && c <= '9') { i++; }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    i++;
                }
                else break;
            }
            String tok = src.substring(start, i);
            if (tok.isEmpty()) throw new IllegalArgumentException("empty number at " + start);
            return isFloat ? (Number) Double.valueOf(tok) : (Number) Long.valueOf(tok);
        }

        void expect(char c) {
            skipWs();
            if (i >= src.length() || src.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }

        char peek() {
            skipWs();
            if (i >= src.length()) throw new IllegalArgumentException("unexpected end at " + i);
            return src.charAt(i);
        }
    }
}
