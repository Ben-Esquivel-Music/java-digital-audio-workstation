package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "One-protocol" (design §4.1; its source half is
 * {@link ExitWiringSourceTest}): the window's close box, File ▸ Exit and File
 * ▸ Close Project — the menu items fired through a real
 * {@link DawMenuBarController} whose actions delegate as
 * {@code MainController}'s do — all drive the one coordinator. An exit runs
 * INTERCEPT, GUARD, FLUSH, CLOSING_CHECKPOINT, SEAL, RELEASE, DISPOSE and
 * HIDE once each, in Persistence Integrity book §5.4's order; the window's
 * disposal runs once, after RELEASE's outcome was published, and the
 * terminal exit once, after HIDE's; a second close box while the exit runs
 * adds no step. Close Project runs up to RELEASE and installs the Untitled
 * project, with no DISPOSE and no HIDE. A window hidden without the protocol
 * disposes nothing: the primary stage has no hidden handler; and the
 * application's stop after the protocol ran disposes nothing again.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ExitProtocolOrderTest {

    private static final List<ExitCoordinator.ExitStep> AN_EXIT = List.of(ExitCoordinator.ExitStep.INTERCEPT,
            ExitCoordinator.ExitStep.GUARD, ExitCoordinator.ExitStep.FLUSH, ExitCoordinator.ExitStep.CLOSING_CHECKPOINT,
            ExitCoordinator.ExitStep.SEAL, ExitCoordinator.ExitStep.RELEASE, ExitCoordinator.ExitStep.DISPOSE,
            ExitCoordinator.ExitStep.HIDE);

    @TempDir
    Path workspace;

    @Test
    void theCloseBoxRunsEveryStepOnceInOrderDisposingAfterReleaseAndExitingAfterHide() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            AtomicInteger terminalExitsAtHide = new AtomicInteger(-1);
            AtomicInteger disposalsAtRelease = new AtomicInteger(-1);
            exit.onStep = outcome -> {
                if (outcome.step() == ExitCoordinator.ExitStep.HIDE) {
                    terminalExitsAtHide.set(exit.terminalExits.get());
                } else if (outcome.step() == ExitCoordinator.ExitStep.RELEASE) {
                    disposalsAtRelease.set(exit.disposals.get());
                }
            };
            exit.edit("Bass");
            exit.save.promptAnswer = "Save and close";
            SaveFlowFixture.Publishes.Hold flush = exit.save.publishes.holdNext(SaveFlowFixture::isHead);
            try {
                exit.closeBox();
                flush.awaitHeld();
                exit.closeBox();
                exit.closeBox();
            } finally {
                flush.release();
            }
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(exit.steps).extracting(ExitCoordinator.StepOutcome::step)
                    .as("§5.4's order, each step once — the second and third close box merged")
                    .containsExactlyElementsOf(AN_EXIT);
            assertThat(exit.stepNames()).contains("FLUSH DONE", "DISPOSE DONE", "HIDE DONE");
            assertThat(disposalsAtRelease).as("RELEASE was published before the disposal ran").hasValue(0);
            assertThat(exit.disposals).as("the window's disposal ran once").hasValue(1);
            assertThat(exit.events).containsSubsequence("step RELEASE DONE", "dispose", "step DISPOSE DONE",
                    "step HIDE DONE");
            assertThat(terminalExitsAtHide).as("HIDE was published before the terminal exit").hasValue(0);
            assertThat(exit.terminalExits).hasValue(1);
            assertThat(onFx(() -> exit.stage().isShowing())).isFalse();
        }
    }

    /**
     * One-protocol's last case (design §4.1, story 333's stage 8):
     * {@code Application.stop()} after HIDE — whose application-stop
     * fallback never runs once the protocol ran — leaves the window's
     * disposal at one and runs no step.
     */
    @Test
    void anApplicationStopAfterHideDisposesNothingAgainAndRunsNoStep() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);
            List<String> steps = exit.stepNames();

            ExitFixture.awaitStopped(exit.stopTheApplication());

            assertThat(exit.disposals).as("the window's disposal ran once").hasValue(1);
            assertThat(exit.stepNames()).as("and nothing more ran").isEqualTo(steps);
            assertThat(exit.terminalExits).hasValue(1);
        }
    }

    @Test
    void fileExitFromTheMenuRunsTheSameProtocolAsTheCloseBox() throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-one-protocol-" + UUID.randomUUID());
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
            Menu file = fileMenuDelegatingAsTheApplicationDoes(exit, host, preferences);

            runOnFx(() -> item(file, "Exit").fire());
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(host.exitCalls).isEqualTo(1);
            assertThat(exit.steps).extracting(ExitCoordinator.StepOutcome::step).containsExactlyElementsOf(AN_EXIT);
            assertThat(exit.steps.getFirst().detail()).as("File ▸ Exit is the exit door").isEqualTo("Exit");
            assertThat(exit.disposals).hasValue(1);
            assertThat(exit.terminalExits).hasValue(1);
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    void closeProjectFromTheMenuRunsUpToReleaseAndInstallsTheUntitledProjectWithNoDisposeAndNoHide()
            throws Exception {
        Preferences preferences = Preferences.userRoot().node("daw-one-protocol-" + UUID.randomUUID());
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
            Menu file = fileMenuDelegatingAsTheApplicationDoes(exit, host, preferences);

            runOnFx(() -> item(file, "Close Project").fire());
            exit.await(() -> exit.welcomes.get() == 1, "the Untitled project installed with the Welcome window");

            assertThat(host.closeProjectCalls).isEqualTo(1);
            assertThat(exit.steps).extracting(ExitCoordinator.StepOutcome::step)
                    .containsExactlyElementsOf(AN_EXIT.subList(0, 6));
            assertThat(exit.steps.getFirst().detail()).isEqualTo("Close Project");
            assertThat(exit.disposals).as("no DISPOSE: the window stays").hasValue(0);
            assertThat(exit.terminalExits).hasValue(0);
            assertThat(onFx(() -> exit.stage().isShowing())).isTrue();
            assertThat(onFx(() -> exit.save.project.get().getName())).isEqualTo("Untitled Project");
            assertThat(exit.save.projectManager.getCurrentProject()).isNull();
        } finally {
            preferences.removeNode();
        }
    }

    /**
     * The stage-7 review N3: the coordinator's door is installed once. The
     * application installing the primary stage again over the same stage —
     * as {@code MainController.installPrimaryStage} does on a partial
     * controller over the fixture, which installed it first — adds no second
     * filter, so one close box asks the protocol once: no second request is
     * merged into the run it started. Another stage is refused.
     */
    @Test
    void theCoordinatorsDoorIsInstalledOnceSoOneCloseBoxAsksOnce() throws Exception {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(ExitCoordinator.class.getName());
        java.util.logging.Level level = log.getLevel();
        List<String> merged = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler recorder = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord entry) {
                if (entry.getMessage() != null && entry.getMessage().contains("merged into the running exit")) {
                    merged.add(entry.getMessage());
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        recorder.setLevel(java.util.logging.Level.ALL);
        log.setLevel(java.util.logging.Level.FINE);
        log.addHandler(recorder);
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            onFx(() -> {
                MainController controller = new MainController();
                setField(controller, "exitCoordinator", exit.coordinator());
                controller.installPrimaryStage(exit.stage());
                return controller;
            });
            runOnFx(() -> exit.coordinator().installOn(exit.stage()));

            exit.closeBox();
            exit.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(merged).as("one close box, one request").isEmpty();
            assertThat(exit.stepNames()).filteredOn(name -> name.startsWith("INTERCEPT")).containsExactly(
                    "INTERCEPT DONE");
            assertThat(exit.terminalExits).hasValue(1);
            javafx.stage.Stage another = onFx(javafx.stage.Stage::new);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runOnFx(() ->
                    exit.coordinator().installOn(another))).hasRootCauseInstanceOf(IllegalStateException.class);
        } finally {
            log.removeHandler(recorder);
            log.setLevel(level);
        }
    }

    /**
     * The stage-7 review N10: an application whose lifecycle controller came
     * without the closer or Save As fails to build its exit protocol — it
     * never leaves every door without one.
     */
    @Test
    void anApplicationWithoutItsClosersFailsToBuildTheExitProtocol() throws Exception {
        MainController main = onFx(MainController::new);
        java.lang.reflect.Method create = MainController.class.getDeclaredMethod("createExitCoordinator");
        create.setAccessible(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runOnFx(() -> {
            try {
                create.invoke(main);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        })).hasRootCauseInstanceOf(NullPointerException.class);
        assertThat(onFx(() -> readField(main, "exitCoordinator"))).isNull();
    }

    @Test
    void theApplicationsPrimaryStageHiddenWithoutTheProtocolDisposesNothing() throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            MainController main = onFx(() -> {
                MainController controller = new MainController();
                setField(controller, "exitCoordinator", exit.coordinator());
                controller.installPrimaryStage(exit.stage());
                return controller;
            });

            runOnFx(() -> {
                assertThat(exit.stage().getOnHidden()).as("the primary stage has no hidden handler").isNull();
                exit.stage().hide();
            });
            exit.save.settle();

            assertThat(exit.steps).as("a hide fires no close request: no protocol ran").isEmpty();
            assertThat(exit.disposals).hasValue(0);
            assertThat(onFx(() -> readField(main, "windowLifetimeDisposal")))
                    .as("the application's disposal never ran").isNull();
        }
    }

    /**
     * The File menu of a real menu bar controller whose Exit and Close
     * Project delegate as {@code MainController}'s menu actions do — one
     * request of the one coordinator each — with Close Project's enablement
     * the coordinator's.
     */
    private static Menu fileMenuDelegatingAsTheApplicationDoes(ExitFixture exit,
            DawMenuBarControllerTest.StubHost host, Preferences preferences) throws Exception {
        host.exitAction = () -> exit.coordinator().request(new ExitCoordinator.CloseDoor.FileExit());
        host.closeProjectAction = () -> exit.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject());
        return onFx(() -> {
            DawMenuBarController menus = new DawMenuBarController(host, new KeyBindingManager(preferences),
                    () -> false, () -> false, () -> false, () -> false, () -> false, () -> false, () -> false,
                    exit.coordinator()::closeProjectAvailable);
            return menus.build().getMenus().getFirst();
        });
    }

    private static MenuItem item(Menu menu, String text) {
        return menu.getItems().stream().filter(candidate -> text.equals(candidate.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " in the File menu"));
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

    private static Object readField(MainController target, String name) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
