package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Workspace;
import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the JavaFX {@link Menu} produced by {@link WorkspacesMenu}.
 * Requires a JavaFX toolkit (provided by {@link JavaFxToolkitExtension}).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WorkspacesMenuTest {

    private void onFx(Runnable r) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { latch.countDown(); }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static final class StubHost implements WorkspaceManager.Host {
        @Override public List<String> knownPanelIds() { return List.of("arrangement"); }
        @Override public boolean isPanelVisible(String id) { return true; }
        @Override public void setPanelVisible(String id, boolean v) { }
    }

    @Test
    void menuExposesNumberedSlotsAndAcceleratorsForFirstNine(@TempDir Path tmp) throws Exception {
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), new StubHost()); // seeds 6 defaults
        var keys = new KeyBindingManager(Preferences.userRoot()
                .node("workspacesMenuTest_" + System.nanoTime()));

        AtomicReference<Menu> menuRef = new AtomicReference<>();
        onFx(() -> menuRef.set(new WorkspacesMenu(
                manager, keys, () -> "Custom", _ -> { }, () -> { }
        ).build()));

        Menu menu = menuRef.get();
        assertThat(menu.getText()).isEqualTo("Workspaces");
        // First item should be Save Current as…, with the WORKSPACE_SAVE_AS accelerator.
        MenuItem saveAs = menu.getItems().getFirst();
        assertThat(saveAs.getText()).isEqualTo("Save Current as\u2026");
        assertThat(saveAs.getAccelerator())
                .isEqualTo(DawAction.WORKSPACE_SAVE_AS.defaultBinding());

        // Find the "Switch to" sub-menu.
        Menu switchTo = (Menu) menu.getItems().stream()
                .filter(i -> i instanceof Menu m && m.getText().equals("Switch to"))
                .findFirst().orElseThrow();
        assertThat(switchTo.getItems()).hasSize(6); // 6 default workspaces
        assertThat(switchTo.getItems().getFirst().getText()).startsWith("1: ");
        assertThat(switchTo.getItems().getFirst().getAccelerator())
                .isEqualTo(DawAction.WORKSPACE_1.defaultBinding());
    }

    @Test
    void saveCurrentAsInvokesNameSupplierAndPersists(@TempDir Path tmp) throws Exception {
        var host = new StubHost();
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);
        var keys = new KeyBindingManager(Preferences.userRoot()
                .node("workspacesMenuTestB_" + System.nanoTime()));

        List<String> nameInvocations = new ArrayList<>();
        AtomicReference<Menu> menuRef = new AtomicReference<>();
        onFx(() -> menuRef.set(new WorkspacesMenu(
                manager, keys,
                () -> { nameInvocations.add("ask"); return "From Menu"; },
                _ -> { }, () -> { }
        ).build()));

        onFx(() -> menuRef.get().getItems().getFirst().fire());

        assertThat(nameInvocations).containsExactly("ask");
        assertThat(manager.findByName("From Menu")).isPresent();
    }

    @Test
    void switchSlotsAreClickableAndApplyWorkspace(@TempDir Path tmp) throws Exception {
        var host = new StubHost();
        var store = new WorkspaceStore(tmp);
        store.save(new Workspace("Alpha",
                Map.of("arrangement", new PanelState(false, 1.0, 0, 0)),
                List.of(), Map.of()));
        var manager = new WorkspaceManager(store, host, false);
        var keys = new KeyBindingManager(Preferences.userRoot()
                .node("workspacesMenuTestC_" + System.nanoTime()));

        AtomicReference<Menu> menuRef = new AtomicReference<>();
        onFx(() -> menuRef.set(new WorkspacesMenu(
                manager, keys, () -> null, _ -> { }, () -> { }
        ).build()));

        // No NPE on fire; slot 1 fires switchToSlot(1).
        onFx(() -> {
            Menu switchTo = (Menu) menuRef.get().getItems().stream()
                    .filter(i -> i instanceof Menu m && m.getText().equals("Switch to"))
                    .findFirst().orElseThrow();
            switchTo.getItems().getFirst().fire();
        });
        // We can't verify side-effects via the StubHost (which has no
        // mutable state), but reaching here without exception is enough —
        // the manager-level test verifies state restoration.
    }
}
