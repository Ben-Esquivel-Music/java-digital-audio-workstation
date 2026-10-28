package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(JavaFxToolkitExtension.class)
class ProjectInstallationInputFenceTest {
    @TempDir Path workspace;

    @Test void registeredAcceleratorsAndRetainedPaletteActionsObeyTheLiveGuardWhileStopRemainsAvailable() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-install-shortcuts-" + UUID.randomUUID());
        try {
            runOnFx(() -> {
                AtomicBoolean allowed = new AtomicBoolean(true);
                KeyboardShortcutController.Host host = mock(KeyboardShortcutController.Host.class);
                KeyBindingManager keys = new KeyBindingManager(preferences);
                KeyboardShortcutController shortcuts = new KeyboardShortcutController(keys, host, allowed::get);
                Map<DawAction, Runnable> palette = shortcuts.buildActionHandlers();
                Scene scene = new Scene(new StackPane());
                shortcuts.register(scene);
                allowed.set(false);
                palette.values().forEach(Runnable::run);
                scene.getAccelerators().values().forEach(Runnable::run);
                verify(host, times(2)).onStop();
                verifyNoMoreInteractions(host);
                allowed.set(true);
                palette.get(DawAction.UNDO).run();
                palette.get(DawAction.DELETE_SELECTION).run();
                scene.getAccelerators().get(keys.getBinding(DawAction.SAVE).orElseThrow()).run();
                verify(host).onUndo();
                verify(host).onDeleteSelection();
                verify(host).onSaveProject();
            });
        } finally { preferences.removeNode(); }
    }

    @Test void menuActionsUseTheGuardEvenWhenFiredDirectlyAndResumeAfterInstallation() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-install-menus-" + UUID.randomUUID());
        try {
            runOnFx(() -> {
                AtomicBoolean allowed = new AtomicBoolean(false);
                DawMenuBarController.MenuActions host = mock(DawMenuBarController.MenuActions.class);
                MenuConstructionService menus = new MenuConstructionService(host, new KeyBindingManager(preferences));
                menus.setProjectActionsAllowed(allowed::get);
                MenuConstructionService.Result result = menus.build();
                clearInvocations(host);
                result.menus().forEach(ProjectInstallationInputFenceTest::fireMenu);
                verifyNoInteractions(host);
                allowed.set(true);
                result.syncableItems().saveItem().fire();
                result.syncableItems().undoItem().fire();
                result.syncableItems().deleteItem().fire();
                verify(host).onSaveProject();
                verify(host).onUndo();
                verify(host).onDeleteSelection();
            });
        } finally { preferences.removeNode(); }
    }

    @Test void workspaceMenusCannotMoveEditablePanelsOutOfTheGuardDuringInstallation() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-install-workspaces-" + UUID.randomUUID());
        try {
            runOnFx(() -> {
                AtomicBoolean allowed = new AtomicBoolean(false);
                WorkspaceManager manager = mock(WorkspaceManager.class);
                AtomicInteger imports = new AtomicInteger();
                WorkspacesMenu builder = new WorkspacesMenu(manager, new KeyBindingManager(preferences),
                        () -> "Tracking", _ -> { }, imports::incrementAndGet);
                builder.setActionsAllowed(allowed::get);
                Menu menu = builder.build();
                clearInvocations(manager);
                fireMenu(menu);
                verifyNoInteractions(manager);
                assertThat(imports).hasValue(0);
                allowed.set(true);
                fireMenu(menu);
                verify(manager).saveCurrentAs("Tracking");
                assertThat(imports).hasValue(1);
            });
        } finally { preferences.removeNode(); }
    }

    @Test void floatingControlsAndDirectSaveCheckpointEntriesStayFencedUntilTheHeldSealCompletes() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            fixture.save(); fixture.settle();
            Path target = fixture.anotherProjectOnDisk("Incoming");
            byte[] outgoingHead = Files.readAllBytes(fixture.songA.resolve("project.daw"));
            CountDownLatch sealing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger edits = new AtomicInteger();
            AtomicReference<Button> editable = new AtomicReference<>();
            AtomicReference<Button> alreadyDisabled = new AtomicReference<>();
            Parent floating = onFx(() -> {
                MainController main = new MainController();
                setField(main, "projectLifecycleController", fixture.lifecycle());
                Button edit = new Button("Edit clip");
                edit.setOnAction(_ -> edits.incrementAndGet());
                editable.set(edit);
                Button disabled = new Button("Unavailable edit");
                disabled.setDisable(true);
                disabled.setOnAction(_ -> edits.incrementAndGet());
                alreadyDisabled.set(disabled);
                Parent root = main.createFloatingProjectRoot(new StackPane(edit, disabled));
                new Scene(root);
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> {
                    sealing.countDown(); await(release);
                }, _ -> { });
                fixture.lifecycle().loadProjectFromPath(target);
                return root;
            });
            try {
                assertThat(sealing.await(15, TimeUnit.SECONDS)).isTrue();
                runOnFx(() -> {
                    assertThat(floating.isDisabled()).isTrue();
                    editable.get().fire(); alreadyDisabled.get().fire();
                    fixture.lifecycle().onSaveProject();
                    assertThat(fixture.snapshots().createCheckpointWithLabel("must be refused")).isNull();
                });
                fixture.settle();
                assertThat(edits).hasValue(0);
                assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).isEqualTo(outgoingHead);
            } finally { release.countDown(); }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "incoming installation");
            runOnFx(() -> {
                assertThat(floating.isDisabled()).isFalse();
                assertThat(alreadyDisabled.get().isDisabled()).isTrue();
                editable.get().fire(); alreadyDisabled.get().fire();
            });
            assertThat(edits).hasValue(1);
        }
    }

    @Test void anAlreadyOpenImmersiveWindowCannotMutateReferencesDuringInstallationAndClosesOnReplacement() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false))) {
            Path incoming = fixture.anotherProjectOnDisk("Immersive target");
            CountDownLatch sealing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MainController main = onFx(() -> {
                MainController controller = new MainController();
                setField(controller, "projectLifecycleController", fixture.lifecycle());
                setField(controller, "project", fixture.project.get());
                controller.onOpenImmersiveAb();
                return controller;
            });
            javafx.stage.Stage stage = onFx(() -> (javafx.stage.Stage) readField(main, "atmosAbStage"));
            DawProject outgoing = fixture.project.get();
            runOnFx(() -> {
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> {
                    sealing.countDown(); await(release);
                }, _ -> { });
                fixture.lifecycle().loadProjectFromPath(incoming);
            });
            try {
                assertThat(sealing.await(15, TimeUnit.SECONDS)).isTrue();
                runOnFx(() -> {
                    assertThat(stage.getScene().getRoot().isDisabled()).isTrue();
                    javafx.scene.control.CheckBox toggle = (javafx.scene.control.CheckBox) stage.getScene().getRoot().lookup(".check-box");
                    toggle.fire();
                    main.onImmersiveAbToggle();
                    assertThat(outgoing.getReferenceTrackManager().isReferenceActive()).isFalse();
                });
            } finally { release.countDown(); }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "immersive target installed");
            runOnFx(() -> {
                try {
                    java.lang.reflect.Method replace = MainController.class.getDeclaredMethod("replacePluginProject", DawProject.class);
                    replace.setAccessible(true); replace.invoke(main, fixture.project.get());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                assertThat(stage.isShowing()).isFalse();
                assertThat(readField(main, "atmosAbStage")).isNull();
            });
        }
    }

    @Test void saveUndoDeleteAndAutosaveCannotCaptureOutgoingModelAfterTheTargetLeaseIsInstalled() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-install-gap-" + UUID.randomUUID());
        FxDispatcher dispatcher = spy(new FxDispatcher());
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace,
                new AutoSaveConfig(Duration.ofMillis(100), 10, true), null, dispatcher)) {
            fixture.save(); fixture.settle();
            DawProject outgoing = fixture.project.get();
            Path incoming = fixture.anotherProjectOnDisk("Incoming");
            byte[] expectedHead = Files.readAllBytes(incoming.resolve("project.daw"));
            AtomicReference<Runnable> heldInstallation = new AtomicReference<>();
            CountDownLatch targetInstalled = new CountDownLatch(1);
            AtomicBoolean heldOnce = new AtomicBoolean();
            doAnswer(invocation -> {
                if (fixture.currentProjectIs(incoming)
                        && fixture.project.get() == outgoing && heldOnce.compareAndSet(false, true)) {
                    heldInstallation.set(invocation.getArgument(0));
                    targetInstalled.countDown();
                    return null;
                }
                return invocation.callRealMethod();
            }).when(dispatcher).onFx(any(Runnable.class));
            AtomicReference<Scene> scene = new AtomicReference<>();
            AtomicReference<Map<DawAction, Runnable>> palette = new AtomicReference<>();
            KeyBindingManager keys = new KeyBindingManager(preferences);
            runOnFx(() -> {
                KeyboardShortcutController.Host host = mock(KeyboardShortcutController.Host.class);
                doAnswer(_ -> { fixture.lifecycle().onSaveProject(); return null; }).when(host).onSaveProject();
                doAnswer(_ -> { outgoing.setName("Undone outgoing"); return null; }).when(host).onUndo();
                doAnswer(_ -> { outgoing.removeTrack(outgoing.getTracks().getFirst()); return null; }).when(host).onDeleteSelection();
                KeyboardShortcutController shortcuts = new KeyboardShortcutController(keys, host,
                        () -> !fixture.lifecycle().isProjectInstallationPending());
                palette.set(shortcuts.buildActionHandlers());
                Scene main = new Scene(new StackPane());
                shortcuts.register(main); scene.set(main);
                fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> { });
                fixture.lifecycle().loadProjectFromPath(incoming);
            });
            try {
                assertThat(targetInstalled.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(fixture.projectManager.getCurrentProject().projectPath()).isEqualTo(incoming);
                assertThat(fixture.project.get()).isSameAs(outgoing);
                long incomingGeneration = fixture.checkpoints.runningGeneration().orElseThrow();
                runOnFx(() -> {
                    for (DawAction action : List.of(DawAction.SAVE, DawAction.UNDO, DawAction.DELETE_SELECTION)) {
                        scene.get().getAccelerators().get(keys.getBinding(action).orElseThrow()).run();
                        palette.get().get(action).run();
                    }
                    fixture.lifecycle().onSaveProject();
                    assertThat(fixture.snapshots().createCheckpointWithLabel("wrong model")).isNull();
                });
                fixture.awaitOnFx(() -> fixture.checkpointEvents.stream().anyMatch(event ->
                        event instanceof CheckpointEvent.Rearmed rearmed && incomingGeneration == rearmed.generation()
                                && rearmed.reason() == CheckpointEvent.RearmReason.SKIPPED), "autosave skipped the install gap");
                fixture.settle();
                assertThat(Files.readAllBytes(incoming.resolve("project.daw"))).isEqualTo(expectedHead);
                assertThat(outgoing.getName()).isEqualTo("Song A");
                assertThat(outgoing.getTracks()).hasSize(1);
                assertThat(fixture.checkpointEvents).noneMatch(event -> event instanceof CheckpointEvent.Written written
                        && incomingGeneration == written.generation());
            } finally {
                Runnable continuation = heldInstallation.getAndSet(null);
                if (continuation != null) runOnFx(continuation);
            }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "FX model caught up");
            runOnFx(() -> {
                fixture.project.get().setName("Incoming edited");
                scene.get().getAccelerators().get(keys.getBinding(DawAction.SAVE).orElseThrow()).run();
            });
            fixture.settle();
            assertThat(new ProjectDeserializer().deserialize(Files.readString(incoming.resolve("project.daw"))).getName())
                    .isEqualTo("Incoming edited");
        } finally { preferences.removeNode(); }
    }

    private static void fireMenu(Menu menu) {
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu nested) fireMenu(nested);
            else if (item.getOnAction() != null) item.fire();
        }
    }

    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true); field.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object readField(MainController target, String name) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true); return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
