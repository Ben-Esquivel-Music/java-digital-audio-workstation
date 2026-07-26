package com.benesquivelmusic.daw.app.ui.settings;

import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Skin for {@link SettingsNavRail} (story 306, Settings View Design Book
 * §5.1/§5.2, {@code javafx-application-design} skill §3). A {@code ScrollPane}
 * (fit-to-width — the rail scrolls independently of the settings pane, book
 * §5.1) over a {@code VBox} of one {@code NavCell} per
 * {@link SettingsNavRail.NavItem}.
 *
 * <h2>Cell anatomy</h2>
 *
 * <p>Each cell is an {@code HBox}: accent-bar {@code Region} (leftmost, fixed
 * ~3&nbsp;px, style class {@code settings-nav-accent-bar}) → icon (when the
 * item has one) → title label → spacer → match-count label
 * ({@code settings-nav-count}, visible only while searching, i.e.
 * {@code matchCount >= 0}) → dirty-dot label ({@code settings-nav-dirty},
 * visible ⇔ {@code dirty}). The accent bar is <em>always</em> in the scene
 * graph and transparent unless the cell carries the {@code selected}
 * pseudo-class — selection is a drawn bar, never an accent fill behind the
 * title (UI Design Book rejection §6, book §5.3). A no-match cell during
 * search carries the {@code dimmed} pseudo-class (reduced opacity, book
 * §5.2). Clicking a cell selects its category.</p>
 *
 * <h2>Listener lifecycle</h2>
 *
 * <p>The skin attaches: a list listener on the control's items, a change
 * listener on {@code selectedCategoryIdProperty}, and per-item listeners /
 * label bindings inside each {@code NavCell}. A list change disposes every
 * cell and re-hooks the per-item listeners on the new cells;
 * {@link #dispose()} detaches all of them from the <em>remembered</em>
 * subjects (the item properties outlive the skin) before delegating to the
 * superclass, so the skin can be swapped without leaking.</p>
 */
public final class SettingsNavRailSkin extends SkinBase<SettingsNavRail> {

    private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass DIMMED_PSEUDO_CLASS = PseudoClass.getPseudoClass("dimmed");

    /** The §5.1 unsaved-edits marker glyph. */
    private static final String DIRTY_DOT = "•";

    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox cellBox = new VBox();
    private final List<NavCell> cells = new ArrayList<>();

    // Remembered subjects — dispose() must detach from exactly these
    // (feedback_listener_accumulation_latent_vs_live).
    private final ObservableList<SettingsNavRail.NavItem> items;
    private final ObjectProperty<String> selection;

    private final ListChangeListener<SettingsNavRail.NavItem> itemsListener;
    private final ChangeListener<String> selectionListener;

    /**
     * Builds the cells and hooks the items/selection listeners.
     *
     * @param rail the owning {@link SettingsNavRail}
     */
    public SettingsNavRailSkin(SettingsNavRail rail) {
        super(rail);
        this.items = rail.getItems();
        this.selection = rail.selectedCategoryIdProperty();

        cellBox.getStyleClass().add("settings-nav-cells");
        scrollPane.setContent(cellBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // The CONTROL is the focus/Tab stop (its key handler owns UP/DOWN/
        // ENTER/RIGHT, §6.5); the inner ScrollPane must not be a second stop.
        scrollPane.setFocusTraversable(false);
        getChildren().add(scrollPane);

        itemsListener = _ -> rebuildCells();
        items.addListener(itemsListener);
        selectionListener = (_, _, selectedId) -> applySelection(selectedId);
        selection.addListener(selectionListener);

        rebuildCells();
    }

    /** Disposes every existing cell, builds one per item, re-marks selection. */
    private void rebuildCells() {
        cells.forEach(NavCell::dispose);
        cells.clear();
        for (SettingsNavRail.NavItem item : items) {
            cells.add(new NavCell(item));
        }
        cellBox.getChildren().setAll(cells);
        applySelection(selection.get());
    }

    /** Reconciles the {@code selected} pseudo-class across all cells. */
    private void applySelection(String selectedId) {
        for (NavCell cell : cells) {
            cell.updateSelected(selectedId);
        }
    }

    @Override
    public void dispose() {
        items.removeListener(itemsListener);
        selection.removeListener(selectionListener);
        cells.forEach(NavCell::dispose);
        cells.clear();
        cellBox.getChildren().clear();
        super.dispose();
    }

    /**
     * One rail row. Its label bindings and the dimmed listener observe the
     * item's properties (which outlive the skin) — {@link #dispose()}
     * detaches all of them.
     */
    private final class NavCell extends HBox {

        private final SettingsNavRail.NavItem item;
        private final Label countLabel = new Label();
        private final Label dirtyLabel = new Label(DIRTY_DOT);
        private final ChangeListener<Boolean> dimmedListener;

        NavCell(SettingsNavRail.NavItem item) {
            this.item = item;
            getStyleClass().add("settings-nav-cell");

            // Accent bar — ALWAYS in the graph; settings-nav-rail.css keeps it
            // transparent unless the cell is :selected (rejection §6: a drawn
            // bar, never an accent fill behind the title).
            Region accentBar = new Region();
            accentBar.getStyleClass().add("settings-nav-accent-bar");

            Label titleLabel = new Label(item.title());
            titleLabel.getStyleClass().add("settings-nav-title");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            countLabel.getStyleClass().add("settings-nav-count");
            countLabel.textProperty().bind(item.matchCountProperty().asString());
            countLabel.visibleProperty().bind(item.matchCountProperty().greaterThanOrEqualTo(0));
            countLabel.managedProperty().bind(countLabel.visibleProperty());

            dirtyLabel.getStyleClass().add("settings-nav-dirty");
            dirtyLabel.visibleProperty().bind(item.dirtyProperty());
            dirtyLabel.managedProperty().bind(dirtyLabel.visibleProperty());

            dimmedListener = (_, _, dimmed) ->
                    pseudoClassStateChanged(DIMMED_PSEUDO_CLASS, dimmed);
            item.dimmedProperty().addListener(dimmedListener);
            pseudoClassStateChanged(DIMMED_PSEUDO_CLASS, item.dimmedProperty().get());

            setOnMouseClicked(_ -> getSkinnable().setSelectedCategoryId(item.categoryId()));

            getChildren().add(accentBar);
            if (item.icon() != null) {
                getChildren().add(item.icon());
            }
            getChildren().addAll(titleLabel, spacer, countLabel, dirtyLabel);
        }

        /** Flips the {@code selected} pseudo-class from the current selection id. */
        void updateSelected(String selectedId) {
            pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, item.categoryId().equals(selectedId));
        }

        /** Unbinds the labels and detaches the per-item dimmed listener. */
        void dispose() {
            countLabel.textProperty().unbind();
            countLabel.visibleProperty().unbind();
            countLabel.managedProperty().unbind();
            dirtyLabel.visibleProperty().unbind();
            dirtyLabel.managedProperty().unbind();
            item.dimmedProperty().removeListener(dimmedListener);
            setOnMouseClicked(null);
        }
    }
}
