package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record coordinator's side of story 338 on a real take: the take's
 * dropout figures follow it from its first recorded block to after its Stop
 * and stay until the next take or a clear (ruling E24); and while it records,
 * its transport frames are sampled on each pulse of the window's dispatcher,
 * so a clock event is dated at the frame sampled when its evidence was seen,
 * and only while the take records (amendment A11, rulings E30, E40, E62).
 */
@ExtendWith(JavaFxToolkitExtension.class)
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

    private static double frameToBeats(Transport transport, RecordingPipeline pipeline, long frame) {
        double framesPerBeat = pipeline.transportFrameOf(1_000.0) / 1_000.0;
        return frame / framesPerBeat;
    }
}
