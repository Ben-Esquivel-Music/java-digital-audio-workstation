package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.ManualOwner;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TestSource;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scheduled checkpoint captures on the owner and writes on the writer
 * (story 331, "Concurrent-edit isolation" core half; Persistence Integrity
 * book §2.3, §4.2): a capture or a render that fails — with an
 * {@link Error} too ("Contain Throwable, not IOException") — is reported
 * exactly once with the cause as thrown and the cadence keeps ticking, the
 * owner is never waited for, and a tick whose revision is already
 * checkpointed writes nothing.
 */
class CheckpointTickIsolationTest {

    /** Short enough that a test sees several ticks; every wait is on an event, bounded. */
    private static final Duration TICK = Duration.ofMillis(40);

    @TempDir
    Path projectDir;

    private CheckpointManager manager;
    private ExecutorService ownerThreadExecutor;

    @AfterEach
    void stopEverything() {
        if (manager != null) {
            manager.stop();
        }
        if (ownerThreadExecutor != null) {
            ownerThreadExecutor.shutdownNow();
        }
    }

    @Test
    void aCaptureBrokenByAnEditDuringTheWalkIsReportedOnceAndTheNextTickWritesACheckpoint() throws Exception {
        DawProject project = new DawProject("Isolation", AudioFormat.CD_QUALITY);
        Track first = project.createAudioTrack("First");
        project.createAudioTrack("Second");
        EditDuringTheWalk edit = new EditDuringTheWalk(project);
        first.getMidiEffectChain().add(edit);
        ProjectSerializer serializer = new ProjectSerializer();
        TestSource source = new TestSource();
        source.captureWith(() -> serializer.capture(project)::toXml);
        ManualOwner owner = new ManualOwner();
        Recorder recorder = startTicking(owner, source);

        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitFailed(1);
        List<CheckpointEvent.Failed> failed = recorder.events(CheckpointEvent.Failed.class);
        assertThat(failed.getFirst().cause()).isInstanceOf(ConcurrentModificationException.class);
        assertThat(failed.getFirst().origin()).isEqualTo(CheckpointOrigin.SCHEDULED);
        assertThat(recorder.sdk("failed")).singleElement()
                .satisfies(call -> assertThat(call.cause()).isSameAs(failed.getFirst().cause()));
        assertThat(checkpointNames(projectDir)).as("nothing was written").isEmpty();
        recorder.awaitRearms(RearmReason.FAILED, 1);

        edit.disarm();
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitWritten(1);

        assertThat(recorder.events(CheckpointEvent.Failed.class)).as("the failure was reported once").hasSize(1);
        assertThat(checkpointNames(projectDir)).as("the same revision, retried by the next tick").hasSize(1);
    }

    @Test
    void aCaptureThatThrowsIsReportedOnceAndTheNextTickWritesACheckpoint() throws Exception {
        IllegalStateException broken = new IllegalStateException("the capture broke");
        TestSource source = new TestSource();
        source.captureWith(() -> {
            throw broken;
        });
        ManualOwner owner = new ManualOwner();
        Recorder recorder = startTicking(owner, source);

        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitFailed(1);
        recorder.awaitRearms(RearmReason.FAILED, 1);
        source.captureHealthily();
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitWritten(1);

        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement()
                .satisfies(failed -> assertThat(failed.cause()).isSameAs(broken));
        assertThat(recorder.sdk("failed")).singleElement()
                .satisfies(call -> assertThat(call.cause()).isSameAs(broken));
        assertThat(recorder.sdk("before")).as("the failed capture never began a write").hasSize(1);
        assertThat(checkpointNames(projectDir)).hasSize(1);
    }

    @Test
    void aCaptureThatThrowsAnErrorIsReportedOnceWithThatErrorAndTheNextTickWritesACheckpoint() throws Exception {
        InternalError broken = new InternalError("the capture broke with an Error, not an Exception");
        TestSource source = new TestSource();
        source.captureWith(() -> {
            throw broken;
        });
        ManualOwner owner = new ManualOwner();
        Recorder recorder = startTicking(owner, source);

        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitFailed(1);
        recorder.awaitRearms(RearmReason.FAILED, 1);
        source.captureHealthily();
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitWritten(1);

        assertThat(recorder.sdk("failed")).singleElement()
                .satisfies(call -> assertThat(call.cause()).isSameAs(broken));
        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement().satisfies(failed -> {
            assertThat(failed.cause()).isSameAs(broken);
            assertThat(failed.origin()).isEqualTo(CheckpointOrigin.SCHEDULED);
        });
        assertThat(checkpointNames(projectDir)).as("the next healthy tick wrote").hasSize(1);
    }

