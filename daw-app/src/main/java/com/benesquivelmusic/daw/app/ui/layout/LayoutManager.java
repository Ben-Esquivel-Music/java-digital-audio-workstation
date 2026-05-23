package com.benesquivelmusic.daw.app.ui.layout;

import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Headless-testable façade for the Mission Control named-layout system
 * (UI Design Book §4 Concept D, story 282).
 *
 * <p>The manager exposes:</p>
 * <ul>
 *   <li>{@link #savedLayouts()} — an {@link ObservableList} of every
 *       layout (the five built-ins followed by user-saved layouts in
 *       insertion order). FX consumers can bind directly to this list to
 *       render radio items in the View → Layout menu.</li>
 *   <li>{@link #saveCurrent(String)}, {@link #load(String)},
 *       {@link #delete(String)}, {@link #rename(String, String)} — the
 *       CRUD entry points from the story.</li>
 *   <li>{@link #currentLayoutProperty()} — a read-only string property
 *       tracking the name of the layout most-recently loaded or saved.
 *       Bound by the View → Layout menu so the "current layout is
 *       checked" requirement is data-driven (story 282 §"List of saved
 *       layouts — radio-style").</li>
 *   <li>{@link #toJson()} / {@link #fromJson(String)} — opaque
 *       per-project persistence hooks consumed by the project-file
 *       writer (coordinates with story 188's schema migration; the
 *       actual write happens off the FX thread via
 *       {@code javafx.concurrent.Task}, per Skill §11).</li>
 * </ul>
 *
 * <p>The manager <strong>delegates every dock interaction</strong> to a
 * {@link Host} callback rather than depending on {@code DockManager}
 * directly — this keeps it usable in unit tests without a JavaFX screen
 * (the {@code Host} can be a recording stub) and avoids coupling the
 * layout layer to whichever dock framework story 195 picked.</p>
 *
 * <h2>Built-in layouts</h2>
 * The five layouts in {@link BuiltInLayouts} are seeded into
 * {@link #savedLayouts()} at construction and are <strong>read-only</strong>:
 * {@link #delete(String)} and {@link #rename(String, String)} return
 * {@code false} for built-in names. {@link #saveCurrent(String)}
 * similarly refuses to overwrite a built-in. This matches the story's
 * "Built-in layouts (read-only)" acceptance criterion.
 *
 * <h2>Threading</h2>
 * Methods on this class are not thread-safe. All mutation is expected to
 * happen on the JavaFX application thread.
 */
public final class LayoutManager {

    private static final Logger LOG = Logger.getLogger(LayoutManager.class.getName());

    /**
     * Bridge between the manager and the live dock framework. Keeping
     * the surface this small means {@code LayoutManager} can be tested
     * with a fake host that just stores and returns JSON.
     */
    public interface Host {
        /**
         * Returns the current dock state serialised as JSON in the
         * format produced by {@code DockLayoutJson} (story 195).
         */
        String captureDockLayoutJson();

        /**
         * Applies a previously-captured dock-layout JSON to the live
         * dock framework. Unknown panel ids are dropped by the dock
         * framework; this method never throws.
         */
        void applyDockLayoutJson(String json);
    }

    private final Host host;
    private final ObservableList<NamedLayout> savedLayouts =
            FXCollections.observableArrayList();
    private final ObservableList<NamedLayout> savedLayoutsView =
            FXCollections.unmodifiableObservableList(savedLayouts);
    private final ReadOnlyStringWrapper currentLayout =
            new ReadOnlyStringWrapper(this, "currentLayout", BuiltInLayouts.DEFAULT);

    /** Creates a manager bound to the given dock-bridge host. */
    public LayoutManager(Host host) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        savedLayouts.addAll(BuiltInLayouts.all());
    }

    // ── Read-only views ─────────────────────────────────────────────────────

    /**
     * Live, observable, <strong>unmodifiable</strong> view of every layout
     * known to the manager. The first five entries are always the
     * built-ins (in {@link BuiltInLayouts#NAMES} order); user-saved
     * layouts follow in insertion order. All mutations must go through
     * {@link #saveCurrent(String)}, {@link #load(String)},
     * {@link #delete(String)}, or {@link #rename(String, String)}.
     */
    public ObservableList<NamedLayout> savedLayouts() {
        return savedLayoutsView;
    }

    /** Returns the layout with the given name, or empty. */
    public Optional<NamedLayout> findByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String stripped = name.strip();
        for (NamedLayout l : savedLayouts) {
            if (l.name().equals(stripped)) return Optional.of(l);
        }
        return Optional.empty();
    }

    /**
     * Property tracking the name of the currently-active layout — the
     * most-recently loaded or saved layout. Bound by the View → Layout
     * menu to drive radio-item check marks.
     */
    public ReadOnlyStringProperty currentLayoutProperty() {
        return currentLayout.getReadOnlyProperty();
    }

    /** Returns the name of the currently-active layout. */
    public String currentLayout() {
        return currentLayout.get();
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    /**
     * Captures the current dock state and saves it under {@code name}.
     * If a user-saved layout with the same name already exists, it is
     * replaced in place (preserving its position in {@link #savedLayouts()}).
     * Refuses to overwrite a built-in: returns the existing built-in
     * unchanged in that case.
     *
     * @param name user-supplied layout name; non-blank
     * @return the saved layout (or the unchanged built-in if {@code name}
     *         collides with a built-in)
     */
    public NamedLayout saveCurrent(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (BuiltInLayouts.isBuiltIn(trimmed)) {
            LOG.fine(() -> "Refusing to overwrite built-in layout '" + trimmed + "'");
            NamedLayout builtIn = BuiltInLayouts.byName(trimmed);
            return builtIn != null ? builtIn : findByName(trimmed).orElseThrow();
        }
        String json = host.captureDockLayoutJson();
        NamedLayout snapshot = NamedLayout.user(trimmed, json == null ? "" : json);
        int idx = indexOf(trimmed);
        if (idx >= 0) {
            savedLayouts.set(idx, snapshot);
        } else {
            savedLayouts.add(snapshot);
        }
        currentLayout.set(trimmed);
        LOG.fine(() -> "Saved layout '" + trimmed + "'");
        return snapshot;
    }

    /**
     * Loads the named layout, applying it to the dock framework via the
     * {@link Host}. Returns {@code true} if the layout was found and
     * applied, {@code false} if no layout with that name exists.
     */
    public boolean load(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String stripped = name.strip();
        Optional<NamedLayout> hit = findByName(stripped);
        if (hit.isEmpty()) return false;
        host.applyDockLayoutJson(hit.get().dockLayoutJson());
        currentLayout.set(hit.get().name());
        LOG.fine(() -> "Loaded layout '" + stripped + "'");
        return true;
    }

    /**
     * Deletes the user-saved layout with the given name. Built-in
     * layouts cannot be deleted: returns {@code false} for built-in
     * names. Returns {@code false} if no user-saved layout with that
     * name exists.
     */
    public boolean delete(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String stripped = name.strip();
        if (BuiltInLayouts.isBuiltIn(stripped)) return false;
        int idx = indexOf(stripped);
        if (idx < 0) return false;
        savedLayouts.remove(idx);
        if (stripped.equals(currentLayout.get())) {
            load(BuiltInLayouts.DEFAULT);
        }
        return true;
    }

    /**
     * Renames a user-saved layout. Built-in layouts cannot be renamed.
     * Returns {@code false} if the old name is not found, is a
     * built-in, or the new name is blank / collides with an existing
     * layout.
     */
    public boolean rename(String oldName, String newName) {
        Objects.requireNonNull(oldName, "oldName must not be null");
        Objects.requireNonNull(newName, "newName must not be null");
        String strippedOld = oldName.strip();
        String trimmed = newName.strip();
        if (trimmed.isEmpty()) return false;
        if (BuiltInLayouts.isBuiltIn(strippedOld)) return false;
        if (BuiltInLayouts.isBuiltIn(trimmed)) return false;
        if (!strippedOld.equals(trimmed) && indexOf(trimmed) >= 0) return false;
        int idx = indexOf(strippedOld);
        if (idx < 0) return false;
        NamedLayout renamed = savedLayouts.get(idx).withName(trimmed);
        savedLayouts.set(idx, renamed);
        if (strippedOld.equals(currentLayout.get())) {
            currentLayout.set(trimmed);
        }
        return true;
    }

    // ── Per-project persistence ─────────────────────────────────────────────

    /**
     * Serialises only the <em>user-saved</em> layouts to JSON, suitable
     * for embedding in the project file. Built-in layouts are
     * deliberately not serialised — they are reconstructed from
     * {@link BuiltInLayouts} on load so updates to built-in definitions
     * always reach existing projects.
     *
     * <p>The format is the same hand-rolled tolerant JSON used by
     * {@code DockLayoutJson} and {@code WorkspaceJson} so the project
     * writer can embed it as a string field without bringing in a JSON
     * dependency.</p>
     *
     * <p>Returns {@code {"current":"Default","layouts":[]}} for a fresh
     * project with no user-saved layouts.</p>
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"current\":\"").append(escape(currentLayout.get())).append('"');
        sb.append(",\"layouts\":[");
        boolean first = true;
        for (NamedLayout l : savedLayouts) {
            if (l.builtIn()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escape(l.name())).append('"');
            sb.append(",\"dock\":\"").append(escape(l.dockLayoutJson())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Replaces the manager's user-saved layouts with those parsed from
     * {@code json} and <strong>applies the resolved current layout</strong>
     * to the dock host via {@link #load(String)}. Built-in layouts are
     * <strong>not</strong> touched: they are always present in
     * {@link #savedLayouts()} regardless of what's in the project file.
     * The {@link #currentLayoutProperty()} is updated to the
     * {@code current} field from the JSON if it matches a known
     * (built-in or user-saved) layout, otherwise falls back to
     * {@link BuiltInLayouts#DEFAULT}.
     *
     * <p>Tolerant parser: {@code null}, blank, or malformed JSON resets
     * the user-saved layouts to empty, applies the "Default" layout,
     * and never throws.</p>
     */
    public void fromJson(String json) {
        // Reset user-saved layouts first; built-ins remain.
        savedLayouts.removeIf(l -> !l.builtIn());
        if (json == null || json.isBlank()) {
            load(BuiltInLayouts.DEFAULT);
            return;
        }
        try {
            Map<String, Object> parsed = MiniJson.parseObject(json);
            Object current = parsed.get("current");
            Object layouts = parsed.get("layouts");
            if (layouts instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object nameObj = m.get("name");
                    Object dockObj = m.get("dock");
                    if (!(nameObj instanceof String rawName) || rawName.isBlank()) continue;
                    String name = rawName.strip();
                    if (name.isEmpty()) continue;
                    if (BuiltInLayouts.isBuiltIn(name)) continue; // never replace built-ins
                    String dock = dockObj instanceof String s ? s : "";
                    int existing = indexOf(name);
                    NamedLayout nl = NamedLayout.user(name, dock);
                    if (existing >= 0) {
                        savedLayouts.set(existing, nl);
                    } else {
                        savedLayouts.add(nl);
                    }
                }
            }
            String wantedCurrent = current instanceof String s ? s.strip() : BuiltInLayouts.DEFAULT;
            String resolved = findByName(wantedCurrent).isPresent()
                    ? wantedCurrent : BuiltInLayouts.DEFAULT;
            load(resolved);
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Failed to parse layout JSON, resetting: " + ex.getMessage());
            savedLayouts.removeIf(l -> !l.builtIn());
            load(BuiltInLayouts.DEFAULT);
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private int indexOf(String name) {
        for (int i = 0; i < savedLayouts.size(); i++) {
            if (savedLayouts.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Tiny hand-rolled JSON parser, mirroring the style used by
     * {@code DockLayoutJson} and {@code WorkspaceJson} so the project
     * stays free of a JSON library dependency at the UI layer.
     */
    private static final class MiniJson {
        private final String src;
        private int i;

        private MiniJson(String src) {
            this.src = src;
            this.i = 0;
        }

        static Map<String, Object> parseObject(String src) {
            Object v = new MiniJson(src).parseValue();
            if (!(v instanceof Map<?, ?> m)) return new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) m;
            return out;
        }

        private Object parseValue() {
            skipWs();
            if (i >= src.length()) throw new IllegalArgumentException("eof");
            char c = src.charAt(i);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNum();
            };
        }

        private Map<String, Object> parseObj() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return out; }
            while (true) {
                skipWs();
                String key = parseStr();
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

        private List<Object> parseArr() {
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

        private String parseStr() {
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
            if (src.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (src.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("bool");
        }

        private Object parseNull() {
            if (src.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("null");
        }

        private Number parseNum() {
            int start = i;
            if (peek() == '-' || peek() == '+') i++;
            boolean isFloat = false;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c >= '0' && c <= '9') { i++; }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true; i++;
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
