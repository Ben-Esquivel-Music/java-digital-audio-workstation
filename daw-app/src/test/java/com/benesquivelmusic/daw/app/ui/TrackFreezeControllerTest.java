package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.UndoHistoryDirtyBinder;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackFreezeService;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TrackFreezeController}: the snowflake tooltip and
 * cache-state helpers, and the generation the freeze workflow runs against.
 * The controller reads the project and undo manager from live suppliers,
 * once per gesture; the tests swap what the suppliers return the way an
 * Open, New or snapshot restore does, without loading {@code MainController}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackFreezeControllerTest {

    private static final long STATUS_TIMEOUT_SECONDS = 15;

    @Test
    void tooltipForUnfrozenTrackIsEmpty() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        UndoManager undoManager = new UndoManager();
        Track track = new Track("t", TrackType.AUDIO);
        TrackFreezeController c = new TrackFreezeController(
                () -> project, () -> undoManager, null, t -> {}, () -> {}, (m, i) -> {});

        assertThat(c.tooltipFor(track)).isEmpty();
    }

    @Test
    void tooltipForFrozenTrackWithoutRecordIsGeneric() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        UndoManager undoManager = new UndoManager();
        Track track = freezeFixtureTrack(project);
        TrackFreezeController c = new TrackFreezeController(
                () -> project, () -> undoManager, null, t -> {}, () -> {}, (m, i) -> {});

        assertThat(c.tooltipFor(track))
                .isEqualTo("Frozen — pre-rendered audio is in use");
    }

    @Test
    void cacheStateIsEmptyBeforeAnyFreeze() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        UndoManager undoManager = new UndoManager();
        Track track = new Track("t", TrackType.AUDIO);
        TrackFreezeController c = new TrackFreezeController(
                () -> project, () -> undoManager, null, t -> {}, () -> {}, (m, i) -> {});

        assertThat(c.cacheState(track)).isEmpty();
        assertThat(c.cacheState(null)).isEmpty();
    }

    @Test
    void freezeIsNoOpForNullOrAlreadyFrozenTrack() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        UndoManager undoManager = new UndoManager();
        Track track = freezeFixtureTrack(project);
        AtomicReference<String> lastStatus = new AtomicReference<>();
        TrackFreezeController c = new TrackFreezeController(
                () -> project, () -> undoManager, null, t -> {}, () -> {},
                (m, i) -> lastStatus.set(m));

        c.freezeTrack(null);
        assertThat(lastStatus.get()).isEqualTo("No track selected to freeze");

        c.freezeTrack(track);
        assertThat(lastStatus.get()).startsWith("Track is already frozen");
    }

    @Test
    void unfreezeIsNoOpForNullOrUnfrozenTrack() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        UndoManager undoManager = new UndoManager();
        Track track = new Track("t", TrackType.AUDIO);
        AtomicReference<String> lastStatus = new AtomicReference<>();
        TrackFreezeController c = new TrackFreezeController(
                () -> project, () -> undoManager, null, t -> {}, () -> {},
                (m, i) -> lastStatus.set(m));

        c.unfreezeTrack(null);
        assertThat(lastStatus.get()).isEqualTo("No track selected to unfreeze");

        c.unfreezeTrack(track);
        assertThat(lastStatus.get()).startsWith("Track is not frozen");
    }

    // ── The generation a gesture runs against ───────────────────────────────

    @Test
    void aFreezeAfterAnotherProjectIsOpenedRunsOnTheNewProjectAndItsUndoManager() throws Exception {
        Session session = new Session();
        UndoManager oldManager = session.undoManager.get();
        DawProject newProject = new DawProject("new", AudioFormat.STUDIO_QUALITY);
        UndoManager newManager = new UndoManager();
        Track vox = audioTrack(newProject, "Vox");
        newProject.markClean();
        UndoHistoryDirtyBinder binder = new UndoHistoryDirtyBinder(newManager, newProject);
        try {
            session.open(newProject, newManager);

            onFx(() -> session.controller.freezeTrack(vox));

            assertThat(session.awaitStatus()).isEqualTo("Frozen: Vox");
            assertThat(vox.isFrozen()).isTrue();
            assertThat(newManager.canUndo()).as("the freeze is an entry on the new manager").isTrue();
            assertThat(newProject.isDirty())
                    .as("the new generation's binder marks the new project dirty")
                    .isTrue();
            assertThat(oldManager.canUndo()).as("the old manager is unchanged").isFalse();
            assertThat(session.trackRefreshes.get()).isEqualTo(1);
        } finally {
            binder.dispose();
        }
    }

    @Test
    void aCancelledBatchRollsBackOnTheManagerItStartedOnEvenAfterAnotherProjectIsOpened() throws Exception {
        Session session = new Session();
        DawProject oldProject = session.project.get();
        UndoManager oldManager = session.undoManager.get();
        Track drums = audioTrack(oldProject, "Drums");
        Track bass = audioTrack(oldProject, "Bass");
        DawProject newProject = new DawProject("new", AudioFormat.STUDIO_QUALITY);
        UndoManager newManager = new UndoManager();
        RecordingAction newEdit = new RecordingAction();
        newManager.execute(newEdit);
        AtomicBoolean hookRan = new AtomicBoolean();

        onFx(() -> {
            session.controller.freezeTracks(List.of(drums, bass));
            Stage indicator = showingStageTitled("Freezing 2 tracks…");
            Label detail = labelShowing(indicator, "Preparing…");
            Button cancel = cancelButton(indicator);
            // One-shot hook inside the batch step, on the FX thread that runs
            // it: once the first track is frozen, the user cancels and another
            // project (whose manager holds one entry) is opened, before the
            // worker rolls the partial batch back.
            ChangeListener<String> hook = new ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends String> observable,
                                    String oldText, String newText) {
                    if (newText != null && newText.startsWith("Frozen (1/2)")) {
                        detail.textProperty().removeListener(this);
                        cancel.fire();
                        session.open(newProject, newManager);
                        hookRan.set(true);
                    }
                }
            };
            detail.textProperty().addListener(hook);
        });

        assertThat(session.awaitStatus()).isEqualTo("Freeze discarded — project closed");
        assertThat(hookRan).as("guard: the hook cancelled after the first track").isTrue();
        assertThat(drums.isFrozen()).as("the partial batch is rolled back").isFalse();
        assertThat(bass.isFrozen()).isFalse();
        assertThat(oldManager.canUndo()).as("the old manager's batch was undone").isFalse();
        assertThat(oldManager.canRedo()).as("and its redo entry cleared").isFalse();
        assertThat(newEdit.undoCount).as("the new project's last edit is untouched").isZero();
        assertThat(newManager.canUndo()).as("and still undoable").isTrue();
        assertThat(session.batchRefreshes.get()).isZero();
    }

    @Test
    void aFreezeThatCompletesAfterAnotherProjectIsOpenedIsDiscarded() throws Exception {
        Session session = new Session();
        DawProject oldProject = session.project.get();
        UndoManager oldManager = session.undoManager.get();
        Track vox = audioTrack(oldProject, "Vox");
        DawProject newProject = new DawProject("new", AudioFormat.STUDIO_QUALITY);
        UndoManager newManager = new UndoManager();
        newProject.markClean();
        UndoHistoryDirtyBinder binder = new UndoHistoryDirtyBinder(newManager, newProject);
        try {
            onFx(() -> {
                session.controller.freezeTrack(vox);
                // The worker needs this thread to execute, so it cannot have
                // finished yet: the user opens another project mid-render.
                session.open(newProject, newManager);
            });

            assertThat(session.awaitStatus()).isEqualTo("Freeze discarded — project closed");
            assertThat(session.trackRefreshes.get()).as("no refresh for the closed project").isZero();
            assertThat(newProject.isDirty()).as("nothing marks the new project dirty").isFalse();
            assertThat(newManager.canUndo()).isFalse();
            assertThat(session.controller.cacheState(vox)).as("no cache record kept").isEmpty();
            assertThat(vox.isFrozen()).as("the freeze finished against the project it started on").isTrue();
            assertThat(oldManager.canUndo()).isTrue();
        } finally {
            binder.dispose();
        }
    }

    @Test
    void anUnfreezeAfterAnotherProjectIsOpenedRunsOnTheNewProjectAndItsUndoManager() throws Exception {
        Session session = new Session();
        UndoManager oldManager = session.undoManager.get();
        DawProject newProject = new DawProject("new", AudioFormat.STUDIO_QUALITY);
        UndoManager newManager = new UndoManager();
        Track single = freezeFixtureTrack(newProject);
        Track first = freezeFixtureTrack(newProject);
        Track second = freezeFixtureTrack(newProject);
        session.open(newProject, newManager);

        onFx(() -> session.controller.unfreezeTrack(single));
        assertThat(session.awaitStatus()).isEqualTo("Unfrozen: t");
        assertThat(single.isFrozen()).isFalse();
        assertThat(newManager.undoSize()).isEqualTo(1);

        onFx(() -> session.controller.unfreezeTracks(List.of(first, second)));
        assertThat(session.awaitStatus()).isEqualTo("Unfrozen 2 track(s)");
        assertThat(first.isFrozen()).isFalse();
        assertThat(second.isFrozen()).isFalse();
        assertThat(newManager.undoSize()).isEqualTo(2);
        assertThat(oldManager.canUndo()).as("the old manager is unchanged").isFalse();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /**
     * One controller over swappable project / undo-manager holders, as
     * {@code MainController} builds it: {@link #open} swaps what the
     * suppliers return, the way an Open or snapshot restore does.
     */
    private static final class Session {

        final AtomicReference<DawProject> project =
                new AtomicReference<>(new DawProject("old", AudioFormat.STUDIO_QUALITY));
        final AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        final AtomicInteger trackRefreshes = new AtomicInteger();
        final AtomicInteger batchRefreshes = new AtomicInteger();
        final TrackFreezeController controller = new TrackFreezeController(
                project::get, undoManager::get, null,
                _ -> trackRefreshes.incrementAndGet(),
                batchRefreshes::incrementAndGet,
                (message, _) -> statuses.add(message));

        void open(DawProject next, UndoManager nextManager) {
            project.set(next);
            undoManager.set(nextManager);
        }

        /**
         * The next status, once the FX runnable that reported it has also
         * finished (so a refresh it ran, or skipped, is visible).
         */
        String awaitStatus() throws Exception {
            String status = statuses.poll(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(status).as("a status was reported within %d s", STATUS_TIMEOUT_SECONDS).isNotNull();
            onFx(() -> { });
            return status;
        }
    }

    /** An undoable edit that counts its undos. */
    private static final class RecordingAction implements UndoableAction {

        volatile int undoCount;

        @Override public String description() { return "New project edit"; }
        @Override public void execute() { }
        @Override public void undo() { undoCount++; }
    }

    /** An unfrozen audio track with half a second of audio, added to {@code project}. */
    private static Track audioTrack(DawProject project, String name) {
        Track track = new Track(name, TrackType.AUDIO);
        AudioClip clip = new AudioClip("c", 0.0, 1.0, null);
        int frames = (int) Math.round(60.0 / 120.0 * project.getFormat().sampleRate());
        clip.setAudioData(new float[project.getFormat().channels()][frames]);
        track.addClip(clip);
        project.addTrack(track);
        return track;
    }

    /**
     * Builds a fully-set-up audio track and freezes it via the core
     * service so the {@link Track#isFrozen()} flag is genuine without
     * needing access to the package-private mutators.
     */
    private static Track freezeFixtureTrack(DawProject project) {
        Track track = audioTrack(project, "t");
        TrackFreezeService.freeze(
                track, project.getMixerChannelForTrack(track),
                (int) project.getFormat().sampleRate(), 120.0,
                project.getFormat().channels());
        return track;
    }

    private static Stage showingStageTitled(String title) {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> title.equals(stage.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no progress window titled " + title));
    }

    private static Label labelShowing(Stage stage, String text) {
        for (Node node : stage.getScene().getRoot().lookupAll(".label")) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return label;
            }
        }
        throw new AssertionError("no label showing " + text);
    }

    private static Button cancelButton(Stage stage) {
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && "Cancel".equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("no Cancel button");
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("the FX action completed").isTrue();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