    @Test
    void aRenderThatThrowsAnErrorIsReportedOnceWithThatErrorAndTheNextTickWritesACheckpoint() throws Exception {
        InternalError broken = new InternalError("the render broke with an Error, not an Exception");
        TestSource source = new TestSource();
        source.captureWith(() -> () -> {
            throw broken;
        });
        ManualOwner owner = new ManualOwner();
        Recorder recorder = startTicking(owner, source);

        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitFailed(1);
        recorder.awaitRearms(RearmReason.FAILED, 1);
        source.captureHealthily();
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitWritten(1);

        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement().satisfies(failed -> {
            assertThat(failed.cause()).isSameAs(broken);
            assertThat(failed.origin()).isEqualTo(CheckpointOrigin.SCHEDULED);
        });
        assertThat(recorder.sdk("failed")).singleElement()
                .satisfies(call -> assertThat(call.cause()).isSameAs(broken));
        assertThat(recorder.sdk("before")).as("the failed render's write began, and so did the next one")
                .hasSize(2);
        assertThat(checkpointNames(projectDir)).as("nothing of the failed render; the next healthy tick wrote")
                .hasSize(1);
    }

    @Test
    void theCaptureRunsOnTheOwnerAndTheRenderAndThePublishOnTheWriter() throws Exception {
        List<Thread> publishers = new CopyOnWriteArrayList<>();
        AtomicFileWriter observed = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER,
                (temp, target, options) -> {
                    AtomicFileWriter.FILE_SYSTEM_MOVER.move(temp, target, options);
                    publishers.add(Thread.currentThread());
                });
        TestSource source = new TestSource();
        Recorder recorder = startTicking(source, observed);

