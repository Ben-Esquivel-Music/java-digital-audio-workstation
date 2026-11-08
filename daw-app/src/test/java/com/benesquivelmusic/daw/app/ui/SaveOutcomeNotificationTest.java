package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, a save's outcome in the UI (rulings F6, G9, G13 #6, #7, J3): a
 * failed save is the head's failure state and an ERROR notification, every
 * time — naming the safety checkpoint when one was written ("Your work is in
 * checkpoint 014 (14:32)."), saying a safety copy is still being written when
 * the save stopped waiting for it, and otherwise that none could be written —
 * with a Retry for an I/O failure, and, for any other failure, a read-only
 * project's included, the one action {@code Save As…} (story 333, the
 * studio engineer's ruling Q1/Q5). A lock another session took over shows no
 * ERROR: the takeover dialog asks instead (ruling R7). An outcome that
 * arrives after a project switch changes nothing of the project that
 * replaced it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SaveOutcomeNotificationTest {

    private static final String HEAD_FAILURE = "injected head write failure";

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> fixture.project.get().markDirty());
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void aFailedHeadWriteNamesItsSafetyCheckpointAndItsRetrySaves() throws Exception {
        IOException failure = new IOException(HEAD_FAILURE);
        fixture.publishes.failNext(SaveFlowFixture::isHead, failure);

        fixture.save();
        fixture.settle();

        List<Path> checkpoints = SaveFlowFixture.checkpointFiles(fixture.songA);
        assertThat(checkpoints).as("the safety copy").hasSize(1);
        CheckpointFileName name = CheckpointFileName.parse(checkpoints.getFirst().getFileName().toString())
                .orElseThrow();
        // The date goes first only when the checkpoint is of another day than the model's today (ruling K1).
        String day = name.stamp().toLocalDate().equals(onFx(fixture.progress::getToday)) ? ""
                : DateTimeFormatter.ofPattern("yyyy-MM-dd ", Locale.ROOT).format(name.stamp());
        String expected = "Save failed: " + HEAD_FAILURE + ". Your work is in checkpoint "
                + String.format(Locale.ROOT, "%03d", name.index()) + " (" + day
                + DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(name.stamp()) + ").";
        NotificationEntry error = fixture.notifications().getLast();
        assertThat(error.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(error.message()).isEqualTo(expected);
        assertThat(error.actionLabel()).as("an I/O failure can be retried").contains("Retry");
        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).as("the head's failure state").isFalse();
        assertThat(onFx(fixture.progress::getSaveFailureCause)).isSameAs(failure);
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("a failed save leaves the project dirty")
                .isTrue();

        runOnFx(() -> error.action().orElseThrow().run());
        fixture.settle();
        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).as("the Retry saved").isTrue();
        assertThat(onFx(fixture.progress::getSaveFailureCause)).isNull();
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
    }

    @Test
    void aSafetyCheckpointOfAnotherDayThanTheModelsTodayIsNamedWithItsDate() throws Exception {
        // The strip's today, as its poll publishes it, is a day the checkpoint is not of.
        fixture.progress.setToday(LocalDate.of(2000, 1, 1));
        fixture.pulse();
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException(HEAD_FAILURE));

        fixture.save();
        fixture.settle();

        CheckpointFileName name = CheckpointFileName.parse(
                SaveFlowFixture.checkpointFiles(fixture.songA).getFirst().getFileName().toString()).orElseThrow();
        assertThat(fixture.notifications().getLast().message())
                .isEqualTo("Save failed: " + HEAD_FAILURE + ". Your work is in checkpoint "
                        + String.format(Locale.ROOT, "%03d", name.index()) + " ("
                        + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(name.stamp()) + ").");
    }

    @Test
    void aCheckpointIsNamedWithItsDateOnlyWhenItIsOfAnotherDayThanToday() {
        Path checkpoint = Path.of("Song A", "checkpoints", "checkpoint-047-20261022T181000.daw");

        assertThat(ProjectLifecycleController.checkpointName(checkpoint, LocalDate.of(2026, 10, 22)))
                .as("a checkpoint of today: its time").isEqualTo("checkpoint 047 (18:10)");
        assertThat(ProjectLifecycleController.checkpointName(checkpoint, LocalDate.of(2026, 10, 23)))
                .as("of another day: its date first, as the strip and the warning give it")
                .isEqualTo("checkpoint 047 (2026-10-22 18:10)");
        assertThat(ProjectLifecycleController.checkpointName(Path.of("Song A", "notes.daw"),
                LocalDate.of(2026, 10, 22))).as("not a checkpoint's name").isEqualTo("the checkpoint notes.daw");
    }

    @Test
    void twoFailedSavesInARowEachShowOneError() throws Exception {
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException(HEAD_FAILURE));
        fixture.publishes.failNext(SaveFlowFixture::isCheckpoint, new IOException("injected checkpoint failure"));
        fixture.save();
        fixture.settle();
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException(HEAD_FAILURE));
        fixture.publishes.failNext(SaveFlowFixture::isCheckpoint, new IOException("injected checkpoint failure"));

        fixture.save();
        fixture.settle();

        String error = "Save failed: " + HEAD_FAILURE + ". No safety copy could be written.";
        assertThat(fixture.notifications()).as("an ERROR every time (ruling F6), one per save")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, error),
                        org.assertj.core.groups.Tuple.tuple(NotificationLevel.ERROR, error));
    }

    @Test
    void aSafetyCopyStillBeingWrittenWhenTheSaveStoppedWaitingIsSaidSo() throws Exception {
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException(HEAD_FAILURE));
        SaveFlowFixture.Publishes.Hold safetyCopy = fixture.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        try {
            fixture.save();
            safetyCopy.awaitHeld();
            // The save waits five seconds for its safety copy (ruling J3), then fails without naming one.
            fixture.awaitOnFx(() -> fixture.progress.getSaveFailureCause() != null,
                    "the save stopped waiting for its safety copy");
        } finally {
            safetyCopy.release();
        }

        NotificationEntry error = fixture.notifications().getLast();
        assertThat(error.message()).isEqualTo("Save failed: " + HEAD_FAILURE
                + ". A safety copy of your work is still being written.");
        assertThat(error.actionLabel()).contains("Retry");
        fixture.settle();
        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).as("the copy still lands").hasSize(1);
    }

    @Test
    void aFailedSaveWithNoSafetyCopySaysNoneCouldBeWritten() throws Exception {
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException(HEAD_FAILURE));
        fixture.publishes.failNext(SaveFlowFixture::isCheckpoint, new IOException("injected checkpoint failure"));

        fixture.save();
        fixture.settle();

        assertThat(fixture.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Save failed: " + HEAD_FAILURE + ". No safety copy could be written.");
        assertThat(fixture.notifications().getLast().actionLabel()).contains("Retry");
        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).isEmpty();
    }

    @Test
    void aLockAnotherSessionTookOverAsksTheTakeoverDialogInsteadOfShowingAnError() throws Exception {
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);

        fixture.save();
        fixture.settle();
        fixture.awaitOnFx(() -> fixture.takeoverHeaders.size() == 1, "the takeover dialog (ruling R7)");

        assertThat(fixture.takeoverHeaders).containsExactly("“Song A” was taken over by alice on Studio-PC (process 42).");
        assertThat(fixture.takeoverButtons).containsExactly(List.of("Save As…", "Not now"));
        assertThat(fixture.notifications()).as("no ERROR toast: the dialog replaces 331's").isEmpty();
        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).isFalse();
        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).as("no safety copy after a lost lock")
                .isEmpty();
    }

    @Test
    void aFailureThatIsNoInputOutputFailureOffersSaveAsAndItsActionOpensTheSaveAsChooser() throws Exception {
        runOnFx(() -> fixture.project.get().getTracks().getFirst().getMidiEffectChain()
                .add(new CaptureThatThrows(new IllegalStateException("the capture broke"))));

        fixture.save();
        fixture.settle();

        NotificationEntry error = fixture.notifications().getLast();
        assertThat(error.level()).isEqualTo(NotificationLevel.ERROR);
        assertThat(error.message()).startsWith("Save failed: the capture broke");
        assertThat(error.actionLabel()).as("ruling Q1: no Retry, the one action Save As…").contains("Save As…");
        fixture.saveAsTargets.cancel();
        runOnFx(() -> error.action().orElseThrow().run());
        fixture.awaitOnFx(() -> fixture.saveAsTargets.calls().size() == 1, "the Save As chooser");
        assertThat(fixture.saveAsTargets.calls().getFirst())
                .isEqualTo(new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song A copy"));
    }

    @Test
    void aReadOnlyProjectOffersSaveAsNotRetry() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        ProjectLockManager otherSession = new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L);
        otherSession.forceAcquire(songB);
        try {
            fixture.projectManager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
            assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).isTrue();
            assertThat(fixture.projectManager.isReadOnly()).as("fixture: opened read-only").isTrue();
            int before = fixture.notifications().size();

            fixture.save();
            fixture.settle();

            List<NotificationEntry> shown = fixture.notifications();
            assertThat(shown).hasSize(before + 1);
            assertThat(shown.getLast().message())
                    .isEqualTo("Save failed: Project was opened read-only; use Save As to a new location");
            assertThat(shown.getLast().actionLabel()).as("a retry cannot succeed: ruling Q5's Save As…")
                    .contains("Save As…");
            assertThat(onFx(fixture.progress::isLastSaveSucceeded)).isFalse();
            fixture.saveAsTargets.cancel();
            runOnFx(() -> shown.getLast().action().orElseThrow().run());
            fixture.awaitOnFx(() -> fixture.saveAsTargets.calls().size() == 1, "the Save As chooser");
            assertThat(fixture.saveAsTargets.calls().getFirst())
                    .as("ruling R6: the original's parent, \"<folder name> copy\"")
                    .isEqualTo(new SaveFlowFixture.SaveAsTargets.Call(workspace, "Song B copy"));
        } finally {
            otherSession.release();
        }
    }

    /** A MIDI effect whose descriptor read — made by the capture's walk — throws: a save that fails, not for I/O. */
    private static final class CaptureThatThrows implements com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin {

        private final RuntimeException failure;

        CaptureThatThrows(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor getDescriptor() {
            throw failure;
        }

        @Override
        public com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage[] process(
                com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage[] in, int sampleOffset,
                com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext ctx) {
            return in;
        }

        @Override public void initialize(com.benesquivelmusic.daw.sdk.plugin.PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }

    @Test
    void anOutcomeThatArrivesAfterAProjectSwitchChangesNothingOfTheNewProject() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.publishes.failNext(SaveFlowFixture::isHead, new IOException(HEAD_FAILURE));
        fixture.save();
        head.awaitHeld();

        // A load that passes no gate replaces Song A while its save writes.
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).isTrue();
        fixture.pulse();
        Instant songBSaved = fixture.projectManager.getCurrentProject().lastModified();
        long savesBefore = onFx(fixture.progress::getSavesSucceeded);

        head.release();
        fixture.settle();

        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).as("Song B has no head failure").isTrue();
        assertThat(onFx(fixture.progress::getSaveFailureCause)).isNull();
        assertThat(onFx(fixture.progress::getLastSaveTime)).isEqualTo(songBSaved);
        assertThat(onFx(fixture.progress::getSavesSucceeded)).isEqualTo(savesBefore);
        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).isTrue();
        assertThat(fixture.operationNames()).isEmpty();
        NotificationEntry last = fixture.notifications().getLast();
        assertThat(last.message()).as("the user still learns Song A was not saved")
                .startsWith("Saving Song A failed: " + HEAD_FAILURE);
        assertThat(last.actionLabel()).as("with no Retry, which would save Song B").isEmpty();
        for (Path checkpoint : SaveFlowFixture.checkpointFiles(songB)) {
            assertThat(Files.readString(checkpoint)).as("nothing of Song A in Song B's checkpoints")
                    .doesNotContain("Song A");
        }
        assertThat(Files.readString(songB.resolve("project.daw"))).doesNotContain("Song A");
    }
}
