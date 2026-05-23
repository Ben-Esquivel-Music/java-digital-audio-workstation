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
        TinyJsonParser p = new TinyJsonParser(json);
        try {
            p.skipWs();
            Object root = p.parseValue();
            if (!(root instanceof Map<?, ?> m)) return null;
            String name = stringField(m, "name");
            if (name == null) return null;
            Map<String, PanelState> panelStates = parsePanelStates(m.get("panelStates"));
            List<String> openDialogs = parseStringArray(m.get("openDialogs"));
            Map<String, Rectangle2D> panelBounds = parsePanelBounds(m.get("panelBounds"));
            // dockLayout is forward-compatible: workspaces written before
            // the dock manager existed simply omit it.
            String dockLayoutJson = stringField(m, "dockLayout");
            if (dockLayoutJson == null) dockLayoutJson = "";
            return new Workspace(name, panelStates, openDialogs, panelBounds, dockLayoutJson);
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
}
