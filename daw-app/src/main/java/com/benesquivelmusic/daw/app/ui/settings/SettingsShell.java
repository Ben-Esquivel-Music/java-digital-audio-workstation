package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * The story-306 Rail &amp; Pane settings shell — the Concept A assembly
 * that replaces {@code SettingsDialog}'s {@code TabPane} body (Settings
 * View Design Book §4.A, §7 Stage 2).
 *
 * <p>A plain one-off {@link BorderPane} layout ({@code
 * javafx-application-design} skill §3 — a single-use composite, not a
 * Control/Skin): top = the always-on §5.2 search bar, left = the
 * {@link SettingsNavRail}, center = a fit-to-width {@link ScrollPane}
 * showing the selected category's pane, bottom = the §5.5 footer action
 * bar (Cancel / Apply / OK — the §5.5 status note and §5.6 restart
 * banner are story 307). The shell renders <em>every</em> descriptor of
 * the story-305 {@link SettingsCatalogue} through one generic
 * {@link SettingRow} per descriptor, built once up front.</p>
 *
 * <h2>Pending edits (§6.2 — dirty/apply generalised)</h2>
 *
 * <p>Each row holds a <em>pending</em> value; the shell diffs it against
 * the persisted value from {@link ValueAccess} on every row edit. An
 * entry exists in the pending map iff the two differ
 * ({@code Objects.equals}); {@link #dirtyProperty()} and the owning
 * category's rail {@code •} marker derive reactively. The shell writes
 * nothing itself — {@code SettingsDialog.applySettings()} consumes
 * {@link #pendingEdits()} and then calls {@link #refreshFromPersisted()}.</p>
 *
 * <h2>Search (§6.1, §5.2)</h2>
 *
 * <p>Non-blank query → search mode: the center swaps to a fresh results
 * pane of the {@link SettingsSearchIndex} hits in rank order, grouped
 * under "Category · Group" headers in first-appearance order. The row
 * <em>nodes are reused</em> (moved into the results pane), so pending
 * state never resets; every query change and leaving search restore each
 * category pane's remembered child list first, re-parenting the rows
 * back — only the current results container ever borrows rows. Rail
 * items get per-category match counts and no-match dimming; matched rows
 * get the query as their highlight.</p>
 *
 * <h2>Keyboard (§6.5, rejection #7)</h2>
 *
 * <p>The shell filters exactly one key: Ctrl/Cmd-F →
 * {@link #focusSearch()}. UP/DOWN/ENTER/RIGHT are rail-owned (the rail's
 * activate-pane action focuses the pane's first row editor), Tab
 * traversal is native, and Esc is consumed only by the search field —
 * and only while it has text (an empty-field Esc must reach the dialog's
 * close semantics). Nothing else is globally swallowed.</p>
 *
 * <h2>Key-binding conflict guard</h2>
 *
 * <p>Parity with the old dialog: a KEY_CAPTURE row's new combination that
 * {@code getName()}-equals another key-binding row's current effective
 * value (pending if edited, else the seeded persisted value) is reverted
 * to the row's previous value. The guard lives in the shell's row-value
 * listener behind a reentrancy flag — conflicts stay cross-setting, the
 * rows stay generic.</p>
 *
 * @see SettingsNavRail
 * @see SettingRow
 * @see SettingsSearchIndex
 */
public final class SettingsShell extends BorderPane {

    /** Stable style class — selectable as {@code .settings-shell}. */
    public static final String DEFAULT_STYLE_CLASS = "settings-shell";

    /**
     * Shared bundle for shell chrome strings ({@code Locale.ROOT}, the
     * codebase-wide convention — Skill §14).
     */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    /** Everything the shell needs that the catalogue does not carry. */
    public interface ValueAccess {

        /**
         * @param settingId a catalogue descriptor id
         * @return the currently persisted value for the setting; may be
         *         {@code null} (e.g. an unbound key binding)
         */
        Object currentValue(String settingId);
    }

    private final ValueAccess persisted;
    private final SettingsSearchIndex searchIndex;

    // ── Row / taxonomy indexes (built once, catalogue order) ─────────────────
    private final Map<String, SettingRow> rowsById = new LinkedHashMap<>();
    private final Map<String, String> categoryByRowId = new HashMap<>();
    private final List<SettingRow> keyCaptureRows = new ArrayList<>();
    private final Map<String, VBox> categoryPanes = new LinkedHashMap<>();
    /**
     * Each category pane's permanent child list (title, group headers,
     * rows, trailing node). Search mode MOVES row nodes into the results
     * pane; every query change and leaving search restore these lists,
     * re-parenting the rows back — row nodes are reused so pending state
     * never resets (§6.1).
     */
    private final Map<String, List<Node>> categoryPaneChildren = new HashMap<>();
    private final Map<String, SettingsNavRail.NavItem> navItemsByCategory =
            new LinkedHashMap<>();
    private final Map<String, String> categoryTitleById = new HashMap<>();
    private final Map<String, String> groupTitleByKey = new HashMap<>();

    // ── Pending-edit state (§6.2) ────────────────────────────────────────────
    /** id → pending value; an entry exists iff pending != persisted. */
    private final Map<String, Object> pending = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper dirty =
            new ReadOnlyBooleanWrapper(this, "dirty", false);

    // ── Chrome ───────────────────────────────────────────────────────────────
    private final SettingsNavRail navRail = new SettingsNavRail();
    private final ScrollPane paneScroll = new ScrollPane();
    private final VBox paneHost = new VBox();
    private final TextField searchField = new TextField();

    /** {@code true} while the center shows search results (§6.1). */
    private boolean searchMode;
    /** Reentrancy flag for the key-binding conflict revert. */
    private boolean conflictRevertInFlight;
    /** {@code true} while {@link #refreshFromPersisted()} re-seeds rows. */
    private boolean refreshing;

    private Runnable onApply;
    private Runnable onOk;
    private Runnable onCancel;

    /**
     * Builds the full shell: one row per catalogue descriptor, the rail,
     * the search index and the footer. Cheap and I/O-free — search is
     * pure in-memory string work and no device/disk source is touched
     * (story non-goal).
     *
     * @param catalogue             the story-305 catalogue to render; not null
     * @param persisted             persisted-value access per descriptor id; not null
     * @param hints                 per-descriptor render hints by id; may be
     *                              {@code null}/partial (missing ids fall back
     *                              to {@link SettingRow.ControlHints#none()})
     * @param categoryTrailingNodes extra node appended to a category's pane,
     *                              by category id (e.g. {@code "audio"} → the
     *                              Audio Device Settings… button); may be
     *                              {@code null}
     */
    public SettingsShell(SettingsCatalogue catalogue,
                         ValueAccess persisted,
                         Map<String, SettingRow.ControlHints> hints,
                         Map<String, Node> categoryTrailingNodes) {
        Objects.requireNonNull(catalogue, "catalogue must not be null");
        this.persisted = Objects.requireNonNull(persisted, "persisted must not be null");
        this.searchIndex = SettingsSearchIndex.of(catalogue);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        Map<String, SettingRow.ControlHints> hintsById =
                hints == null ? Map.of() : hints;
        Map<String, Node> trailing =
                categoryTrailingNodes == null ? Map.of() : categoryTrailingNodes;

        buildRows(catalogue, hintsById);
        buildCategoryPanes(catalogue, trailing);
        buildNavRail(catalogue);

        setTop(buildSearchBar());
        setLeft(navRail);
        setCenter(buildCenter());
        setBottom(buildFooter());

        // §6.5 — the shell filters EXACTLY one key: Ctrl/Cmd-F focuses
        // search. Everything else (rail keys, Tab, Esc) is owned closer
        // to its target; nothing is globally swallowed (rejection #7).
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F && event.isShortcutDown()) {
                focusSearch();
                event.consume();
            }
        });

        if (!catalogue.categories().isEmpty()) {
            navRail.setSelectedCategoryId(catalogue.categories().get(0).id());
        }
    }

    // ── Construction ─────────────────────────────────────────────────────────

    /** One {@link SettingRow} per descriptor, seeded from {@link ValueAccess}. */
    private void buildRows(SettingsCatalogue catalogue,
                           Map<String, SettingRow.ControlHints> hintsById) {
        for (SettingsCatalogue.Category category : catalogue.categories()) {
            categoryTitleById.put(category.id(), category.title());
            for (SettingsCatalogue.Group group : category.groups()) {
                groupTitleByKey.put(groupKey(category.id(), group.id()), group.title());
                for (SettingDescriptor<?> descriptor : group.settings()) {
                    String id = descriptor.id();
                    SettingRow row = new SettingRow(descriptor,
                            persisted.currentValue(id), hintsById.get(id));
                    rowsById.put(id, row);
                    categoryByRowId.put(id, category.id());
                    if (descriptor.controlKind() == ControlKind.KEY_CAPTURE) {
                        keyCaptureRows.add(row);
                    }
                    row.valueProperty().addListener(
                            (_, oldValue, newValue) ->
                                    onRowValueChanged(row, oldValue, newValue));
                }
            }
        }
    }

    /**
     * One permanent pane per category: title label (§5.3 — a plain label,
     * NEVER an accent fill, rejection §6), then per non-empty group a
     * header + its rows, then the category's trailing node if present.
     */
    private void buildCategoryPanes(SettingsCatalogue catalogue,
                                    Map<String, Node> trailing) {
        for (SettingsCatalogue.Category category : catalogue.categories()) {
            List<Node> children = new ArrayList<>();
            Label title = new Label(category.title());
            title.getStyleClass().add("settings-category-title");
            children.add(title);
            for (SettingsCatalogue.Group group : category.groups()) {
                if (group.settings().isEmpty()) {
                    continue; // placeholder taxonomy slots (story 308)
                }
                children.add(groupHeader(group.title()));
                for (SettingDescriptor<?> descriptor : group.settings()) {
                    children.add(rowsById.get(descriptor.id()));
                }
            }
            Node trailingNode = trailing.get(category.id());
            if (trailingNode != null) {
                children.add(trailingNode);
            }
            VBox pane = new VBox();
            pane.getStyleClass().add("settings-category-pane");
            pane.getChildren().setAll(children);
            categoryPanes.put(category.id(), pane);
            categoryPaneChildren.put(category.id(), List.copyOf(children));
        }
    }

    /** Rail items in catalogue order; selection swaps the browse pane. */
    private void buildNavRail(SettingsCatalogue catalogue) {
        for (SettingsCatalogue.Category category : catalogue.categories()) {
            SettingsNavRail.NavItem item = new SettingsNavRail.NavItem(
                    category.id(), category.title(), categoryIcon(category.id()));
            navItemsByCategory.put(category.id(), item);
            navRail.getItems().add(item);
        }
        navRail.selectedCategoryIdProperty().addListener((_, _, id) -> {
            if (!searchMode) {
                showCategory(id);
            }
        });
        // §6.5 ENTER/RIGHT — focus the pane's first visible row editor.
        navRail.onActivatePaneProperty().set(this::focusFirstRowEditor);
    }

    /** §5.2 search bar: icon, always-on field, ✕ clear (visible with text). */
    private Node buildSearchBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("settings-search-bar");

        Node searchIcon = DawgIcon.of("search", DawgIcon.Size.SIZE_16);

        searchField.getStyleClass().add("settings-search-field");
        searchField.setPromptText(msg("settings.search.prompt"));
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((_, _, text) -> handleSearchChanged(text));
        // §6.5 Esc: clear + consume ONLY while the field has text; an
        // empty-field Esc must propagate so the dialog's close semantics
        // (dirty prompt included) still work.
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !searchField.getText().isEmpty()) {
                searchField.clear();
                event.consume();
            }
        });

        Button clear = new Button();
        clear.getStyleClass().addAll("dawg-button", "icon-only", "settings-search-clear");
        clear.setGraphic(DawgIcon.of("x", DawgIcon.Size.SIZE_16));
        clear.setAccessibleText(msg("settings.search.clear"));
        clear.visibleProperty().bind(searchField.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        clear.setOnAction(_ -> searchField.clear());

        bar.getChildren().addAll(searchIcon, searchField, clear);
        return bar;
    }

    /** Fit-to-width scroll pane hosting the browse pane or search results. */
    private Node buildCenter() {
        paneHost.getStyleClass().add("settings-pane-host");
        paneScroll.getStyleClass().add("settings-pane");
        paneScroll.setContent(paneHost);
        paneScroll.setFitToWidth(true);
        paneScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return paneScroll;
    }

    /**
     * §5.5 footer: Cancel / Apply / OK only (note + banner are story 307).
     * Apply is enabled exactly while dirty; OK is the default button,
     * Cancel the cancel button.
     */
    private Node buildFooter() {
        HBox footer = new HBox();
        footer.getStyleClass().add("settings-footer");

        Button cancel = new Button(msg("dialog.cancel"));
        cancel.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(_ -> run(onCancel));

        Button apply = new Button(msg("dialog.apply"));
        apply.getStyleClass().addAll("dawg-button", "size-default");
        apply.disableProperty().bind(dirty.getReadOnlyProperty().not());
        apply.setOnAction(_ -> run(onApply));

        Button ok = new Button(msg("dialog.ok"));
        ok.getStyleClass().addAll("dawg-button", "size-default", "primary");
        ok.setDefaultButton(true);
        ok.setOnAction(_ -> run(onOk));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().addAll(spacer, cancel, apply, ok);
        return footer;
    }

    // ── Pending edits (§6.2) ─────────────────────────────────────────────────

    /**
     * The single row-value listener: key-binding conflict guard first
     * (parity with the old dialog's cross-setting check), then pending
     * bookkeeping against the persisted value.
     */
    private void onRowValueChanged(SettingRow row, Object oldValue, Object newValue) {
        if (refreshing) {
            return; // refreshFromPersisted() rebuilds state wholesale below
        }
        if (!conflictRevertInFlight
                && row.descriptor().controlKind() == ControlKind.KEY_CAPTURE
                && newValue instanceof KeyCombination combo
                && conflictsWithOtherBinding(row, combo)) {
            conflictRevertInFlight = true;
            try {
                // Re-enters this listener; the nested call records the
                // reverted (previous) value in the pending bookkeeping.
                row.setValue(oldValue);
            } finally {
                conflictRevertInFlight = false;
            }
            return;
        }
        recordPending(row, newValue);
    }

    /**
     * @return {@code true} if another key-binding row's current effective
     *         value (its row value — pending if edited, else the seeded
     *         persisted binding) has the same {@link KeyCombination#getName()
     *         name}
     */
    private boolean conflictsWithOtherBinding(SettingRow row, KeyCombination combo) {
        for (SettingRow other : keyCaptureRows) {
            if (other != row
                    && other.getValue() instanceof KeyCombination existing
                    && existing.getName().equals(combo.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Diffs a row value against persisted; maintains the pending map.
     * The diff runs on the canonical comparison value — a TEXT row's
     * numeric String parses to the persisted boxed type first, see
     * {@link SettingRow#canonicalComparisonValue} — so typing the
     * persisted tempo back reads as clean (§6.2: an entry exists iff the
     * values actually differ). The map keeps the RAW row value: the apply
     * path still receives exactly what the editor produced.
     */
    private void recordPending(SettingRow row, Object value) {
        String id = row.descriptor().id();
        if (Objects.equals(SettingRow.canonicalComparisonValue(row.descriptor(), value),
                persisted.currentValue(id))) {
            pending.remove(id);
        } else {
            pending.put(id, value);
        }
        updateDirtyMarkers();
    }

    /** Recomputes {@link #dirtyProperty()} and every rail {@code •} marker. */
    private void updateDirtyMarkers() {
        dirty.set(!pending.isEmpty());
        Set<String> dirtyCategories = new HashSet<>();
        for (String id : pending.keySet()) {
            String categoryId = categoryByRowId.get(id);
            if (categoryId != null) {
                dirtyCategories.add(categoryId);
            }
        }
        for (SettingsNavRail.NavItem item : navItemsByCategory.values()) {
            item.dirtyProperty().set(dirtyCategories.contains(item.categoryId()));
        }
    }

    // ── Search (§6.1 / §5.2) ─────────────────────────────────────────────────

    private void handleSearchChanged(String text) {
        if (text == null || text.isBlank()) {
            exitSearchMode();
        } else {
            enterSearchMode(text);
        }
    }

    /**
     * Search mode: a fresh results pane of the index hits in rank order,
     * grouped under "Category · Group" headers in first-appearance order.
     * Matched row nodes are MOVED here (reused, never rebuilt); rail
     * items get counts and no-match dimming.
     */
    private void enterSearchMode(String query) {
        searchMode = true;

        // Re-parent every row home BEFORE building the next results pane:
        // a row that drops out of the match set would otherwise stay
        // parented to the previous (now detached) results container,
        // keeping a growing chain of orphan containers reachable while
        // the user types.
        restoreCategoryPaneChildren();
        for (SettingRow row : rowsById.values()) {
            row.highlightQueryProperty().set("");
        }

        VBox results = new VBox();
        results.getStyleClass().add("settings-search-results");
        Map<String, VBox> sections = new LinkedHashMap<>();
        for (SettingsSearchIndex.Match match : searchIndex.search(query)) {
            SettingRow row = rowsById.get(match.descriptor().id());
            if (row == null) {
                continue;
            }
            row.highlightQueryProperty().set(query);
            String key = groupKey(match.categoryId(), match.groupId());
            VBox section = sections.computeIfAbsent(key, k -> {
                VBox s = new VBox();
                s.getStyleClass().add("settings-search-section");
                s.getChildren().add(groupHeader(
                        categoryTitleById.getOrDefault(match.categoryId(), match.categoryId())
                                + " · "
                                + groupTitleByKey.getOrDefault(k, match.groupId())));
                results.getChildren().add(s);
                return s;
            });
            section.getChildren().add(row); // re-parents the reused node
        }

        Map<String, Integer> counts = searchIndex.matchCountsByCategory(query);
        for (SettingsNavRail.NavItem item : navItemsByCategory.values()) {
            int count = counts.getOrDefault(item.categoryId(), 0);
            item.matchCountProperty().set(count);
            item.dimmedProperty().set(count == 0);
        }

        paneHost.getChildren().setAll(results);
    }

    /**
     * Browse mode: restores every category pane's remembered child list
     * (re-parenting the moved rows back), clears highlights/counts/dims,
     * and shows the selected category again.
     */
    private void exitSearchMode() {
        searchMode = false;
        for (SettingRow row : rowsById.values()) {
            row.highlightQueryProperty().set("");
        }
        for (SettingsNavRail.NavItem item : navItemsByCategory.values()) {
            item.matchCountProperty().set(-1);
            item.dimmedProperty().set(false);
        }
        restoreCategoryPaneChildren();
        showCategory(navRail.getSelectedCategoryId());
    }

    /**
     * Restores every category pane's permanent child list, re-parenting
     * any row a results pane borrowed back to its home pane.
     */
    private void restoreCategoryPaneChildren() {
        for (Map.Entry<String, VBox> entry : categoryPanes.entrySet()) {
            entry.getValue().getChildren()
                    .setAll(categoryPaneChildren.get(entry.getKey()));
        }
    }

    private void showCategory(String categoryId) {
        VBox pane = categoryId == null ? null : categoryPanes.get(categoryId);
        if (pane == null) {
            paneHost.getChildren().clear();
        } else {
            paneHost.getChildren().setAll(pane);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** @return {@code true} while any pending edit exists (§6.2) */
    public ReadOnlyBooleanProperty dirtyProperty() {
        return dirty.getReadOnlyProperty();
    }

    /**
     * @return an unmodifiable snapshot of id → pending value, containing
     *         only ids where {@code !Objects.equals(pending, persisted)};
     *         values may be {@code null} (a cleared key binding)
     */
    public Map<String, Object> pendingEdits() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pending));
    }

    /**
     * Re-seeds every row from {@link ValueAccess} and clears the pending
     * state (call after Apply). Runs with the conflict guard and pending
     * bookkeeping suspended — persisted values are authoritative — then
     * rebuilds the dirty markers from scratch.
     */
    public void refreshFromPersisted() {
        refreshing = true;
        try {
            for (SettingRow row : rowsById.values()) {
                row.setValue(persisted.currentValue(row.descriptor().id()));
            }
        } finally {
            refreshing = false;
        }
        pending.clear();
        updateDirtyMarkers();
    }

    /** @param r footer Apply action (may be {@code null}) */
    public void setOnApply(Runnable r) {
        this.onApply = r;
    }

    /** @param r footer OK action (may be {@code null}) */
    public void setOnOk(Runnable r) {
        this.onOk = r;
    }

    /** @param r footer Cancel action (may be {@code null}) */
    public void setOnCancel(Runnable r) {
        this.onCancel = r;
    }

    /** Focuses the search field (the §6.5 Ctrl/Cmd-F target). */
    public void focusSearch() {
        searchField.requestFocus();
    }

    /** @return the navigation rail (tests / keyboard glue) */
    public SettingsNavRail navRail() {
        return navRail;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * §6.5 ENTER/RIGHT from the rail: focus the first visible row's first
     * focus-traversable editor in the active pane. A skinless row (no
     * realized editor — headless construction) is a silent no-op.
     */
    private void focusFirstRowEditor() {
        SettingRow first = findFirstVisibleRow(paneHost);
        if (first == null) {
            return;
        }
        Node editor = findFirstFocusTraversable(first);
        if (editor != null) {
            editor.requestFocus();
        }
    }

    private static SettingRow findFirstVisibleRow(Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof SettingRow row && row.isVisible()) {
                return row;
            }
            if (child instanceof Parent nested) {
                SettingRow row = findFirstVisibleRow(nested);
                if (row != null) {
                    return row;
                }
            }
        }
        return null;
    }

    private static Node findFirstFocusTraversable(Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child.isFocusTraversable() && !child.isDisabled() && child.isVisible()) {
                return child;
            }
            if (child instanceof Parent nested) {
                Node node = findFirstFocusTraversable(nested);
                if (node != null) {
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * Group header label — label-small, upper-cased in code the same way
     * {@code DawgDialog.sectionHeaderText} realises the §3.2 uppercase
     * rule (JavaFX CSS has no {@code -fx-text-transform}).
     */
    private static Label groupHeader(String title) {
        Label header = new Label(title.toUpperCase(Locale.ROOT));
        header.getStyleClass().add("settings-group-title");
        return header;
    }

    private static String groupKey(String categoryId, String groupId) {
        return categoryId + "/" + groupId;
    }

    /**
     * Category id → ready-made rail icon (UI Design Book §3.6 — Lucide
     * only; the rail itself never imports an icon type). Unknown ids get
     * no icon.
     */
    private static Node categoryIcon(String categoryId) {
        String glyph = switch (categoryId) {
            case "audio" -> "headphones";
            case "appearance" -> "monitor";
            case "project" -> "folder";
            case "recording" -> "mic";
            case "backups" -> "archive";
            case "keyBindings" -> "keyboard";
            case "plugins" -> "plug";
            case "general" -> "settings";
            default -> null;
        };
        return glyph == null ? null : DawgIcon.of(glyph, DawgIcon.Size.SIZE_16);
    }

    private static void run(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    /**
     * Key-fallback bundle lookup (the {@code DawgDialog#msg} idiom —
     * returns the key itself for a missing resource).
     */
    private static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
    }
}
