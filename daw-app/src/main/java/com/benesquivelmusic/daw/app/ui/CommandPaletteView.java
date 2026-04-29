package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Command palette overlay — a centered floating panel activated by
 * {@code Ctrl+K} or {@code Ctrl+Shift+P}. Provides fuzzy search over every
 * registered {@link CommandPaletteEntry}, ranked by
 * {@link CommandPaletteFuzzyMatcher}.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>When opened with an empty query, the most-recently-executed entries
 *       (up to {@link CommandPaletteRecentsStore#MAX_RECENTS}) are shown
 *       under a "Recent" label, followed by all other entries.</li>
 *   <li>Typing in the search box re-ranks all entries by fuzzy match score;
 *       non-matching entries are hidden.</li>
 *   <li>{@code Up}/{@code Down} navigate the list; {@code Enter} executes
 *       the selected entry's {@link CommandPaletteEntry#handler() handler};
 *       {@code Esc} closes the palette.</li>
 *   <li>The palette closes automatically when its {@link Stage} loses
 *       focus (e.g. the user clicks outside the palette window).</li>
 *   <li>Disabled entries are rendered greyed-out, are not selectable via
 *       Enter, and surface their {@link CommandPaletteEntry#disabledReason()}
 *       via tooltip.</li>
 *   <li>Executing an entry records it in the {@link CommandPaletteRecentsStore}.</li>
 * </ul>
 *
 * <p>The palette renders in its own borderless, undecorated {@link Stage}
 * anchored to the primary application window. It is shown/hidden via
 * {@link #show()} and {@link #hide()} which delegate to
 * {@link Stage#show()} and {@link Stage#hide()}.</p>
 */
public final class CommandPaletteView {

    private static final Logger LOG = Logger.getLogger(CommandPaletteView.class.getName());

    /** Marker prefix used internally to distinguish the "Recent" header row. */
    private static final String HEADER_RECENT = "__header_recent__";
    private static final String HEADER_ALL = "__header_all__";

    private final Supplier<List<CommandPaletteEntry>> entrySupplier;
    private final CommandPaletteRecentsStore recentsStore;

    private final Stage stage;
    private final StackPane root;
    private final VBox panel;
    private final TextField searchField;
    private final ListView<Object> resultList;

    private List<CommandPaletteEntry> currentEntries = List.of();
    private Window owner;

    /**
     * Creates a command palette backed by the given entry source and recents store.
     *
     * @param entrySupplier  supplies the current set of entries each time
     *                       the palette is opened (re-evaluated to pick up
     *                       enabled-state changes since last open)
     * @param recentsStore   persists recently-executed entry IDs
     */
    public CommandPaletteView(Supplier<List<CommandPaletteEntry>> entrySupplier,
                              CommandPaletteRecentsStore recentsStore) {
        this.entrySupplier = Objects.requireNonNull(entrySupplier, "entrySupplier must not be null");
        this.recentsStore = Objects.requireNonNull(recentsStore, "recentsStore must not be null");

        this.searchField = new TextField();
        this.searchField.setPromptText("Type a command…");
        this.searchField.setId("commandPaletteSearchField");
        this.searchField.getStyleClass().add("command-palette-search");

        this.resultList = new ListView<>();
        this.resultList.setId("commandPaletteResults");
        this.resultList.getStyleClass().add("command-palette-list");
        this.resultList.setCellFactory(_ -> new EntryCell());
        this.resultList.setPrefHeight(360);
        this.resultList.setFocusTraversable(false);

        this.panel = new VBox(8, searchField, resultList);
        this.panel.setId("commandPalettePanel");
        this.panel.getStyleClass().add("command-palette-panel");
        this.panel.setPadding(new Insets(12));
        this.panel.setMaxWidth(560);
        this.panel.setMaxHeight(Region.USE_PREF_SIZE);
        this.panel.setStyle(
                "-fx-background-color: #2a2a2a;"
                + "-fx-border-color: #555;"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 6;"
                + "-fx-border-radius: 6;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 16, 0.3, 0, 4);");

        this.root = new StackPane(panel);
        this.root.setId("commandPaletteOverlay");
        this.root.getStyleClass().add("command-palette-overlay");
        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        this.panel.setTranslateY(40);
        this.root.setStyle("-fx-background-color: rgba(0,0,0,0.25);");
        this.root.setPrefSize(640, 480);

        // Construct a borderless utility stage eagerly so the scene graph is
        // ready for programmatic interaction (e.g. tests driving searchField).
        this.stage = new Stage(StageStyle.UNDECORATED);
        this.stage.setScene(new Scene(root, 640, 480));
        this.stage.setResizable(false);

        // Re-rank as the user types.
        this.searchField.textProperty().addListener((_, _, _) -> refreshList());

        // Up/Down navigate the list, Enter executes, Esc closes.
        this.searchField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSearchKey);
        this.resultList.addEventFilter(KeyEvent.KEY_PRESSED, this::handleListKey);

        // Close when the stage loses focus (clicking outside).
        ChangeListener<Boolean> stageFocus = (_, _, focused) -> {
            if (!focused && stage.isShowing()) {
                hide();
            }
        };
        this.stage.focusedProperty().addListener(stageFocus);

        this.resultList.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 2) {
                executeSelected();
            }
        });

        // Clicking on the dimmed background area (outside the panel) closes
        // the palette.
        this.root.setOnMouseClicked(e -> {
            if (e.getTarget() == root) {
                hide();
            }
        });
    }

    /** Sets the parent window — used to anchor and modally relate the palette. */
    public void setOwner(Window owner) {
        this.owner = owner;
        if (owner != null && stage.getOwner() == null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
    }

    /** Returns the JavaFX {@link Stage} the palette renders in (visible for tests). */
    Stage stage() {
        return stage;
    }

    /** Returns whether the palette is currently visible. */
    public boolean isVisible() {
        return stage.isShowing();
    }

    /**
     * Opens the palette: refreshes the entry list, clears the search box,
     * and gives the search field focus.
     */
    public void show() {
        currentEntries = List.copyOf(entrySupplier.get());
        searchField.setText("");
        refreshList();
        if (owner != null) {
            // Center over the owner window.
            stage.setX(owner.getX() + (owner.getWidth() - 640) / 2.0);
            stage.setY(owner.getY() + 80);
        }
        stage.show();
        stage.toFront();
        searchField.requestFocus();
    }

    /** Closes the palette. */
    public void hide() {
        if (stage.isShowing()) {
            stage.hide();
        }
    }

    /**
     * Refreshes the entry list from the supplier and recomputes visible rows
     * <em>without</em> showing the stage. Visible for tests so they can
     * exercise search and execution without requiring a real display.
     */
    void refreshFromSupplierForTesting() {
        currentEntries = List.copyOf(entrySupplier.get());
        searchField.setText("");
        refreshList();
    }

    /** Toggles the palette's visibility — used by the Ctrl+K accelerator. */
    public void toggle() {
        if (isVisible()) {
            hide();
        } else {
            show();
        }
    }

    /**
     * Returns a snapshot of the rows currently shown in the result list, in
     * order. {@link CommandPaletteEntry} rows are real entries; {@link String}
     * rows are section headers ("Recent", "All Commands"). Visible for tests.
     */
    List<Object> visibleRows() {
        return List.copyOf(resultList.getItems());
    }

    /** Returns the search field — exposed for tests to drive the UI. */
    TextField searchField() {
        return searchField;
    }

    /** Returns the result list — exposed for tests. */
    ListView<Object> resultList() {
        return resultList;
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void handleSearchKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> { hide(); e.consume(); }
            case ENTER -> { executeSelected(); e.consume(); }
            case DOWN -> { moveSelection(+1); e.consume(); }
            case UP -> { moveSelection(-1); e.consume(); }
            default -> { /* allow text input */ }
        }
    }

    private void handleListKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> { hide(); e.consume(); }
            case ENTER -> { executeSelected(); e.consume(); }
            default -> { /* default ListView handling */ }
        }
    }

    private void moveSelection(int delta) {
        ObservableList<Object> items = resultList.getItems();
        if (items.isEmpty()) return;
        int current = resultList.getSelectionModel().getSelectedIndex();
        int next = current;
        // Skip header rows.
        for (int step = 0; step < items.size(); step++) {
            next += delta;
            if (next < 0) next = items.size() - 1;
            if (next >= items.size()) next = 0;
            if (items.get(next) instanceof CommandPaletteEntry) {
                resultList.getSelectionModel().select(next);
                resultList.scrollTo(next);
                return;
            }
        }
    }

    private void executeSelected() {
        Object selected = resultList.getSelectionModel().getSelectedItem();
        if (!(selected instanceof CommandPaletteEntry entry)) {
            return;
        }
        if (!entry.enabled()) {
            return;
        }
        hide();
        try {
            entry.handler().run();
        } catch (RuntimeException ex) {
            LOG.warning("Command palette handler for '" + entry.label() + "' threw: " + ex);
            throw ex;
        }
        recentsStore.recordExecution(entry.id());
    }

    /**
     * Recomputes the visible rows based on the current search text. Visible
     * for testing — production code should drive it via the search field's
     * text property.
     */
    void refreshList() {
        String query = searchField.getText() == null ? "" : searchField.getText();
        ObservableList<Object> items = FXCollections.observableArrayList();

        if (query.isEmpty()) {
            // Show recents at the top, then everything else (alphabetically).
            Map<String, CommandPaletteEntry> byId = new LinkedHashMap<>();
            for (CommandPaletteEntry e : currentEntries) {
                byId.put(e.id(), e);
            }
            List<String> recents = recentsStore.load();
            List<CommandPaletteEntry> recentEntries = new ArrayList<>();
            for (String id : recents) {
                CommandPaletteEntry e = byId.get(id);
                if (e != null) recentEntries.add(e);
            }
            if (!recentEntries.isEmpty()) {
                items.add(HEADER_RECENT);
                items.addAll(recentEntries);
                items.add(HEADER_ALL);
            }
            // Exclude entries already shown in the recents section.
            java.util.Set<String> recentIds = new java.util.HashSet<>();
            for (CommandPaletteEntry re : recentEntries) {
                recentIds.add(re.id());
            }
            List<CommandPaletteEntry> rest = new ArrayList<>();
            for (CommandPaletteEntry e : currentEntries) {
                if (!recentIds.contains(e.id())) {
                    rest.add(e);
                }
            }
            rest.sort(Comparator.comparing(e -> e.label().toLowerCase()));
            items.addAll(rest);
        } else {
            record Scored(CommandPaletteEntry entry, int score) {}
            List<Scored> scored = new ArrayList<>();
            for (CommandPaletteEntry e : currentEntries) {
                int s = CommandPaletteFuzzyMatcher.score(query, e.label());
                if (s != CommandPaletteFuzzyMatcher.NO_MATCH) {
                    scored.add(new Scored(e, s));
                }
            }
            scored.sort(Comparator.<Scored>comparingInt(Scored::score).reversed()
                    .thenComparing(s -> s.entry().label().toLowerCase()));
            for (Scored s : scored) items.add(s.entry());
        }

        resultList.setItems(items);
        // Auto-select the first selectable row.
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CommandPaletteEntry) {
                resultList.getSelectionModel().select(i);
                resultList.scrollTo(i);
                break;
            }
        }
    }

    /**
     * Returns the currently selected entry, or empty. Visible for tests.
     */
    Optional<CommandPaletteEntry> selectedEntry() {
        Object sel = resultList.getSelectionModel().getSelectedItem();
        return sel instanceof CommandPaletteEntry e ? Optional.of(e) : Optional.empty();
    }

    /** Triggers the selected entry's handler — visible for tests. */
    void invokeSelected() {
        executeSelected();
    }

    // ── Cell renderer ───────────────────────────────────────────────────────

    private static final class EntryCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setMouseTransparent(false);
                setStyle("");
                return;
            }
            if (item instanceof String header) {
                setText(headerText(header));
                setGraphic(null);
                setTooltip(null);
                setMouseTransparent(true);
                setFocusTraversable(false);
                setStyle("-fx-text-fill: #888; -fx-font-size: 10px; -fx-font-weight: bold;"
                        + " -fx-padding: 6 4 2 4;");
                return;
            }
            if (item instanceof CommandPaletteEntry entry) {
                setMouseTransparent(false);
                setText(null);
                setGraphic(buildRow(entry));
                if (!entry.enabled()) {
                    setStyle("-fx-opacity: 0.45;");
                    String reason = entry.disabledReasonOpt().orElse("Currently disabled");
                    setTooltip(new Tooltip(reason));
                } else {
                    setStyle("");
                    setTooltip(entry.description().isEmpty()
                            ? null
                            : new Tooltip(entry.description()));
                }
            }
        }

        private String headerText(String marker) {
            return switch (marker) {
                case HEADER_RECENT -> "RECENT";
                case HEADER_ALL -> "ALL COMMANDS";
                default -> marker;
            };
        }

        private Node buildRow(CommandPaletteEntry entry) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            // Icon slot — fixed size so labels align.
            StackPane iconBox = new StackPane();
            iconBox.setMinSize(20, 20);
            iconBox.setPrefSize(20, 20);
            DawIcon icon = entry.icon();
            if (icon != null) {
                try {
                    iconBox.getChildren().add(IconNode.of(icon, 18));
                } catch (RuntimeException ex) {
                    // Missing icon resource is non-fatal; row simply renders with no icon.
                }
            }
            VBox text = new VBox(2);
            Label label = new Label(entry.label());
            label.setStyle("-fx-text-fill: #eee; -fx-font-size: 13px;");
            text.getChildren().add(label);
            if (!entry.description().isEmpty()) {
                Label desc = new Label(entry.description());
                desc.setStyle("-fx-text-fill: #999; -fx-font-size: 10px;");
                text.getChildren().add(desc);
            }
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(Double.MAX_VALUE);

            row.getChildren().addAll(iconBox, text);
            if (!entry.shortcut().isEmpty()) {
                Label sc = new Label(entry.shortcut());
                sc.setStyle("-fx-text-fill: #aaa; -fx-font-size: 10px;"
                        + " -fx-background-color: #3a3a3a;"
                        + " -fx-padding: 2 6 2 6; -fx-background-radius: 3;");
                row.getChildren().add(sc);
            }
            return row;
        }
    }
}
