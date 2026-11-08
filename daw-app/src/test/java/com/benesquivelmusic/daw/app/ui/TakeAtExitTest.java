package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecoveryTemps;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryPlan;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryScanner;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Take-at-exit" (story 323's hand-off; the studio
 * engineer's rulings R1, R2, R4; ruling 4 Q6.5), for the window's close box,
 * File ▸ Exit, File ▸ Close Project, the close-then-continue primitive and
 * the application-stop fallback, with a real take: recording, and with its
 * Stop pending while its capture thread is held mid-pass, not terminated.
 * The doors' recording asks the confirmation first; no FLUSH, SEAL, RELEASE
 * or HIDE runs while the take is being written; once its publication turn
 * ran, the prompt says the changes include it, and Save and close puts the
 * take's clip into {@code project.daw} — the primitive's continuation
 * receiving {@code Closed} after that. The fallback asks nothing: it stops
 * the recording at once, runs no CLOSING_CHECKPOINT, SEAL or RELEASE until
 * the take was published, then keeps the take in its closing checkpoint —
 * {@code project.daw} byte-identical — which the next open offers; past its
 * 30 s bound it skips them all, the lock left and the journal unsealed.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TakeAtExitTest {

    /** The doors that may ask. */
    enum Door { CLOSE_BOX, FILE_EXIT, CLOSE_PROJECT, CLOSE_THEN_CONTINUE }

    /** The close-then-continue primitive's switch. */
    private static final String SWITCH = "Switch Workspace";

    /** The application-stop fallback's deadline: ruling R4's 30 s. */
    private static final Duration DEADLINE = ProjectManager.EXIT_WRITE_WAIT;

    /** The take's state when the door is asked. */
    enum TakeState { RECORDING, STOP_PENDING }

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private RecordingInFlightFixture recording;
    private final CaptureThreadHold hold = new CaptureThreadHold();
    private java.util.concurrent.CompletableFuture<ExitCoordinator.CloseOutcome> continuation;
    private java.util.concurrent.CompletableFuture<Void> stopped;
    // Held strongly: the log manager keeps a logger only weakly.
    private final java.util.logging.Logger coordinatorLog =
            java.util.logging.Logger.getLogger(ExitCoordinator.class.getName());
    private final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.logging.Handler recordingWarnings = new java.util.logging.Handler() {
        @Override
        public void publish(java.util.logging.LogRecord logRecord) {
            if (logRecord.getLevel() == java.util.logging.Level.WARNING) {
                warnings.add(logRecord.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    static Stream<Arguments> doorsAndStates() {
        List<Arguments> all = new ArrayList<>();
        for (Door door : Door.values()) {
            for (TakeState state : TakeState.values()) {
                all.add(Arguments.of(door, state));
            }
        }
        return all.stream();
    }

    @BeforeEach
    void aTakeOnAnArmedTrack() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.save();
        fixture.save.settle();
        recording = RecordingInFlightFixture.audio(fixture.save.project.get());
        fixture.transport = recording.controller();
        runOnFx(() -> hold.installOn(recording.controller()));
        coordinatorLog.addHandler(recordingWarnings);
    }

    @AfterEach
    void close() throws Exception {
        hold.release();
        try {
            if (stopped != null && !stopped.isDone()) {
                // A failed assertion left the fallback waiting: its deadline lets it go.
                fixture.fire(DEADLINE);
                ExitFixture.awaitStopped(stopped);
            }
            fixture.transport = null;
            recording.close();
        } finally {
            coordinatorLog.removeHandler(recordingWarnings);
            fixture.close();
        }
    }

    @ParameterizedTest
    @MethodSource("doorsAndStates")
    void noFlushSealReleaseOrHideRunsUntilTheTakeWasPublishedAndSaveAndCloseKeepsIt(Door door, TakeState state)
            throws Exception {
        com.benesquivelmusic.daw.core.project.DawProject song = fixture.save.project.get();
        // The take's publication, recorded on the FX thread in the order of the steps (the stage-6 review N4).
        runOnFx(() -> recording.controller().takeBeingWrittenProperty().addListener((_, was, is) -> {
            if (was && !is) {
                fixture.events.add("take published");
            }
        }));
        recording.start();
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));
        if (state == TakeState.STOP_PENDING) {
            runOnFx(() -> recording.controller().stop());
            assertThat(onFx(() -> recording.controller().isTakeBeingWritten())).as("fixture: the Stop is pending")
                    .isTrue();
        }
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.save.promptAnswer = "Save and close";

        ask(door);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.INTERCEPT)
                && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the take wait");

        assertThat(fixture.stopTakePrompts).as("the confirmation comes first, only while recording")
                .hasSize(state == TakeState.RECORDING ? 1 : 0);
        assertThat(onFx(() -> recording.controller().isTakeBeingWritten())).isTrue();
        fixture.save.settle();
        assertThat(fixture.stepNames()).as("nothing past INTERCEPT while the take is being written")
                .containsExactly("INTERCEPT DONE");
        assertThat(fixture.unsavedPrompts).isEmpty();

        hold.release();
        if (door == Door.CLOSE_PROJECT) {
            fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project"), "Untitled");
        } else if (door == Door.CLOSE_THEN_CONTINUE) {
            assertThat(continuation.get(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the primitive's one outcome").isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
            assertThat(fixture.events).as("Closed after the take was saved into project.daw")
                    .containsSubsequence("step FLUSH DONE", "continuation Closed");
            assertThat(fixture.continuations).hasSize(1);
            assertThat(fixture.welcomes).as("no Welcome window for the primitive").hasValue(0);
        } else {
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        }

        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                .as("ruling R2's take line: the take was waited out and published a clip")
                .startsWith("Your changes include the take you just recorded.\n"));
        String head = Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8);
        Track vox = onFx(() -> song.getTracks().stream().filter(Track::isArmed).findFirst().orElseThrow());
        List<AudioClip> clips = onFx(() -> List.copyOf(vox.getClips()));
        assertThat(clips).as("the take published its clip").hasSize(1);
        String clipFile = Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString();
        assertThat(head).as("the take is in project.daw after Save and close").contains(clipFile);
        int published = fixture.events.lastIndexOf("take published");
        assertThat(published).as("fixture: the take's publication was recorded").isNotNegative();
        assertThat(fixture.events.indexOf("step GUARD DONE")).as("GUARD ended only once the take was published")
                .isGreaterThan(published);
    }

    /**
     * The application-stop fallback (ruling R4; ruling 4 Q6.5) × the take
     * recording and its Stop pending: nothing asked — a recording is stopped
     * at once — and no CLOSING_CHECKPOINT, SEAL or RELEASE until the take
     * was published; then the take's clip is in the closing checkpoint,
     * {@code project.daw} is byte-identical, and the next open offers to
     * recover from that checkpoint.
     */
    @ParameterizedTest
    @EnumSource(TakeState.class)
    void theApplicationStopWaitsForTheTakeAndKeepsItInTheClosingCheckpointNotInProjectDaw(TakeState state)
            throws Exception {
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        com.benesquivelmusic.daw.core.project.DawProject song = fixture.save.project.get();
        aTakeIn(state);

        stopped = fixture.stopTheApplication();
        fixture.await(() -> recording.controller().isTakeBeingWritten()
                && !fixture.timers.pending(DEADLINE).isEmpty(), "the fallback's wait for the take");
        fixture.save.settle();
        assertThat(fixture.stopTakePrompts).as("the fallback asks nothing").isEmpty();
        assertThat(fixture.steps).as("no step while the take is being written").isEmpty();

        hold.release();
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.stepNames()).containsExactly("GUARD DONE", "CLOSING_CHECKPOINT DONE", "SEAL DONE",
                "RELEASE DONE", "DISPOSE DONE");
        Track vox = onFx(() -> song.getTracks().stream().filter(Track::isArmed).findFirst().orElseThrow());
        List<AudioClip> clips = onFx(() -> List.copyOf(vox.getClips()));
        assertThat(clips).as("the take published its clip").hasSize(1);
        String clipFile = Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString();
        Path closing = SaveFlowFixture.checkpointFiles(fixture.songA).getLast();
        assertThat(Files.readString(closing, StandardCharsets.UTF_8)).as("the take is in the closing checkpoint")
                .contains(clipFile);
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("project.daw never written (R4)")
                .isEqualTo(head);
        ProjectContext context = ProjectContext.forProject(fixture.songA);
        assertThat(JournalState.needsRecovery(context)).as("the next open offers to recover it").isTrue();
        assertThat(new RecoveryScanner().plan(context, RecoveryTemps.inspect(fixture.songA), true)).get()
                .extracting(RecoveryPlan::recovery).isInstanceOfSatisfying(RecoveryPlan.ReplayJournal.class,
                        replay -> assertThat(replay.base()).isInstanceOfSatisfying(RecoveryPlan.CheckpointCapture.class,
                                capture -> assertThat(capture.file().getFileName()).isEqualTo(closing.getFileName())));
    }

    /**
     * The take still being written 30 s after the stop (ruling R4's bound):
     * the closing checkpoint, the seal and the release skipped — the lock
     * left, the journal unsealed — and ruling 4 Q6.3's L4; in each state of
     * the take (ruling 4 Q6.5; the stage-8 review S7 — the publication-held
     * state is {@code TakeAtExitInEveryRecordingStateTest}'s).
     */
    @ParameterizedTest
    @EnumSource(TakeState.class)
    void aTakeStillBeingWrittenAtTheApplicationStopsDeadlineSkipsTheTailAsAfterACrash(TakeState state)
            throws Exception {
        aTakeIn(state);

        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the fallback's wait for the take");
        fixture.fire(DEADLINE);
        ExitFixture.awaitStopped(stopped);

        assertThat(warnings).contains("The take was still being written 30 seconds after the application stopped;"
                + " the closing checkpoint, the seal and the release are skipped, as after a crash.");
        assertThat(fixture.stepNames()).containsExactly("GUARD FAILED", "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                "RELEASE SKIPPED", "DISPOSE DONE");
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock is left").exists();
        fixture.awaitTheJournalEnded();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).as("the journal is unsealed")
                .noneMatch(JournalRecord::isSessionSeal);
    }

    /** A take recording, its capture thread held mid-pass; with {@code STOP_PENDING} its Stop pressed too. */
    private void aTakeIn(TakeState state) throws Exception {
        recording.start();
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));
        if (state == TakeState.STOP_PENDING) {
            runOnFx(() -> recording.controller().stop());
            assertThat(onFx(() -> recording.controller().isTakeBeingWritten())).as("fixture: the Stop is pending")
                    .isTrue();
        }
    }

    private void ask(Door door) throws Exception {
        switch (door) {
            case CLOSE_BOX -> fixture.closeBox();
            case FILE_EXIT -> fixture.fileExit();
            case CLOSE_PROJECT -> fixture.closeProject();
            case CLOSE_THEN_CONTINUE -> continuation = fixture.closeThenContinue(SWITCH);
        }
    }
}
