package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Lock-lifecycle" (the studio engineer's
 * finding, the closing checkpoint, and rulings R3, B1; Persistence Integrity
 * book §3.2, §5.4): after a clean exit the lock file is gone and the journal
 * is sealed, so the next launch's recovery gate (story 332) classifies the
 * project as clean and opens it plainly — also after "Ctrl+S, play it back,
 * quit", the commonest way a session ends, whose transport records the
 * closing checkpoint covers; with autosave off; after Discard and close; and
 * with records published right before the journal's detach and, from
 * another thread, right after it — which the detach, the close's point of no
 * return, keeps out of the journal.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CleanExitOpensPlainlyTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @AfterEach
    void close() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void saveThenPlaybackThenTheCloseBoxSealsTheJournalReleasesTheLockAndTheNextOpenIsPlain() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        fixture.save.save();
        fixture.save.settle();
        playItBack();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertTheNextOpenIsPlain();
        assertThat(fixture.save.projectManager.whenWritersIdle()).as("both writers idle after HIDE")
                .succeedsWithin(ExitFixture.GUARD);
    }

    /**
     * Ruling R3's trigger (i), the final prober's P03 (the studio engineer's
     * final ruling F16): Ctrl+S, then the close box with nothing recorded
     * since the save's checkpoint placed its marker — no transport, no edit —
     * writes no closing checkpoint: CLOSING_CHECKPOINT is SKIPPED, the
     * checkpoint folder holds what it held after the save, and the next open
     * is plain. A closing checkpoint at every clean exit would push older
     * checkpoints out at the cap.
     */
    @Test
    void saveThenTheCloseBoxWithNoRecordSinceTheSaveWritesNoClosingCheckpoint() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
        fixture.save.save();
        fixture.save.settle();
        fixture.save.projectManager.whenWritersIdle().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(fixture.events).as("fixture: the save's own checkpoint, which places the marker")
                .contains("checkpoint Written AFTER_SAVE");
        List<Path> afterTheSave = SaveFlowFixture.checkpointFiles(fixture.songA);

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).contains("CLOSING_CHECKPOINT SKIPPED");
        assertThat(fixture.steps).filteredOn(step -> step.step() == ExitCoordinator.ExitStep.CLOSING_CHECKPOINT)
                .singleElement().satisfies(step -> assertThat(step.detail()).isEqualTo("no closing checkpoint is owed"));
        assertThat(fixture.events).as("no closing checkpoint").doesNotContain("checkpoint Written CLOSING");
        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).as("the checkpoints as the save left them")
                .isEqualTo(afterTheSave);
        assertTheNextOpenIsPlain();
    }

    @Test
    void saveAndCloseWithAutosaveDisabledIsCleanToo() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT.withEnabled(false));
        fixture.edit("Bass");
        playItBack();
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8)).contains("Bass");
        assertTheNextOpenIsPlain();
    }

    @Test
    void discardAndCloseIsClean() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.save.save();
        fixture.save.settle();
        fixture.edit("Bass");
        playItBack();
        fixture.save.promptAnswer = "Discard and close";

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertTheNextOpenIsPlain();
    }

    @Test
    void recordsPublishedRightBeforeAndRightAfterTheDetachLeaveTheNextOpenPlain() throws Exception {
        AtomicReference<Thread> late = new AtomicReference<>();
        AtomicReference<ExitFixture> self = new AtomicReference<>();
        UnaryOperator<JournalEnding> lateRecords = real -> new JournalEnding() {
            @Override
            public void detach() {
                // (a) the turn before the detach: a record the journal still takes, which the checkpoint covers.
                self.get().bus.publish(new TransportEvent.Stopped(0L, Instant.now()));
                real.detach();
                // (b) after the detach, from another thread (a device rescue's fallback): never journaled.
                if (late.get() == null) {
                    late.set(Thread.ofVirtual().start(() -> self.get().bus.publish(new BackendFallbackEvent(
                            "ASIO", "Interface", "WASAPI", "Speakers", "the driver went away"))));
                }
            }

            @Override
            public Runnable prepare(JournalEnd mode) {
                return real.prepare(mode);
            }
        };
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT, true, lateRecords);
        self.set(fixture);
        fixture.edit("Bass");
        fixture.save.save();
        fixture.save.settle();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        late.get().join(ExitFixture.GUARD.toMillis());

        assertTheNextOpenIsPlain();
    }

    /**
     * The post-roll variant (design §4.1; the studio engineer's B2, FEAS-B4):
     * a two-bar post-roll is running at the close box — the user's Stop is
     * already in it — and Save and close is chosen. The close's Stop, a real
     * transport controller's {@code stopForClose()} as the application wires
     * it ({@code ExitWiringSourceTest}), ends the post-roll before FLUSH's
     * capture: while the head that capture renders is being published the
     * transport is already stopped, out of its post-roll, and its Stopped is
     * journaled; no transport event follows the detach, and the next open is
     * plain. The head's publish is held through the fixture's mover, never by
     * a sleep; the post-roll's own timer is real time, two bars, so a close
     * that did not end it would find the transport still in it.
     */
    @Test
    void aPostRollRunningAtTheCloseBoxEndsBeforeTheCaptureAndTheNextOpenIsPlain() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        com.benesquivelmusic.daw.sdk.event.EventBus previous =
                com.benesquivelmusic.daw.core.event.EventBusPublisher.getDefault();
        com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(fixture.bus);
        try (RecordingInFlightFixture playing = RecordingInFlightFixture.audio(fixture.save.project.get())) {
            fixture.transport = playing.controller();
            fixture.edit("Bass");
            com.benesquivelmusic.daw.core.transport.Transport transport = fixture.save.project.get().getTransport();
            fixture.bus.onPublication(TransportEvent.class,
                    event -> fixture.events.add("transport " + event.getClass().getSimpleName()));
            runOnFx(() -> {
                transport.setPreRollPostRoll(new com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll(0, 2, true));
                fixture.bus.publish(new TransportEvent.Started(0L, Instant.now()));
                transport.play();
                playing.controller().stop();
                assertThat(transport.isInPostRoll()).as("fixture: the user's Stop entered the post-roll").isTrue();
            });
            fixture.save.promptAnswer = "Save and close";
            SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
            try {
                fixture.closeBox();
                head.awaitHeld();
                // Recorded on the acting thread, right before the close's stop (the stage-7 review N5): the
                // post-roll was still running when the close stopped it, so the case never rests on speed.
                assertThat(fixture.transportBeforeStops).as("the close's stop met the running post-roll")
                        .isNotEmpty().first().asString().endsWith(" post-roll");
                runOnFx(() -> {
                    assertThat(transport.getState()).as("stopped before the capture")
                            .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
                    assertThat(transport.isInPostRoll()).as("and out of its post-roll").isFalse();
                });
                assertThat(fixture.events).as("the post-roll's Stopped was journaled before the capture")
                        .contains("transport Stopped");
            } finally {
                head.release();
            }
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            List<String> events = List.copyOf(fixture.events);
            int detached = events.indexOf(events.stream().filter(event -> event.startsWith("step CLOSING_CHECKPOINT"))
                    .findFirst().orElseThrow());
            assertThat(events.subList(detached, events.size())).as("no transport event after the detach")
                    .noneMatch(event -> event.startsWith("transport "));
            assertTheNextOpenIsPlain();
        } finally {
            com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(previous);
        }
    }

    /** Ctrl+S, then playback: transport records that mark nothing dirty (the studio engineer's finding). */
    private void playItBack() throws Exception {
        runOnFx(() -> {
            fixture.bus.publish(new TransportEvent.Started(0L, Instant.now()));
            fixture.bus.publish(new TransportEvent.Stopped(48_000L, Instant.now()));
        });
    }

    private void assertTheNextOpenIsPlain() throws Exception {
        Path songA = fixture.songA;
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("the lock file is gone").doesNotExist();
        ProjectContext context = ProjectContext.forProject(songA);
        List<JournalRecord> records = JournalState.records(context);
        assertThat(records.getLast().isSessionSeal()).as("the journal is sealed").isTrue();
        assertThat(JournalState.needsRecovery(context)).as("no recovery decision is owed").isFalse();
        assertThat(ProjectHealthScanner.isRecoverable(songA, InstantSource.system())).isFalse();
        assertThat(manifestText(songA)).as("the sealed manifest names the segment the seal landed in")
                .contains(newestSegment(songA));

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
            try (ProjectManager.PreparedOpen ignored = next.prepareOpen(songA)) {
                opened.complete(null);
            } catch (Throwable failure) {
                opened.completeExceptionally(failure);
            }
        });
        opened.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(result.get()).as("the recovery gate opens it plainly").isEqualTo(OpenRecoveryGate.Result.PLAIN);
        assertThat(decisions).as("and asks nothing").hasValue(0);
        assertThat(JournalState.records(context)).as("the plain open retired the sealed journal").isEmpty();
    }

    private static String newestSegment(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder.resolve("journal"))) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.startsWith("segment-"))
                    .sorted().reduce((first, second) -> second).orElseThrow();
        }
    }

    private static String manifestText(Path folder) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(folder.resolve("sessions"))) {
            for (Path file : files.sorted().toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }
}