        recorder.awaitWritten(1);
        Thread ownerThread = CompletableFuture.supplyAsync(Thread::currentThread, ownerThreadExecutor)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(source.captureThread).as("the capture runs on the owner").isSameAs(ownerThread);
        assertThat(source.renderThread).as("the render never runs on the owner").isNotSameAs(ownerThread);
        assertThat(source.renderThread.isVirtual()).isTrue();
        assertThat(source.renderThread.getName()).isEqualTo("daw-checkpoint-writer");
        assertThat(publishers).singleElement().isSameAs(source.renderThread);
    }

    @Test
    void withTheOwnerStalledLaterTicksAreDroppedAndTheSchedulerKeepsTicking() throws Exception {
        ManualOwner stalled = new ManualOwner();
        TestSource source = new TestSource();
        Recorder recorder = startTicking(stalled, source);

        stalled.awaitPosted(1);
        recorder.awaitRearms(RearmReason.TICK_DROPPED, 3);

        assertThat(stalled.pendingCount()).as("at most one capture is pending").isEqualTo(1);
        assertThat(source.captures).hasValue(0);
        assertThat(recorder.rearms(RearmReason.TICK_DROPPED)).as("every drop re-armed the countdown")
                .allSatisfy(rearm -> assertThat(rearm.interval()).isEqualTo(TICK));
        assertThat(recorder.calls()).filteredOn(call -> call.event() instanceof CheckpointEvent.Rearmed)
                .allSatisfy(call -> assertThat(call.thread().getName()).isEqualTo("daw-checkpoint-scheduler"));

        stalled.runPending();
        recorder.awaitWritten(1);
        assertThat(source.captures).hasValue(1);
        assertThat(checkpointNames(projectDir)).hasSize(1);
    }

    @Test
    void anUnchangedRevisionWritesNothingUsesNoIndexAndCallsNoListener() throws Exception {
        TestSource source = new TestSource();
        Recorder recorder = startTicking(source, new AtomicFileWriter());
        recorder.awaitWritten(1);
        int skipsBefore = recorder.rearms(RearmReason.SKIPPED).size();

        recorder.awaitRearms(RearmReason.SKIPPED, skipsBefore + 3);

        assertThat(checkpointNames(projectDir)).hasSize(1);
        assertThat(manager.getCheckpointCount()).as("no index was used").isEqualTo(1);
        assertThat(source.captures).as("an unchanged revision is not even captured").hasValue(1);
        assertThat(recorder.sdk("before")).hasSize(1);
        assertThat(recorder.sdk("after")).hasSize(1);
        assertThat(recorder.events(CheckpointEvent.Written.class)).hasSize(1);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
    }

    @Test
    void withNoCaptureSourceATickWritesNothingAndCallsNoListener() throws Exception {
        CountingClock clock = new CountingClock();
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(TICK, 10, true), new AtomicFileWriter(),
                clock, Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        recorder.awaitRearms(RearmReason.STARTED, 1);
        clock.resetReads();

        clock.awaitReads(3);

        assertThat(checkpointNames(projectDir)).isEmpty();
        assertThat(manager.getCheckpointCount()).isZero();
        assertThat(recorder.calls()).as("only the start's seeding and re-arm were reported")
                .extracting(call -> call.event() == null ? call.kind() : call.event().getClass().getSimpleName())
                .containsExactly("Seeded", "Rearmed");
    }

    @Test
    void aUserCheckpointIsWrittenEvenForAnUnchangedRevisionAndTheNextTickSkipsItsRevision() throws Exception {
        ManualOwner owner = new ManualOwner();
        TestSource source = new TestSource();
        Recorder recorder = startTicking(owner, source);
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitWritten(1);

        Path unchanged = manager.writeCheckpoint(document(source.revision()), source.revision(),
                CheckpointOrigin.USER).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long edited = source.revision.incrementAndGet();
        Path user = manager.writeCheckpoint(document(edited), edited, CheckpointOrigin.USER)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int skipsBefore = recorder.rearms(RearmReason.SKIPPED).size();
        owner.awaitPosted(1);
        owner.runPending();
        recorder.awaitRearms(RearmReason.SKIPPED, skipsBefore + 1);

        assertThat(recorder.events(CheckpointEvent.Written.class)).extracting(CheckpointEvent.Written::origin)
                .containsExactly(CheckpointOrigin.SCHEDULED, CheckpointOrigin.USER, CheckpointOrigin.USER);
        assertThat(checkpointNames(projectDir)).hasSize(3)
                .contains(unchanged.getFileName().toString(), user.getFileName().toString());
        assertThat(source.captures).as("the tick after the user checkpoint of the edit captured nothing")
                .hasValue(1);
    }

    /** Starts ticking on a real single-thread owner. */
    private Recorder startTicking(TestSource source, AtomicFileWriter writer) throws Exception {
        ownerThreadExecutor = Executors.newSingleThreadExecutor();
        return startTicking(ownerThreadExecutor, source, writer);
    }

    /** Starts ticking on an owner the test runs by hand. */
    private Recorder startTicking(ManualOwner manualOwner, TestSource source) throws Exception {
        return startTicking(manualOwner, source, new AtomicFileWriter());
    }

    private Recorder startTicking(Executor ownerExecutor, TestSource source,
                                  AtomicFileWriter writer) throws Exception {
        manager = CheckpointTestSupport.manager(new AutoSaveConfig(TICK, 10, true), writer, new CountingClock(),
                Files::delete, CheckpointManager.STOP_WAIT);
        Recorder recorder = Recorder.on(manager);
        manager.setCaptureSource(ownerExecutor, source);
        manager.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return recorder;
    }

    /**
     * A MIDI effect whose descriptor read — made by the serializer while it
     * walks the track list — adds a track: the walk's iterator then throws
     * {@link ConcurrentModificationException}, as an edit interleaved with a
     * capture off the owner thread would make it throw.
     */
    private static final class EditDuringTheWalk implements MidiEffectPlugin {

        private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
                "test.edit-during-the-walk", "Edit During The Walk", "1.0", "Test", PluginType.EFFECT);

        private final DawProject project;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        EditDuringTheWalk(DawProject project) {
            this.project = project;
        }

        void disarm() {
            armed.set(false);
        }

        @Override
        public PluginDescriptor getDescriptor() {
            if (armed.get()) {
                project.createAudioTrack("Added during the walk");
            }
            return DESCRIPTOR;
        }

        @Override
        public MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext ctx) {
            return in;
        }

        @Override
        public void initialize(PluginContext context) {
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
        }
    }
}
