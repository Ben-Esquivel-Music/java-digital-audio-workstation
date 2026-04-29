package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.Workspace;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Constructs the JavaFX {@link Menu} that exposes
 * {@link WorkspaceManager}-backed user workspaces under
 * <em>Workspaces &gt; Save Current as…</em> and
 * <em>Workspaces &gt; Switch to…</em>.
 *
 * <p>The first nine workspaces (in {@link WorkspaceManager#listAll}
 * order) are surfaced as {@code "1: Tracking"}, {@code "2: Editing"} …
 * with the {@code WORKSPACE_1}…{@code WORKSPACE_9} accelerators picked
 * up from {@link KeyBindingManager}.</p>
 *
 * <p>This builder is a thin, dependency-free utility — it takes a
 * {@code WorkspaceManager} plus a small set of {@link Runnable}/
 * {@link Supplier}/{@link Consumer} hooks for the actions that need to
 * pop dialogs (the host owns the dialog UI). That keeps it testable on
 * the JavaFX thread without dragging the whole {@code MainController}
 * into tests.</p>
 */
public final class WorkspacesMenu {

    private final WorkspaceManager manager;
    private final KeyBindingManager keyBindings;
    private final Supplier<String> nameSupplier;
    private final Consumer<String> exportRequest;
    private final Runnable importRequest;

    /**
     * Creates a new builder.
     *
     * @param manager       the workspace manager owning save/recall logic (must not be {@code null})
     * @param keyBindings   the key-binding manager used to apply
     *                      accelerators to the menu items (must not be {@code null})
     * @param nameSupplier  supplies the workspace name when the user
     *                      clicks "Save Current as…" — typically a
     *                      JavaFX {@code TextInputDialog} or {@code null}
     *                      if the user cancelled
     * @param exportRequest invoked with the workspace name when the user
     *                      clicks "Export…" — the callback is responsible
     *                      for prompting for a destination file and
     *                      invoking {@link WorkspaceManager#exportTo}
     * @param importRequest invoked when the user clicks "Import…" — the
     *                      callback is responsible for prompting for a
     *                      source file and invoking
     *                      {@link WorkspaceManager#importFrom}
     */
    public WorkspacesMenu(WorkspaceManager manager,
                          KeyBindingManager keyBindings,
                          Supplier<String> nameSupplier,
                          Consumer<String> exportRequest,
                          Runnable importRequest) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.keyBindings = Objects.requireNonNull(keyBindings, "keyBindings must not be null");
        this.nameSupplier = Objects.requireNonNull(nameSupplier, "nameSupplier must not be null");
        this.exportRequest = Objects.requireNonNull(exportRequest, "exportRequest must not be null");
        this.importRequest = Objects.requireNonNull(importRequest, "importRequest must not be null");
    }

    /** Builds a fresh "Workspaces" menu reflecting the current store contents. */
    public Menu build() {
        Menu menu = new Menu("Workspaces");
        menu.getStyleClass().add("daw-menu");

        MenuItem saveAs = item("Save Current as\u2026", DawAction.WORKSPACE_SAVE_AS, () -> {
            String name = nameSupplier.get();
            if (name != null && !name.isBlank()) {
                manager.saveCurrentAs(name.trim());
            }
        });

        Menu switchTo = new Menu("Switch to");
        switchTo.getStyleClass().add("daw-menu");
        List<Workspace> all = manager.listAll();
        List<DawAction> slotActions = DawAction.workspaceSlotActions();
        for (int i = 0; i < all.size(); i++) {
            Workspace ws = all.get(i);
            String label = (i < WorkspaceManager.MAX_NUMBERED_SLOTS)
                    ? (i + 1) + ": " + ws.name()
                    : ws.name();
            DawAction action = i < slotActions.size() ? slotActions.get(i) : null;
            int slot = i + 1;
            switchTo.getItems().add(item(label, action, () -> manager.switchToSlot(slot)));
        }
        if (all.isEmpty()) {
            MenuItem none = new MenuItem("(no saved workspaces)");
            none.setDisable(true);
            switchTo.getItems().add(none);
        }

        MenuItem exportItem = item("Export\u2026", null, () -> {
            // Default behaviour: export the first workspace if no specific
            // selection — callers typically wire this through a chooser.
            if (manager.listAll().isEmpty()) return;
            exportRequest.accept(manager.listAll().getFirst().name());
        });
        MenuItem importItem = item("Import\u2026", null, importRequest);

        menu.getItems().addAll(
                saveAs,
                new SeparatorMenuItem(),
                switchTo,
                new SeparatorMenuItem(),
                exportItem,
                importItem
        );
        return menu;
    }

    private MenuItem item(String text, DawAction action, Runnable handler) {
        MenuItem mi = new MenuItem(text);
        if (action != null) {
            Optional<javafx.scene.input.KeyCombination> binding =
                    keyBindings.getBinding(action);
            binding.ifPresent(mi::setAccelerator);
        }
        mi.setOnAction(_ -> handler.run());
        return mi;
    }
}
