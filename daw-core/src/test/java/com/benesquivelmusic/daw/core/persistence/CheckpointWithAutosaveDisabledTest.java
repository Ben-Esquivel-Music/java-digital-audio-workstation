package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.ManualOwner;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TestSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * With autosave disabled the automatic checkpoints stop and the generation
 * does not (story 331, rulings H3, J2): a start still seeds the folder and
 * arms no tick; a user's checkpoint and a failed save's safety copy take the
 * full write path and re-arm nothing; the checkpoint after a save is refused
 * at the call, and one or a scheduled job that reaches the writer after the
 * disable writes nothing; none of those tells a listener; reconfiguring
 * between the two never stops the checkpoints, and a re-arm that a disable
 * overtakes is never reported.
 */
class CheckpointWithAutosaveDisabledTest {

    private static final Duration INTERVAL = Duration.ofHours(1);

    /** A tick interval short enough that a test sees ticks without waiting for them. */
    private static final Duration SHORT_INTERVAL = Duration.ofMillis(40);

    private static final String SEEDED = "checkpoint-007-20260101T090000.daw";

    /** A temp the atomic writer leaves after a hard kill: exactly its grammar, of a checkpoint name. */
    private static final String STRAY_TEMP = ".checkpoint-008-20260101T091000.daw.0123456789abcdef.tmp";

    @TempDir
    Path projectDir;

    private final CountingClock clock = new CountingClock();
    private final CountDownLatch moveEntered = new CountDownLatch(1);
    private final CountDownLatch moveReleased = new CountDownLatch(1);
    private CheckpointManager manager;
    private Recorder recorder;

