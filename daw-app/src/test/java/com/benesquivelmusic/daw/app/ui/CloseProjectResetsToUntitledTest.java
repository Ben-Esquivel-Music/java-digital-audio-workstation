package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Story 333, ruling R10 — File ▸ Close Project as the application wires it
 * (the coordinator's half is {@link CloseProjectInstallsAFreshUntitledProjectTest}):
 * fired from a real File menu whose action delegates as
 * {@code MainController}'s does, it leaves the window as at launch — a fresh,
 * clean Untitled project, its transport stopped at the start, no project
 * folder — and the Welcome window over it through
 * {@code MainController.showWelcomeIfNoProjectOpen}, the hook the
 * application installs. The window stays and the audio engine is not
 * stopped: no DISPOSE, no HIDE, no terminal exit. Close Project is disabled
 * afterwards in the menu and in the Command Palette, with the ruled reason,
 * and enabled again by the first edit, and its shortcut then does nothing;
 * Song A's lock is released. Over the application's own replacement and
 * disposal, the closed project's plug-ins are disposed and their editor
 * closed, the selection is cleared, and the audio engine is not stopped until
 * an exit. The resets the window's rebuild makes (it needs the FXML load) are
 * pinned by source here; story 333's Resolution records them as a category
 * with the future story that owns them.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CloseProjectResetsToUntitledTest {

    @TempDir
    Path workspace;

    @Test
    void closeProjectLeavesTheWindowAsAtLaunchAndDisablesItselfInTheMenuAndThePaletteUntilAnEdit() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-close-project-" + UUID.randomUUID());
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicInteger welcomesShown = new AtomicInteger();
            runOnFx(() -> {
                MainController controller = new MainController();
                setField(controller, "projectManager", exit.save.projectManager);
                setField(controller, "projectLifecycleController", exit.lifecycle());
                exit.lifecycle().setWelcomePresenter(_ -> welcomesShown.incrementAndGet());
                // As MainController.createExitCoordinator installs it.
                exit.lifecycle().setWelcomeAfterClose(controller::showWelcomeIfNoProjectOpen);
            });
            DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
            host.closeProjectAction = () -> exit.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject());
            KeyBindingManager keys = new KeyBindingManager(preferences);
            DawMenuBarController menus = onFx(() -> new DawMenuBarController(host, keys, () -> false, () -> false,
                    () -> false, () -> false, () -> false, () -> false, () -> false,
                    exit.coordinator()::closeProjectAvailable));
            Menu file = onFx(() -> menus.build().getMenus().getFirst());
            MenuItem closeProject = file.getItems().stream()
                    .filter(item -> "Close Project".equals(item.getText())).findFirst().orElseThrow();
            Map<DawAction, Runnable> handlers = onFx(() -> new KeyboardShortcutController(keys,
                    mock(KeyboardShortcutController.Host.class)).buildActionHandlers());
            DawProject songA = exit.save.project.get();
            assertThat(closeProject.isDisable()).as("Song A's folder is open: something to close").isFalse();

            runOnFx(closeProject::fire);
            exit.await(() -> welcomesShown.get() == 1, "the Welcome window over the Untitled project");

            DawProject untitled = exit.save.project.get();
            assertThat(untitled).isNotSameAs(songA);
            assertThat(untitled.getName()).isEqualTo("Untitled Project");
            assertThat(untitled.isDirty()).isFalse();
            assertThat(untitled.getTransport().getState()).isEqualTo(TransportState.STOPPED);
            assertThat(untitled.getTransport().getPositionInBeats()).isZero();
            assertThat(exit.save.projectManager.getCurrentProject()).as("no project folder").isNull();
            assertThat(exit.disposals).as("the window and the audio engine stay: no DISPOSE").hasValue(0);
            assertThat(exit.terminalExits).hasValue(0);
            assertThat(onFx(() -> exit.stage().isShowing())).isTrue();
            assertThat(exit.steps).noneMatch(step -> step.step() == ExitCoordinator.ExitStep.HIDE);

            // MainController's folder-changed hook syncs the menu; the palette re-reads at each open.
            runOnFx(menus::syncMenuState);
            assertThat(closeProject.isDisable()).as("a pristine Untitled project: nothing to close").isTrue();
            CommandPaletteEntry entry = onFx(() -> MainController.commandPaletteEntries(handlers, keys,
                    exit.coordinator()::closeProjectAvailable).stream()
                    .filter(candidate -> candidate.id().equals("CLOSE_PROJECT")).findFirst().orElseThrow());
            assertThat(entry.enabled()).isFalse();
            assertThat(entry.disabledReason())
                    .isEqualTo("Nothing to close — this project has never been saved and has no changes.");

            exit.edit("Bass");
            runOnFx(menus::syncMenuState);
            assertThat(closeProject.isDisable()).as("unsaved changes: something to close").isFalse();
            assertThat(welcomesShown).as("the hook showed one Welcome window").hasValue(1);

            exit.save.projectManager.whenRetiredLeasesReleased()
                    .get(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(exit.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("Song A's lock is released")
                    .doesNotExist();
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    void theApplicationsWelcomeHookShowsTheWelcomeWindowOnlyWhenNoProjectFolderIsOpen() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicInteger welcomesShown = new AtomicInteger();
            MainController main = onFx(() -> {
                MainController controller = new MainController();
                setField(controller, "projectManager", exit.save.projectManager);
                setField(controller, "projectLifecycleController", exit.lifecycle());
                exit.lifecycle().setWelcomePresenter(_ -> welcomesShown.incrementAndGet());
                return controller;
            });

            runOnFx(main::showWelcomeIfNoProjectOpen);
            assertThat(welcomesShown).as("Song A is open: no Welcome window").hasValue(0);

            exit.closeProject();
            exit.await(() -> exit.welcomes.get() == 1, "Close Project ended");
            runOnFx(main::showWelcomeIfNoProjectOpen);
            assertThat(welcomesShown).as("no folder open: the Welcome window, as at launch").hasValue(1);
        }
    }

    /**
     * The studio engineer's stage-7 N1 (ruling R10): Close Project's shortcut
     * on a pristine Untitled project does nothing — no step, no second
     * Untitled, no second Welcome window — as its disabled menu item does,
     * whichever accelerator the scene holds for it: the keyboard map's own
     * handler, wired as the application's keyboard host is, reaches the
     * coordinator, which refuses it.
     */
    @Test
    void closeProjectsShortcutWithNothingToCloseDoesNothing() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-close-nothing-" + UUID.randomUUID());
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            KeyboardShortcutController.Host host = mock(KeyboardShortcutController.Host.class);
            doAnswer(_ -> {
                // As MainController's keyboard host routes it.
                exit.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject());
                return null;
            }).when(host).onCloseProject();
            Map<DawAction, Runnable> handlers = onFx(() -> new KeyboardShortcutController(
                    new KeyBindingManager(preferences), host).buildActionHandlers());
            exit.closeProject();
            exit.await(() -> exit.welcomes.get() == 1, "Close Project ended with the Welcome window");
            DawProject untitled = exit.save.project.get();
            int steps = exit.steps.size();
            assertThat(onFx(() -> exit.coordinator().closeProjectAvailable())).as("fixture: nothing to close").isFalse();

            runOnFx(() -> handlers.get(DawAction.CLOSE_PROJECT).run());
            exit.save.settle();

            verify(host).onCloseProject();
            assertThat(exit.steps).as("no step ran").hasSize(steps);
            assertThat(exit.save.project.get()).as("the same Untitled project").isSameAs(untitled);
            assertThat(exit.welcomes).as("one Welcome window").hasValue(1);
            assertThat(onFx(() -> exit.lifecycle().aSwitchIsPending() || exit.coordinator().isWindowInert()))
                    .as("nothing pending, the window live").isFalse();
        } finally {
            preferences.removeNode();
        }
    }

    /**
     * Ruling R10's plug-in rows, over the application's own replacement
     * ({@code MainController.replacePluginProject}, the lifecycle
     * controller's {@code setProject} in the application — pinned below):
     * File ▸ Close Project disposes the closed project's inserts — an
     * insert's resources are released once the chain is quiescent — and
     * closes the plug-in editor that was open on one of its channels. The
     * controller is partial: its live project and its plug-in view
     * controller are set on non-final fields (the main controller is never
     * FXML-loaded in tests), and the fixture's {@code setProject} also hands
     * the project installed to it, as the application's does.
     */
    @Test
    void closeProjectDisposesTheClosedProjectsPluginsAndClosesTheirEditor() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            DawProject songA = exit.save.project.get();
            java.util.concurrent.CountDownLatch disposed = new java.util.concurrent.CountDownLatch(1);
            AtomicReference<PluginViewController> plugins = new AtomicReference<>();
            MainController main = onFx(() -> {
                MainController controller = new MainController();
                setField(controller, "project", songA);
                PluginViewController view = new PluginViewController(new PluginViewController.Deps(() -> 48_000,
                        () -> 512, () -> (DawProject) readField(controller, "project"), () -> { },
                        (message, icon) -> { }, (level, message) -> { }, (segments, node) -> { }, () -> null,
                        () -> { }));
                com.benesquivelmusic.daw.core.mixer.MixerChannel channel =
                        songA.getMixer().getChannels().getFirst();
                PluginSignalPathActivationTest.configure(view, channel);
                view.onActivateBuiltInPlugin(com.benesquivelmusic.daw.core.plugin.CompressorPlugin.class);
                // The insert's resources: what its disposal releases once the chain is quiescent.
                channel.getInsertSlots().getFirst().setDisposal(disposed::countDown);
                setField(controller, "pluginViewController", view);
                plugins.set(view);
                return controller;
            });
            try {
                assertThat(onFx(() -> plugins.get().activeEditorSessionForTest())).as("fixture: an editor is open")
                        .isNotNull();
                exit.save.onProjectSet = next -> replacePluginProject(main, next);
                exit.save.promptAnswer = "Discard and close";

                exit.closeProject();
                exit.await(() -> exit.welcomes.get() == 1, "Close Project ended");

                DawProject untitled = exit.save.project.get();
                assertThat(onFx(() -> readField(main, "project"))).as("the window's live project").isSameAs(untitled);
                assertThat(onFx(() -> plugins.get().activeEditorSessionForTest()))
                        .as("the closed project's plug-in editor was closed").isNull();
                assertThat(onFx(() -> songA.getMixer().getChannels().getFirst().getInsertSlots()))
                        .as("the closed project's inserts were retired").isEmpty();
                assertThat(disposed.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                        .as("and disposed once the chain was quiescent").isTrue();
            } finally {
                runOnFx(() -> plugins.get().dispose());
            }
        }

        String main = FxHalfOfASaveMakesNoFileSystemCallTest.code(java.nio.file.Files.readString(
                SourceScanSupport.locateDawAppModule().resolve(
                        "src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java"),
                java.nio.charset.StandardCharsets.UTF_8)).replaceAll("\\s+", " ");
        assertThat(main).as("the application's setProject is this replacement")
                .contains("new ProjectLifecycleController.Deps( () -> project, this::replacePluginProject,");
    }

    /**
     * Ruling R10's "Undo history and selection are cleared" (the studio
     * engineer's app-fix-2 review S2 and the ruling on app fix pass 2b, §1):
     * Close Project, through the protocol and over the application's own
     * {@code MainController.replacePluginProject}, clears both halves of the
     * selection that named Song A — the time range and the clip selection —
     * through the selection model's own clears, whose change listener re-syncs
     * the menus.
     */
    @Test
    void closeProjectClearsTheTimeRangeAndTheClipSelectionOfTheClosedProject() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicInteger resyncs = new AtomicInteger();
            MainController main = aSelectionOfSongA(exit, resyncs);

            exit.closeProject();
            exit.await(() -> exit.welcomes.get() == 1, "Close Project ended");

            assertTheSelectionIsCleared(main, resyncs);
        }
    }

    /**
     * The same at File ▸ Open of Song B: every door that replaces the project
     * clears it (the ruling on app fix pass 2b, §1).
     */
    @Test
    void openingAnotherProjectClearsTheSelectionToo() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path songB = exit.save.anotherProjectOnDisk("Song B");
            AtomicInteger resyncs = new AtomicInteger();
            MainController main = aSelectionOfSongA(exit, resyncs);

            runOnFx(() -> {
                exit.lifecycle().setOpenProjectPrompt(() -> songB);
                exit.lifecycle().onOpenProject();
            });
            exit.await(() -> exit.save.currentProjectIs(songB) && !exit.lifecycle().isProjectInstallationPending(),
                    "Song B installed");

            assertTheSelectionIsCleared(main, resyncs);
        }
    }

    /** The identity guard: the same project installed again keeps both halves of its selection. */
    @Test
    void theSameProjectInstalledAgainKeepsItsSelection() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            MainController main = aSelectionOfSongA(exit, new AtomicInteger());
            DawProject songA = exit.save.project.get();

            runOnFx(() -> replacePluginProject(main, songA));

            assertThat(onFx(() -> main.getSelectionModel().hasSelection())).as("the time range kept").isTrue();
            assertThat(onFx(() -> main.getSelectionModel().hasClipSelection())).as("the clip selection kept")
                    .isTrue();
        }
    }

    /**
     * A partial controller whose live project is Song A, with Song A's Vox
     * track holding a clip, that clip selected and the beats 1-4 selected, a
     * counting change listener installed after it, and the fixture's
     * {@code setProject} handing each project installed to
     * {@code replacePluginProject}, as the application's does.
     */
    private static MainController aSelectionOfSongA(ExitFixture exit, AtomicInteger resyncs) throws Exception {
        DawProject songA = exit.save.project.get();
        MainController main = onFx(() -> {
            MainController controller = new MainController();
            setField(controller, "project", songA);
            com.benesquivelmusic.daw.core.track.Track vox = songA.getTracks().getFirst();
            com.benesquivelmusic.daw.core.audio.AudioClip clip =
                    new com.benesquivelmusic.daw.core.audio.AudioClip("Take", 0.0, 4.0, null);
            vox.addClip(clip);
            songA.markClean();
            controller.getSelectionModel().selectClip(vox, clip);
            controller.getSelectionModel().setSelection(1.0, 4.0);
            controller.getSelectionModel().setSelectionChangeListener(resyncs::incrementAndGet);
            return controller;
        });
        assertThat(onFx(() -> main.getSelectionModel().hasSelection() && main.getSelectionModel().hasClipSelection()))
                .as("fixture: both halves selected").isTrue();
        exit.save.onProjectSet = next -> replacePluginProject(main, next);
        return main;
    }

    private static void assertTheSelectionIsCleared(MainController main, AtomicInteger resyncs) throws Exception {
        runOnFx(() -> {
            SelectionModel selection = main.getSelectionModel();
            assertThat(selection.hasSelection()).as("the time range is cleared").isFalse();
            assertThat(selection.getStartBeat()).isZero();
            assertThat(selection.getEndBeat()).isZero();
            assertThat(selection.hasClipSelection()).as("the clip selection is cleared").isFalse();
            assertThat(selection.getSelectedClips()).isEmpty();
            assertThat(selection.getFocusedTrack()).isNull();
            assertThat(selection.getTracksInClipSelection()).isEmpty();
        });
        assertThat(resyncs.get()).as("the menus were re-synced through the model's one listener").isPositive();
    }

    /**
     * Ruling R10's "the audio engine and device stay open and running": File
     * ▸ Close Project never reaches the window's disposal, which alone stops
     * the audio engine (its hand-off, {@code ExitWiringSourceTest}) — under
     * the application's own disposal; the exit after it does stop it.
     */
    @Test
    void closeProjectNeverStopsTheAudioEngineAndTheExitAfterItDoes() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            com.benesquivelmusic.daw.core.audio.AudioEngine engine =
                    mock(com.benesquivelmusic.daw.core.audio.AudioEngine.class);
            MainController main = onFx(() -> {
                MainController controller = new MainController();
                setField(controller, "audioEngine", engine);
                setField(controller, "exitCoordinator", exit.coordinator());
                controller.installPrimaryStage(exit.stage());
                return controller;
            });
            exit.windowDisposal = main::disposeWindowLifetime;

            exit.closeProject();
            exit.await(() -> exit.welcomes.get() == 1, "Close Project ended");
            exit.save.settle();

            verify(engine, never()).shutdown();
            assertThat(exit.disposals).as("no DISPOSE").hasValue(0);
            assertThat(onFx(() -> readField(main, "windowLifetimeDisposal"))).as("the disposal never ran").isNull();

            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);
            verify(engine, timeout(ExitFixture.GUARD.toMillis())).shutdown();
            assertThat(exit.disposals).hasValue(1);
        }
    }

    /**
     * Ruling R10's resets that the window's rebuild makes, which no test can
     * run (the main controller is never FXML-loaded in tests): the
     * replacement Close Project shares with New runs the lifecycle
     * controller's rebuild — in the application
     * {@code MainController.handleProjectRebuild}, which reconciles the
     * plug-in editors, builds a new mixer view on the meter feed whose
     * insert racks open their editors through the plug-in view controller,
     * replaces the mixer view (the navigation controller disposes the
     * replaced one, with its meters and its racks), builds a new transport
     * controller (the old one retired: {@code ProjectChangeWhileTakeIsWrittenTest}
     * pins that) and the next view-model generation, whose rebuild binds the
     * engine to the new project ({@code EngineBinderConformanceTest}). A
     * source pin: story 333's Resolution records these resets as pinned,
     * not asserted.
     */
    @Test
    void theWindowsRebuildBehindCloseProjectIsTheOneThatResetsTheWindow() throws java.io.IOException {
        String main = FxHalfOfASaveMakesNoFileSystemCallTest.code(java.nio.file.Files.readString(
                SourceScanSupport.locateDawAppModule().resolve(
                        "src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java"),
                java.nio.charset.StandardCharsets.UTF_8));
        String lifecycle = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                FxHalfOfASaveMakesNoFileSystemCallTest.read(FxHalfOfASaveMakesNoFileSystemCallTest.LIFECYCLE));

        assertThat(main.replaceAll("\\s+", " ")).as("the application's lifecycle rebuild is the window's own")
                .contains("() -> historyPanelController.rebuild(), this::handleProjectRebuild,");
        assertThat(FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "rebuildUI").code()).contains("deps.onProjectUIRebuild().run();");
        assertThat(FxHalfOfASaveMakesNoFileSystemCallTest.body("MainController.java", main, "handleProjectRebuild")
                .code().replaceAll("\\s+", " "))
                .contains("pluginViewController.reconcileGraph();", "newMixerView.setMeterFeed(meterFeed());",
                        "createTransportController();", "rebuildViewModels();",
                        "wirePluginRackEditors(newMixerView);", "viewNavigationController.setMixerView(newMixerView);");
        assertThat(FxHalfOfASaveMakesNoFileSystemCallTest.body("MainController.java", main, "wirePluginRackEditors")
                .code().replaceAll("\\s+", " "))
                .as("every rack of the rebuilt view opens its editor through the plug-in view controller (R10 row 11)")
                .contains("mixerView.setOnOpenInsertEditor(pluginViewController::openSlotEditor);");
    }

    private static void replacePluginProject(MainController main, DawProject next) {
        try {
            java.lang.reflect.Method replace = MainController.class.getDeclaredMethod("replacePluginProject",
                    DawProject.class);
            replace.setAccessible(true);
            replace.invoke(main, next);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object readField(MainController target, String name) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * The studio engineer's stage-7 S3 (ruling R10, "the same replacement New
     * performs, without creating a folder"): New and Close Project share one
     * replacement — the live project, the undo history, the rebuild and the
     * layout reset — so a later change to New's reset reaches Close Project
     * too. A source pin over the controller (a partial MC cannot be
     * FXML-loaded).
     */
    @Test
    void newAndCloseProjectInstallTheirUntitledProjectThroughOneReplacement() throws java.io.IOException {
        String lifecycle = FxHalfOfASaveMakesNoFileSystemCallTest.code(
                FxHalfOfASaveMakesNoFileSystemCallTest.read(FxHalfOfASaveMakesNoFileSystemCallTest.LIFECYCLE));
        String newProject = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java", lifecycle,
                "createNewProjectAfterClose").code().replaceAll("\\s+", " ");
        String closeProject = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java",
                lifecycle, "installUntitledAfterClose").code().replaceAll("\\s+", " ");
        String replacement = FxHalfOfASaveMakesNoFileSystemCallTest.body("ProjectLifecycleController.java",
                lifecycle, "installFreshUntitledProject").code().replaceAll("\\s+", " ");

        assertThat(newProject).contains("installFreshUntitledProject();").doesNotContain("deps.setProject()");
        assertThat(closeProject).contains("installFreshUntitledProject();").doesNotContain("deps.setProject()");
        assertThat(replacement).contains("deps.setProject().accept(untitled);", "projectReplaced(null);",
                "deps.setUndoManager().accept(new UndoManager());", "rebuildUI();",
                "deps.applyLayoutJson().accept(null);");
    }

    /** Sets a non-final field of the partial controller (never a final one: JEP 500). */
    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).as("%s is not final", name).isFalse();
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
