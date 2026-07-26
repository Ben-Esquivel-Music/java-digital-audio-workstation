package com.benesquivelmusic.daw.app.ui.settings;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;
import javafx.scene.input.KeyEvent;

import java.util.Objects;

/**
 * Vertical category rail {@link Control} for the story-306 Rail &amp; Pane
 * settings shell — one row per settings category, with a per-category dirty
 * marker, search match count and search dimming (Settings View Design Book
 * §4.A, §5.1, §5.2).
 *
 * <p>Follows the {@code Control + SkinBase + package-resource user-agent
 * stylesheet} convention of {@link com.benesquivelmusic.daw.app.ui.hub.ProjectCard}
 * and {@link com.benesquivelmusic.daw.app.ui.status.StatusStripCell}
 * ({@code javafx-application-design} skill §3 Control/Skin split, §8 themeable
 * via a user-agent stylesheet plus stable style classes). The rail is generic:
 * it renders {@link NavItem} facts and never imports the settings catalogue or
 * an icon enum — the shell constructs the items (icons ready-made) and reacts
 * to {@link #selectedCategoryIdProperty()}.</p>
 *
 * <h2>Selection is an accent bar, never a fill (UI Design Book rejection §6)</h2>
 *
 * <p>The selected cell is marked by a drawn accent-bar {@code Region} on its
 * left edge — always present in the scene graph, transparent unless selected
 * (book §5.3; {@code settings-nav-rail.css}). The category title label never
 * receives an accent fill.</p>
 *
 * <h2>Keyboard contract (book §6.5, rejection #7)</h2>
 *
 * <p>The control itself handles exactly four keys while focused:
 * UP/DOWN move the selection by one item (clamped) and are consumed;
 * ENTER/RIGHT run the {@link #onActivatePaneProperty() activate-pane action}
 * (the shell focuses the pane's first row control) and are consumed. Every
 * other key propagates untouched — the rail never globally swallows
 * navigation keys.</p>
 *
 * @see SettingsNavRailSkin
 */
public final class SettingsNavRail extends Control {

    /** Stable style class — selectable as {@code .settings-nav-rail}. */
    public static final String DEFAULT_STYLE_CLASS = "settings-nav-rail";

    /**
     * One rail row's reactive facts (book §5.1/§5.2). Constructed by the
     * shell; identity facts ({@code categoryId}, {@code title}, {@code icon})
     * are immutable, the search/dirty facts are properties the shell drives
     * and the skin observes.
     */
    public static final class NavItem {

        private final String categoryId;
        private final String title;
        private final Node icon;

        /**
         * Unsaved-edit marker (the {@code •} in book §5.1) — {@code true}
         * while any setting in this category holds a pending edit.
         */
        private final BooleanProperty dirty =
                new SimpleBooleanProperty(this, "dirty", false);

        /**
         * Per-category search match count (book §5.2). {@code -1} means "not
         * searching" (the default) and hides the count label entirely.
         */
        private final IntegerProperty matchCount =
                new SimpleIntegerProperty(this, "matchCount", -1);

        /**
         * {@code true} while a search is active and this category has no
         * matches — the skin dims the cell via the {@code dimmed}
         * pseudo-class (book §5.2).
         */
        private final BooleanProperty dimmed =
                new SimpleBooleanProperty(this, "dimmed", false);

        /**
         * Creates a rail item.
         *
         * @param categoryId the catalogue category id; must not be {@code null}
         * @param title      the localized category title; must not be {@code null}
         * @param icon       the ready-made icon node, or {@code null} for none
         */
        public NavItem(String categoryId, String title, Node icon) {
            this.categoryId = Objects.requireNonNull(categoryId, "categoryId must not be null");
            this.title = Objects.requireNonNull(title, "title must not be null");
            this.icon = icon;
        }

        /** @return the catalogue category id this row represents. */
        public String categoryId() {
            return categoryId;
        }

        /** @return the localized category title. */
        public String title() {
            return title;
        }

        /** @return the icon node, or {@code null} if the row has none. */
        public Node icon() {
            return icon;
        }

        /** @return the unsaved-edit {@code •} marker property (book §5.1). */
        public BooleanProperty dirtyProperty() {
            return dirty;
        }