    @AfterEach
    void stop() {
        moveReleased.countDown();
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void aStartSeedsTheFolderAndArmsNoTick() throws Exception {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(directory.resolve(SEEDED), document(7));
        Files.writeString(directory.resolve(STRAY_TEMP), "<daw-project");

        start(new AutoSaveConfig(INTERVAL, 10, false), new AtomicFileWriter());

        assertThat(manager.getCheckpointCount()).as("the highest seeded index").isEqualTo(7);
        assertThat(manager.getCheckpointFiles()).containsExactly(directory.resolve(SEEDED));
        assertThat(directory.resolve(STRAY_TEMP)).as("the stray temp is cleared").doesNotExist();
        assertThat(manager.isRunning()).as("the automatic checkpoints are not running").isFalse();
        assertNothingWasArmedWhileDisabled(10);
    }

    @Test
    void aUserCheckpointTakesTheFullWritePathAndReArmsNothing() throws Exception {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(directory.resolve(SEEDED), document(7));
        AtomicInteger guardChecks = new AtomicInteger();
        start(new AutoSaveConfig(INTERVAL, 1, false), new AtomicFileWriter(), guardChecks::incrementAndGet);

        Path written = write(3, CheckpointOrigin.USER);

        assertThat(written.getFileName().toString()).as("the index continues from the seeded one")
                .startsWith("checkpoint-008-");
        assertThat(written).hasContent(document(3));
        assertThat(guardChecks).as("the write guard was asked").hasValue(1);
        assertThat(recorder.sdk("before")).hasSize(1);
        assertThat(recorder.sdk("after")).hasSize(1);
        assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement()
                .satisfies(event -> assertThat(event.origin()).isEqualTo(CheckpointOrigin.USER));
        assertThat(checkpointNames(projectDir)).as("pruned to the cap of one, seeded file first")
                .containsExactly(written.getFileName().toString());
        assertNothingWasArmedWhileDisabled(1);
    }

    @Test
    void aSafetyCopyIsWrittenAndOneOfAnAlreadyCheckpointedRevisionNamesThatFile() throws Exception {
        start(new AutoSaveConfig(INTERVAL, 10, false), new AtomicFileWriter());

        Path written = write(4, CheckpointOrigin.SAFETY_COPY);
        Path again = write(4, CheckpointOrigin.SAFETY_COPY);

        assertThat(written).hasContent(document(4));
        assertThat(again).as("no duplicate of a checkpointed revision (ruling G9)").isEqualTo(written);
        assertThat(checkpointNames(projectDir)).containsExactly(written.getFileName().toString());
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.SAFETY_COPY, 4L));
        assertNothingWasArmedWhileDisabled(10);
    }

    @Test
    void aFailedUserCheckpointIsReportedAndReArmsNothing() throws Exception {
        IOException diskFull = new IOException("the disk is full");
        start(new AutoSaveConfig(INTERVAL, 10, false), new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    if (CheckpointFileName.parse(target.getFileName().toString()).isPresent()) throw diskFull;
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                }));

        CompletableFuture<Path> failed = manager.writeCheckpoint(document(5), 5, CheckpointOrigin.USER);

        assertThat(failed).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(failed.exceptionNow()).isSameAs(diskFull);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement()
                .satisfies(event -> assertThat(event.cause()).isSameAs(diskFull));
        assertNothingWasArmedWhileDisabled(10);
    }

    @Test
    void aCheckpointAfterASaveWritesNothingAndTellsNoListener() throws Exception {
        start(new AutoSaveConfig(INTERVAL, 10, false), new AtomicFileWriter());
        List<Recorder.Call> callsBefore = recorder.calls();

        CompletableFuture<Path> afterSave = manager.writeCheckpoint(document(5), 5, CheckpointOrigin.AFTER_SAVE);

        assertThat(afterSave).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(afterSave.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessageContaining("Autosave is disabled");
        assertThat(checkpointNames(projectDir)).isEmpty();
        assertThat(callsBefore).as("fixture: the start reported its seeding, and nothing else")
                .extracting(Recorder.Call::event).singleElement().isInstanceOf(CheckpointEvent.Seeded.class);
        assertThat(recorder.calls()).as("no listener hears of it").isEqualTo(callsBefore);
        Path safetyCopy = write(5, CheckpointOrigin.SAFETY_COPY);
        assertThat(safetyCopy).as("the revision was not recorded as checkpointed: its safety copy is a new file")
                .hasContent(document(5));
        assertThat(checkpointNames(projectDir)).containsExactly(safetyCopy.getFileName().toString());
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.SAFETY_COPY, 5L));
        assertNothingWasArmedWhileDisabled(10);
    }

    @Test
    void aCheckpointAfterASaveIsRefusedAtTheCallWithoutWaitingBehindAHeldWrite() throws Exception {
        start(new AutoSaveConfig(INTERVAL, 10, false), holdingTheFirstCheckpointPublish());
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitMoveEntered();
        List<Recorder.Call> callsBefore = recorder.calls();

        CompletableFuture<Path> afterSave = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.AFTER_SAVE);

        assertThat(afterSave).as("done when the call returns, with the user checkpoint still held")
                .isCompletedExceptionally();
        assertThat(afterSave.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessageContaining("Autosave is disabled");
        assertThat(held).isNotDone();
        assertThat(recorder.calls()).as("no listener hears of it").isEqualTo(callsBefore);
        moveReleased.countDown();
        Path written = held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(written).as("the held write is unaffected").hasContent(document(1));
        assertThat(checkpointNames(projectDir)).containsExactly(written.getFileName().toString());
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.USER, 1L));
        assertNothingWasArmedWhileDisabled(10);
    }

    @Test
    void aCheckpointAfterASaveThatReachesTheWriterAfterAutosaveIsDisabledWritesNothing() throws Exception {
        AutoSaveConfig enabled = new AutoSaveConfig(INTERVAL, 10, true);
        start(enabled, holdingTheFirstCheckpointPublish());
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitMoveEntered();
        CompletableFuture<Path> afterSave = manager.writeCheckpoint(document(2), 2, CheckpointOrigin.AFTER_SAVE);
        assertThat(afterSave).as("autosave was enabled at the call: it is queued behind the held write")
                .isNotDone();

        manager.reconfigure(enabled.withEnabled(false));
        moveReleased.countDown();

        Path written = held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(afterSave).failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(afterSave.exceptionNow()).isInstanceOf(IOException.class)
                .hasMessageContaining("Autosave is disabled");
        assertThat(checkpointNames(projectDir)).containsExactly(written.getFileName().toString());
        assertThat(recorder.sdk("before")).as("only the user checkpoint began").hasSize(1);
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.USER, 1L));
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
        Path safetyCopy = write(2, CheckpointOrigin.SAFETY_COPY);
        assertThat(safetyCopy).as("the writer's refusal did not record revision 2 as checkpointed: its safety copy"
                + " is a new file").hasContent(document(2));
        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrder(written.getFileName().toString(),
                safetyCopy.getFileName().toString());
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.USER, 1L), tuple(CheckpointOrigin.SAFETY_COPY, 2L));
    }

    @Test
    void aCheckpointAfterASaveRefusedAtTheCallStillMakesTheFirstTickCheckpoint() throws Exception {
        Path directory = Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(directory.resolve(SEEDED), document(7));
        Files.writeString(projectDir.resolve("project.daw"), document(7));
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        source.clean.set(true);
        start(new AutoSaveConfig(INTERVAL, 10, false), new AtomicFileWriter());
        manager.setCaptureSource(owner, source);

        assertThat(manager.writeCheckpoint(document(8), 8, CheckpointOrigin.AFTER_SAVE))
                .isCompletedExceptionally();
        manager.reconfigure(new AutoSaveConfig(SHORT_INTERVAL, 10, true));
        owner.awaitPosted(1);
        owner.runPending();

        assertThat(source.captures).as("the refused call still invalidated the seeded match (ruling G3)")
                .hasValue(1);
        recorder.awaitWritten(1);
        assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement()
                .satisfies(event -> assertThat(event.origin()).isEqualTo(CheckpointOrigin.SCHEDULED));
    }

    @ParameterizedTest(name = "the capture throws: {0}")
    @ValueSource(booleans = {false, true})
    void aScheduledJobThatReachesTheWriterAfterAutosaveIsDisabledWritesNothingAndTellsNoListener(
            boolean theCaptureThrows) throws Exception {
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        source.revision.set(2);
        if (theCaptureThrows) {
            source.captureWith(() -> {
                throw new IllegalStateException("a capture broken by an edit during the walk");
            });
        }
        AutoSaveConfig enabled = new AutoSaveConfig(SHORT_INTERVAL, 10, true);
        start(enabled, holdingTheFirstCheckpointPublish());
        manager.setCaptureSource(owner, source);
        CompletableFuture<Path> held = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.USER);
        awaitMoveEntered();
        owner.awaitPosted(1);
        owner.runPending();

        manager.reconfigure(enabled.withEnabled(false));
        moveReleased.countDown();
        held.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path later = write(3, CheckpointOrigin.USER);

        assertThat(source.captures).as("the tick's capture, or its failure, was handed to the writer").hasValue(1);
        assertThat(source.renders).as("and never rendered there").hasValue(0);
        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(tuple(CheckpointOrigin.USER, 1L), tuple(CheckpointOrigin.USER, 3L));
        assertThat(recorder.sdk("before")).as("only the two user checkpoints began").hasSize(2);
        assertThat(recorder.sdk("failed")).isEmpty();
        assertThat(checkpointNames(projectDir)).containsExactlyInAnyOrder(held.get().getFileName().toString(),
                later.getFileName().toString());
    }

    @Test
    void disablingDropsACaptureNotYetHandedOverAndKeepsTheGeneration() throws Exception {
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        AutoSaveConfig enabled = new AutoSaveConfig(SHORT_INTERVAL, 10, true);
        start(enabled, new AtomicFileWriter());
        manager.setCaptureSource(owner, source);
        owner.awaitPosted(1);

        manager.reconfigure(enabled.withEnabled(false));
        owner.runPending();

        assertThat(source.captures).as("the capture posted before the disable is dropped untaken").hasValue(0);
        assertThat(manager.isRunning()).isFalse();
        Path user = write(9, CheckpointOrigin.USER);
        assertThat(user).as("the generation was kept: a user checkpoint is still written").exists();
        assertThat(checkpointNames(projectDir)).containsExactly(user.getFileName().toString());
        assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement()
                .satisfies(event -> assertThat(event.origin()).isEqualTo(CheckpointOrigin.USER));
    }

    @Test
    void aReArmThatADisableOvertakesIsCancelledAndNeverReported() throws Exception {
        AtomicBoolean holdTheNextArming = new AtomicBoolean(true);
        CountDownLatch armingHeld = new CountDownLatch(1);
        CountDownLatch armingReleased = new CountDownLatch(1);
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        AutoSaveConfig enabled = new AutoSaveConfig(SHORT_INTERVAL, 10, true);
        manager = CheckpointTestSupport.manager(enabled, new AtomicFileWriter(),
                armingHeldOnce(holdTheNextArming, armingHeld, armingReleased), Files::delete,
                CheckpointManager.STOP_WAIT);
        recorder = Recorder.on(manager);
        manager.setCaptureSource(owner, source);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(armingHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the start's re-arm is arming").isTrue();
        // The scheduler, the only thread that reports re-arms, is held: this count is final.
        int reportedBefore = recorder.events(CheckpointEvent.Rearmed.class).size();

        manager.reconfigure(enabled.withEnabled(false));
        armingReleased.countDown();

        awaitTheIntervalsAfterTheDisable();
        assertThat(reArmsReportedAfter(reportedBefore)).as("the re-arm the disable overtook is never reported")
                .isEmpty();
    }

    @Test
    void aDroppedTickThatADisableOvertakesIsNeverReported() throws Exception {
        AtomicBoolean holdTheNextArming = new AtomicBoolean();
        CountDownLatch armingHeld = new CountDownLatch(1);
        CountDownLatch armingReleased = new CountDownLatch(1);
        ManualOwner stalled = new ManualOwner();
        AutoSaveConfig enabled = new AutoSaveConfig(SHORT_INTERVAL, 10, true);
        manager = CheckpointTestSupport.manager(enabled, new AtomicFileWriter(),
                armingHeldOnce(holdTheNextArming, armingHeld, armingReleased), Files::delete,
                CheckpointManager.STOP_WAIT);
        recorder = Recorder.on(manager);
        manager.setCaptureSource(stalled, new TestSource());
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        stalled.awaitPosted(1);
        // The capture stays in flight: the scheduler's next arming is a later tick's drop.
        holdTheNextArming.set(true);
        assertThat(armingHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("a dropped tick is re-arming").isTrue();
        int reportedBefore = recorder.events(CheckpointEvent.Rearmed.class).size();

        manager.reconfigure(enabled.withEnabled(false));
        armingReleased.countDown();

        awaitTheIntervalsAfterTheDisable();
        assertThat(reArmsReportedAfter(reportedBefore)).as("the drop the disable overtook is never reported")
                .isEmpty();
    }

    @Test
    void enablingWithALiveGenerationArmsATickAFullIntervalFromNow() throws Exception {
        ManualOwner owner = new ManualOwner();
        start(new AutoSaveConfig(INTERVAL, 10, false), new AtomicFileWriter());
        manager.setCaptureSource(owner, new TestSource());
        awaitWriterIdle();

        manager.reconfigure(new AutoSaveConfig(SHORT_INTERVAL, 10, true));
        recorder.awaitRearms(RearmReason.RECONFIGURED, 1);

        assertThat(recorder.events(CheckpointEvent.Rearmed.class).getFirst()).as("the first re-arm ever")
                .isEqualTo(new CheckpointEvent.Rearmed(manager.runningGeneration().orElseThrow(),
                        CountingClock.NOW.plus(SHORT_INTERVAL), SHORT_INTERVAL, RearmReason.RECONFIGURED));
        assertThat(manager.isRunning()).isTrue();
        owner.awaitPosted(1);
    }

    private void start(AutoSaveConfig config, AtomicFileWriter writer) throws Exception {
        start(config, writer, () -> { });
    }

    /** Starts the generation with {@code guard} as its write guard (story 333: each generation has its own). */
    private void start(AutoSaveConfig config, AtomicFileWriter writer, CheckpointManager.WriteGuard guard)
            throws Exception {
        manager = CheckpointTestSupport.manager(config, writer, clock, Files::delete, CheckpointManager.STOP_WAIT);
        recorder = Recorder.on(manager);
        manager.start(projectDir, guard).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Path write(long revision, CheckpointOrigin origin) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, origin).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** A writer whose first checkpoint publish waits inside its atomic write until released. */
    private AtomicFileWriter holdingTheFirstCheckpointPublish() {
        AtomicBoolean holdTheFirstPublish = new AtomicBoolean(true);
        return new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
            if (CheckpointFileName.parse(target.getFileName().toString()).isPresent()
                    && holdTheFirstPublish.compareAndSet(true, false)) {
                moveEntered.countDown();
                try {
                    if (!moveReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("the test never released the publish");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted inside the publish", interrupted);
                }
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        });
    }

    private void awaitMoveEntered() throws InterruptedException {
        assertThat(moveEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the user checkpoint is publishing")
                .isTrue();
    }

    /**
     * A clock at {@link CountingClock#NOW} that, once {@code hold} is set,
     * holds the scheduler's next read — inside an arming, after its caller
     * checked that autosave is enabled — until {@code released}.
     */
    private static InstantSource armingHeldOnce(AtomicBoolean hold, CountDownLatch held, CountDownLatch released) {
        return () -> {
            if (Thread.currentThread().getName().equals("daw-checkpoint-scheduler") && hold.compareAndSet(true, false)) {
                held.countDown();
                try {
                    released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return CountingClock.NOW;
        };
    }

    /**
     * Waits ten short intervals with autosave still disabled: a re-arm
     * released from its hold would long since have been reported. Autosave
     * is not enabled again to make a barrier, because the held arming's own
     * check could then read the new configuration.
     */
    private static void awaitTheIntervalsAfterTheDisable() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(SHORT_INTERVAL.toMillis() * 10);
    }

    /** The re-arms reported after the first {@code before}. */
    private List<CheckpointEvent.Rearmed> reArmsReportedAfter(int before) {
        List<CheckpointEvent.Rearmed> reported = recorder.events(CheckpointEvent.Rearmed.class);
        return reported.subList(before, reported.size());
    }

    /**
     * Waits until the writer has decided the re-arm of every job handed to
     * it. A job completes its future before it decides, so a test that
     * enabled autosave as soon as that future completed could have the
     * decision read the new configuration.
     */
    private void awaitWriterIdle() throws InterruptedException {
        assertThat(manager.awaitWriterIdle(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .as("the writer finished every job handed to it").isTrue();
    }

    /**
     * Enables autosave once the writer has decided every earlier job's
     * re-arm, and waits for enabling's re-arm. The scheduler runs every
     * arming in the order it was asked for, so an arming made while autosave
     * was disabled would have been reported before this one.
     */
    private void assertNothingWasArmedWhileDisabled(int cap) throws InterruptedException {
        awaitWriterIdle();
        manager.reconfigure(new AutoSaveConfig(INTERVAL, cap, true));
        recorder.awaitRearms(RearmReason.RECONFIGURED, 1);
        assertThat(recorder.events(CheckpointEvent.Rearmed.class)).as("no tick was armed while disabled")
                .containsExactly(new CheckpointEvent.Rearmed(manager.runningGeneration().orElseThrow(),
                        CountingClock.NOW.plus(INTERVAL), INTERVAL, RearmReason.RECONFIGURED));
    }
}
