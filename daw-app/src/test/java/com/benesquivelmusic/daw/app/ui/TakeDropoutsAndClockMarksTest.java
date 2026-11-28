package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.DiskHeadroomWatch;
import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The record coordinator's side of story 338 on a real take: the take's
 * dropout figures follow it from its first recorded block to after its Stop
 * and stay until the next take or a clear (ruling E24); and while it records,
 * its transport frames are sampled on each pulse of the window's dispatcher,
 * so a clock event is dated at a frame sampled around its evidence, and only
 * while the take records (amendment A11, rulings E30, E40, E62): an unlock or
 * a resync at the newest frame sampled at or before its evidence, a relock at
 * the oldest at or after it, held until such a sample or the Stop and dropped
 * at a device loss, a retire or an early seal; from the capture thread's early
 * seal until the take is stopped for it nothing is marked and no relock is
 * held (ruling E280); an unlock while the take is marked unlocked
 * and a relock while it is marked locked write nothing, and an unlock or a
 * start mark cancels a relock held (rulings E221, E223 and E258). A Stop
 * whose held relock's write or dropout report breaks still latches the take's
 * figures and ends its sampling (ruling E245).
 */
@ExtendWith(JavaFxToolkitExtension.class)
@Tag("slow")
class TakeDropoutsAndClockMarksTest {

    @TempDir
    Path projectDirectory;

    private final FxDispatcher dispatcher = new FxDispatcher();
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private DeviceLossRecordingFixture fixture;

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void theTakesFiguresFollowItFromItsStartToAfterItsStopUntilTheNextTakeOrAClear() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("no take yet").isEmpty();

        fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline first = fixture.pipeline();
        assertThat(onFx(fixture.coordinator()::takeDropouts)).hasValueSatisfying(figures -> {
            assertThat(figures.recording()).as("this take").isTrue();
            assertThat(figures.blocksLost()).isGreaterThanOrEqualTo(first.getFileOverflowBlocks()
                    + first.getStarvedInputBlocks());
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        TakeDropouts last = onFx(fixture.coordinator()::takeDropouts).orElseThrow();
        assertThat(last.recording()).as("the last take, after its Stop").isFalse();
        assertThat(last.blocksLost()).as("the take's own losses, read from its pipeline")
                .isGreaterThanOrEqualTo(first.getFileOverflowBlocks() + first.getStarvedInputBlocks());
        assertThat(last.truncationEpisodes()).isEqualTo(first.getFileTruncationEpisodes());
        assertThat(last.framesTruncated()).isEqualTo(first.getFileTruncatedFrames());
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("unchanged once stopped").contains(last);

        runOnFx(fixture.coordinator()::clearTakeDropouts);
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("the log's Clear").isEmpty();

        fixture.recordUntilABlockIsOnDisk();
        assertThat(fixture.pipeline()).as("premise: a new take").isNotSameAs(first);
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("the next take's own figures")
                .hasValueSatisfying(figures -> assertThat(figures.recording()).isTrue());
        runOnFx(fixture.coordinator()::clearTakeDropouts);
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("a Clear leaves the take in flight its figures")
                .isPresent();
    }

    @Test
    void aClockEventIsDatedAtTheFrameSampledWhenItsEvidenceWasSeenAndOnlyWhileTheTakeRecords() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        int participantsBefore = dispatcher.pulseParticipantCount();
        runOnFx(() -> fixture.coordinator().setTakeFrameClockForTest(nanos::get));
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        assertThat(dispatcher.pulseParticipantCount()).as("the take samples on the window's pulse")
                .isEqualTo(participantsBefore + 1);

        long evidence = nanos.addAndGet(1_000_000_000L);
        long[] sampled = onFx(() -> {
            long before = pipeline.transportFrameOf(transport.getPositionInBeats());
            dispatcher.pulse();
            return new long[] {before, pipeline.transportFrameOf(transport.getPositionInBeats())};
        });
        nanos.addAndGet(1_500_000_000L);
        await(() -> transport.getPositionInBeats() >= frameToBeats(transport, pipeline, sampled[1]) + 1.0,
                "the take recorded on past the sample");
        runOnFx(dispatcher::pulse);

        runOnFx(() -> fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, evidence));
        long[] current = onFx(() -> {
            long before = pipeline.transportFrameOf(transport.getPositionInBeats());
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.RESYNC, 0L);
            return new long[] {before, pipeline.transportFrameOf(transport.getPositionInBeats())};
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();
        runOnFx(() -> fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.LOCKED, 0L));

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .as("nothing after the take stopped recording")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.RESYNC);
        assertThat(marks.get(0).frame()).as("the frame sampled when the evidence was seen (ruling E62)")
                .isBetween(sampled[0], sampled[1]);
        assertThat(marks.get(1).frame()).as("unknown evidence: the frame now (ruling E40)")
                .isBetween(current[0], current[1]);
        assertThat(dispatcher.pulseParticipantCount()).as("no sampling once the take stopped")
                .isEqualTo(participantsBefore);
    }

    /**
     * Ruling E177: an unlock whose first bad answer came before the take's
     * first sample, declared seconds into the take, is dated at the take's
     * start-frame, the manifest's {@code start-frame}.
     */
    @Test
    void anUnlockWhoseFirstBadAnswerPrecedesTheTakeIsMarkedAtTheTakesStartFrame() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        runOnFx(() -> {
            transport.setPositionInBeats(4.0);
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
        });
        long beforeTheTake = nanos.get();
        nanos.addAndGet(500_000_000L);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        long startFrame = pipeline.getRecordingStartFrame();
        await(() -> transport.getPositionInBeats() >= pipeline.getRecordingStartBeat() + 1.0,
                "the take recorded on past its start");
        runOnFx(dispatcher::pulse);

        long frameWhenDeclared = onFx(() -> {
            long now = pipeline.transportFrameOf(transport.getPositionInBeats());
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, beforeTheTake);
            return now;
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(startFrame).as("premise: the take starts past its first frame").isPositive();
        assertThat(frameWhenDeclared).as("premise: declared after the take recorded on").isGreaterThan(startFrame);
        assertThat(marks).singleElement().satisfies(mark -> {
            assertThat(mark.event()).isEqualTo(TakeManifest.ClockEvent.UNLOCKED);
            assertThat(mark.frame()).as("suspect from the take's start-frame").isEqualTo(startFrame);
        });
    }

    /**
     * Rulings E173 and E177: a relock or a resync whose evidence came before
     * the take's first sample is dated at the take's start-frame, as an
     * unlock is.
     */
    @Test
    void aRelockOrAResyncWhoseEvidencePrecedesTheTakeIsMarkedAtTheTakesStartFrame() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        runOnFx(() -> {
            transport.setPositionInBeats(4.0);
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
        });
        long beforeTheTake = nanos.get();
        nanos.addAndGet(500_000_000L);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        long startFrame = pipeline.getRecordingStartFrame();
        await(() -> transport.getPositionInBeats() >= pipeline.getRecordingStartBeat() + 1.0,
                "the take recorded on past its start");
        runOnFx(dispatcher::pulse);

        long frameWhenDeclared = onFx(() -> {
            long now = pipeline.transportFrameOf(transport.getPositionInBeats());
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, beforeTheTake);
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.LOCKED, beforeTheTake);
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.RESYNC, beforeTheTake);
            return now;
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(frameWhenDeclared).as("premise: declared after the take recorded on").isGreaterThan(startFrame);
        assertThat(marks).extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.UNLOCKED,
                TakeManifest.ClockEvent.LOCKED, TakeManifest.ClockEvent.RESYNC);
        assertThat(marks).extracting(TakeManifest.ClockMark::frame).as("each at the take's start-frame")
                .containsExactly(startFrame, startFrame, startFrame);
    }

    @Test
    void anUnlockWhileTheTakeIsMarkedUnlockedWritesNothing() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        List<Boolean> returned = List.of(unlock(e1), unlock(e1 + 1));

        assertThat(returned).as("the take is marked unlocked after each").containsExactly(true, true);
        assertThat(stopAndReadMarks(takeDirectory)).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    @Test
    void aRelockWhileTheTakeIsMarkedLockedWritesNothing() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e0 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        boolean unlocked = unlock(e0);
        long t1 = nanos.addAndGet(1_000_000_000L);
        long[] s1 = sampleNow();
        List<Boolean> returned = List.of(unlocked, relock(t1), relock(t1));

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(returned).containsExactly(true, false, false);
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(marks.get(1).frame()).as("written at the sample taken at its evidence").isBetween(s1[0], s1[1]);
    }

    /** Ruling E221: a relock is never dated before its evidence. */
    @Test
    void aRelockIsDatedAtTheOldestFrameSampledAtOrAfterItsEvidence() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e0 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        unlock(e0);
        long t1 = nanos.addAndGet(1_000_000_000L);
        long[] s1 = sampleNow();
        long e = nanos.addAndGet(500_000_000L);
        nanos.addAndGet(500_000_000L);
        recordsOnPast(s1[1]);
        long[] s2 = sampleNow();
        relock(e);

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(t1).as("premise: the earlier sample was taken before the evidence").isLessThan(e);
        assertThat(s2[0]).as("premise: the take recorded on between the two samples").isGreaterThan(s1[1]);
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(marks.get(1).frame()).as("the sample after the evidence, not the one before")
                .isBetween(s2[0], s2[1]);
    }

    /** Ruling E221: a relock no sample can date yet is held for the next one at or after its evidence. */
    @Test
    void aRelockWithNoSampleAfterItsEvidenceIsWrittenAtTheNextPulsesSample() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        long[] s0 = sampleNow();
        unlock(e1);
        boolean held = relock(e1 + 1);
        nanos.addAndGet(1_000_000_000L);
        recordsOnPast(s0[1]);
        long[] s = sampleNow();

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(held).isFalse();
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(marks.get(1).frame()).as("at the next pulse's sample").isBetween(s[0], s[1]);
    }

    /** Ruling E221: a held relock the next sample wrote marks the take locked, so the next unlock is marked. */
    @Test
    void anUnlockAfterAHeldRelockTheSamplerWroteIsMarkedAgain() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        long[] s0 = sampleNow();
        unlock(e1);
        relock(e1 + 1);
        nanos.addAndGet(1_000_000_000L);
        recordsOnPast(s0[1]);
        long[] s1 = sampleNow();
        long e3 = nanos.addAndGet(1_000_000_000L);
        long[] s3 = sampleNow();
        boolean marked = unlock(e3);

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(marked).isTrue();
        assertThat(marks).extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.UNLOCKED,
                TakeManifest.ClockEvent.LOCKED, TakeManifest.ClockEvent.UNLOCKED);
        assertThat(marks.get(1).frame()).as("the held relock at the sample that wrote it").isBetween(s1[0], s1[1]);
        assertThat(marks.get(2).frame()).as("the unlock after it at its own sample").isBetween(s3[0], s3[1]);
    }

    /** Rulings E221 and E223: a relock and the unlock after it with no frame sampled between them write neither. */
    @Test
    void aHeldRelockAndTheUnlockAfterItWithNoSampleBetweenWriteNeither() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        List<Boolean> returned = List.of(unlock(e1), relock(e1 + 1), unlock(e1 + 2));
        runOnFx(dispatcher::pulse);

        assertThat(returned).as("the take stays marked unlocked across the pair").containsExactly(true, false, true);
        assertThat(stopAndReadMarks(takeDirectory)).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    @Test
    void aHeldRelockIsWrittenAtTheStopWhenNoSampleCame() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        long[] s = sampleNow();
        unlock(e1);
        relock(e1 + 1);

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(marks.get(1).frame()).as("at the Stop, after the last sample").isGreaterThanOrEqualTo(s[1]);
    }

    /** Ruling E221: a device loss drops a held relock, so the rescued take ends marked unlocked. */
    @Test
    void aHeldRelockIsDroppedAtADeviceLoss() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        unlock(e1);
        relock(e1 + 1);
        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(fixture.activeDevice()));
        fixture.awaitRescued();

        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /** Ruling E221: a retire drops a held relock, so the take the next Stop seals ends marked unlocked. */
    @Test
    void aHeldRelockIsDroppedWhenTheCoordinatorIsRetired() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        unlock(e1);
        relock(e1 + 1);
        runOnFx(fixture.controller()::retire);

        assertThat(stopAndReadMarks(takeDirectory)).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * Ruling E280: from the capture thread's early seal until the take is stopped for it, an unlock and a start mark
     * mark nothing and answer that the take is not marked, and the take reads as not recording.
     */
    @Test
    void anUnlockOrAStartMarkAfterTheEarlySealMarksNothingAndTheTakeReadsAsNotRecording() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();
        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        TakeClockMarks marks = TakeClockMarks.of(fixture::controller);
        TakeClockMarks.TakeRecording before = onFx(marks::takeRecording);
        runOnFx(fixture.coordinator()::readTheCaptureAsSealedForTest);
        List<Object> answers = onFx(() -> List.<Object>of(marks.takeRecording(), marks.lockChanged(false, e1),
                marks.takeStartsUnlocked(), fixture.coordinator().getState()));

        assertThat(before).as("premise: an audio take records").isEqualTo(TakeClockMarks.TakeRecording.AUDIO);
        assertThat(answers).containsExactly(TakeClockMarks.TakeRecording.NONE, false, false, RecordState.RECORDING);
        assertThat(stopAndReadMarks(takeDirectory)).as("nothing marked in the window (ruling E280)").isEmpty();
    }

    /** Ruling E280: a relock handed over after the early seal is neither written nor held. */
    @Test
    void aRelockAfterTheEarlySealIsNeitherWrittenNorHeld() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();
        long e1 = nanos.addAndGet(1_000_000_000L);
        long[] s0 = sampleNow();
        boolean unlocked = unlock(e1);
        long e2 = nanos.addAndGet(1_000_000_000L);
        nanos.addAndGet(1_000_000_000L);
        recordsOnPast(s0[1]);
        long[] s1 = sampleNow();
        runOnFx(fixture.coordinator()::readTheCaptureAsSealedForTest);
        boolean relocked = relock(e2);
        runOnFx(dispatcher::pulse);

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(s1[0]).as("premise: a frame was sampled after the relock's evidence").isGreaterThan(s0[1]);
        assertThat(List.of(unlocked, relocked)).containsExactly(true, false);
        assertThat(marks).extracting(TakeManifest.ClockMark::event).as("the take stays marked unlocked")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * Ruling E280: a relock held when the early seal comes is written neither by the next sample nor at the Stop the
     * coordinator makes for the early seal, so the take's last lock line stays clock=unlocked.
     */
    @Test
    void aRelockHeldWhenTheEarlySealComesIsDropped() throws Exception {
        CompletableFuture<EarlySeal> earlySeal = new CompletableFuture<>();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> {
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
            fixture.coordinator().setEarlySealSignalForTest(_ -> earlySeal);
        });
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        await(() -> transport.getPositionInBeats() >= pipeline.getRecordingStartBeat() + 1.0,
                "the take recorded on past its start");
        long e1 = nanos.addAndGet(1_000_000_000L);
        long[] s0 = sampleNow();
        boolean unlocked = unlock(e1);
        relock(e1 + 1);
        runOnFx(fixture.coordinator()::readTheCaptureAsSealedForTest);
        nanos.addAndGet(1_000_000_000L);
        recordsOnPast(s0[1]);
        sampleNow();
        RecordState inTheWindow = onFx(fixture.coordinator()::getState);
        runOnFx(() -> earlySeal.complete(
                new EarlySeal.DiskExhausted(DiskHeadroomWatch.DEFAULT_FLOOR_BYTES, false, true)));
        RecordState afterTheAutoStop = onFx(fixture.coordinator()::getState);
        fixture.awaitPublished();

        assertThat(unlocked).isTrue();
        assertThat(inTheWindow).as("premise: the take is in its early seal's window").isEqualTo(RecordState.RECORDING);
        assertThat(afterTheAutoStop).as("premise: the coordinator stopped the take for its early seal")
                .isNotEqualTo(RecordState.RECORDING);
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event).as("the take's last lock line stays clock=unlocked")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * Ruling E245: a Stop whose dropout report breaks with an error still
     * writes the held relock, latches the take's figures and stops sampling
     * its frames, and the error reaches the Stop's caller.
     */
    @Test
    void aStopWhoseDropoutCountCannotBeReadStillStopsSamplingTheTake() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        AtomicReference<Error> nextReport = new AtomicReference<>();
        AudioBackend device = failingItsNextDropoutReport(fixture.backend(), nextReport);
        runOnFx(() -> {
            fixture.engine().setStreamingProvision(new StreamingProvision(device.name(),
                    List.of(new BackendStreamRung(device, fixture.activeDevice()))));
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
        });
        int participantsBefore = dispatcher.pulseParticipantCount();
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        unlock(e1);
        relock(e1 + 1);
        int participantsWhileRecording = dispatcher.pulseParticipantCount();
        InternalError injected = new InternalError("injected: the dropout report broke at the Stop");
        Throwable thrown = catchThrowable(() -> runOnFx(() -> {
            nextReport.set(injected);
            fixture.controller().stop();
        }));
        int participantsAfterTheStop = dispatcher.pulseParticipantCount();
        TakeDropouts figures = onFx(fixture.coordinator()::takeDropouts).orElseThrow();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(nextReport.get()).as("premise: the Stop read the broken report").isNull();
        assertThat(thrown).isSameAs(injected);
        assertThat(participantsWhileRecording).as("premise: the take samples on the window's pulse")
                .isEqualTo(participantsBefore + 1);
        assertThat(participantsAfterTheStop).as("the take's frames are no longer sampled")
                .isEqualTo(participantsBefore);
        assertThat(figures.recording()).as("the figures are the last take's").isFalse();
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event).as("the held relock was written before the error")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
    }

    /**
     * Ruling E245, the first of the Stop's three steps: a Stop whose write of the held relock breaks with an error
     * still latches the take's figures and stops sampling its frames, and the error reaches the Stop's caller.
     */
    @Test
    void aStopWhoseHeldRelockCannotBeWrittenStillLatchesTheTakesFiguresAndStopsSamplingIt() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> fixture.coordinator().setTakeFrameClockForTest(nanos::get));
        int participantsBefore = dispatcher.pulseParticipantCount();
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        unlock(e1);
        relock(e1 + 1);
        int participantsWhileRecording = dispatcher.pulseParticipantCount();
        InternalError injected = new InternalError("injected: the held relock's write broke at the Stop");
        AtomicInteger writes = new AtomicInteger();
        runOnFx(() -> fixture.coordinator().setHeldRelockWriteForTest((pipeline, frame) -> {
            writes.incrementAndGet();
            throw injected;
        }));
        Throwable thrown = catchThrowable(() -> runOnFx(fixture.controller()::stop));
        int participantsAfterTheStop = dispatcher.pulseParticipantCount();
        TakeDropouts figures = onFx(fixture.coordinator()::takeDropouts).orElseThrow();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(writes).as("premise: the Stop tried to write the held relock once").hasValue(1);
        assertThat(thrown).isSameAs(injected);
        assertThat(participantsWhileRecording).as("premise: the take samples on the window's pulse")
                .isEqualTo(participantsBefore + 1);
        assertThat(participantsAfterTheStop).as("the take's frames are no longer sampled")
                .isEqualTo(participantsBefore);
        assertThat(figures.recording()).as("the take's figures are latched: the last take's").isFalse();
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event).as("the relock the write lost is not written later")
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /** Ruling E221: evidence older than the sampled span is dated at the newest sample before it, never after it. */
    @Test
    void anUnlockSeenAfterAStretchLongerThanTheSpanIsDatedAtTheLastFrameSampledBeforeIt() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long t0 = nanos.addAndGet(1_000_000_000L);
        long[] sampled = sampleNow();
        nanos.addAndGet(10_000_000_000L);
        recordsOnPast(sampled[1]);
        runOnFx(dispatcher::pulse);
        unlock(t0 + 1_000_000_000L);

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(sampled[0]).as("premise: the sample is not the take's start-frame")
                .isGreaterThan(fixture.pipeline().getRecordingStartFrame());
        assertThat(marks).singleElement().satisfies(mark -> {
            assertThat(mark.event()).isEqualTo(TakeManifest.ClockEvent.UNLOCKED);
            assertThat(mark.frame()).as("the last frame sampled before the evidence").isBetween(sampled[0], sampled[1]);
        });
    }

    /**
     * Ruling E221's second sentence: when the frame that dates a relock was
     * already sampled, the relock is written there and the unlock after it
     * at the last frame before the stretch, so the take reads unlocked
     * across both.
     */
    @Test
    void aRelockWhoseFrameWasSampledIsWrittenAndTheUnlockAfterItIsDatedBeforeTheStretch() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long b = nanos.addAndGet(1_000_000_000L);
        long[] last = sampleNow();
        boolean firstUnlock = unlock(b);
        long eL = nanos.addAndGet(1_000_000_000L);
        long eU = nanos.addAndGet(1_000_000_000L);
        nanos.addAndGet(1_000_000_000L);
        recordsOnPast(last[1]);
        long[] first = sampleNow();
        boolean[] pair = onFx(() -> new boolean[] {
                fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.LOCKED, eL),
                fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, eU)});

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(List.of(firstUnlock, pair[0], pair[1])).containsExactly(true, false, true);
        assertThat(first[0]).as("premise: the take recorded on between the two samples").isGreaterThan(last[1]);
        assertThat(marks).extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.UNLOCKED,
                TakeManifest.ClockEvent.LOCKED, TakeManifest.ClockEvent.UNLOCKED);
        assertThat(marks.get(0).frame()).isBetween(last[0], last[1]);
        assertThat(marks.get(1).frame()).as("the relock at the sample after its evidence")
                .isBetween(first[0], first[1]);
        assertThat(marks.get(2).frame()).as("the unlock at the last sample before the stretch, below the relock")
                .isBetween(last[0], last[1]);
    }

    /** Ruling E223: a start mark on a take already marked unlocked writes nothing and says the take is marked. */
    @Test
    void theStartMarkOnATakeAlreadyMarkedUnlockedWritesNothingAndSaysTheTakeIsMarked() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        long[] s = sampleNow();
        unlock(e1);
        boolean marked = onFx(fixture.coordinator()::noteTakeStartsUnlocked);

        List<TakeManifest.ClockMark> marks = stopAndReadMarks(takeDirectory);
        assertThat(s[0]).as("premise: e1's sample is past the take's start-frame")
                .isGreaterThan(fixture.pipeline().getRecordingStartFrame());
        assertThat(marked).isTrue();
        assertThat(marks).singleElement().satisfies(mark -> {
            assertThat(mark.event()).isEqualTo(TakeManifest.ClockEvent.UNLOCKED);
            assertThat(mark.frame()).as("at e1's sample, not at the start-frame").isBetween(s[0], s[1]);
        });
    }

    /** Rulings E221 and E258: a start mark is an unlock, so it cancels a relock held, and writes nothing itself. */
    @Test
    void theStartMarkCancelsAHeldRelock() throws Exception {
        Path takeDirectory = recordingOnPastItsStart();

        long e1 = nanos.addAndGet(1_000_000_000L);
        sampleNow();
        unlock(e1);
        relock(e1 + 1);
        boolean marked = onFx(fixture.coordinator()::noteTakeStartsUnlocked);

        assertThat(marked).isTrue();
        assertThat(stopAndReadMarks(takeDirectory)).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED);
    }

    /**
     * Ruling E183: loop [32, 48) beats, the playhead parked at beat 80 and a
     * one-bar count-in. The count-in fires at the start-frame and the
     * transport wraps into the loop before the take's first sample, so no
     * sample reads the start-frame again: the take is not anchored at a
     * punch-in, its first sample is its reach, evidence seen while it
     * records is dated at a frame of the loop range, and evidence from before
     * the take at its start-frame.
     */
    @Test
    void aTakeWhoseTransportWrapsIntoTheLoopBeforeItsFirstSampleIsMarkedInTheLoop() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.ONE_BAR, dispatcher);
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        runOnFx(() -> {
            transport.setLoopWindow(true, 32.0, 48.0);
            transport.setPositionInBeats(80.0);
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
        });
        long beforeTheTake = nanos.get();
        nanos.addAndGet(500_000_000L);
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.COUNT_IN, "the take counts in");
        RecordingPipeline pipeline = fixture.pipeline();
        Path takeDirectory = pipeline.getTakeDirectory();
        awaitOnFx(() -> transport.getPositionInBeats() < 48.0, "the count-in fired and the transport wrapped");
        awaitOnFx(() -> {
            dispatcher.pulse();
            return fixture.coordinator().getState() == RecordState.RECORDING;
        }, "the count-in's fire, observed on the dispatcher's pulse");

        long evidence = nanos.addAndGet(1_000_000_000L);
        runOnFx(dispatcher::pulse);
        nanos.addAndGet(1_000_000_000L);
        runOnFx(() -> {
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, evidence);
            fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.LOCKED, beforeTheTake);
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        long loopStart = pipeline.transportFrameOf(32.0);
        long loopEnd = pipeline.transportFrameOf(48.0);
        long startFrame = pipeline.getRecordingStartFrame();
        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(pipeline.isRecordingStartAPunchIn()).as("premise: no punch anchors the take").isFalse();
        assertThat(startFrame).as("premise: the start-frame lies past the loop").isGreaterThan(loopEnd);
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.UNLOCKED, TakeManifest.ClockEvent.LOCKED);
        assertThat(marks.get(0).frame()).as("evidence while the take records: the frame sampled, in the loop range")
                .isGreaterThanOrEqualTo(loopStart).isLessThan(loopEnd);
        assertThat(marks.get(1).frame()).as("evidence from before the take: its start-frame").isEqualTo(startFrame);
    }

    /**
     * Ruling E177 through the window's wiring: a punch take enters RECORDING
     * at the start of its pre-roll, its start-frame is the punch-in, and
     * evidence seen in the pre-roll is dated at the punch-in. The punch-in
     * lies ten minutes on, so the whole test runs in the pre-roll.
     */
    @Test
    void aPunchTakesPreRollEvidenceIsMarkedAtItsPunchIn() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        long punchIn = 44_100L * 600;
        runOnFx(() -> {
            transport.setPunchRegion(PunchRegion.enabled(punchIn, punchIn + 44_100L));
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
        });
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.RECORDING, "the take records its pre-roll");
        RecordingPipeline pipeline = fixture.pipeline();
        Path takeDirectory = pipeline.getTakeDirectory();

        long evidence = nanos.addAndGet(1_000_000_000L);
        long sampled = onFx(() -> {
            dispatcher.pulse();
            return pipeline.transportFrameOf(transport.getPositionInBeats());
        });
        nanos.addAndGet(1_000_000_000L);
        runOnFx(() -> fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, evidence));
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(pipeline.isRecordingStartAPunchIn()).as("premise: the punch-in anchors the take").isTrue();
        assertThat(pipeline.getRecordingStartFrame()).as("premise: its start-frame").isEqualTo(punchIn);
        assertThat(sampled).as("premise: the evidence was seen in the pre-roll").isLessThan(punchIn);
        assertThat(marks).singleElement().satisfies(mark -> {
            assertThat(mark.event()).isEqualTo(TakeManifest.ClockEvent.UNLOCKED);
            assertThat(mark.frame()).as("at the punch-in, not at the pre-roll frame sampled").isEqualTo(punchIn);
        });
    }

    @Test
    void aClockEventDuringTheCountInMarksNothingAndTheTakeStartsAfterIt() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.ONE_BAR, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.COUNT_IN, "the take counts in");
        Path takeDirectory = fixture.pipeline().getTakeDirectory();

        runOnFx(() -> fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, 0L));
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("the take has not started recording").isEmpty();
        awaitOnFx(() -> {
            dispatcher.pulse();
            return fixture.coordinator().getState() == RecordState.RECORDING;
        }, "the count-in fired, observed on the dispatcher's pulse");
        assertThat(onFx(fixture.coordinator()::takeDropouts)).as("now it has")
                .hasValueSatisfying(figures -> assertThat(figures.recording()).isTrue());
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .as("an event during the count-in is not the take's").isEmpty();
    }

    /**
     * The shared setup of the clock-mark tests: a take recording on past its
     * start, its frames sampled on {@link #nanos}, its reach sample taken at
     * the clock's first reading. In these tests {@code long x =
     * nanos.addAndGet(d)} advances the clock and names the time, a bare
     * {@code nanos.addAndGet(d)} only advances it, and {@code x + 1} is plain
     * arithmetic that advances nothing. Every sample is taken at its own time.
     *
     * @return the take directory
     */
    private Path recordingOnPastItsStart() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> fixture.coordinator().setTakeFrameClockForTest(nanos::get));
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        await(() -> transport.getPositionInBeats() >= pipeline.getRecordingStartBeat() + 1.0,
                "the take recorded on past its start");
        return takeDirectory;
    }

    /** One pulse of the window's dispatcher, whose sampler takes the take's frame: the frames just before and after. */
    private long[] sampleNow() throws Exception {
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        return onFx(() -> {
            long before = pipeline.transportFrameOf(transport.getPositionInBeats());
            dispatcher.pulse();
            return new long[] {before, pipeline.transportFrameOf(transport.getPositionInBeats())};
        });
    }

    /** Waits until the take has recorded a beat past {@code frame}. */
    private void recordsOnPast(long frame) {
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        await(() -> transport.getPositionInBeats() >= frameToBeats(transport, pipeline, frame) + 1.0,
                "the take recorded on past frame " + frame);
    }

    private boolean unlock(long evidenceNanos) throws Exception {
        return onFx(() -> fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.UNLOCKED, evidenceNanos));
    }

    private boolean relock(long evidenceNanos) throws Exception {
        return onFx(() -> fixture.coordinator().noteClockEvent(TakeManifest.ClockEvent.LOCKED, evidenceNanos));
    }

    private List<TakeManifest.ClockMark> stopAndReadMarks(Path takeDirectory) throws Exception {
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();
        return TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
    }

    private static double frameToBeats(Transport transport, RecordingPipeline pipeline, long frame) {
        double framesPerBeat = pipeline.transportFrameOf(1_000.0) / 1_000.0;
        return frame / framesPerBeat;
    }

    /**
     * {@code device} as the engine's rung, except that once {@code failure} holds an error the next dropout report
     * throws it.
     */
    private static AudioBackend failingItsNextDropoutReport(AudioBackend device, AtomicReference<Error> failure) {
        InvocationHandler calls = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "FailingItsNextDropoutReport[" + device.name() + "]";
                };
            }
            if (method.getName().equals("dropoutCounters")) {
                Error next = failure.getAndSet(null);
                if (next != null) {
                    throw next;
                }
            }
            try {
                return method.invoke(device, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
        };
        return (AudioBackend) Proxy.newProxyInstance(AudioBackend.class.getClassLoader(),
                new Class<?>[] {AudioBackend.class}, calls);
    }
}
