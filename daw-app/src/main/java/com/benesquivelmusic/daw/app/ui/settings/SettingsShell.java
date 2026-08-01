package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
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

import java.text.MessageFormat;
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
import java.util.Optional;
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
 * {@link #focusSearch()}, which also enters the story-309 §4.C
 * search-focus mode (the rail dims until Esc or focus loss restores it —
 * Concept C as a mode of A). UP/DOWN/ENTER/RIGHT are rail-owned (the
 * rail's activate-pane action focuses the pane's first row editor), Tab
 * traversal is native, and Esc is consumed only by the search field —
 * and only while it has text (an empty-field Esc still restores the rail
 * but must reach the dialog's close semantics). Nothing else is globally
 * swallowed.</p>
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
     * Story 309 §4.C — the rail-level search-focus dim (Concept C as a
     * mode of A). Applied to the nav rail while search focus mode is
     * active; {@code settings-nav-rail.css} gives every cell the same
     * 0.4-opacity treatment as no-match dimming. Rail-level (not per
     * {@code NavItem}) so it composes with the §5.2 per-category
     * match-count dimming without either source fighting the other.
     */
    private static final PseudoClass SEARCH_FOCUS_PSEUDO_CLASS =
            PseudoClass.getPseudoClass("search-focus");

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
    private final Map<String, SettingDescriptor<?>> descriptorsById =
            new LinkedHashMap<>();
    private final Map<String, List<SettingRow>> rowsByGroup = new HashMap<>();
    private final Map<String, Label> progressByGroup = new HashMap<>();
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
    private final Label restartBannerText = new Label();
    private final HBox restartBanner = new HBox();
    private final Label operationNoticeText = new Label();
    private final HBox operationNotice = new HBox();
    private final Label footerStatus = new Label();

    /** {@code true} while the center shows search results (§6.1). */
    private boolean searchMode;
    /** Story 309 §4.C — {@code true} while the search-focus rail dim is active. */
    private boolean searchFocusMode;
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
     * @param categoryTrailingNodes optional extension node appended to a
     *                              category's pane, keyed by category id; may
     *                              be {@code null}
     */
    public SettingsShell(SettingsCatalogue catalogue,
                         ValueAccess persisted,
                         Map<String, SettingRow.ControlHints> hints,
                         Map<String, Node> categoryTrailingNodes) {
        this(catalogue, persisted, hints, categoryTrailingNodes, Map.of());
    }

    /**
     * Builds the shell with optional non-persisted group utilities, keyed as
     * {@code categoryId/groupId}. These nodes supplement, never replace, the
     * descriptor-backed setting rows.
     */
    public SettingsShell(SettingsCatalogue catalogue,
                         ValueAccess persisted,
                         Map<String, SettingRow.ControlHints> hints,
                         Map<String, Node> categoryTrailingNodes,
                         Map<String, Node> groupAncillaryNodes) {
        Objects.requireNonNull(catalogue, "catalogue must not be null");
        this.persisted = Objects.requireNonNull(persisted, "persisted must not be null");
        this.searchIndex = SettingsSearchIndex.of(catalogue);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        Map<String, SettingRow.ControlHints> hintsById =
                hints == null ? Map.of() : hints;
        Map<String, Node> trailing =
                categoryTrailingNodes == null ? Map.of() : categoryTrailingNodes;
        Map<String, Node> groupAncillary =
                groupAncillaryNodes == null ? Map.of() : groupAncillaryNodes;

        buildRows(catalogue, hintsById);
        buildCategoryPanes(catalogue, trailing, groupAncillary);
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
            Scope categoryModalScope = modalScope(category).orElse(null);
            for (SettingsCatalogue.Group group : category.groups()) {
                groupTitleByKey.put(groupKey(category.id(), group.id()), group.title());
                for (SettingDescriptor<?> descriptor : group.settings()) {
                    String id = descriptor.id();
                    SettingRow row = new SettingRow(descriptor,
                            persisted.currentValue(id), hintsById.get(id));
                    // Story 309 §5.3/§3.2 — per-row scope override chip
                    // (scope differs from the category's modal scope) and
                    // the "applies to…" cue, both resolved HERE: the row
                    // never touches the bundle (the badgeText contract).
                    if (categoryModalScope != null
                            && descriptor.scope() != categoryModalScope) {
                        row.setScopeChipText(scopeDisplayName(descriptor.scope()));
                    }
                    row.setScopeCueText(scopeCueText(descriptor.scope()));
                    rowsById.put(id, row);
                    descriptorsById.put(id, descriptor);
                    rowsByGroup.computeIfAbsent(groupKey(category.id(), group.id()),
                            _ -> new ArrayList<>()).add(row);
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
     * One permanent pane per category: header row (the §5.3 plain title
     * label — NEVER an accent fill, rejection §6 — plus the §5.7 tier-3
     * ⋯ menu), then per group a header + its rows + optional ancillary
     * node, then the category's trailing node if present. A group with
     * neither descriptors nor an ancillary node renders nothing (a
     * placeholder taxonomy slot); an ancillary-only group (backups /
     * diskUsage, story 308) renders its header + the ancillary visual.
     */
    private void buildCategoryPanes(SettingsCatalogue catalogue,
                                    Map<String, Node> trailing,
                                    Map<String, Node> groupAncillary) {
        for (SettingsCatalogue.Category category : catalogue.categories()) {
            List<Node> children = new ArrayList<>();
            children.add(categoryHeader(category));
            for (SettingsCatalogue.Group group : category.groups()) {
                Node ancillary = groupAncillary.get(groupKey(category.id(), group.id()));
                if (group.settings().isEmpty() && ancillary == null) {
                    // Placeholder taxonomy slots (general/language today —
                    // story 309 filled startup/resetProfiles with group
                    // ancillary visuals, never descriptors).
                    continue;
                }
                children.add(groupHeader(category.id(), group));
                for (SettingDescriptor<?> descriptor : group.settings()) {
                    children.add(rowsById.get(descriptor.id()));
                }
                if (ancillary != null) {
                    children.add(ancillary);
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

    /**
     * §5.3 pane header: the plain title label, the story-309 "Scope: …"
     * chip naming the category's modal scope, plus the uniform §5.7
     * tier-3 ⋯ menu carrying "Reset {category} to defaults" (story 308).
     * The title keeps the {@code settings-category-title} style class.
     *
     * <p>The chip derives from the category's descriptors — never a
     * hard-coded category map (§1.8 fix, rejection #8). A descriptor-less
     * category (General) has no scope to derive, so it renders no chip —
     * the documented story-309 choice.</p>
     */
    private Node categoryHeader(SettingsCatalogue.Category category) {
        Label title = new Label(category.title());
        title.getStyleClass().add("settings-category-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        MenuButton menu = new MenuButton();
        menu.getStyleClass().addAll("dawg-button", "icon-only", "settings-category-menu");
        menu.setId("settings-category-menu-" + category.id());
        menu.setGraphic(DawgIcon.of("ellipsis", DawgIcon.Size.SIZE_16));
        menu.setAccessibleText(msg("settings.category.menu"));
        MenuItem reset = new MenuItem(MessageFormat.format(
                msg("settings.category.reset"), category.title()));
        reset.setOnAction(_ -> resetCategory(category.id()));
        menu.getItems().add(reset);

        HBox header = new HBox(title);
        modalScope(category).ifPresent(scope -> {
            Label chip = new Label(MessageFormat.format(
                    msg("settings.scope.prefix"), scopeDisplayName(scope)));
            chip.getStyleClass().add("settings-scope-chip");
            chip.setId("settings-scope-chip-" + category.id());
            header.getChildren().add(chip);
        });
        header.getChildren().addAll(spacer, menu);
        header.getStyleClass().add("settings-category-header");
        return header;
    }

    /**
     * Story 309 §5.3 — the category's <em>modal</em> (most frequent)
     * descriptor scope, ties broken deterministically toward the
     * first-encountered scope in catalogue order. Empty for a category
     * with zero descriptors (General).
     */
    private static Optional<Scope> modalScope(SettingsCatalogue.Category category) {
        Map<Scope, Integer> counts = new LinkedHashMap<>();
        for (SettingsCatalogue.Group group : category.groups()) {
            for (SettingDescriptor<?> descriptor : group.settings()) {
                counts.merge(descriptor.scope(), 1, Integer::sum);
            }
        }
        Scope modal = null;
        int best = 0;
        for (Map.Entry<Scope, Integer> entry : counts.entrySet()) {
            // Strictly-greater keeps the FIRST-encountered scope on ties
            // (LinkedHashMap preserves catalogue encounter order).
            if (entry.getValue() > best) {
                modal = entry.getKey();
                best = entry.getValue();
            }
        }
        return Optional.ofNullable(modal);
    }

    /** The canonical §3.2 scope display names ({@code research-daw} §4). */
    private static String scopeDisplayName(Scope scope) {
        return msg(switch (scope) {
            case APPLICATION -> "settings.scope.application";
            case PROJECT_DEFAULTS -> "settings.scope.projectDefaults";
            case THIS_PROJECT -> "settings.scope.thisProject";
            case AUDIO_DEVICE -> "settings.scope.audioDevice";
        });
    }

    /**
     * The §3.2 "applies to…" cue — keyed off {@link Scope} only:
     * PROJECT_DEFAULTS reads "applies to new projects", THIS_PROJECT
     * "applies to the open project"; the other scopes carry no cue.
     */
    private static String scopeCueText(Scope scope) {
        return switch (scope) {
            case PROJECT_DEFAULTS -> msg("settings.scope.cue.projectDefaults");
            case THIS_PROJECT -> msg("settings.scope.cue.thisProject");
            case APPLICATION, AUDIO_DEVICE -> null;
        };
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
        // (dirty prompt included) still work. Story 309 §4.C: any Esc in
        // the field also restores the rail from search-focus dim —
        // WITHOUT changing what is consumed.
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                exitSearchFocusMode();
                if (!searchField.getText().isEmpty()) {
                    searchField.clear();
                    event.consume();
                }
            }
        });
        // Story 309 §4.C — leaving the field restores the rail too.
        searchField.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                exitSearchFocusMode();
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

        restartBanner.getStyleClass().add("settings-restart-banner");
        restartBanner.setId("settings-restart-banner");
        restartBanner.setManaged(false);
        restartBanner.setVisible(false);
        restartBannerText.getStyleClass().add("settings-restart-banner-text");
        restartBannerText.setWrapText(true);
        restartBanner.getChildren().addAll(
                DawgIcon.of("rotate-ccw", DawgIcon.Size.SIZE_16), restartBannerText);

        operationNotice.getStyleClass().add("settings-operation-notice");
        operationNotice.setManaged(false);
        operationNotice.setVisible(false);
        operationNoticeText.getStyleClass().add("settings-operation-notice-text");
        operationNoticeText.setWrapText(true);
        operationNotice.getChildren().addAll(
                DawgIcon.of("alert-triangle", DawgIcon.Size.SIZE_16), operationNoticeText);

        VBox center = new VBox(operationNotice, restartBanner, paneScroll);
        center.getStyleClass().add("settings-center");
        VBox.setVgrow(paneScroll, Priority.ALWAYS);
        return center;
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
        footerStatus.getStyleClass().add("settings-footer-status");
        footerStatus.setManaged(false);
        footerStatus.setVisible(false);
        footer.getChildren().addAll(footerStatus, spacer, cancel, apply, ok);
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
        updateApplyContext();
    }

    /** Keeps the footer note and restart banner truthful to pending metadata. */
    private void updateApplyContext() {
        List<String> restartLabels = pending.keySet().stream()
                .map(descriptorsById::get)
                .filter(Objects::nonNull)
                .filter(descriptor -> descriptor.applyClass() == ApplyClass.RESTART_REQUIRED)
                .map(SettingDescriptor::label)
                .toList();
        boolean hasEngineReconfigure = pending.keySet().stream()
                .map(descriptorsById::get)
                .filter(Objects::nonNull)
                .anyMatch(descriptor ->
                        descriptor.applyClass() == ApplyClass.ENGINE_RECONFIGURE);
        boolean hasRestart = !restartLabels.isEmpty();

        restartBanner.setManaged(hasRestart);
        restartBanner.setVisible(hasRestart);
        if (hasRestart) {
            restartBannerText.setText(MessageFormat.format(
                    msg("settings.restartBanner"), String.join(", ", restartLabels)));
        }

        boolean showFooterStatus = hasEngineReconfigure || hasRestart;
        footerStatus.setManaged(showFooterStatus);
        footerStatus.setVisible(showFooterStatus);
        if (showFooterStatus) {
            footerStatus.setText(msg(hasEngineReconfigure
                    ? "settings.footer.engineReconfigure"
                    : "settings.footer.restartRequired"));
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
                s.getChildren().add(searchGroupHeader(
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

    /** Re-seeds one row without discarding unrelated pending edits. */
    public void refreshSettingFromPersisted(String settingId) {
        SettingRow row = rowsById.get(settingId);
        if (row == null) {
            throw new IllegalArgumentException("Unknown setting: " + settingId);
        }
        refreshing = true;
        try {
            row.setValue(persisted.currentValue(settingId));
        } finally {
            refreshing = false;
        }
        pending.remove(settingId);
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

    /**
     * Focuses the search field (the §6.5 Ctrl/Cmd-F target) and enters
     * the story-309 §4.C search-focus mode: the rail dims (Concept C as
     * a mode of A) until Esc or focus loss restores it.
     */
    public void focusSearch() {
        searchField.requestFocus();
        enterSearchFocusMode();
    }

    /** @return whether the §4.C search-focus rail dim is active (story 309) */
    public boolean isSearchFocusDimActive() {
        return searchFocusMode;
    }

    private void enterSearchFocusMode() {
        searchFocusMode = true;
        navRail.pseudoClassStateChanged(SEARCH_FOCUS_PSEUDO_CLASS, true);
    }

    private void exitSearchFocusMode() {
        searchFocusMode = false;
        navRail.pseudoClassStateChanged(SEARCH_FOCUS_PSEUDO_CLASS, false);
    }

    /** @return the navigation rail (tests / keyboard glue) */
    public SettingsNavRail navRail() {
        return navRail;
    }

    /** Selects a category explicitly (used by live deep-link entry points). */
    public void selectCategory(String categoryId) {
        if (!categoryPanes.containsKey(categoryId)) {
            throw new IllegalArgumentException("Unknown settings category: " + categoryId);
        }
        searchField.clear();
        navRail.setSelectedCategoryId(categoryId);
        showCategory(categoryId);
    }

    /** Finds the one generic row for a descriptor id. */
    public Optional<SettingRow> settingRow(String settingId) {
        return Optional.ofNullable(rowsById.get(settingId));
    }

    /** Replaces an asynchronously discovered CHOICE row's options. */
    public void replaceChoiceOptions(String settingId, List<?> options) {
        SettingRow row = rowsById.get(settingId);
        if (row == null) {
            throw new IllegalArgumentException("Unknown setting: " + settingId);
        }
        row.replaceChoiceOptions(options);
    }

    /** Replaces options and falls back when a backend-specific value vanished. */
    public void replaceChoiceOptions(String settingId, List<?> options, Object fallback) {
        SettingRow row = rowsById.get(settingId);
        if (row == null) {
            throw new IllegalArgumentException("Unknown setting: " + settingId);
        }
        row.replaceChoiceOptions(options, fallback);
    }

    /** Replaces options while retaining visibly disabled unavailable choices. */
    public void replaceChoiceOptions(String settingId,
                                     List<?> options,
                                     Object fallback,
                                     List<?> unavailableOptions) {
        SettingRow row = rowsById.get(settingId);
        if (row == null) {
            throw new IllegalArgumentException("Unknown setting: " + settingId);
        }
        row.replaceChoiceOptions(options, fallback, unavailableOptions);
    }

    /** Shows or hides the small progress affordance in a permanent group header. */
    public void setGroupBusy(String categoryId, String groupId, boolean busy) {
        Label progress = progressByGroup.get(groupKey(categoryId, groupId));
        if (progress == null) {
            return;
        }
        progress.setManaged(busy);
        progress.setVisible(busy);
    }

    /** Resets every row in one group to its descriptor default. */
    public void resetGroup(String categoryId, String groupId) {
        List<SettingRow> rows = rowsByGroup.get(groupKey(categoryId, groupId));
        if (rows == null) {
            throw new IllegalArgumentException(
                    "Unknown settings group: " + categoryId + "/" + groupId);
        }
        rows.forEach(SettingRow::resetToDefault);
    }

    /**
     * §5.7 tier 3 (story 308): resets every row of every group in one
     * category to its descriptor default. Pending-only, like every other
     * reset tier — nothing is persisted until Apply.
     *
     * @param categoryId a catalogue category id
     * @throws IllegalArgumentException for an unknown category id
     */
    public void resetCategory(String categoryId) {
        if (!categoryPanes.containsKey(categoryId)) {
            throw new IllegalArgumentException(
                    "Unknown settings category: " + categoryId);
        }
        String prefix = categoryId + "/";
        for (Map.Entry<String, List<SettingRow>> entry : rowsByGroup.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().forEach(SettingRow::resetToDefault);
            }
        }
    }

    /**
     * §5.7 tier 4 (story 309): resets every row of every category to its
     * descriptor default. Pending-only, exactly like tiers 1–3 — nothing
     * is persisted until the caller drives Apply. The confirm guard lives
     * with the caller ({@code SettingsDialog}'s "Restore all defaults"),
     * not here: the shell stays write-free.
     */
    public void resetAll() {
        // Clear every key-capture row FIRST: the per-row conflict guard
        // reverts a value another key row currently holds, so two
        // swapped bindings could otherwise veto each other's defaults.
        // The interim nulls are plain pending edits the default-reset
        // below immediately overwrites.
        keyCaptureRows.forEach(row -> row.setValue(null));
        rowsById.values().forEach(SettingRow::resetToDefault);
    }

    /** @return whether the pending-only restart banner is currently visible */
    public boolean isRestartBannerVisible() {
        return restartBanner.isVisible();
    }

    /** @return whether the contextual footer warning is visible */
    public boolean isFooterStatusVisible() {
        return footerStatus.isVisible();
    }

    /** @return current contextual footer warning text */
    public String footerStatusText() {
        return footerStatus.getText();
    }

    /** @return current restart-banner text */
    public String restartBannerText() {
        return restartBannerText.getText();
    }

    /** Shows a persistent, user-visible warning for a failed Settings operation. */
    public void showOperationNotice(String message) {
        operationNoticeText.setText(Objects.requireNonNull(message, "message must not be null"));
        operationNotice.setManaged(true);
        operationNotice.setVisible(true);
    }

    /** Clears the previous operation warning when the user retries Apply. */
    public void clearOperationNotice() {
        operationNotice.setManaged(false);
        operationNotice.setVisible(false);
        operationNoticeText.setText("");
    }

    /** @return whether a failed-operation notice is visible */
    public boolean isOperationNoticeVisible() {
        return operationNotice.isVisible();
    }

    /** @return current failed-operation notice text */
    public String operationNoticeText() {
        return operationNoticeText.getText();
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
    private Node groupHeader(String categoryId, SettingsCatalogue.Group group) {
        HBox header = new HBox();
        header.getStyleClass().add("settings-group-header");
        header.setId("settings-group-" + categoryId + "-" + group.id());

        Label title = searchGroupHeader(group.title());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(title, spacer);

        // §5.8 / §6.6 — every group header carries the hidden progress
        // affordance so setGroupBusy works for any async group work
        // (audio/device enumeration, backups/diskUsage scan) with no
        // per-story gating (story 308 generalised the 307 audio gate).
        Label progress = new Label(msg("settings.group.recomputing"));
        progress.getStyleClass().add("settings-group-progress");
        progress.setManaged(false);
        progress.setVisible(false);
        progressByGroup.put(groupKey(categoryId, group.id()), progress);
        header.getChildren().add(progress);

        // §5.7 tier 2 — reset is a uniform affordance (rejection #6):
        // every group with at least one row gets it; ancillary-only
        // groups have nothing to reset.
        if (!group.settings().isEmpty()) {
            Button reset = new Button(msg("settings.group.reset"));
            reset.getStyleClass().addAll("dawg-button", "settings-group-reset");
            reset.setId("reset-group-" + categoryId + "-" + group.id());
            reset.setOnAction(_ -> resetGroup(categoryId, group.id()));
            header.getChildren().add(reset);
        }
        return header;
    }

    private static Label searchGroupHeader(String title) {
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
