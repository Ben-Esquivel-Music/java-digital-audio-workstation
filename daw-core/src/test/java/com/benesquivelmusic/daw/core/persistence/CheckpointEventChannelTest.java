package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.ManualOwner;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TestSource;
import com.benesquivelmusic.daw.sdk.event.AutoSaveListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What a status surface hears from the checkpoints (story 331, rulings G13
 * #2, #9 and K1): what each generation's seeding found, every re-arm of the
 * cadence with its next due time, every outcome with its origin — each fact
 * naming the generation that reported it — and nothing a throwing listener
 * could turn into another outcome.
 */
class CheckpointEventChannelTest {

    private static final Duration INTERVAL = Duration.ofHours(1);

    @TempDir
    Path projectDir;

    private final CountingClock clock = new CountingClock();
    private final AtomicBoolean failPublish = new AtomicBoolean();
    private final IOException publishFailure = new IOException("the disk is full");
    private CheckpointManager manager;

    @AfterEach
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void everyReArmIsReportedWithItsDueTimeAfterAStartASuccessAFailureAndASkip() throws Exception {
        Recorder recorder = start(INTERVAL);
        recorder.awaitRearms(RearmReason.STARTED, 1);

        Instant afterTheSave = CountingClock.NOW.plusSeconds(60);
        clock.set(afterTheSave);
        write(1, CheckpointOrigin.AFTER_SAVE);
        recorder.awaitRearms(RearmReason.WRITTEN, 1);

        Instant afterTheFailure = CountingClock.NOW.plusSeconds(120);
        clock.set(afterTheFailure);
        failPublish.set(true);
        assertThat(manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER))
                .failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        recorder.awaitRearms(RearmReason.FAILED, 1);

        Instant afterTheSkip = CountingClock.NOW.plusSeconds(180);
        clock.set(afterTheSkip);
        failPublish.set(false);
        write(1, CheckpointOrigin.AFTER_SAVE);
        recorder.awaitRearms(RearmReason.SKIPPED, 1);

        long generation = manager.runningGeneration().orElseThrow();
        assertThat(recorder.events(CheckpointEvent.Rearmed.class)).containsExactly(
                new CheckpointEvent.Rearmed(generation, CountingClock.NOW.plus(INTERVAL), INTERVAL, RearmReason.STARTED),
                new CheckpointEvent.Rearmed(generation, afterTheSave.plus(INTERVAL), INTERVAL, RearmReason.WRITTEN),
                new CheckpointEvent.Rearmed(generation, afterTheFailure.plus(INTERVAL), INTERVAL, RearmReason.FAILED),
                new CheckpointEvent.Rearmed(generation, afterTheSkip.plus(INTERVAL), INTERVAL, RearmReason.SKIPPED));
    }

    @Test
    void aReconfigureReArmsWithTheNewInterval() throws Exception {
        Recorder recorder = start(INTERVAL);
        recorder.awaitRearms(RearmReason.STARTED, 1);
        Duration shorter = Duration.ofMinutes(10);

        manager.reconfigure(new AutoSaveConfig(shorter, 10, true));
        recorder.awaitRearms(RearmReason.RECONFIGURED, 1);

        assertThat(recorder.rearms(RearmReason.RECONFIGURED)).singleElement().isEqualTo(
                new CheckpointEvent.Rearmed(manager.runningGeneration().orElseThrow(), CountingClock.NOW.plus(shorter),
                        shorter, RearmReason.RECONFIGURED));
    }

    @Test
    void everyFactNamesTheGenerationThatReportedItAndNoTwoGenerationsShareOne() throws Exception {
        Recorder recorder = start(INTERVAL);
        recorder.awaitRearms(RearmReason.STARTED, 1);
        long first = manager.runningGeneration().orElseThrow();
        write(1, CheckpointOrigin.USER);
        recorder.awaitRearms(RearmReason.WRITTEN, 1);
        failPublish.set(true);
        assertThat(manager.writeCheckpoint(document(2), 2, CheckpointOrigin.USER))
                .failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        recorder.awaitRearms(RearmReason.FAILED, 1);
        failPublish.set(false);

        // The same folder opened again: a generation of its own.
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        recorder.awaitRearms(RearmReason.STARTED, 2);
        long second = manager.runningGeneration().orElseThrow();
        write(3, CheckpointOrigin.USER);
        recorder.awaitRearms(RearmReason.WRITTEN, 2);

        assertThat(second).isNotEqualTo(first);
        assertThat(recorder.calls()).extracting(Recorder.Call::event).filteredOn(event -> event != null)
                .extracting(CheckpointEvent::generation)
                .containsExactly(first, first, first, first, first, first, second, second, second, second);
        manager.stop();
        assertThat(manager.runningGeneration()).as("a stopped generation runs no more").isEmpty();
    }

    @Test
    void seedingReportsTheNewestCheckpointOnDiskBeforeAnyWriteOfItsGenerationWithAutosaveOnOrOff()
            throws Exception {
        Path folder = Files.createDirectories(projectDir.resolve("checkpoints"));
        Files.writeString(folder.resolve("checkpoint-046-20261022T175500.daw"), document(46));
        Files.writeString(folder.resolve("checkpoint-047-20261022T181000.daw"), document(47));
        Files.writeString(folder.resolve("checkpoint-003-20261021T090000.daw"), document(3));
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(INTERVAL, 10, false), writer(), clock,
                Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);

        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(manager.getCheckpointFiles()).as("the seeded index's newest: the file the first tick compares with")
                .last().isEqualTo(folder.resolve("checkpoint-047-20261022T181000.daw"));
        write(1, CheckpointOrigin.USER);

        long generation = manager.runningGeneration().orElseThrow();
        assertThat(recorder.calls()).extracting(Recorder.Call::event).filteredOn(event -> event != null)
                .as("autosave is off: no re-arm, but the seeding is reported, before the write")
                .hasSize(2).first()
                .isEqualTo(new CheckpointEvent.Seeded(generation,
                        Optional.of(folder.resolve("checkpoint-047-20261022T181000.daw"))));
    }

    @Test
    void seedingAFolderWithNoCheckpointReportsNone() throws Exception {
        Recorder recorder = start(INTERVAL);
        recorder.awaitRearms(RearmReason.STARTED, 1);
        long noFolder = manager.runningGeneration().orElseThrow();

        Files.createDirectories(projectDir.resolve("checkpoints"));
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long emptyFolder = manager.runningGeneration().orElseThrow();

        assertThat(recorder.events(CheckpointEvent.Seeded.class)).as("no checkpoints/, then an empty one")
                .containsExactly(new CheckpointEvent.Seeded(noFolder, Optional.empty()),
                        new CheckpointEvent.Seeded(emptyFolder, Optional.empty()));
    }

    @Test
    void eachOutcomeCarriesItsOrigin() throws Exception {
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        source.revision.set(10);
        Recorder recorder = start(Duration.ofMillis(40));
        manager.setCaptureSource(owner, source);

        write(1, CheckpointOrigin.AFTER_SAVE);
        write(2, CheckpointOrigin.USER);
        write(3, CheckpointOrigin.SAFETY_COPY);
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitWritten(4);
        failPublish.set(true);
        assertThat(manager.writeCheckpoint(document(4), 4, CheckpointOrigin.SAFETY_COPY))
                .failsWithin(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        recorder.awaitFailed(1);

        assertThat(recorder.events(CheckpointEvent.Written.class))
                .extracting(CheckpointEvent.Written::origin, CheckpointEvent.Written::revision)
                .containsExactly(
                        tuple(CheckpointOrigin.AFTER_SAVE, 1L),
                        tuple(CheckpointOrigin.USER, 2L),
                        tuple(CheckpointOrigin.SAFETY_COPY, 3L),
                        tuple(CheckpointOrigin.SCHEDULED, 10L));
        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement().satisfies(failed -> {
            assertThat(failed.origin()).isEqualTo(CheckpointOrigin.SAFETY_COPY);
            assertThat(failed.cause()).isSameAs(publishFailure);
        });
        assertThat(recorder.events(CheckpointEvent.Written.class)).allSatisfy(written ->
                assertThat(written.file()).exists().hasParentRaw(projectDir.resolve("checkpoints")));
    }

    @Test
    void aListenerThatThrowsStopsNoOtherListenerAndTurnsNoSuccessIntoAFailure() throws Exception {
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(INTERVAL, 10, true), writer(), clock,
                Files::delete, CheckpointManager.STOP_WAIT);
        manager.addListener(new AutoSaveListener() {
            @Override
            public void onBeforeCheckpoint(String checkpointId) {
                throw new IllegalStateException("a listener broken before the write");
            }

            @Override
            public void onAfterCheckpoint(String checkpointId) {
                throw new IllegalStateException("a listener broken after the write");
            }

            @Override
            public void onCheckpointFailed(String checkpointId, Throwable cause) {
                throw new IllegalStateException("a listener broken by the failure");
            }
        });
        manager.addEventListener(event -> {
            throw new IllegalStateException("an event listener that always breaks");
        });
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Path> written = manager.writeCheckpoint(document(1), 1, CheckpointOrigin.AFTER_SAVE);

        assertThat(written.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).exists();
        assertThat(recorder.sdk("before")).hasSize(1);
        assertThat(recorder.sdk("after")).hasSize(1);
        assertThat(recorder.sdk("failed")).as("a throwing listener is no failed checkpoint").isEmpty();
        assertThat(recorder.events(CheckpointEvent.Written.class)).hasSize(1);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
    }

    private Recorder start(Duration interval) throws Exception {
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(interval, 10, true), writer(), clock,
                Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return recorder;
    }

    /** A real writer whose publish fails while {@link #failPublish} is set. */
    private AtomicFileWriter writer() {
        return new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
            if (failPublish.get()) {
                throw publishFailure;
            }
            AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
        });
    }

    private Path write(long revision, CheckpointOrigin origin) throws Exception {
        return manager.writeCheckpoint(document(revision), revision, origin).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
