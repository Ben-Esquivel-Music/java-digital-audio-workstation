package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled tolerant JSON serialiser/parser for {@link DockLayout},
 * mirroring the dependency-free style used by {@code WorkspaceJson} and
 * {@code WorkspaceStore}.
 *
 * <p>Format:</p>
 * <pre>{@code
 * {
 *   "entries": [
 *     {"id":"arrangement","zone":"CENTER","tabIndex":0,"visible":true},
 *     {"id":"mixer","zone":"FLOATING","visible":true,
 *      "bounds":{"x":120,"y":80,"width":900,"height":480}}
 *   ]
 * }
 * }</pre>
 *
 * <p>Unknown fields and zones are tolerated — unknown zone names fall
 * back to {@link DockZone#CENTER} so future zones can land safely on
 * older clients.</p>
 */
public final class DockLayoutJson {

    private DockLayoutJson() { }

    /** Serialises the given layout to a compact JSON string. */
    public static String toJson(DockLayout layout) {
        if (layout == null || layout.entries().isEmpty()) {
            return "{\"entries\":[]}";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"entries\":[");
        boolean first = true;
        for (DockEntry e : layout.entries().values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(escape(e.panelId())).append('"');
            sb.append(",\"zone\":\"").append(e.zone().name()).append('"');
            if (e.zone() != DockZone.FLOATING) {
                sb.append(",\"tabIndex\":").append(e.tabIndex());
            }
            sb.append(",\"visible\":").append(e.visible());
            Rectangle2D b = e.floatingBounds();
            if (b != null) {
                sb.append(",\"bounds\":{")
                        .append("\"x\":").append(num(b.x()))
                        .append(",\"y\":").append(num(b.y()))
                        .append(",\"width\":").append(num(b.width()))
                        .append(",\"height\":").append(num(b.height()))
                        .append('}');
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Parses {@code json} into a {@link DockLayout}. Returns
     * {@link DockLayout#empty()} for {@code null}, blank input, or
     * malformed JSON — never throws, so callers can treat persistence
     * corruption as "no layout".
     */
    public static DockLayout parse(String json) {
        if (json == null || json.isBlank()) return DockLayout.empty();
        try {
            Object root = new TinyJson(json).parseValue();
            if (!(root instanceof Map<?, ?> m)) return DockLayout.empty();
            Object arr = m.get("entries");
            if (!(arr instanceof List<?> list)) return DockLayout.empty();
            Map<String, DockEntry> entries = new LinkedHashMap<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> em)) continue;
                Object idObj = em.get("id");
                if (!(idObj instanceof String id) || id.isBlank()) continue;
                DockZone zone = DockZone.parseOr(stringOf(em.get("zone")), DockZone.CENTER);
                int tabIndex = Math.max(0, intOf(em.get("tabIndex"), 0));
                boolean visible = em.get("visible") instanceof Boolean b ? b : true;
                Rectangle2D bounds = null;
                if (em.get("bounds") instanceof Map<?, ?> bm) {
                    double x = doubleOf(bm.get("x"), 0);
                    double y = doubleOf(bm.get("y"), 0);
                    double w = Math.max(0, doubleOf(bm.get("width"), 0));
                    double h = Math.max(0, doubleOf(bm.get("height"), 0));
                    bounds = new Rectangle2D(x, y, w, h);
                }
                if (zone == DockZone.FLOATING && bounds == null) {
                    // FLOATING entries require bounds — fall back to CENTER if missing.
                    zone = DockZone.CENTER;
                }
                entries.put(id, new DockEntry(id, zone, tabIndex, visible, bounds));
            }
            return DockLayout.of(entries);
        } catch (RuntimeException ex) {
            return DockLayout.empty();
        }
    }

    // ── tiny helpers ────────────────────────────────────────────────────────
    private static String stringOf(Object o) {
        return o instanceof String s ? s : null;
    }

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static double doubleOf(Object o, double fallback) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        return fallback;
    }

    private static String num(double d) {
        if (!Double.isFinite(d)) return "0";
        if (d == Math.floor(d) && Math.abs(d) < (double) Long.MAX_VALUE) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Minimal recursive-descent JSON parser, intentionally a near-copy
     * of the one in {@code WorkspaceJson} so the two stay aligned.
     */
    private static final class TinyJson {
        private final String src;
        private int i;

        TinyJson(String src) {
            this.src = src;
            this.i = 0;
        }

        Object parseValue() {
            skipWs();
            if (i >= src.length()) throw new IllegalArgumentException("eof");
            char c = src.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
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
                throw new IllegalArgumentException(", or }");
            }
        }

        private List<Object> parseArray() {
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
                throw new IllegalArgumentException(", or ]");
            }
        }

        private String parseString() {
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
            throw new IllegalArgumentException("unterminated");
        }

        private Boolean parseBool() {
            if (src.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (src.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("bool");
        }

        private Object parseNull() {
            if (src.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("null");
        }

        private Number parseNumber() {
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
            if (tok.isEmpty()) throw new IllegalArgumentException("number");
            return isFloat ? (Number) Double.valueOf(tok) : (Number) Long.valueOf(tok);
        }

        private void expect(char c) {
            skipWs();
            if (i >= src.length() || src.charAt(i) != c) {
                throw new IllegalArgumentException("expected " + c);
            }
            i++;
        }

        private char peek() {
            skipWs();
            if (i >= src.length()) throw new IllegalArgumentException("eof");
            return src.charAt(i);
        }

        private void skipWs() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        }
    }
}
