package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Take-at-exit", its other states (the stage-6
 * reviews' B3 and S5, ruling 4 Q5 (iv); the studio engineer's ruling R1 and
 * critique B2): the take's capture thread terminated but the FX turn that
 * publishes it not run yet — for the window's close box, File ▸ Exit, File ▸
 * Close Project and the close-then-continue primitive — waited out with
 * nothing past INTERCEPT, then the prompt's take line and the clip in
 * {@code project.daw}; for the application-stop fallback, waited out with no
 * step, then the clip in its closing checkpoint and {@code project.daw}
 * byte-identical (ruling 4 Q6.5); and, at the close
 * box, a take being prepared (its Stop cancels the start, which counts as
 * being written until the removal of its files ran), a take counting in (the
 * Stop ends the count-in with no take), a MIDI take (the confirmation, then
 * the take line), a take with post-roll on (the close's Stop ends the
 * post-roll at once, so the transport is stopped and out of it) and a take
 * Record started inside a running post-roll's tail (the close's Stop at
 * GUARD stops it, and GUARD waits for its publication; PR #989 review
 * 5704604621) — and the same take at the application-stop fallback, which
 * stops it and waits for its publication before its closing checkpoint. The
 * device is stepped by the test ({@link SteppedTakeFixture}) wherever a
 * state must hold; the MIDI and post-roll takes record as the app records
 * ({@link RecordingInFlightFixture}).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TakeAtExitInEveryRecordingStateTest {

    /** The doors that may ask. */
    enum Door { CLOSE_BOX, FILE_EXIT, CLOSE_PROJECT, CLOSE_THEN_CONTINUE }

    private static final AudioFormat FORMAT = AudioFormat.CD_QUALITY;
    private static final String TAKE_LINE = "Your changes include the take you just recorded.\n";

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private SteppedTakeFixture stepped;
    private RecordingInFlightFixture recording;

    @BeforeEach
    void songASaved() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        try {
            fixture.transport = null;
            if (stepped != null) {
                stepped.close();
            }
            if (recording != null) {
                recording.close();
            }
        } finally {
            fixture.close();
        }
    }

    /**
     * The publication turn held (Goals — Tests: "Stop pending / publication
     * turn held", {@code SteppedTakeFixture.stopAndHoldTheRead()}): the
     * capture thread has terminated and the read that comes before the
     * publication is held — no confirmation (nothing records), nothing past
     * INTERCEPT; once the turn ran, the take line and the clip in the head.
     */
    @ParameterizedTest
    @EnumSource(Door.class)
    void aTakeWhosePublicationTurnIsHeldIsWaitedOutAndSaveAndCloseKeepsIt(Door door) throws Exception {
        Track vox = steppedOnAnArmedTrack();
        stepped.startRecording();
        stepped.feedRamp(2);
        stepped.stopAndHoldTheRead();
        assertThat(onFx(stepped.controller::isTakeBeingWritten)).as("fixture: written, not yet published").isTrue();
        fixture.save.promptAnswer = "Save and close";

        ask(door);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.INTERCEPT)
                && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the take wait");
        fixture.save.settle();

        assertThat(fixture.stopTakePrompts).as("nothing records: no confirmation").isEmpty();
        assertThat(fixture.stepNames()).as("nothing past INTERCEPT while the take is being written")
                .containsExactly("INTERCEPT DONE");
        assertThat(fixture.unsavedPrompts).isEmpty();

        stepped.runTheHeldRead();
        awaitTheEnd(door);

        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                .as("R2's take line: the take was waited out and published a clip").startsWith(TAKE_LINE));
        List<AudioClip> clips = onFx(() -> List.copyOf(vox.getClips()));
        assertThat(clips).as("the take published its clip").hasSize(1);
        String clipFile = Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString();
        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("the take is in project.daw after Save and close").contains(clipFile);
    }

    /**
     * A take being prepared (R1: PREPARING is in flight; the stage-6 report's
     * D7): the confirmation shows; Stop the take and close cancels the start,
     * which counts as being written until the removal of its files ran —
     * nothing past INTERCEPT until then; then no take line and no clip.
     */
    @Test
    void aTakeBeingPreparedIsAskedAboutAndItsCancelledStartIsWaitedOut() throws Exception {
        Track vox = steppedOnAnArmedTrack();
        stepped.pressRecord();
        assertThat(onFx(stepped.controller::isPreparingTake)).as("fixture: PREPARING, its allocation held")
                .isTrue();
        fixture.stopTakeAnswer = "Stop the take and close";

        fixture.closeBox();
        fixture.await(() -> fixture.stopTakePrompts.size() == 1
                && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the take wait");
        fixture.save.settle();

        assertThat(onFx(stepped.controller::isRecordingInFlight)).as("the Stop cancelled the start").isFalse();
        assertThat(onFx(stepped.controller::isTakeBeingWritten)).as("the cancelled start is still being written")
                .isTrue();
        assertThat(fixture.stepNames()).as("nothing past INTERCEPT until the removal ran")
                .containsExactly("INTERCEPT DONE");

        stepped.storage.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.unsavedPrompts).as("no take, nothing to save: no prompt").isEmpty();
        assertThat(onFx(() -> List.copyOf(vox.getClips()))).isEmpty();
    }

    /**
     * A take counting in (R1 names COUNT_IN; the stepped device never ends
     * the count-in by itself): the confirmation shows, and the Stop ends the
     * count-in with no take.
     */
    @Test
    void aTakeCountingInIsAskedAboutAndTheStopEndsTheCountInWithNoTake() throws Exception {
        Track vox = steppedOnAnArmedTrack();
        stepped.countIn = CountInMode.ONE_BAR;
        stepped.pressRecord();
        stepped.storage.runNext();
        SteppedTakeFixture.awaitOnFx(stepped.controller::isCountingIn, "the take counts in");
        fixture.stopTakeAnswer = "Stop the take and close";

        fixture.closeBox();
        fixture.await(() -> fixture.stopTakePrompts.size() == 1, "the confirmation");
        stepped.storage.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(onFx(stepped.controller::isCountingIn)).as("the Stop ended the count-in").isFalse();
        assertThat(onFx(() -> List.copyOf(vox.getClips()))).as("no take").isEmpty();
        assertThat(fixture.unsavedPrompts).as("nothing to save").isEmpty();
    }

    /**
     * The final prober's P06 (the studio engineer's final ruling F16;
     * studio-recording "Session decisions"): a count-in that the close of an
     * edited project stops finalised no file, so R2 asks about the unsaved
     * changes without the take line — "Your changes include the take you
     * just recorded" would be false, and could make the user save to keep a
     * take that does not exist.
     */
    @Test
    void aCountInStoppedByTheCloseOfAnEditedProjectAsksWithoutTheTakeLine() throws Exception {
        Track vox = steppedOnAnArmedTrack();
        fixture.edit("Bass");
        stepped.countIn = CountInMode.ONE_BAR;
        stepped.pressRecord();
        stepped.storage.runNext();
        SteppedTakeFixture.awaitOnFx(stepped.controller::isCountingIn, "the take counts in");
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.save.promptAnswer = "Discard and close";

        fixture.closeBox();
        fixture.await(() -> fixture.stopTakePrompts.size() == 1, "the confirmation");
        stepped.storage.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(onFx(() -> List.copyOf(vox.getClips()))).as("fixture: the count-in recorded no take").isEmpty();
        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                .as("R2's unsaved-changes text alone, with no take line")
                .matches("If you discard them, “Song A” keeps its last save from \\d{2}:\\d{2}\\."));
    }

    /** A MIDI take with one note: the confirmation, then R2's take line, and the note's clip saved. */
    @Test
    void aMidiTakeIsAskedAboutThenThePromptSaysTheChangesIncludeIt() throws Exception {
        recording = RecordingInFlightFixture.midiOnly(fixture.save.project.get());
        fixture.transport = recording.controller();
        fixture.save.save();
        fixture.save.settle();
        recording.start();
        recording.holdANote();
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stopTakePrompts).hasSize(1);
        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                .as("the MIDI take published a note").startsWith(TAKE_LINE));
    }

    /**
     * A take with post-roll on (critique B2): the close's Stop of the take
     * ends the post-roll it would enter at once — right after it the
     * transport is stopped and out of post-roll, recorded on the acting
     * thread — and Save and close keeps the take.
     */
    @Test
    void aTakeWithPostRollOnIsStoppedOutOfItsPostRollAndKept() throws Exception {
        DawProject song = fixture.save.project.get();
        recording = RecordingInFlightFixture.audio(song);
        fixture.transport = recording.controller();
        fixture.save.save();
        fixture.save.settle();
        runOnFx(() -> song.getTransport().setPreRollPostRoll(new PreRollPostRoll(0, 2, true)));
        recording.start();
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stopTakePrompts).hasSize(1);
        assertThat(fixture.transportAfterStops).as("each of the close's stops left the transport stopped, out of"
                + " the post-roll").isNotEmpty().allMatch("STOPPED"::equals);
        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                .startsWith(TAKE_LINE));
        Track vox = onFx(() -> song.getTracks().stream().filter(Track::isArmed).findFirst().orElseThrow());
        List<AudioClip> clips = onFx(() -> List.copyOf(vox.getClips()));
        assertThat(clips).hasSize(1);
        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .contains(Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString());
    }

    /**
     * A take Record started inside a running post-roll's tail (PR #989
     * review 5704604621; {@code Transport.record()} leaves the post-roll flag
     * set): the close's Stop at GUARD gives it its ordinary Stop, so GUARD
     * waits for its publication before the unsaved-changes prompt and FLUSH —
     * the prompt's take line, the clip in {@code project.daw} after Save and
     * close — and each of the close's stops leaves the transport stopped,
     * out of the post-roll.
     */
    @Test
    void aTakeRecordStartedInsideARunningPostRollIsStoppedAtTheGuardAndKept() throws Exception {
        recordInsideARunningPostRoll();
        fixture.stopTakeAnswer = "Stop the take and close";
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stopTakePrompts).hasSize(1);
        assertThat(fixture.transportBeforeStops).as("the close's first stop met the take recording inside the tail")
                .first().isEqualTo("RECORDING post-roll");
        assertThat(fixture.transportAfterStops).as("each of the close's stops left the transport stopped, out of"
                + " the post-roll").isNotEmpty().allMatch("STOPPED"::equals);
        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.content())
                .as("GUARD waited for the take's publication before the prompt").startsWith(TAKE_LINE));
        List<AudioClip> clips = onFx(() -> List.copyOf(recording.armed().getClips()));
        assertThat(clips).hasSize(1);
        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("the take is in project.daw after Save and close")
                .contains(Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString());
    }

    /**
     * The application-stop fallback with a take Record started inside a
     * running post-roll's tail (PR #989 review 5704604621; ruling R4 step 1):
     * the fallback's transport stop gives the take its ordinary Stop, so the
     * fallback waits for the take's publication before its closing
     * checkpoint, which then holds the take's clip; {@code project.daw} is
     * never written (R4), and each of the close's stops leaves the transport
     * stopped, out of the post-roll.
     */
    @Test
    void theApplicationStopStopsATakeRecordStartedInsideARunningPostRollAndKeepsItInTheClosingCheckpoint()
            throws Exception {
        recordInsideARunningPostRoll();
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));

        java.util.concurrent.CompletableFuture<Void> stopped = fixture.stopTheApplication();
        try {
            ExitFixture.awaitStopped(stopped);
        } finally {
            if (!stopped.isDone()) {
                fixture.fire(com.benesquivelmusic.daw.core.persistence.ProjectManager.EXIT_WRITE_WAIT);
            }
        }

        assertThat(fixture.transportBeforeStops).as("the fallback's stop met the take recording inside the tail")
                .first().isEqualTo("RECORDING post-roll");
        assertThat(fixture.transportAfterStops).as("each of the close's stops left the transport stopped, out of"
                + " the post-roll").isNotEmpty().allMatch("STOPPED"::equals);
        assertThat(fixture.stepNames()).containsExactly("GUARD DONE", "CLOSING_CHECKPOINT DONE", "SEAL DONE",
                "RELEASE DONE", "DISPOSE DONE");
        List<AudioClip> clips = onFx(() -> List.copyOf(recording.armed().getClips()));
        assertThat(clips).as("the take was published before the fallback went on").hasSize(1);
        String clipFile = Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString();
        assertThat(Files.readString(SaveFlowFixture.checkpointFiles(fixture.songA).getLast(), StandardCharsets.UTF_8))
                .as("the take is in the closing checkpoint").contains(clipFile);
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("project.daw never written (R4)")
                .isEqualTo(head);
    }

    /**
     * Song A saved with an armed audio track recorded as the app records;
     * post-roll on — eight bars at 20 BPM, a 96 s tail that outlasts the
     * take's preparation — Play, the user's Stop, whose tail runs, and Record
     * inside the tail; then waits, bounded, until the take records, and
     * asserts that the post-roll flag is still set.
     */
    private void recordInsideARunningPostRoll() throws Exception {
        DawProject song = fixture.save.project.get();
        recording = RecordingInFlightFixture.audio(song);
        fixture.transport = recording.controller();
        fixture.save.save();
        fixture.save.settle();
        runOnFx(() -> {
            Transport transport = song.getTransport();
            transport.setPreRollPostRoll(new PreRollPostRoll(0, PreRollPostRoll.MAX_BARS, true));
            transport.setTempo(20.0);
            transport.play();
            recording.controller().stop();
            assertThat(transport.isInPostRoll()).as("fixture: the user's Stop entered the post-roll").isTrue();
            recording.startOnFx();
        });
        recording.assertStillRecording();
        assertThat(onFx(song.getTransport()::isInPostRoll)).as("fixture: the take records inside the tail").isTrue();
    }

    /** Song A's Vox armed and saved, recorded through a device the test steps. */
    private Track steppedOnAnArmedTrack() throws Exception {
        DawProject song = fixture.save.project.get();
        Track vox = onFx(() -> song.getTracks().getFirst());
        runOnFx(() -> vox.setArmed(true));
        fixture.save.save();
        fixture.save.settle();
        stepped = new SteppedTakeFixture(song, FORMAT, RoundTripLatency.UNKNOWN, _ -> { });
        fixture.transport = stepped.controller;
        return vox;
    }

    /**
     * The application-stop fallback with the take's publication turn held
     * (ruling 4 Q6.5, PUBLICATION_HELD): no step until the turn ran; then the
     * take's clip in the closing checkpoint, {@code project.daw}
     * byte-identical, the journal unsealed so the next open offers it.
     */
    @Test
    void theApplicationStopWaitsForAHeldPublicationTurnAndKeepsTheTakeInTheClosingCheckpoint() throws Exception {
        Track vox = steppedOnAnArmedTrack();
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        stepped.startRecording();
        stepped.feedRamp(2);
        stepped.stopAndHoldTheRead();

        java.util.concurrent.CompletableFuture<Void> stopped = fixture.stopTheApplication();
        try {
            fixture.await(() -> !fixture.timers.pending(com.benesquivelmusic.daw.core.persistence.ProjectManager
                    .EXIT_WRITE_WAIT).isEmpty(), "the fallback's wait for the take");
            fixture.save.settle();
            assertThat(fixture.steps).as("no step while the publication turn is held").isEmpty();
            stepped.runTheHeldRead();
            ExitFixture.awaitStopped(stopped);
        } finally {
            if (!stopped.isDone()) {
                fixture.fire(com.benesquivelmusic.daw.core.persistence.ProjectManager.EXIT_WRITE_WAIT);
            }
        }

        assertThat(fixture.stepNames()).containsExactly("GUARD DONE", "CLOSING_CHECKPOINT DONE", "SEAL DONE",
                "RELEASE DONE", "DISPOSE DONE");
        List<AudioClip> clips = onFx(() -> List.copyOf(vox.getClips()));
        assertThat(clips).as("the take published its clip").hasSize(1);
        String clipFile = Path.of(clips.getFirst().getSourceFilePath()).getFileName().toString();
        assertThat(Files.readString(SaveFlowFixture.checkpointFiles(fixture.songA).getLast(), StandardCharsets.UTF_8))
                .as("the take is in the closing checkpoint").contains(clipFile);
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("project.daw never written (R4)")
                .isEqualTo(head);
        assertThat(com.benesquivelmusic.daw.core.persistence.journal.JournalState.needsRecovery(
                com.benesquivelmusic.daw.core.persistence.journal.ProjectContext.forProject(fixture.songA)))
                .as("the next open offers to recover it").isTrue();
    }

    /**
     * The same with the take's publication turn still held at the fallback's
     * 30 s deadline (ruling 4 Q6.5; the stage-8 review S7): ruling 4 Q6.3's
     * L4, the closing checkpoint, the seal and the release skipped — the
     * lock left, the journal unsealed — and the turn that runs after it
     * publishes no further step.
     */
    @Test
    void aPublicationTurnStillHeldAtTheApplicationStopsDeadlineSkipsTheTailAsAfterACrash() throws Exception {
        steppedOnAnArmedTrack();
        stepped.startRecording();
        stepped.feedRamp(2);
        stepped.stopAndHoldTheRead();
        java.util.logging.Logger coordinatorLog = java.util.logging.Logger.getLogger(ExitCoordinator.class.getName());
        List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler recording = new java.util.logging.Handler() {
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
        coordinatorLog.addHandler(recording);
        try {
            java.util.concurrent.CompletableFuture<Void> stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.timers.pending(com.benesquivelmusic.daw.core.persistence.ProjectManager
                    .EXIT_WRITE_WAIT).isEmpty(), "the fallback's wait for the take");
            fixture.fire(com.benesquivelmusic.daw.core.persistence.ProjectManager.EXIT_WRITE_WAIT);
            ExitFixture.awaitStopped(stopped);

            assertThat(warnings).contains("The take was still being written 30 seconds after the application stopped;"
                    + " the closing checkpoint, the seal and the release are skipped, as after a crash.");
            List<String> atTheDeadline = fixture.stepNames();
            assertThat(atTheDeadline).containsExactly("GUARD FAILED", "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                    "RELEASE SKIPPED", "DISPOSE DONE");
            assertThat(fixture.songA.resolve(com.benesquivelmusic.daw.core.persistence.ProjectLockManager
                    .LOCK_FILE_NAME)).as("the lock is left").exists();
            fixture.awaitTheJournalEnded();
            assertThat(com.benesquivelmusic.daw.core.persistence.journal.JournalState.records(
                    com.benesquivelmusic.daw.core.persistence.journal.ProjectContext.forProject(fixture.songA)))
                    .as("the journal is unsealed")
                    .noneMatch(com.benesquivelmusic.daw.core.persistence.journal.JournalRecord::isSessionSeal);

            stepped.runTheHeldRead();
            fixture.save.settle();
            assertThat(fixture.stepNames()).as("the turn after the deadline publishes no step").isEqualTo(atTheDeadline);
        } finally {
            coordinatorLog.removeHandler(recording);
        }
    }

    private java.util.concurrent.CompletableFuture<ExitCoordinator.CloseOutcome> continuation;

    private void ask(Door door) throws Exception {
        switch (door) {
            case CLOSE_BOX -> fixture.closeBox();
            case FILE_EXIT -> fixture.fileExit();
            case CLOSE_PROJECT -> fixture.closeProject();
            case CLOSE_THEN_CONTINUE -> continuation = fixture.closeThenContinue("Switch Workspace");
        }
    }

    private void awaitTheEnd(Door door) throws Exception {
        if (door == Door.CLOSE_PROJECT) {
            fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project"), "Untitled");
        } else if (door == Door.CLOSE_THEN_CONTINUE) {
            assertThat(continuation.get(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the primitive's one outcome, once the take was saved").isEqualTo(
                            new ExitCoordinator.CloseOutcome.Closed());
            assertThat(fixture.events).containsSubsequence("step FLUSH DONE", "continuation Closed");
        } else {
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        }
    }
}
