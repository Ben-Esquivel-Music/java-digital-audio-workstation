package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.layout.BuiltInLayouts;
import com.benesquivelmusic.daw.app.ui.layout.LayoutManager;
import com.benesquivelmusic.daw.app.ui.layout.NamedLayout;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Constructs the View → Layout {@link Menu} that surfaces the five
 * Mission Control built-in layouts (story 282) plus user-saved layouts
 * as radio-style entries, followed by "Save Layout As…" and
 * "Manage Layouts…" actions.
 *
 * <p>The radio group is bound to {@link LayoutManager#currentLayoutProperty()}
 * via a listener so toggling layouts updates the check mark
 * automatically. Clicking a radio entry calls
 * {@link LayoutManager#load(String)}.</p>
 *
 * <p>This builder is dependency-free outside the layout package and the
 * supplied callbacks — it is testable on the JavaFX thread without
 * dragging {@code MainController} into the test.</p>
 */
public final class LayoutsMenu {

    private final LayoutManager layoutManager;
    private final Supplier<String> nameSupplier;
    private final Consumer<String> errorReporter;
    private final Runnable manageRequest;

    /**
     * @param layoutManager  the layout manager owning the named layouts
     * @param nameSupplier   supplies the layout name when the user
     *                       clicks "Save Layout As…" — typically a
     *                       {@code DawgDialog} prompt; {@code null} or
     *                       blank cancels the save
     * @param errorReporter  invoked when the user attempts to save over
     *                       a built-in name; receives a human-readable
     *                       message and is responsible for surfacing it
     *                       (typically a notification)
     * @param manageRequest  invoked when the user clicks
     *                       "Manage Layouts…" — the callback opens the
     *                       rename / delete dialog
     */
    public LayoutsMenu(LayoutManager layoutManager,
                       Supplier<String> nameSupplier,
                       Consumer<String> errorReporter,
                       Runnable manageRequest) {
        this.layoutManager = Objects.requireNonNull(layoutManager, "layoutManager must not be null");
        this.nameSupplier = Objects.requireNonNull(nameSupplier, "nameSupplier must not be null");
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter must not be null");
        this.manageRequest = Objects.requireNonNull(manageRequest, "manageRequest must not be null");
    }

    /**
     * Builds the layout menu. The returned menu re-populates itself
     * whenever {@link LayoutManager#savedLayouts()} changes so user-
     * saved layouts appear / disappear without rebuilding the entire
     * menu bar.
     */
    public Menu build() {
        Menu menu = new Menu("Layout");
        menu.getStyleClass().add("daw-menu");

        ToggleGroup group = new ToggleGroup();
        populate(menu, group);

        // Re-populate on every change to the saved-layouts list. The
        // FX consumers (radio items + ToggleGroup) need to be rebuilt
        // because the radio-button identity is per-name.
        layoutManager.savedLayouts().addListener(
                (ListChangeListener<NamedLayout>) _ -> populate(menu, group));

        // Bind the radio-check to currentLayoutProperty — the menu
        // reflects programmatic changes to currentLayout (e.g. on
        // project load) without needing manual sync from the caller.
        layoutManager.currentLayoutProperty().addListener(
                (_, _, name) -> syncSelection(group, name));

        return menu;
    }

    private void populate(Menu menu, ToggleGroup group) {
        menu.getItems().clear();
        for (NamedLayout layout : layoutManager.savedLayouts()) {
            menu.getItems().add(radioItem(group, layout.name()));
        }
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(saveAsItem());
        menu.getItems().add(manageItem());
        syncSelection(group, layoutManager.currentLayoutProperty().get());
    }

    private RadioMenuItem radioItem(ToggleGroup group, String name) {
        RadioMenuItem item = new RadioMenuItem(name);
        item.setUserData(name);
        item.setToggleGroup(group);
        item.setOnAction(_ -> {
            if (item.isSelected()) {
                layoutManager.load(name);
            }
        });
        return item;
    }

    private MenuItem saveAsItem() {
        MenuItem item = new MenuItem("Save Layout As\u2026");
        item.setOnAction(_ -> {
            String name = nameSupplier.get();
            if (name == null || name.isBlank()) return;
            String trimmed = name.strip();
            if (BuiltInLayouts.isBuiltIn(trimmed)) {
                errorReporter.accept(
                        "Cannot overwrite built-in layout \"" + trimmed + "\"");
                return;
            }
            layoutManager.saveCurrent(trimmed);
        });
        return item;
    }

    private MenuItem manageItem() {
        MenuItem item = new MenuItem("Manage Layouts\u2026");
        item.setOnAction(_ -> manageRequest.run());
        return item;
    }

    private static void syncSelection(ToggleGroup group, String name) {
        if (name == null) return;
        for (var toggle : group.getToggles()) {
            if (toggle instanceof RadioMenuItem rmi
                    && name.equals(rmi.getUserData())) {
                if (!rmi.isSelected()) rmi.setSelected(true);
                return;
            }
        }
    }
}