        /** @return the search match-count property; {@code -1} = not searching. */
        public IntegerProperty matchCountProperty() {
            return matchCount;
        }

        /** @return the no-matches-while-searching dimming property (book §5.2). */
        public BooleanProperty dimmedProperty() {
            return dimmed;
        }
    }

    private final ObservableList<NavItem> items = FXCollections.observableArrayList();

    private final ObjectProperty<String> selectedCategoryId =
            new SimpleObjectProperty<>(this, "selectedCategoryId", null);

    /**
     * Action run when ENTER/RIGHT activates the pane (book §6.5). Nullable —
     * a {@code null} action makes ENTER/RIGHT a consumed no-op (the keys stay
     * rail-owned either way).
     */
    private final ObjectProperty<Runnable> onActivatePane =
            new SimpleObjectProperty<>(this, "onActivatePane", null);

    /** Creates an empty rail (no items, no selection). */
    public SettingsNavRail() {
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setFocusTraversable(true);
        // Key handling lives on the CONTROL (not the skin) so the §6.5
        // contract holds independently of skin realization. addEventHandler
        // (not setOnKeyPressed) leaves the convenience slot free for callers.
        addEventHandler(KeyEvent.KEY_PRESSED, this::handleRailKey);
    }

    /**
     * §6.5 rail keys: UP/DOWN move the selection, ENTER/RIGHT activate the
     * pane; each of the four is consumed. Any other key falls through
     * unconsumed (rejection #7 — the rail never swallows navigation keys it
     * does not own).
     */
    private void handleRailKey(KeyEvent event) {
        switch (event.getCode()) {
            case UP -> {
                selectPrevious();
                event.consume();
            }
            case DOWN -> {
                selectNext();
                event.consume();
            }
            case ENTER, RIGHT -> {
                Runnable action = onActivatePane.get();
                if (action != null) {
                    action.run();
                }
                event.consume();
            }
            default -> {
                // Deliberately unconsumed — rejection #7.
            }
        }
    }

    /** @return the mutable item list; the shell populates it in catalogue order. */
    public ObservableList<NavItem> getItems() {
        return items;
    }

    /**
     * @return the selected category id property; {@code null} = no selection.
     *         The shell swaps the settings pane on change; the skin marks the
     *         matching cell with the {@code selected} pseudo-class.
     */
    public ObjectProperty<String> selectedCategoryIdProperty() {
        return selectedCategoryId;
    }

    /** @return the selected category id, or {@code null} if none. */
    public String getSelectedCategoryId() {
        return selectedCategoryId.get();
    }

    /** @param id the category id to select, or {@code null} to clear. */
    public void setSelectedCategoryId(String id) {
        selectedCategoryId.set(id);
    }

    /** Moves selection up by one item, clamped (keyboard model §6.5). */
    public void selectPrevious() {
        moveSelection(-1);
    }

    /** Moves selection down by one item, clamped (keyboard model §6.5). */
    public void selectNext() {
        moveSelection(1);
    }

    /**
     * @return the ENTER/RIGHT activate-pane action property (nullable
     *         {@code Runnable}); the shell installs "focus the pane's first
     *         visible row control" here (book §6.5)
     */
    public ObjectProperty<Runnable> onActivatePaneProperty() {
        return onActivatePane;
    }

    /**
     * Moves the selection by {@code delta} items, clamped to the list. With
     * no current selection (or a stale id no longer in the list) either
     * direction selects the first item.
     */
    private void moveSelection(int delta) {
        if (items.isEmpty()) {
            return;
        }
        int current = indexOfCategory(getSelectedCategoryId());
        int target = current < 0 ? 0 : Math.clamp(current + delta, 0, items.size() - 1);
        setSelectedCategoryId(items.get(target).categoryId());
    }

    /** @return the index of {@code id} in {@link #items}, or {@code -1}. */
    private int indexOfCategory(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).categoryId())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SettingsNavRailSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return Objects.requireNonNull(
                SettingsNavRail.class.getResource("settings-nav-rail.css"),
                "settings-nav-rail.css not on classpath").toExternalForm();
    }
}
