package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.ProjectVM;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 294 — the project-lifecycle surface rides the shared wiring: it binds
 * {@link ProjectVM}'s {@code name} / {@code dirty} / {@code tracks}, and the Save
 * flow updates them through the §1.2 "one dirty bit" ({@code DawProject.markClean()})
 * rather than the retired imperative {@code MainController.projectDirty} boolean.
 *
 * <p>{@code MainController} is never FXML-loaded in tests, so this substitutes
 * in-process objects: a live {@link DawProject}, a {@link ProjectVM} bound to it, and
 * a {@link ProjectLifecycleController} wired to the same project via its
 * functional-dep record. Property writes are marshalled through {@link FxDispatcher}
 * and observed only after {@link #flushFx()} (mirrors {@code ProjectVMTest}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectSurfaceWiringTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    @Test
    void projectVmBindsNameDirtyAndTracks() throws Exception {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        ProjectVM vm = callOnFx(() -> new ProjectVM(project, new FxDispatcher()));
        try {
            assertThat(callOnFx(vm::getName)).as("name seeds from the project").isEqualTo("Song");
            assertThat(callOnFx(vm::isDirty)).as("a fresh project is clean").isFalse();
            assertThat(callOnFx(() -> List.copyOf(vm.getTracks()))).as("no tracks yet").isEmpty();

            project.setName("Renamed");
            flushFx();
            assertThat(callOnFx(vm::getName)).as("ProjectVM.name follows the project").isEqualTo("Renamed");

            project.markDirty();
            flushFx();
            assertThat(callOnFx(vm::isDirty)).as("ProjectVM.dirty follows markDirty()").isTrue();

            Track drums = project.createAudioTrack("Drums");
            flushFx();
            assertThat(callOnFx(() -> List.copyOf(vm.getTracks())))
                    .as("ProjectVM.tracks follows a TRACKS add").containsExactly(drums);
        } finally {
            disposeOnFx(vm);
        }
    }

    @Test
    void saveFlowClearsProjectVmDirtyThroughTheSharedDirtyApi() throws Exception {
        ProjectManager pm = dummyProjectManager();
        // A current project on disk so trySaveProject takes the save path (no temp-dir creation).
        pm.createProject("SaveTest", tempDir);

        DawProject project = new DawProject("SaveTest", AudioFormat.CD_QUALITY);
        ProjectVM vm = callOnFx(() -> new ProjectVM(project, new FxDispatcher()));
        try {
            // Simulate an edit — the §1.2 one-dirty-bit producer is now markDirty().
            project.markDirty();
            flushFx();
            assertThat(callOnFx(vm::isDirty))
                    .as("an edit marks the project dirty; ProjectVM mirrors it").isTrue();

            // Build the lifecycle surface wired to this project, then Save.
            ProjectLifecycleController controller = callOnFx(() -> new ProjectLifecycleController(
                    pm, new SessionInterchangeController(), new NotificationBar(),
                    new Label(), new Label(), new BorderPane(), new VBox(),
                    depsFor(project), new ProjectArchiver()));
            callOnFx(() -> {
                controller.onSaveProject();
                return null;
            });
            flushFx();

            assertThat(callOnFx(vm::isDirty))
                    .as("the Save flow clears ProjectVM.dirty via DawProject.markClean() — the §1.2 "
                            + "one dirty bit (story 294), not an imperative projectDirty boolean")
                    .isFalse();
            assertThat(project.isDirty())
                    .as("and the project itself is genuinely clean (the producer side)").isFalse();
        } finally {
            disposeOnFx(vm);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Functional-dep record wired to {@code project}; only project + captureLayoutJson matter to Save. */
    private static ProjectLifecycleController.Deps depsFor(DawProject project) {
        AtomicReference<DawProject> p = new AtomicReference<>(project);
        AtomicReference<UndoManager> um = new AtomicReference<>(new UndoManager());
        return new ProjectLifecycleController.Deps(
                p::get, p::set, um::get, um::set,
                () -> { }, () -> { }, mixerView -> { }, () -> null, json -> { });
    }

    private static ProjectManager dummyProjectManager() {
        Preferences prefs = Preferences.userRoot().node("daw-test-psw-" + System.nanoTime());
        return new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                new RecentProjectsStore(prefs));
    }

    private static void disposeOnFx(ProjectVM vm) throws InterruptedException {
        callOnFx(() -> {
            vm.dispose();
            return null;
        });
    }

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

    private static <T> T callOnFx(FxCallable<T> work) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Throwable t = thrown.get();
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new AssertionError("FX work threw a checked exception", t);
        }
        return result.get();
    }

    @FunctionalInterface
    private interface FxCallable<T> {
        T call();
    }
}
