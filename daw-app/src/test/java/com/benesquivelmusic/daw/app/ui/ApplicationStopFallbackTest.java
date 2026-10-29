package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecoveryTemps;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryPlan;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryScanner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, design §4.2's R4 row and ruling 4 Q6.5 — the application-stop
 * fallback ({@code ExitCoordinator.applicationStopping()}, which
 * {@code DawApplication.stop()} calls): an end of the toolkit the exit
 * protocol did not run closes the open project without asking, never writes
 * {@code project.daw}, and keeps unsaved changes in a closing checkpoint the
 * next open offers to recover, the journal left unsealed; with none, the
 * journal is sealed and the next open is plain; either way the lock is
 * released, then DISPOSE runs — before the bus and the dispatcher go down.
 * A recording is stopped at once, its take waited for, and a take or a
 * writer still busy at the 30 s deadline skips the rest, as after a crash.
 * An interactive close in progress is taken over: a decision stands and
 * the steps it began are joined, never begun again. After the protocol ran,
 * {@code stop()} only waits for the audio engine's stop, at most 15 s from
 * its start (ruling 2 OQ-3). Every wait is a one-shot nested loop with a
 * deadline; where none can start, the defensive branch runs on a thread of
 * its own. Every log line is ruling 4 Q6.3's.
 *
 * <p>The fallback is started with a post this test never waits on
 * ({@link ExitFixture#stopTheApplication()}): its waits are nested loops on
 * the FX thread, which the test drives — the take, the holds, the manual
 * timers — with FX posts that run inside them, then waits for its return
 * with a bound longer than every wait inside (the feasibility critique's
 * S9).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ApplicationStopFallbackTest {

    private static final Duration DEADLINE = ProjectManager.EXIT_WRITE_WAIT;
    private static final String SONG_A = "“Song A”";
    private static final AutoSaveConfig EVERY_200_MS = new AutoSaveConfig(Duration.ofMillis(200), 10, true);
    /** Ruling 4 Q6.3's L10b, as the studio engineer's stage-8 S5 corrects it. */
    private static final String L10B = "The application is stopping where no nested event loop can run, while a take"
            + " may still be recording or being written: the closing checkpoint, the seal and the release are skipped,"
            + " as after a crash, and the audio engine is left to the end of the process.";
    /** Ruling 4 Q6.3's L5. */
    private static final String L5 = "A save or a checkpoint was still being written 30 seconds after the application"
            + " stopped; the closing checkpoint, the seal and the release are skipped, as after a crash.";
    /** Ruling 4 Q6.3's L1, for Song A, as the studio engineer's final ruling F5 words it: true on every row. */
    private static final String L1 = "The application is stopping without the exit protocol; the application-stop"
            + " fallback asks nothing more about " + SONG_A + " and starts no save of its own.";
    /** The FINE line of the takeover offer held off while a close is pending (the studio engineer's final ruling F1). */
    private static final String NO_TAKEOVER_DIALOG = "The takeover dialog was not shown while %s closes the open"
            + " project";

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private CompletableFuture<Void> stopped;
    /** Undoes {@link #recordTheLifecycleLogAtFine()}, after the test. */
    private Runnable lifecycleLogRestore = () -> { };
    // Held strongly: the log manager keeps a logger only weakly, so a handler added to one nobody holds may be lost.
    private final Logger coordinatorLog = Logger.getLogger(ExitCoordinator.class.getName());
    private final Logger closerLog = Logger.getLogger(ProjectFolderCloser.class.getName());
    private final Logger lifecycleLog = Logger.getLogger(ProjectLifecycleController.class.getName());
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler recording = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logged.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void recordTheLog() {
        recording.setLevel(Level.ALL);
        coordinatorLog.addHandler(recording);
        closerLog.addHandler(recording);
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (fixture != null) {
                unwindTheStop();
                fixture.close();
            }
        } finally {
            coordinatorLog.removeHandler(recording);
            closerLog.removeHandler(recording);
            lifecycleLogRestore.run();
        }
    }

    // ── no protocol ran: the fallback's own close ───────────────────────────

    /** Ruling R1 and R4 step 1: the fallback cannot ask — it stops the recording at once and waits for the take. */
    @Test
    void aRecordingInFlightIsStoppedAtOnceAndItsTakeWaitedForBeforeTheTail() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(takes::record);

        stopped = fixture.stopTheApplication();
        fixture.await(() -> takes.takeBeingWritten() && !fixture.timers.pending(DEADLINE).isEmpty(),
                "the fallback's Stop, then its wait for the take");
        fixture.save.settle();
        assertThat(stopped).as("waiting for the take").isNotDone();
        assertThat(fixture.steps).as("nothing past the take's wait").isEmpty();

        runOnFx(takes::published);
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.transportStops).as("stopped at once").hasValue(1);
        assertThat(fixture.stopTakePrompts).as("never asked (ruling R1)").isEmpty();
        assertThat(messages(Level.WARNING))
                .contains("A recording was in progress when the application stopped; it was stopped.");
        assertThat(fixture.steps).extracting(ExitCoordinator.StepOutcome::step).containsExactly(
                ExitCoordinator.ExitStep.GUARD, ExitCoordinator.ExitStep.CLOSING_CHECKPOINT,
                ExitCoordinator.ExitStep.SEAL, ExitCoordinator.ExitStep.RELEASE, ExitCoordinator.ExitStep.DISPOSE);
        assertThat(fixture.steps.getFirst().detail()).isEqualTo("the recording was stopped and its take written");
    }

    /**
     * No unsaved changes: the journal sealed, the lock released, the next
     * open plain; DISPOSE ran inside {@code stop()}, before it returned — so
     * before the bus and the dispatcher, which {@code stop()} tears down
     * after it ({@code ExitWiringSourceTest}); no HIDE, no terminal exit.
     */
    @Test
    void withNoUnsavedChangesTheJournalIsSealedTheLockReleasedAndTheNextOpenIsPlain() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.steps).extracting(ExitCoordinator.StepOutcome::step).containsExactly(
                ExitCoordinator.ExitStep.GUARD, ExitCoordinator.ExitStep.CLOSING_CHECKPOINT,
                ExitCoordinator.ExitStep.SEAL, ExitCoordinator.ExitStep.RELEASE, ExitCoordinator.ExitStep.DISPOSE);
        assertThat(fixture.stepNames()).contains("GUARD DONE", "SEAL DONE", "RELEASE DONE", "DISPOSE DONE");
        assertThat(messages(Level.WARNING)).as("L1, as the studio engineer's final ruling F5 words it").contains(L1);
        assertThat(fixture.disposals).hasValue(1);
        assertThat(fixture.terminalExits).as("the toolkit is already ending").hasValue(0);
        assertThat(fixture.events).as("DISPOSE ran inside stop(), before it returned and the bus went down")
                .containsSubsequence("dispose", "stopped");
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("project.daw never written (R4)")
                .isEqualTo(head);
        assertThat(fixture.nestedLoopsEntered).as("a wait whose condition held entered no loop: no take")
                .noneMatch(key -> key.toString().endsWith("the take"));
        assertTheNextOpenIsPlain();
    }

    /**
     * Unsaved changes (R4 steps 3-5; ruling 4 Q6.2 item 2): a CLOSING
     * checkpoint of them, the journal drained unsealed — so the next open
     * owes a decision, its recovery point that checkpoint — the lock
     * released, {@code project.daw} byte-identical; L7.
     */
    @Test
    void unsavedChangesAreKeptInAClosingCheckpointTheNextOpenOffersAndProjectDawIsNeverWritten() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        fixture.edit("Bass");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.events).as("one closing checkpoint").containsOnlyOnce("checkpoint Written CLOSING");
        Path closing = SaveFlowFixture.checkpointFiles(fixture.songA).getLast();
        assertThat(Files.readString(closing, StandardCharsets.UTF_8)).as("it holds the unsaved changes")
                .contains("Bass");
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("project.daw never written (R4)")
                .isEqualTo(head);
        assertThat(fixture.steps).filteredOn(step -> step.step() == ExitCoordinator.ExitStep.SEAL)
                .singleElement().satisfies(seal -> assertThat(seal.detail()).isEqualTo("DRAIN_UNSEALED"));
        assertThat(messages(Level.INFO)).as("L7").contains("Unsaved changes of " + SONG_A + " were kept in checkpoint "
                + closing.getFileName() + "; the journal is left unsealed, so the next open offers to recover them.");
        assertTheLockIsReleased();
        assertRecoveryIsOfferedFrom(closing);
    }

    /** Journaled persistence off, unsaved changes: the closing checkpoint and the release; L7b; a plain open. */
    @Test
    void withTheJournalOffUnsavedChangesGetTheirClosingCheckpointAndTheRelease() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, false, UnaryOperator.identity());
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        fixture.edit("Bass");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.events).containsOnlyOnce("checkpoint Written CLOSING");
        Path closing = SaveFlowFixture.checkpointFiles(fixture.songA).getLast();
        assertThat(Files.readString(closing, StandardCharsets.UTF_8)).contains("Bass");
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).isEqualTo(head);
        assertThat(messages(Level.INFO)).as("L7b, as the studio engineer's final ruling F4 words it")
                .contains(l7b(closing));
        assertTheLockIsReleased();
        assertThat(new RecoveryScanner().plan(ProjectContext.forProject(fixture.songA),
                RecoveryTemps.inspect(fixture.songA), false)).as("the next open is plain").isEmpty();
    }

    /**
     * The studio engineer's final ruling F4: journaled persistence on, but
     * paused — an exit kept open after its journal's detach (Don't quit while
     * the seal runs) leaves the project journaled no further — then an edit
     * and the application stop. The unsaved changes get their closing
     * checkpoint, and the log says no journal was open, so the next open does
     * not offer them (L7b): a capture made with no journal open is no
     * recovery base.
     */
    @Test
    void aKeepAfterTheDetachThenTheStopKeepsTheChangesAndSaysNoJournalWasOpen() throws Exception {
        CountDownLatch sealBegan = new CountDownLatch(1);
        CountDownLatch releaseTheSeal = new CountDownLatch(1);
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                Runnable end = real.prepare(mode);
                if (mode != JournalEnd.SEAL) {
                    return end;
                }
                return () -> {
                    sealBegan.countDown();
                    awaitQuietly(releaseTheSeal);
                    end.run();
                };
            }
        });
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Discard and close";
        try {
            fixture.closeBox();
            assertThat(sealBegan.await(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS)).as("the seal began")
                    .isTrue();
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            fixture.await(() -> fixture.surface() != null && fixture.surface().isOpen(), "the progress surface");
            fixture.fire(ExitCoordinator.KEEP_AFTER_DETACH_DELAY);
            fixture.await(() -> !fixture.surface().keepButton().isDisabled(), "the keep usable after the detach");
            runOnFx(() -> fixture.surface().keepButton().fire());
            fixture.await(() -> !fixture.coordinator().isWindowInert(), "Don't quit: the project stays open");
        } finally {
            releaseTheSeal.countDown();
        }
        fixture.awaitTheJournalEnded();
        assertThat(fixture.journal.isOpen()).as("fixture: journaling paused, the setting still on").isFalse();
        fixture.edit("Keys");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        Path closing = SaveFlowFixture.checkpointFiles(fixture.songA).getLast();
        assertThat(closing).as("the stop's closing checkpoint").exists();
        assertThat(Files.readString(closing, StandardCharsets.UTF_8)).as("it holds the unsaved changes")
                .contains("Keys");
        assertThat(messages(Level.INFO)).as("L7b: no journal was open at the detach").contains(l7b(closing))
                .noneMatch(message -> message.endsWith("so the next open offers to recover them."));
    }

    /**
     * The application stop's door ({@code CloseDoor.ApplicationStop}) is the
     * fallback itself: asked as a door, it runs the fallback — no prompt,
     * no INTERCEPT — and answers {@code Closed}; it asks nothing
     * ({@code mayAsk()} false).
     */
    @Test
    void theApplicationStopDoorRunsTheFallbackAndAsksNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        assertThat(new ExitCoordinator.CloseDoor.ApplicationStop().mayAsk()).isFalse();
        assertThat(new ExitCoordinator.CloseDoor.ApplicationStop().exitsApplication()).isTrue();
        CompletableFuture<ExitCoordinator.CloseOutcome> answered = new CompletableFuture<>();
        stopped = new CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            fixture.coordinator().request(new ExitCoordinator.CloseDoor.ApplicationStop())
                    .whenComplete((outcome, failure) -> answered.complete(outcome));
            stopped.complete(null);
        });
        ExitFixture.awaitStopped(stopped);

        assertThat(answered.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
        assertThat(fixture.stepNames()).doesNotContain("INTERCEPT DONE").contains("GUARD DONE", "SEAL DONE",
                "RELEASE DONE", "DISPOSE DONE");
        assertThat(fixture.unsavedPrompts).isEmpty();
    }

    /** The protocol ran before: {@code stop()} runs no step, disposes nothing again, and waits for nothing. */
    @Test
    void afterTheProtocolRanTheStopRunsNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        List<String> before = fixture.stepNames();

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.stepNames()).as("no step, nothing published").isEqualTo(before);
        assertThat(fixture.disposals).hasValue(1);
        assertThat(fixture.terminalExits).hasValue(1);
        assertThat(fixture.nestedLoopsEntered).as("the hand-off had ended: its wait entered no loop").isEmpty();
    }

    // ── the deadline and the one-shot waits ─────────────────────────────────

    /**
     * The take still being written at the 30 s deadline (R4; the studio
     * engineer's N10): L4, the closing checkpoint, the seal and the release
     * skipped — the lock left — and the journal drained unsealed, best
     * effort; DISPOSE still runs.
     */
    @Test
    void aTakeStillBeingWrittenAtTheDeadlineSkipsTheTailLeavesTheLockAndDrainsTheJournal() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(() -> takes.beingWritten(true));

        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the wait for the take");
        fixture.fire(DEADLINE);
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.WARNING)).as("L4").contains("The take was still being written 30 seconds after the"
                + " application stopped; the closing checkpoint, the seal and the release are skipped, as after a"
                + " crash.");
        assertThat(fixture.stepNames()).containsExactly("GUARD FAILED", "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                "RELEASE SKIPPED", "DISPOSE DONE");
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock is left").exists();
        fixture.awaitTheJournalEnded();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).as("drained, never sealed")
                .isNotEmpty().noneMatch(JournalRecord::isSessionSeal);
    }

    /** A writer still busy at the deadline: L5, the tail skipped, the lock left. */
    @Test
    void aSaveStillBeingWrittenAtTheDeadlineSkipsTheTailAndLeavesTheLock() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.save.save();
            head.awaitHeld();

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the wait for every writer");
            fixture.fire(DEADLINE);
            ExitFixture.awaitStopped(stopped);
        } finally {
            head.release();
        }

        assertThat(messages(Level.WARNING)).as("L5").contains("A save or a checkpoint was still being written 30"
                + " seconds after the application stopped; the closing checkpoint, the seal and the release are"
                + " skipped, as after a crash.");
        assertThat(fixture.stepNames()).containsExactly("GUARD DONE", "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                "RELEASE SKIPPED", "DISPOSE DONE");
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
    }

    /** The deadline passing mid-closer, its closing checkpoint held: L6, the rest skipped, the lock left. */
    @Test
    void theDeadlinePassingInsideTheCloseSkipsWhatItHadNotDone() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        try {
            stopped = fixture.stopTheApplication();
            closing.awaitHeld();
            fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the wait for the close");
            fixture.fire(DEADLINE);
            ExitFixture.awaitStopped(stopped);
        } finally {
            closing.release();
        }

        assertThat(messages(Level.WARNING)).as("L6").contains("The close of " + SONG_A + " had not finished 30"
                + " seconds after the application stopped; its remaining steps are skipped, as after a crash.");
        assertThat(fixture.stepNames()).containsExactly("GUARD DONE", "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                "RELEASE SKIPPED", "DISPOSE DONE");
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
    }

    /**
     * The design's FEAS-S8 one-shot rules: a wait whose stage has completed
     * enters no loop; each wait has its own key; and the deadline timer that
     * fires once its wait ended — here a timer whose cancel lost the race —
     * does nothing (no second exit of a loop that has gone).
     */
    @Test
    void aWaitWhoseStageEndedEntersNoLoopAndItsDeadlineFiringLaterDoesNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.handOff = new CompletableFuture<>();

        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                "DISPOSE's wait for the engine's stop");
        ManualCloseTimers.Armed bound = fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND)
                .getFirst();
        fixture.handOff.complete(null);
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.nestedLoopsEntered).as("each wait its own key").doesNotHaveDuplicates()
                .noneMatch(key -> key.toString().endsWith("the take"));
        assertThat(bound.cancelled()).as("the ended wait cancelled its deadline").isTrue();
        runOnFx(() -> fixture.timers.runAnyway(bound));
        assertThat(messages(Level.WARNING)).as("no OQ-3 line: the engine's stop ended in time")
                .noneMatch(message -> message.startsWith("Audio engine shutdown did not finish"));
    }

    /**
     * The fallback's own DISPOSE waits for the engine's stop at most 15 s from
     * its start, as after the protocol (the studio engineer's stage-8 S1,
     * reversing OQ-3 c1's 5 s): OQ-3's line, and no 5 s bound armed.
     */
    @Test
    void theFallbacksDisposeWaitsForTheEngineStopAtMostFifteenSeconds() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.handOff = new CompletableFuture<>();

        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                "DISPOSE's wait for the engine's stop, 15 s from its start");
        assertThat(stopped).isNotDone();
        fixture.fire(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND);
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.WARNING)).as("OQ-3's line").contains("Audio engine shutdown did not finish within 15"
                + " seconds; the application ends with the audio device still stopping.");
        assertThat(fixture.timers.armed()).as("no 5 s bound armed by the stop")
                .noneMatch(timer -> timer.delay().equals(ExitCoordinator.ENGINE_STOP_BOUND));
    }

    // ── the defensive branch (design §1.7 step 9) ───────────────────────────

    /** Off the FX thread the take is unknown: the closing checkpoint, the seal and the release skipped; L10b. */
    @Test
    void offTheFxThreadTheTakeIsUnknownSoTheTailIsSkippedAndTheLockLeft() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);

        fixture.coordinator().applicationStopping();

        assertThat(messages(Level.WARNING)).as("L10b").contains(L10B);
        assertThat(fixture.stepNames()).containsExactly("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED", "RELEASE SKIPPED");
        assertThat(fixture.disposals).as("no DISPOSE off the FX thread (ruling 4 Q6.2 item 3)").hasValue(0);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
        assertThat(fixture.journal.isOpen()).as("the journal is left as it is").isTrue();
    }

    /** No nested loop can start, a take being written: the same skip, on the FX thread too. */
    @Test
    void withNoNestedLoopATakeInFlightSkipsTheTailToo() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(() -> takes.beingWritten(true));
        fixture.nestedLoopsAllowed = false;

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.WARNING)).as("L10b, true on the FX thread too (the studio engineer's stage-8 S5)")
                .contains(L10B);
        assertThat(fixture.stepNames()).containsExactly("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED", "RELEASE SKIPPED");
        assertThat(fixture.nestedLoopsEntered).isEmpty();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).exists();
    }

    /**
     * No nested loop can start, the take known and idle: on the non-daemon
     * {@code daw-exit-fallback} thread, the journal drained unsealed with no
     * closing checkpoint, every writer waited for, the lock released; no
     * DISPOSE; L10.
     */
    @Test
    void withNoNestedLoopAndNoTakeTheJournalIsDrainedAndTheLockReleasedOnTheFallbacksThread() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        AtomicReference<Thread> releasedOn = new AtomicReference<>();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.RELEASE) {
                releasedOn.set(Thread.currentThread());
            }
        };
        fixture.nestedLoopsAllowed = false;

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.RELEASE), "the fallback thread's release");

        assertThat(messages(Level.WARNING)).as("L10 (the studio engineer's stage-8 S5)").contains("The application is"
                + " stopping where no nested event loop can run: the open project's journal is drained unsealed with"
                + " no closing checkpoint, and the audio engine is left to the end of the process.");
        assertThat(fixture.stepNames()).containsExactly("CLOSING_CHECKPOINT SKIPPED", "SEAL DONE", "RELEASE DONE");
        assertThat(releasedOn.get().getName()).isEqualTo(ExitCoordinator.EXIT_FALLBACK_THREAD);
        assertThat(releasedOn.get().isDaemon()).as("the JVM waits for it, bounded (ruling R4)").isFalse();
        assertThat(fixture.disposals).hasValue(0);
        assertThat(fixture.nestedLoopsEntered).isEmpty();
        assertThat(fixture.events).doesNotContain("checkpoint Written CLOSING");
        assertTheLockIsReleased();
        assertThat(fixture.journal.isOpen()).isFalse();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).noneMatch(JournalRecord::isSessionSeal);
    }

    // ── an interactive close taken over (ruling 4 Q6.2, item 1) ─────────────

    /**
     * An interactive exit inside its SEAL when {@code stop()} comes (the
     * studio engineer's S8, FEAS-S10): the fallback waits for that SEAL and
     * starts no second journal end — one journal end prepared, one seal —
     * then the release the exit began, then its own DISPOSE; L2.
     */
    @Test
    void anExitInsideItsSealIsJoinedAndNoSecondJournalEndStarts() throws Exception {
        CountDownLatch sealBegan = new CountDownLatch(1);
        CountDownLatch releaseTheSeal = new CountDownLatch(1);
        AtomicInteger prepared = new AtomicInteger();
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                prepared.incrementAndGet();
                Runnable end = real.prepare(mode);
                return () -> {
                    sealBegan.countDown();
                    awaitQuietly(releaseTheSeal);
                    end.run();
                };
            }
        });
        try {
            fixture.closeBox();
            assertThat(sealBegan.await(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the stop joins the exit");
            fixture.save.settle();
            assertThat(stopped).as("waiting for the exit's own SEAL").isNotDone();
            assertThat(fixture.disposals).hasValue(0);
        } finally {
            releaseTheSeal.countDown();
        }
        ExitFixture.awaitStopped(stopped);

        assertThat(prepared).as("one journal end: the exit's, joined").hasValue(1);
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                .filteredOn(JournalRecord::isSessionSeal).hasSize(1);
        assertThat(fixture.steps).extracting(ExitCoordinator.StepOutcome::step).containsExactly(
                ExitCoordinator.ExitStep.INTERCEPT, ExitCoordinator.ExitStep.GUARD, ExitCoordinator.ExitStep.FLUSH,
                ExitCoordinator.ExitStep.CLOSING_CHECKPOINT, ExitCoordinator.ExitStep.SEAL,
                ExitCoordinator.ExitStep.RELEASE, ExitCoordinator.ExitStep.DISPOSE);
        assertThat(messages(Level.WARNING)).as("L2").contains("Exit was still closing " + SONG_A + " when the"
                + " application stopped; the application-stop fallback takes it over.");
        assertThat(fixture.terminalExits).hasValue(0);
        assertTheNextOpenIsPlain();
    }

    /**
     * An undecided close — the unsaved-changes prompt up — is dropped: the
     * fallback's own row runs (a closing checkpoint, the journal unsealed,
     * recovery offered) and the prompt's later answer, Save and close,
     * saves nothing.
     */
    @Test
    void anUndecidedCloseIsDroppedAndItsLaterAnswerSavesNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        fixture.edit("Bass");
        AtomicReference<CompletableFuture<Void>> stop = new AtomicReference<>();
        // Posted right before the prompt shows: it runs inside the prompt's own nested loop, the prompt up.
        fixture.beforeTheUnsavedPrompt = () -> {
            if (stop.get() == null) {
                stop.set(fixture.stopTheApplication());
            }
        };
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.await(() -> stop.get() != null, "the stop, posted with the prompt up");
        stopped = stop.get();
        ExitFixture.awaitStopped(stopped);
        fixture.save.settle();

        assertThat(fixture.unsavedPrompts).as("the prompt was up").hasSize(1);
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("its answer saved nothing")
                .isEqualTo(head);
        assertThat(fixture.events).containsOnlyOnce("checkpoint Written CLOSING");
        assertTheLockIsReleased();
        assertRecoveryIsOfferedFrom(SaveFlowFixture.checkpointFiles(fixture.songA).getLast());
    }

    /**
     * The taken-over close is fenced (ruling 4 Q6.2 item 1): an undecided
     * exit waiting for a queued save when the stop comes is dropped — the
     * save's report, which reaches the FX thread while the fallback still
     * waits, decides nothing: no second GUARD, no prompt, and no save of the
     * edit made after it, which the closing checkpoint keeps instead.
     */
    @Test
    void anUndecidedCloseWaitingForASaveIsFencedSoItsReportDuringTheFallbackDecidesNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.save.save();
            head.awaitHeld();
            fixture.edit("Keys");
            fixture.save.promptAnswer = "Save and close";
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(),
                    "the exit waits for the queued save");

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the fallback waits for every writer");
        } finally {
            head.release();
        }
        ExitFixture.awaitStopped(stopped);
        fixture.save.settle();

        assertThat(fixture.unsavedPrompts).as("the dropped close asked nothing").isEmpty();
        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("GUARD"))
                .as("one GUARD: the fallback's").containsExactly("GUARD DONE");
        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("the queued save landed; nothing saved the edit after it").contains("Bass")
                .doesNotContain("Keys");
        assertThat(fixture.events).as("the edit after it is in the closing checkpoint instead")
                .containsOnlyOnce("checkpoint Written CLOSING");
    }

    /**
     * A decided Discard mid-CLOSING_CHECKPOINT: its decision stands — exactly
     * one closing checkpoint, then {@code $session-seal}, the next open plain
     * — and R3's line.
     */
    @Test
    void aDiscardMidClosingCheckpointKeepsItsRowOneClosingCheckpointAndItsSeal() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Discard and close";
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        try {
            fixture.closeBox();
            closing.awaitHeld();
            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the stop joins the Discard");
        } finally {
            closing.release();
        }
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.events).as("exactly one closing checkpoint").containsOnlyOnce("checkpoint Written CLOSING");
        Path written = SaveFlowFixture.checkpointFiles(fixture.songA).getLast();
        assertThat(messages(Level.INFO)).contains("Discarded changes of " + SONG_A + " were kept in checkpoint "
                + written.getFileName() + ".");
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).isEqualTo(head);
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)).getLast().isSessionSeal()).isTrue();
        assertTheNextOpenIsPlain();
    }

    /** A close on its seal-failure surface goes on as Quit anyway would: the lock released, the journal unsealed. */
    @Test
    void aCloseOnItsSealFailureSurfaceIsReleasedWithItsJournalUnsealed() throws Exception {
        AtomicBoolean failTheSeal = new AtomicBoolean(true);
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                if (mode != JournalEnd.SEAL || !failTheSeal.compareAndSet(true, false)) {
                    return real.prepare(mode);
                }
                Runnable closedUnsealed = real.prepare(JournalEnd.DRAIN_UNSEALED);
                return () -> {
                    closedUnsealed.run();
                    throw new UncheckedIOException("Project journal could not be sealed",
                            new IOException("the journal disk refused the seal"));
                };
            }
        });
        fixture.closeBox();
        fixture.await(() -> fixture.surface() != null && fixture.surface().isOpen()
                && fixture.surface().offerButton().isVisible(), "the seal failure's surface");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.stepNames()).containsSubsequence("SEAL FAILED", "RELEASE DONE", "DISPOSE DONE")
                .doesNotContain("HIDE DONE");
        assertThat(onFx(() -> fixture.surface().isOpen())).as("the taken-over close's surface closed").isFalse();
        assertTheLockIsReleased();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).noneMatch(JournalRecord::isSessionSeal);
    }

    /**
     * A Close Project mid-closer, whose plan does not wait for the release:
     * after its closer, every writer and the retired leases — Song A's,
     * whose labelled checkpoint is held — are waited for before DISPOSE.
     */
    @Test
    void aCloseProjectMidCloserWaitsForEveryWriterAndTheReleaseBeforeDispose() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        SaveFlowFixture save = fixture.save;
        Path songA = save.songA;
        Path songB = save.anotherProjectOnDisk("Song B");
        long songAGeneration = save.checkpoints.runningGeneration().orElseThrow();
        save.awaitCheckpointsSeeded(songAGeneration - 1);
        AtomicBoolean songALockedAtDispose = new AtomicBoolean(true);
        fixture.windowDisposal = () -> {
            songALockedAtDispose.set(Files.exists(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)));
            return CompletableFuture.completedFuture(null);
        };
        SaveFlowFixture.Publishes.Hold heldA = save.publishes.holdNext(
                target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(songA));
        try {
            runOnFx(() -> save.snapshots().createCheckpointWithLabel("Held in Song A"));
            heldA.awaitHeld();
            runOnFx(() -> {
                save.lifecycle().setOpenProjectPrompt(() -> songB);
                save.lifecycle().onOpenProject();
            });
            save.awaitOnFx(() -> save.currentProjectIs(songB) && !save.lifecycle().isProjectInstallationPending(),
                    "Song B installed");
            save.awaitCheckpointsSeeded(songAGeneration);
            fixture.edit("Bass");
            save.promptAnswer = "Discard and close";
            SaveFlowFixture.Publishes.Hold heldB = save.publishes.holdNext(
                    target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(songB));
            try {
                fixture.closeProject();
                heldB.awaitHeld();
                stopped = fixture.stopTheApplication();
                fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the stop joins Close Project");
            } finally {
                heldB.release();
            }
            fixture.await(() -> fixture.stepNames().contains("RELEASE DONE"), "Close Project's own release");
            save.settle();
            assertThat(fixture.disposals).as("DISPOSE waits for every writer and the retired leases").hasValue(0);
            assertThat(stopped).isNotDone();
        } finally {
            heldA.release();
        }
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.disposals).hasValue(1);
        assertThat(songALockedAtDispose).as("Song A's retired lease was released before DISPOSE").isFalse();
        assertThat(songB.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    /** A close past DISPOSE: no step, DISPOSE never again — its own hand-off waited for, up to 15 s from it. */
    @Test
    void anExitPastDisposeRunsNoStepAndDisposesOnce() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.handOff = new CompletableFuture<>();
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        List<String> before = fixture.stepNames();

        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                "the wait for that DISPOSE's hand-off, 15 s from its start");
        assertThat(stopped).isNotDone();
        fixture.handOff.complete(null);
        ExitFixture.awaitStopped(stopped);
        fixture.save.settle();

        assertThat(fixture.stepNames()).as("no step").isEqualTo(before);
        assertThat(fixture.disposals).as("DISPOSE once").hasValue(1);
        assertThat(fixture.terminalExits).hasValue(0);
    }

    /**
     * The app-fix-2 review N3's second half (the app-fix-3 review N6): an
     * observer of the fallback's own DISPOSE outcome that throws neither asks
     * for a second disposal nor skips the wait for the audio engine's stop —
     * the outcome is published quietly once the disposal's outcome is known:
     * DISPOSE once, its hand-off still waited for, and no SEVERE line about
     * the window's disposal.
     */
    @Test
    void aDisposeOutcomeObserverThatThrowsAtTheStopStillWaitsForTheEnginesStop() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.handOff = new CompletableFuture<>();
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.DISPOSE && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };

        stopped = fixture.stopTheApplication();
        fixture.await(() -> stopped.isDone()
                        || !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                "the stop returned, or waits for the engine's stop");

        assertThat(thrown).as("fixture: the observer threw").isTrue();
        assertThat(stopped).as("the engine's stop is still waited for").isNotDone();
        assertThat(fixture.disposals).as("no second disposal").hasValue(1);
        assertThat(messages(Level.SEVERE)).noneMatch(message -> message.startsWith("The window's disposal failed"));
        fixture.handOff.complete(null);
        ExitFixture.awaitStopped(stopped);
        assertThat(fixture.stepNames()).endsWith("DISPOSE DONE");
    }

    // ── after the protocol: the 15 s wait for the engine's stop (ruling 2 OQ-3) ─

    /** HIDE at 5 s with the engine's stop held; {@code stop()} then returns at the 15 s bound, logged. */
    @Test
    void hideAtFiveSecondsThenStopReturnsAtTheFifteenSecondBoundWithItsWarning() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        WindowLifetimeDisposalTest.HeldEngine engine = new WindowLifetimeDisposalTest.HeldEngine();
        try {
            MainController main = theApplicationsDisposal(engine, false);
            hideAtTheFiveSecondBound(engine);

            stopped = stop(main);
            fixture.await(() -> !fixture.timers.pending(Duration.ofSeconds(10)).isEmpty(),
                    "the wait up to 15 s from the hand-off's start");
            fixture.save.settle();
            assertThat(stopped).as("stop() waits for the engine's stop").isNotDone();
            fixture.fire(Duration.ofSeconds(10));
            ExitFixture.awaitStopped(stopped);

            assertThat(messages(Level.WARNING)).contains("Audio engine shutdown did not finish within 15 seconds; the"
                    + " application ends with the audio device still stopping.");
            assertThat(engine.stopEnded()).isFalse();
            assertThat(fixture.stepNames()).as("no step after HIDE").endsWith("DISPOSE DONE", "HIDE DONE");
        } finally {
            engine.release();
        }
    }

    /** The engine's stop released at 7 s: {@code stop()} returns then, no WARNING. */
    @Test
    void anEngineStopReleasedAtSevenSecondsLetsStopReturnThen() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        WindowLifetimeDisposalTest.HeldEngine engine = new WindowLifetimeDisposalTest.HeldEngine();
        try {
            MainController main = theApplicationsDisposal(engine, false);
            hideAtTheFiveSecondBound(engine);

            stopped = stop(main);
            fixture.await(() -> !fixture.timers.pending(Duration.ofSeconds(10)).isEmpty(), "the wait");
            fixture.nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            engine.release();
            ExitFixture.awaitStopped(stopped);

            assertThat(engine.stopEnded()).isTrue();
            assertThat(messages(Level.WARNING)).noneMatch(message -> message.startsWith("Audio engine shutdown"));
        } finally {
            engine.release();
        }
    }

    /** A hand-off that ended before HIDE: {@code stop()} waits for nothing. */
    @Test
    void aHandOffThatEndedBeforeHideMakesStopWaitForNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        WindowLifetimeDisposalTest.HeldEngine engine = new WindowLifetimeDisposalTest.HeldEngine();
        try {
            MainController main = theApplicationsDisposal(engine, false);
            fixture.closeBox();
            fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
            engine.awaitStopping();
            engine.release();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            int armedAtHide = fixture.timers.armed().size();

            stopped = stop(main);
            ExitFixture.awaitStopped(stopped);

            assertThat(fixture.nestedLoopsEntered).as("no loop entered").isEmpty();
            assertThat(fixture.timers.armed()).as("no deadline armed").hasSize(armedAtHide);
        } finally {
            engine.release();
        }
    }

    /** DISPOSE's FX part failed: {@code stop()} still waits for the hand-off it asked again (ruling 4 Q6.5). */
    @Test
    void aFailedDisposeFxPartsReAskedHandOffIsWaitedForToo() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        WindowLifetimeDisposalTest.HeldEngine engine = new WindowLifetimeDisposalTest.HeldEngine();
        try {
            MainController main = theApplicationsDisposal(engine, true);
            hideAtTheFiveSecondBound(engine);
            assertThat(fixture.stepNames()).contains("DISPOSE FAILED");

            stopped = stop(main);
            fixture.await(() -> !fixture.timers.pending(Duration.ofSeconds(10)).isEmpty(), "the wait");
            fixture.save.settle();
            assertThat(stopped).isNotDone();
            engine.release();
            ExitFixture.awaitStopped(stopped);

            assertThat(engine.stopEnded()).isTrue();
        } finally {
            engine.release();
        }
    }

    // ── what the fallback could not keep (ruling 4 Q6.3, L8) ────────────────

    /** Unsaved changes in a folder another session took: nothing written there; L8's lock variant. */
    @Test
    void unsavedChangesUnderAStolenLockAreNotKept() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);
        String alicesLock = Files.readString(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME));
        fixture.edit("Bass");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.WARNING)).as("L8").contains("Unsaved changes of " + SONG_A + " were not kept: another"
                + " session holds its lock.");
        assertThat(fixture.events).doesNotContain("checkpoint Written CLOSING");
        assertThat(Files.readString(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)))
                .as("the other session's lock is left").isEqualTo(alicesLock);
    }

    /** Unsaved changes of a read-only open: nothing written in its folder; L8's read-only variant. */
    @Test
    void unsavedChangesOfAReadOnlyOpenAreNotKept() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        Path songC = fixture.save.anotherProjectOnDisk("Song C");
        new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(songC);
        fixture.save.projectManager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> songC);
            fixture.lifecycle().onOpenProject();
        });
        fixture.await(() -> fixture.save.currentProjectIs(songC)
                && !fixture.lifecycle().isProjectInstallationPending(), "Song C open read-only");
        fixture.edit("Sketch");
        Map<String, FileTime> original = listing(songC);

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.WARNING)).contains("Unsaved changes of “Song C” were not kept: the project is open"
                + " read-only.");
        assertThat(listing(songC)).as("nothing written in the read-only folder").isEqualTo(original);
    }

    /** Unsaved changes of a project never saved: L8's never-saved variant. */
    @Test
    void unsavedChangesOfAProjectNeverSavedAreNotKept() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.closeProject();
        fixture.await(() -> fixture.save.project.get().getName().equals("Untitled Project")
                && !fixture.lifecycle().aSwitchIsPending(), "the Untitled project");
        fixture.edit("Sketch");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.WARNING)).contains("Unsaved changes of “Untitled Project” were not kept: the"
                + " project has never been saved.");
    }

    /** A closing checkpoint that fails: L8's last variant, after the closer's own WARNING; the release still runs. */
    @Test
    void unsavedChangesWhoseClosingCheckpointFailsAreNotKept() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.publishes.failNext(SaveFlowFixture::isCheckpoint, new IOException("the disk is full"));
        fixture.edit("Bass");

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        LogRecord closersWarning = logged.stream().filter(entry -> entry.getLevel() == Level.WARNING
                && "The closing checkpoint could not be written; the close goes on".equals(entry.getMessage()))
                .findFirst().orElseThrow();
        assertThat(messages(Level.WARNING)).containsSubsequence(closersWarning.getMessage(), "Unsaved changes of "
                + SONG_A + " were not kept: their closing checkpoint could not be written ("
                + ProjectLifecycleController.failureReason(closersWarning.getThrown()) + ").");
        assertThat(fixture.stepNames()).contains("CLOSING_CHECKPOINT FAILED", "RELEASE DONE");
    }

    /**
     * The studio engineer's final ruling F2: an exit whose FLUSH met a lock
     * another session took, and whose takeover dialog's Save As… is copying
     * when the application stops, is not waited for — the copy registers no
     * pending switch, so the fallback closes the original by the lost lock's
     * row: nothing written into its folder, no closing checkpoint — and L8's
     * Save As variant (L8c) says the copy keeps the changes only if it
     * finished; {@code stop()} returns while the copy is still held.
     */
    @Test
    void aTakeoverSaveAsStillCopyingAtTheStopIsNotWaitedForAndItsLogSaysSo() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);
        byte[] head = Files.readAllBytes(fixture.songA.resolve("project.daw"));
        fixture.save.promptAnswer = "Save and close";
        fixture.save.takeoverAnswer = "Save As…";
        fixture.save.saveAsTargets.answer(call -> Optional.of(call.directory().resolve(call.name())));
        SaveFlowFixture.Publishes.Hold copy = fixture.save.publishes.holdNext(path ->
                path.getFileName().toString().equals("project.daw") && path.toString().contains(".saving-as.tmp"));
        try {
            fixture.closeBox();
            copy.awaitHeld();

            stopped = fixture.stopTheApplication();
            ExitFixture.awaitStopped(stopped);
            assertThat(copy.isHeld()).as("stop() returned while the copy is still held").isTrue();
        } finally {
            copy.release();
        }
        fixture.save.settle();

        assertThat(fixture.save.takeoverHeaders).as("fixture: R7's exit dialog asked once").hasSize(1);
        assertThat(messages(Level.WARNING)).as("L8c").contains("Unsaved changes of " + SONG_A + " were not written"
                + " into its folder: another session holds its lock. The Save As asked for before the application"
                + " stopped keeps them only if its copy finished before the application ended; the application-stop"
                + " fallback does not wait for it.")
                .noneMatch(message -> message.contains("were not kept"));
        assertThat(Files.readAllBytes(fixture.songA.resolve("project.daw"))).as("project.daw never written")
                .isEqualTo(head);
        assertThat(fixture.events).as("no closing checkpoint in a folder another session holds")
                .doesNotContain("checkpoint Written CLOSING");
        assertThat(fixture.stepNames()).contains("DISPOSE DONE");
    }

    // ── nothing opens a modal loop inside the fallback (the stage-8 review B1) ─

    /**
     * The studio engineer's stage-8 S3 and final ruling F1 (the stage-8
     * review B1): a queued save that meets a lock another session took, while
     * the fallback waits for every writer, shows no takeover dialog — the
     * fallback's close is pending, so the offer is held off — which inside
     * its nested wait would hold {@code stop()} until someone answers it; its
     * row reads the loss (L8's lock variant), nothing is written into the
     * folder, and {@code stop()} returns with no answer given.
     */
    @Test
    void aQueuedSaveThatMeetsAStolenLockWhileTheFallbackWaitsOpensNoTakeoverDialog() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        recordTheLifecycleLogAtFine();
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold first = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.save.save();
            first.awaitHeld();
            fixture.edit("Keys");
            new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);
            fixture.save.save();

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the fallback waits for every writer");
        } finally {
            first.release();
        }
        ExitFixture.awaitStopped(stopped);
        fixture.save.settle();

        assertThat(fixture.save.takeoverHeaders).as("no takeover dialog during stop()").isEmpty();
        assertThat(takeoverOffersHeldOff("Application stop")).as("the offer ran, and the gate held it off")
                .hasSize(1);
        assertThat(messages(Level.WARNING)).as("L8, the lock variant").contains("Unsaved changes of " + SONG_A
                + " were not kept: another session holds its lock.");
        assertThat(fixture.events).as("nothing written into the folder")
                .doesNotContain("checkpoint Written CLOSING");
    }

    /**
     * The stage-8 review B1 and the studio engineer's final ruling F1: an
     * offer of the takeover dialog that runs once the fallback began shows
     * nothing — its close stays pending to the end of the process — and the
     * fallback shows nothing (ruling 4 Q6.2 item 4).
     */
    @Test
    void aTakeoverOfferThatRunsOnceTheFallbackBeganShowsNoDialog() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        recordTheLifecycleLogAtFine();
        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        runOnFx(() -> offerSaveAsAfterTakeover(fixture.lifecycle(), fixture.save.progress.projectGeneration(),
                new com.benesquivelmusic.daw.core.persistence.LockStolenException("taken over", null)));
        fixture.save.settle();

        assertThat(fixture.save.takeoverHeaders).isEmpty();
        assertThat(takeoverOffersHeldOff("Application stop")).hasSize(1);
    }

    /**
     * The studio engineer's final ruling F1, the production path (the
     * app-fix-3 review S1): after a Quit anyway, HIDE comes while a save is
     * still held and another is queued behind it, so {@code stop()} waits for
     * the audio engine's stop (ruling 2 OQ-3) — and the queued save that
     * meets a lock another session took meanwhile shows no takeover dialog
     * over the hidden window: the exit's close stays pending after HIDE.
     */
    @Test
    void afterAQuitAnywayASaveThatMeetsAStolenLockDuringTheEngineWaitShowsNoDialog() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        recordTheLifecycleLogAtFine();
        SaveFlowFixture.Publishes.Hold first = aQuitAnywayWithASaveHeldAndOneQueued();
        try {
            fixture.fire(ExitCoordinator.ENGINE_STOP_BOUND);
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                    "stop()'s wait for the engine's stop, 15 s from it");
            first.release();
            awaitTheTakeoverOffer("Exit");

            assertThat(fixture.save.takeoverHeaders).as("no takeover dialog inside stop()'s wait").isEmpty();
            assertThat(takeoverOffersHeldOff("Exit")).hasSize(1);
            assertThat(stopped).as("stop() still waits for the engine's stop").isNotDone();
        } finally {
            first.release();
        }
        fixture.handOff.complete(null);
        ExitFixture.awaitStopped(stopped);
    }

    /**
     * The studio engineer's final ruling F1 on the fallback's past-DISPOSE row
     * (the app-fix-3 review S1): a Quit anyway whose DISPOSE began — HIDE not
     * yet — gets no step from the fallback, which waits for that DISPOSE's
     * hand-off; a queued save that meets a stolen lock meanwhile shows no
     * takeover dialog.
     */
    @Test
    void pastDisposeASaveThatMeetsAStolenLockDuringTheFallbacksWaitShowsNoDialog() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        recordTheLifecycleLogAtFine();
        SaveFlowFixture.Publishes.Hold first = aQuitAnywayWithASaveHeldAndOneQueued();
        try {
            assertThat(fixture.reached(ExitCoordinator.ExitStep.HIDE)).as("fixture: DISPOSE began, HIDE not yet")
                    .isFalse();
            new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                    "the fallback's wait for that DISPOSE's hand-off");
            first.release();
            awaitTheTakeoverOffer("Exit");

            assertThat(fixture.save.takeoverHeaders).as("no takeover dialog inside the fallback's wait").isEmpty();
            assertThat(takeoverOffersHeldOff("Exit")).hasSize(1);
            assertThat(stopped).isNotDone();
        } finally {
            first.release();
        }
        fixture.handOff.complete(null);
        ExitFixture.awaitStopped(stopped);
        assertThat(fixture.disposals).as("DISPOSE once").hasValue(1);
    }

    /**
     * The studio engineer's final ruling F1, an interactive close: while it
     * is pending — here in GUARD, waiting for a held save — a takeover offer
     * shows no dialog (the close meets the loss itself, in the dialog whose
     * Save As can run); once the run is kept, the offer asks again.
     */
    @Test
    void aTakeoverOfferWhileACloseIsPendingShowsNoDialogAndAfterTheKeepItDoes() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        recordTheLifecycleLogAtFine();
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.edit("Bass");
            fixture.save.save();
            head.awaitHeld();
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(),
                    "the close waits in GUARD for the held save");

            runOnFx(() -> offerSaveAsAfterTakeover(fixture.lifecycle(), fixture.save.progress.projectGeneration(),
                    new com.benesquivelmusic.daw.core.persistence.LockStolenException("taken over", null)));
            assertThat(fixture.save.takeoverHeaders).as("no dialog while the close is pending").isEmpty();
            assertThat(takeoverOffersHeldOff("Exit")).hasSize(1);

            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            runOnFx(() -> fixture.surface().keepButton().fire());
            fixture.await(() -> !fixture.coordinator().isWindowInert(), "the run kept, the close ended");
        } finally {
            head.release();
        }
        fixture.save.settle();

        runOnFx(() -> offerSaveAsAfterTakeover(fixture.lifecycle(), fixture.save.progress.projectGeneration(),
                new com.benesquivelmusic.daw.core.persistence.LockStolenException("taken over", null)));
        assertThat(fixture.save.takeoverHeaders).as("kept open: the offer asks again (the fixture answers Not now)")
                .hasSize(1);
    }

    // ── a step that throws, and the transport (the stage-8 review S1; the studio engineer's S2) ─

    /**
     * The stage-8 review S1: a step of the fallback that throws — here GUARD's
     * outcome observer — is logged SEVERE once and skips the rest of the
     * tail, as after a crash — the journal drained, never sealed, the lock
     * left — and DISPOSE still runs, so the audio engine is stopped.
     */
    @Test
    void aStepOfTheFallbackThatThrowsSkipsTheTailAndStillDisposes() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.GUARD && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(messages(Level.SEVERE)).containsOnlyOnce("The application-stop fallback failed: injected");
        assertThat(fixture.stepNames()).containsSubsequence("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                "RELEASE SKIPPED", "DISPOSE DONE");
        assertThat(fixture.disposals).hasValue(1);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock is left").exists();
        fixture.awaitTheJournalEnded();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).as("drained, never sealed")
                .noneMatch(JournalRecord::isSessionSeal);
    }

    /**
     * The stage-8 review S1: the fallback's transport stop is a cleanup — one
     * that throws is logged and the close goes on: sealed, released, plain.
     */
    @Test
    void aTransportStopThatThrowsIsLoggedAndTheFallbacksCloseGoesOn() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.beforeTheTransportStop = () -> {
            if (thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };

        stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertThat(thrown).as("fixture: the stop threw").isTrue();
        assertThat(fixture.stepNames()).contains("GUARD DONE", "SEAL DONE", "RELEASE DONE", "DISPOSE DONE");
        assertTheNextOpenIsPlain();
    }

    /**
     * The studio engineer's stage-8 S2 (ruling R4 step 1, as amended): the
     * fallback stops the transport at once — playback as well as a
     * recording — before it waits for anything: with the project playing and
     * nothing recording, a real transport controller's stop for the close
     * ran and the transport is STOPPED while the fallback still waits for a
     * writer, and no L3 (the studio engineer's final ruling F12: what is
     * pinned is what the user hears).
     */
    @Test
    void theFallbackStopsTheTransportAtOnceWithNothingRecording() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        com.benesquivelmusic.daw.core.transport.Transport transport = onFx(() ->
                fixture.save.project.get().getTransport());
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try (RecordingInFlightFixture playing = RecordingInFlightFixture.audio(fixture.save.project.get())) {
            fixture.transport = playing.controller();
            runOnFx(transport::play);
            assertThat(onFx(transport::getState)).as("fixture: playing")
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);
            fixture.save.save();
            head.awaitHeld();

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the fallback waits for every writer");
            assertThat(onFx(transport::getState)).as("playback stopped while the fallback still waits")
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
            assertThat(fixture.transportStops).as("stopped before any wait ended").hasValue(1);
            assertThat(stopped).isNotDone();
            head.release();
            ExitFixture.awaitStopped(stopped);
        } finally {
            head.release();
            fixture.transport = null;
        }

        assertThat(messages(Level.WARNING)).as("no L3: nothing was recording")
                .noneMatch(message -> message.startsWith("A recording was in progress"));
        assertThat(fixture.steps).extracting(ExitCoordinator.StepOutcome::step).as("the steps as before").containsExactly(
                ExitCoordinator.ExitStep.GUARD, ExitCoordinator.ExitStep.CLOSING_CHECKPOINT,
                ExitCoordinator.ExitStep.SEAL, ExitCoordinator.ExitStep.RELEASE, ExitCoordinator.ExitStep.DISPOSE);
        assertThat(fixture.steps.getFirst().detail()).isEqualTo("nothing was recording");
    }

    /**
     * The studio engineer's stage-8 S4: while a Save As copies, the switch
     * owns the open folder's journal and lock — the fallback runs no closer
     * and drains nothing: L11, the closing checkpoint, the seal and the
     * release skipped, then DISPOSE; no journal end prepared and the
     * original's lock left.
     */
    @Test
    void aSaveAsCopyingWhenTheApplicationStopsGetsNoCloserFromTheFallback() throws Exception {
        AtomicInteger prepared = new AtomicInteger();
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                prepared.incrementAndGet();
                return real.prepare(mode);
            }
        });
        SaveFlowFixture.Publishes.Hold copy = fixture.save.publishes.holdNext(path ->
                path.getFileName().toString().equals("project.daw") && path.toString().contains(".saving-as.tmp"));
        try {
            fixture.save.saveAsTargets.choose(workspace.resolve("Song A copy"));
            runOnFx(() -> fixture.save.saveAsFlow().start(SaveAsFlow.Prefill.COPY));
            copy.awaitHeld();

            stopped = fixture.stopTheApplication();
            ExitFixture.awaitStopped(stopped);

            assertThat(messages(Level.WARNING)).as("L11").contains("Save As was still replacing " + SONG_A + " when the"
                    + " application stopped; the closing checkpoint, the seal and the release are skipped, as after a"
                    + " crash.");
            assertThat(fixture.stepNames()).containsExactly("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                    "RELEASE SKIPPED", "DISPOSE DONE");
            assertThat(prepared).as("no journal end prepared by the fallback").hasValue(0);
            assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the original's lock is left")
                    .exists();
        } finally {
            copy.release();
        }
        fixture.save.settle();
    }

    /**
     * The studio engineer's final ruling F11 (the app-fix-3 review S2): a
     * step of the fallback that throws while a Save As copies — here the
     * observer of L11's first SKIPPED step — is logged SEVERE once, and its
     * catch still runs no closer and drains nothing: the switch owns the open
     * folder. The steps left are skipped, DISPOSE runs, no journal end is
     * prepared and the original's lock is left.
     */
    @Test
    void aStepThatThrowsWhileASaveAsCopiesStillGivesTheFallbackNoCloser() throws Exception {
        AtomicInteger prepared = new AtomicInteger();
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, real -> new JournalEnding() {
            @Override
            public void detach() {
                real.detach();
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                prepared.incrementAndGet();
                return real.prepare(mode);
            }
        });
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.CLOSING_CHECKPOINT
                    && outcome.result() == ExitCoordinator.StepResult.SKIPPED && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };
        SaveFlowFixture.Publishes.Hold copy = fixture.save.publishes.holdNext(path ->
                path.getFileName().toString().equals("project.daw") && path.toString().contains(".saving-as.tmp"));
        try {
            fixture.save.saveAsTargets.choose(workspace.resolve("Song A copy"));
            runOnFx(() -> fixture.save.saveAsFlow().start(SaveAsFlow.Prefill.COPY));
            copy.awaitHeld();

            stopped = fixture.stopTheApplication();
            ExitFixture.awaitStopped(stopped);

            assertThat(thrown).as("fixture: the observer threw").isTrue();
            assertThat(messages(Level.SEVERE)).containsOnlyOnce("The application-stop fallback failed: injected");
            assertThat(prepared).as("no journal end prepared by the fallback, its catch included").hasValue(0);
            assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the original's lock is left")
                    .exists();
            assertThat(fixture.stepNames()).containsExactly("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                    "RELEASE SKIPPED", "DISPOSE DONE");
        } finally {
            copy.release();
        }
        fixture.save.settle();
    }

    /**
     * The studio engineer's final ruling F11 (the app-fix-3 review N4): on
     * the past-DISPOSE row a throw inside the fallback — here its read of the
     * project's name, the open project gone from its supplier — is logged
     * SEVERE, and the audio engine's stop that DISPOSE began is still waited
     * for, up to 15 s from its start (ruling 2 OQ-3); no step, no second
     * DISPOSE.
     */
    @Test
    void aThrowPastDisposeStillWaitsForThatDisposesHandOff() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.handOff = new CompletableFuture<>();
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        List<String> before = fixture.stepNames();
        com.benesquivelmusic.daw.core.project.DawProject songA = onFx(fixture.save.project::get);
        try {
            // The supplier answers nothing in the stop's own turn, so the fallback's read of the readiness throws.
            javafx.application.Platform.runLater(() -> fixture.save.project.set(null));
            stopped = fixture.stopTheApplication();
            fixture.await(() -> stopped.isDone()
                            || !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                    "the stop returned, or waits for the engine's stop");
        } finally {
            runOnFx(() -> fixture.save.project.set(songA));
        }

        assertThat(stopped).as("the hand-off DISPOSE began is still waited for").isNotDone();
        assertThat(fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND)).as("up to 15 s from it")
                .isNotEmpty();
        assertThat(messages(Level.SEVERE)).filteredOn(message -> message.startsWith(
                "The application-stop fallback failed: ")).hasSize(1);
        fixture.handOff.complete(null);
        ExitFixture.awaitStopped(stopped);
        assertThat(fixture.stepNames()).as("no step").isEqualTo(before);
        assertThat(fixture.disposals).as("DISPOSE once").hasValue(1);
    }

    // ── a decided close with no closer yet, and the one deadline (the stage-8 review S4, S5) ─

    /**
     * The stage-8 review S4 (a): a Save and close whose FLUSH save is still
     * running when the stop comes is waited for with the writers, then its
     * decision's row runs — the save landed, so the journal is sealed, no
     * unsaved changes are accounted for and the next open is plain; GUARD
     * is the run's, published once.
     */
    @Test
    void aSaveAndCloseWhoseSaveIsStillRunningIsWaitedForAndItsRowRuns() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Save and close";
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.closeBox();
            head.awaitHeld();
            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the fallback waits for every writer");
        } finally {
            head.release();
        }
        ExitFixture.awaitStopped(stopped);

        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8))
                .as("the Save and close saved the edit").contains("Bass");
        assertThat(messages(Level.INFO)).as("no L7: nothing was left unsaved")
                .noneMatch(message -> message.startsWith("Unsaved changes of"));
        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("GUARD")).as("the run's GUARD, once")
                .containsExactly("GUARD DONE");
        assertTheNextOpenIsPlain();
    }

    /**
     * The stage-8 review S4 (b): the same Save and close whose save fails —
     * the project is left with unsaved changes, so the fallback's row applies
     * (ruling 4 Q6.2 item 1): one closing checkpoint, the journal unsealed,
     * L7.
     */
    @Test
    void aSaveAndCloseWhoseSaveFailsTakesTheFallbacksRow() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Save and close";
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.publishes.failNext(SaveFlowFixture::isHead, new IOException("the volume went away"));
        try {
            fixture.closeBox();
            head.awaitHeld();
            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the fallback waits for every writer");
        } finally {
            head.release();
        }
        ExitFixture.awaitStopped(stopped);

        assertThat(fixture.events).as("one closing checkpoint").containsOnlyOnce("checkpoint Written CLOSING");
        Path closing = SaveFlowFixture.checkpointFiles(fixture.songA).getLast();
        assertThat(messages(Level.INFO)).as("L7").contains("Unsaved changes of " + SONG_A + " were kept in checkpoint "
                + closing.getFileName() + "; the journal is left unsealed, so the next open offers to recover them.");
        assertRecoveryIsOfferedFrom(closing);
    }

    /**
     * The stage-8 review S5: every wait shares the one deadline, 30 s from
     * the stop — 20 s spent on the take leave the writers' wait 10 s, and its
     * end is L5.
     */
    @Test
    void everyWaitSharesTheOneDeadline() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(() -> takes.beingWritten(true));
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.save.save();
            head.awaitHeld();
            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the wait for the take");
            fixture.nanos.addAndGet(Duration.ofSeconds(20).toNanos());
            runOnFx(takes::published);
            fixture.await(() -> !fixture.timers.pending(Duration.ofSeconds(10)).isEmpty(),
                    "the writers' wait, 10 s left of the one deadline");
            assertThat(fixture.timers.pending(DEADLINE)).as("no fresh 30 s").isEmpty();
            fixture.fire(Duration.ofSeconds(10));
            ExitFixture.awaitStopped(stopped);
        } finally {
            head.release();
        }

        assertThat(messages(Level.WARNING)).as("L5").contains(L5);
        assertThat(fixture.stepNames()).containsExactly("GUARD DONE", "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED",
                "RELEASE SKIPPED", "DISPOSE DONE");
    }

    // ── a joined Close Project: its logs, and an exit asked after its release (the stage-8 review N1, N2, N11) ─

    /**
     * The stage-8 review N1: an upgraded Close Project's Discard, whose
     * closed() logged R3's line before D11's wait for every writer, is
     * joined there by the stop: R3's line is not logged again.
     */
    @Test
    void anUpgradedCloseProjectJoinedInItsExitsWaitLogsRThreeOnce() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        SaveFlowFixture.Publishes.Hold heldA = aCloseProjectOfSongBWithSongAsCheckpointHeld();
        try {
            fixture.edit("Bass");
            fixture.save.promptAnswer = "Discard and close";
            // The exit that upgrades Close Project, asked at its FLUSH — before its release began (ruling Q8).
            fixture.onStep = outcome -> {
                if (outcome.step() == ExitCoordinator.ExitStep.FLUSH) {
                    fixture.coordinator().request(new ExitCoordinator.CloseDoor.FileExit());
                }
            };
            fixture.closeProject();
            fixture.await(() -> fixture.stepNames().contains("RELEASE DONE"), "Close Project's own release");
            fixture.save.settle();
            assertThat(discardedLines()).as("closed() logged R3's line").hasSize(1);

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the stop joins the exit's wait");
        } finally {
            heldA.release();
        }
        ExitFixture.awaitStopped(stopped);

        assertThat(discardedLines()).as("R3's line once").hasSize(1);
        assertThat(fixture.disposals).hasValue(1);
    }

    /**
     * The stage-8 review N2: the deadline passing in a joined Close Project's
     * wait for every writer and the retired leases — its close had ended —
     * skips nothing: not L6, but a line that says only these waits were cut.
     */
    @Test
    void theDeadlineInAJoinedCloseProjectsReleaseWaitSkipsNothing() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        SaveFlowFixture.Publishes.Hold heldA = aCloseProjectOfSongBWithSongAsCheckpointHeld();
        try {
            fixture.edit("Bass");
            fixture.save.promptAnswer = "Discard and close";
            SaveFlowFixture.Publishes.Hold heldB = fixture.save.publishes.holdNext(
                    target -> SaveFlowFixture.isCheckpoint(target) && !target.startsWith(fixture.songA));
            try {
                fixture.closeProject();
                heldB.awaitHeld();
                stopped = fixture.stopTheApplication();
                fixture.await(() -> !fixture.nestedLoopsEntered.isEmpty(), "the stop joins Close Project");
            } finally {
                heldB.release();
            }
            fixture.await(() -> fixture.stepNames().contains("RELEASE DONE") && fixture.nestedLoopsEntered.size() == 2
                    && fixture.timers.pending(DEADLINE).size() == 1, "the joined close's release wait");
            fixture.fire(DEADLINE);
            ExitFixture.awaitStopped(stopped);
        } finally {
            heldA.release();
        }

        assertThat(messages(Level.WARNING)).contains("A save, a checkpoint or a lock's release was still running 30"
                + " seconds after the application stopped; the application stop goes on without waiting for it.")
                .noneMatch(message -> message.startsWith("The close of “Song B” had not finished"));
        assertThat(fixture.stepNames()).as("nothing skipped").doesNotContain("RELEASE SKIPPED").contains("DISPOSE DONE");
    }

    /**
     * The stage-8 review N11: an exit asked after Close Project's release —
     * its run then waits for that exit — completes too when the fallback
     * takes the close over: {@code Cancelled}, as the close itself.
     */
    @Test
    void anExitAskedAfterCloseProjectsReleaseCompletesWhenTheFallbackTakesItOver() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        AtomicReference<java.util.concurrent.CompletionStage<ExitCoordinator.CloseOutcome>> exitAfter =
                new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> stop = new AtomicReference<>();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.RELEASE && outcome.result() == ExitCoordinator.StepResult.DONE
                    && exitAfter.get() == null) {
                exitAfter.set(fixture.coordinator().request(new ExitCoordinator.CloseDoor.FileExit()));
                // Posted before Close Project's own end, which the release's outcome posts next.
                stop.set(fixture.stopTheApplication());
            }
        };

        fixture.closeProject();
        fixture.await(() -> stop.get() != null, "the stop, asked at Close Project's release");
        stopped = stop.get();
        ExitFixture.awaitStopped(stopped);

        assertThat(exitAfter.get().toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
    }

    // ── the scheduled checkpoints around the fallback's DISPOSE ─────────────
    //    (the studio engineer's ruling on app fix pass 2's Java items, §1.6)

    /**
     * C1: before its own DISPOSE the fallback pauses no tick — an edit made
     * on the FX thread during its wait for the take, as a late publication
     * makes one, reaches a SCHEDULED checkpoint while it waits.
     */
    @Test
    void ticksKeepCapturingWhileTheFallbackWaitsForTheTake() throws Exception {
        fixture = new ExitFixture(workspace, EVERY_200_MS);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(() -> takes.beingWritten(true));

        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the fallback's wait for the take");
        long late = editOnTheFxThread("Late");
        fixture.await(() -> fixture.save.checkpointEvents.stream().anyMatch(event ->
                        event instanceof CheckpointEvent.Written written
                                && written.origin() == CheckpointOrigin.SCHEDULED && written.revision() == late),
                "a scheduled checkpoint of the edit made during the wait");
        assertThat(stopped).as("while the fallback still waits").isNotDone();

        runOnFx(takes::published);
        ExitFixture.awaitStopped(stopped);
    }

    /**
     * C2 and C5: the take not published by the deadline — L4's row, RELEASE
     * skipped, so the checkpoint generation runs on with the lock. After the
     * fallback's DISPOSE no tick captures the project: an edit then is
     * skipped, the M1 hook's wait finds nothing more, and the lock stays.
     */
    @Test
    void noTickCapturesTheProjectOnceTheFallbacksDisposeRan() throws Exception {
        fixture = new ExitFixture(workspace, EVERY_200_MS);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(() -> takes.beingWritten(true));
        stopped = fixture.stopTheApplication();
        fixture.await(() -> !fixture.timers.pending(DEADLINE).isEmpty(), "the fallback's wait for the take");
        fixture.fire(DEADLINE);
        ExitFixture.awaitStopped(stopped);
        assertThat(fixture.stepNames()).contains("RELEASE SKIPPED", "DISPOSE DONE");

        assertNoTickCapturesAnEditMadeNow();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock is left").exists();
        runOnFx(takes::published);
    }

    /**
     * C3, ruling 4 Q6.2 item 1's last row as amended: a run whose DISPOSE was
     * a Quit anyway before its RELEASE gets no step from the fallback — no
     * closing checkpoint — and no DISPOSE again; only that DISPOSE's hand-off
     * is waited for; and no tick captures the project after it.
     */
    @Test
    void aQuitAnywayBeforeTheReleaseGivesTheFallbackNoStepAndNoTickCapturesAfterIt() throws Exception {
        fixture = new ExitFixture(workspace, EVERY_200_MS);
        fixture.handOff = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicLong revisionAtDispose = new java.util.concurrent.atomic.AtomicLong(-1);
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.DISPOSE) {
                revisionAtDispose.compareAndSet(-1, fixture.save.project.get().getEditRevision());
            }
        };
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.edit("Bass");
            fixture.save.save();
            head.awaitHeld();
            fixture.closeBox();
            fixture.await(() -> fixture.fence().isRaised(), "the close waits for the held save");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            fixture.fire(ExitCoordinator.NOTE_DELAY);
            fixture.await(() -> fixture.surface() != null && fixture.surface().offerButton().isVisible(),
                    "Quit anyway is offered");
            runOnFx(() -> fixture.surface().offerButton().fire());
            fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
            List<String> atDispose = fixture.stepNames();
            assertThat(atDispose).as("fixture: DISPOSE before RELEASE").contains("RELEASE SKIPPED");
            // The held save is a writer the M1 hook waits for too: once DISPOSE began it may end — and it has
            // landed before the edit below, so nothing but the gate keeps a tick from capturing that edit.
            head.release();
            fixture.save.settle();

            stopped = fixture.stopTheApplication();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.ENGINE_STOP_AFTER_HIDE_BOUND).isEmpty(),
                    "the wait for that DISPOSE's hand-off");
            // Asserted before the tick helper, so a step or a second DISPOSE is named as itself (the app-fix-3
            // review N1).
            assertThat(fixture.stepNames()).as("no step from the fallback").isEqualTo(atDispose);
            assertThat(fixture.disposals).as("DISPOSE once, the run's own").hasValue(1);
            assertNoTickCapturesAnEditMadeNow();
            fixture.handOff.complete(null);
            ExitFixture.awaitStopped(stopped);

            assertThat(fixture.stepNames()).as("no step").isEqualTo(atDispose);
            assertThat(fixture.disposals).as("DISPOSE once").hasValue(1);
            assertThat(fixture.events).as("no closing checkpoint after DISPOSE").doesNotContain(
                    "checkpoint Written CLOSING");
            // A capture taken before DISPOSE is written as usual (the ruling on app fix pass 2's Java items,
            // §1.2); none taken after it.
            assertThat(revisionAtDispose.get()).as("fixture: DISPOSE's revision recorded").isNotNegative();
            assertThat(fixture.save.checkpointEvents).as("nothing captured after DISPOSE is written")
                    .noneMatch(event -> event instanceof CheckpointEvent.Written written
                            && written.revision() > revisionAtDispose.get());
        } finally {
            head.release();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * In one FX turn an edit no tick has captured — the validity check — then
     * a tick skipped after it, the M1 hook's wait, and no checkpoint of it.
     */
    private void assertNoTickCapturesAnEditMadeNow() throws Exception {
        java.util.concurrent.atomic.AtomicLong late = new java.util.concurrent.atomic.AtomicLong();
        AtomicInteger mark = new AtomicInteger();
        runOnFx(() -> {
            com.benesquivelmusic.daw.core.project.DawProject project = fixture.save.project.get();
            project.createAudioTrack("Late");
            project.markDirty();
            late.set(project.getEditRevision());
            assertThat(fixture.save.projectManager.isCheckpointed(project))
                    .as("the validity check: a tick would capture this revision").isFalse();
            mark.set(fixture.save.checkpointEvents.size());
        });
        // Three ticks skipped after the edit: a skip from a tick that ran before it may be recorded after the mark,
        // but with the gate open the edit is captured before a second tick after it could skip. The wait also ends
        // on a checkpoint of the edit, so the assertion below names that defect (the app-fix-3 review N1).
        fixture.await(() -> fixture.save.checkpointEvents.stream().skip(mark.get()).filter(event ->
                        event instanceof CheckpointEvent.Rearmed rearmed
                                && rearmed.reason() == CheckpointEvent.RearmReason.SKIPPED).count() >= 3
                        || fixture.save.checkpointEvents.stream().anyMatch(event ->
                                event instanceof CheckpointEvent.Written written && written.revision() == late.get()),
                "three ticks skipped after DISPOSE, or a checkpoint of the edit");
        assertThat(fixture.save.projectManager.awaitWritesBeforeExit()).as("the M1 hook's wait").isTrue();
        assertThat(fixture.save.checkpointEvents).as("no checkpoint of the edited revision").noneMatch(event ->
                event instanceof CheckpointEvent.Written written && written.revision() == late.get());
    }

    /**
     * Song A edited and saved with its head held at the publish, edited again
     * and saved again — a save queued behind the held one, which meets
     * whatever lock is on disk when it runs; the close box, then Quit anyway
     * once its note offers it (ruling R8): DISPOSE began, its hand-off held,
     * HIDE not yet. Returns the held save's hold.
     */
    private SaveFlowFixture.Publishes.Hold aQuitAnywayWithASaveHeldAndOneQueued() throws Exception {
        fixture.handOff = new CompletableFuture<>();
        SaveFlowFixture.Publishes.Hold first = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.edit("Bass");
        fixture.save.save();
        first.awaitHeld();
        fixture.edit("Keys");
        fixture.save.save();
        fixture.closeBox();
        fixture.await(() -> fixture.fence().isRaised(), "the close waits for the held save");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        fixture.await(() -> fixture.surface() != null && fixture.surface().offerButton().isVisible(),
                "Quit anyway is offered");
        runOnFx(() -> fixture.surface().offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        return first;
    }

    /**
     * Waits, bounded, until the takeover offer of a save that met a lost lock
     * ran: held off while {@code closing} closes the open project, or shown —
     * so a dialog is named by the assertion that follows, not by a timeout.
     */
    private void awaitTheTakeoverOffer(String closing) throws Exception {
        fixture.await(() -> !takeoverOffersHeldOff(closing).isEmpty() || !fixture.save.takeoverHeaders.isEmpty(),
                "the takeover offer of the save that met the lost lock");
    }

    /** An edit of the open project on the FX thread — a track added, the project dirty; returns its revision. */
    private long editOnTheFxThread(String track) throws Exception {
        java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
        runOnFx(() -> {
            com.benesquivelmusic.daw.core.project.DawProject project = fixture.save.project.get();
            project.createAudioTrack(track);
            project.markDirty();
            revision.set(project.getEditRevision());
        });
        return revision.get();
    }

    /**
     * Song B open in Song A's place while Song A's labelled checkpoint is
     * held at its publish — so the exit's or the fallback's wait for every
     * writer and the retired leases waits on it; returns that hold.
     */
    private SaveFlowFixture.Publishes.Hold aCloseProjectOfSongBWithSongAsCheckpointHeld() throws Exception {
        SaveFlowFixture save = fixture.save;
        Path songA = save.songA;
        Path songB = save.anotherProjectOnDisk("Song B");
        long songAGeneration = save.checkpoints.runningGeneration().orElseThrow();
        save.awaitCheckpointsSeeded(songAGeneration - 1);
        SaveFlowFixture.Publishes.Hold heldA = save.publishes.holdNext(
                target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(songA));
        runOnFx(() -> save.snapshots().createCheckpointWithLabel("Held in Song A"));
        heldA.awaitHeld();
        runOnFx(() -> {
            save.lifecycle().setOpenProjectPrompt(() -> songB);
            save.lifecycle().onOpenProject();
        });
        save.awaitOnFx(() -> save.currentProjectIs(songB) && !save.lifecycle().isProjectInstallationPending(),
                "Song B installed");
        save.awaitCheckpointsSeeded(songAGeneration);
        return heldA;
    }

    private List<String> discardedLines() {
        return messages(Level.INFO).stream()
                .filter(message -> message.startsWith("Discarded changes of “Song B” were kept in checkpoint")).toList();
    }

    /** The lifecycle controller's takeover offer, run directly (a private method; a fixture, not a consumer). */
    private static void offerSaveAsAfterTakeover(ProjectLifecycleController lifecycle, long generation,
                                                 com.benesquivelmusic.daw.core.persistence.LockStolenException stolen) {
        try {
            java.lang.reflect.Method offer = ProjectLifecycleController.class.getDeclaredMethod(
                    "offerSaveAsAfterTakeover", long.class,
                    com.benesquivelmusic.daw.core.persistence.LockStolenException.class);
            offer.setAccessible(true);
            offer.invoke(lifecycle, generation, stolen);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** The application's own disposal over the blocking-engine seam ({@link WindowLifetimeDisposalTest}). */
    private MainController theApplicationsDisposal(WindowLifetimeDisposalTest.HeldEngine engine, boolean fxPartFails)
            throws Exception {
        MainController main = fxPartFails
                ? WindowLifetimeDisposalTest.partialApplicationWhoseFxPartFails(fixture, engine)
                : WindowLifetimeDisposalTest.partialApplication(fixture, engine);
        fixture.windowDisposal = main::disposeWindowLifetime;
        return main;
    }

    /** The close box, the engine's stop held, HIDE at {@link ExitCoordinator#ENGINE_STOP_BOUND}; the clock then 5 s on. */
    private void hideAtTheFiveSecondBound(WindowLifetimeDisposalTest.HeldEngine engine) throws Exception {
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        engine.awaitStopping();
        fixture.save.settle();
        assertThat(fixture.reached(ExitCoordinator.ExitStep.HIDE)).isFalse();
        fixture.nanos.addAndGet(ExitCoordinator.ENGINE_STOP_BOUND.toNanos());
        fixture.fire(ExitCoordinator.ENGINE_STOP_BOUND);
        assertThat(fixture.stepNames()).as("HIDE at 5 s").endsWith("HIDE DONE");
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /** {@code DawApplication.stop()}'s call, {@code MainController.applicationStopping()}, posted, never waited on. */
    private static CompletableFuture<Void> stop(MainController main) {
        CompletableFuture<Void> returned = new CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                main.applicationStopping();
                returned.complete(null);
            } catch (Throwable failed) {
                returned.completeExceptionally(failed);
            }
        });
        return returned;
    }

    /** A stop left in a wait by a failed assertion is let go — its holds released, its deadlines fired — bounded. */
    private void unwindTheStop() throws Exception {
        if (stopped == null || stopped.isDone()) {
            return;
        }
        fixture.handOff.complete(null);
        fixture.save.publishes.releaseAll();
        fixture.await(() -> {
            for (ManualCloseTimers.Armed timer : fixture.timers.armed()) {
                if (!timer.cancelled() && !timer.fired()) {
                    fixture.timers.fire(timer);
                }
            }
            return stopped.isDone();
        }, "the stop let go");
    }

    private List<String> messages(Level level) {
        return logged.stream().filter(entry -> entry.getLevel() == level).map(LogRecord::getMessage).toList();
    }

    /**
     * Ruling 4 Q6.3's L7b for Song A, as the studio engineer's final ruling F4
     * words it: true whenever no journal was open at the detach — journaling
     * off, or paused.
     */
    private static String l7b(Path closing) {
        return "Unsaved changes of " + SONG_A + " were kept in checkpoint " + closing.getFileName() + "; no journal was"
                + " open for the project, so the next open does not offer to recover them.";
    }

    /** The lifecycle controller's log recorded down to FINE, for the test's length (the takeover offer's line). */
    private void recordTheLifecycleLogAtFine() {
        Level before = lifecycleLog.getLevel();
        lifecycleLog.setLevel(Level.FINE);
        lifecycleLog.addHandler(recording);
        lifecycleLogRestore = () -> {
            lifecycleLog.removeHandler(recording);
            lifecycleLog.setLevel(before);
        };
    }

    /** The FINE lines of the takeover offer held off while {@code closing} closes the open project. */
    private List<String> takeoverOffersHeldOff(String closing) {
        return messages(Level.FINE).stream().filter(String.format(NO_TAKEOVER_DIALOG, closing)::equals).toList();
    }

    private void assertTheLockIsReleased() throws Exception {
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock is released").doesNotExist();
    }

    /** The journal owes a decision whose recovery point is {@code closing} (ruling R4: the RecoveryDialog offers it). */
    private void assertRecoveryIsOfferedFrom(Path closing) throws IOException {
        ProjectContext context = ProjectContext.forProject(fixture.songA);
        assertThat(JournalState.records(context)).as("left unsealed").noneMatch(JournalRecord::isSessionSeal);
        assertThat(JournalState.needsRecovery(context)).as("the next open owes a decision").isTrue();
        Optional<RecoveryPlan> plan = new RecoveryScanner().plan(context, RecoveryTemps.inspect(fixture.songA), true);
        assertThat(plan).isPresent();
        assertThat(plan.get().recovery()).isInstanceOfSatisfying(RecoveryPlan.ReplayJournal.class, replay ->
                assertThat(replay.base()).isInstanceOfSatisfying(RecoveryPlan.CheckpointCapture.class, capture ->
                        assertThat(capture.file().getFileName()).as("its recovery point is the closing checkpoint")
                                .isEqualTo(closing.getFileName())));
    }

    /** The lock gone, the journal sealed, nothing owed, and a fresh manager's recovery gate opens it plainly. */
    private void assertTheNextOpenIsPlain() throws Exception {
        assertTheLockIsReleased();
        ProjectContext context = ProjectContext.forProject(fixture.songA);
        assertThat(JournalState.records(context).getLast().isSessionSeal()).as("the journal is sealed").isTrue();
        assertThat(JournalState.needsRecovery(context)).as("no recovery decision is owed").isFalse();
        AtomicInteger decisions = new AtomicInteger();
        ProjectManager next = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT.withEnabled(false)));
        OpenRecoveryGate gate = new OpenRecoveryGate(next, () -> true, _ -> {
            decisions.incrementAndGet();
            return RecoveryChoice.CANCEL;
        }, (_, _, _) -> { throw new AssertionError("a plain open replaces no head"); });
        AtomicReference<OpenRecoveryGate.Result> result = new AtomicReference<>();
        next.setRecoveryGate((directory, readOnly, lock) -> result.set(gate.run(directory, readOnly, lock)));
        CompletableFuture<Void> opened = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (ProjectManager.PreparedOpen ignored = next.prepareOpen(fixture.songA)) {
                opened.complete(null);
            } catch (Throwable failure) {
                opened.completeExceptionally(failure);
            }
        });
        opened.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result.get()).isEqualTo(OpenRecoveryGate.Result.PLAIN);
        assertThat(decisions).hasValue(0);
    }

    private static Map<String, FileTime> listing(Path folder) throws IOException {
        Map<String, FileTime> entries = new TreeMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.toList()) {
                entries.put(folder.relativize(file).toString(), Files.getLastModifiedTime(file));
            }
        }
        return entries;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(ExitFixture.GUARD.toMillis() * 3, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
