package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.recording.LiveCapturePeaks;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CaptureFlushService;
import com.benesquivelmusic.daw.core.recording.CapturePeakSnapshot;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded peak mirror of a take rides one {@code FxDispatcher}
 * continuous channel per armed audio track (Recording Reliability book
 * §4.5, §6.1), through the real {@link TransportController} record path: the
 * take's capture thread only publishes; the dispatcher's pulse delivers the
 * newest snapshot of each track to the controller's FX-side holder, and
 * nothing reaches it between pulses; and every way a take ends closes its
 * channels, so the dispatcher's open-channel count returns to what it was
 * before Record.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LiveCapturePeaksChannelContractTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 1024);

    @TempDir
    Path projectDirectory;

    private SteppedTakeFixture fixture;
    private final CaptureThreadHold hold = new CaptureThreadHold();

    @AfterEach
    void endTheTake() throws Exception {
        hold.release();
        if (fixture != null) {
            fixture.close();
        }
    }

    private DawProject projectWithADirectory() {
        DawProject project = new DawProject("saved", FORMAT);
        SteppedTakeFixture.giveADirectory(project, projectDirectory);
        return project;
    }

    private Optional<CapturePeakSnapshot> heldFor(Track track) throws Exception {
        return SteppedTakeFixture.get(() -> fixture.controller.liveCapturePeaks(track.getId()));
    }

    @Test
    void theNewestSnapshotOfEachArmedTrackIsDeliveredByAPulseAndNothingBetweenPulses() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        Track guitar = project.createAudioTrack("Gtr");
        guitar.setArmed(true);
        // The take's clock stands still unless the test moves it, so the
        // capture thread publishes when the test says: the first block at
        // once, and again only once the publish interval has passed.
        AtomicLong clock = new AtomicLong();
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN,
                pipeline -> pipeline.setNanoClock(clock::get));
        int channelsBeforeRecord = fixture.openChannels();

        fixture.startRecording();
        assertThat(fixture.openChannels()).as("one continuous channel per armed audio track")
                .isEqualTo(channelsBeforeRecord + 2);
        RecordingPipeline pipeline = fixture.pipeline();
        fixture.feedRamp(1);
        pipeline.awaitFlushed(SteppedTakeFixture.GUARD);

        SteppedTakeFixture.onFx(() -> { });
        assertThat(heldFor(vox)).as("published by the capture thread, but no pulse has run").isEmpty();
        assertThat(heldFor(guitar)).isEmpty();

        fixture.pulse();
        for (Track track : new Track[] {vox, guitar}) {
            CapturePeakSnapshot first = heldFor(track).orElseThrow();
            assertThat(first.trackId()).as("each track's holder has that track's snapshot").isEqualTo(track.getId());
            assertThat(first.laneIndex()).isZero();
            assertThat(first.sampleRate()).isEqualTo(48_000.0);
            assertThat(first.totalFrames()).isEqualTo(1024);
            assertThat(first.bucketCount()).isEqualTo(4).isLessThanOrEqualTo(CapturePeakSnapshot.MAX_BUCKETS);
            assertThat(first.min(0)).as("bucket 0 is the smallest sample of its 256 frames, across both channels")
                    .isEqualTo(fedExtreme(0, 256, false));
            assertThat(first.max(0)).isEqualTo(fedExtreme(0, 256, true));
        }

        clock.addAndGet(CaptureFlushService.PEAK_PUBLISH_INTERVAL.toNanos());
        fixture.feedRamp(3);
        pipeline.awaitFlushed(SteppedTakeFixture.GUARD);
        SteppedTakeFixture.onFx(() -> { });
        SteppedTakeFixture.onFx(() -> { });
        assertThat(heldFor(vox).orElseThrow().totalFrames())
                .as("a newer snapshot is in the channel; without a pulse the holder keeps the one it had")
                .isEqualTo(1024);

        fixture.pulse();
        CapturePeakSnapshot newest = heldFor(vox).orElseThrow();
        assertThat(newest.totalFrames()).as("the pulse delivered the newest snapshot").isGreaterThan(1024)
                .isLessThanOrEqualTo(4096);
        assertThat(newest.bucketCount()).isLessThanOrEqualTo(CapturePeakSnapshot.MAX_BUCKETS);
        assertThat(heldFor(guitar).orElseThrow().totalFrames()).isEqualTo(newest.totalFrames());

        fixture.stopAndAwaitThePublication();

        assertThat(fixture.openChannels()).as("the published take closed its channels")
                .isEqualTo(channelsBeforeRecord);
        fixture.pulse();
        assertThat(heldFor(vox)).as("the peaks of a finished take are not held as live").isEmpty();
        assertThat(heldFor(guitar)).isEmpty();
    }

    @Test
    void aStartCancelledWhileItsTakeIsBeingPreparedClosesItsChannels() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN,
                pipeline -> pipeline.setNanoClock(hold));
        int channelsBeforeRecord = fixture.openChannels();
        hold.arm();

        fixture.pressRecord();
        assertThat(fixture.openChannels()).as("no pipeline, no channel, while the take directory is being allocated")
                .isEqualTo(channelsBeforeRecord);
        fixture.storage.runNext();
        hold.awaitHolding(Duration.ofSeconds(10));
        assertThat(SteppedTakeFixture.get(fixture.controller::isPreparingTake))
                .as("fixture: the take is still being prepared").isTrue();
        assertThat(fixture.openChannels()).as("the take's pipeline is built: its channel is open")
                .isEqualTo(channelsBeforeRecord + 1);

        SteppedTakeFixture.onFx(fixture.controller::toggleRecord);

        assertThat(fixture.openChannels()).as("the cancelled start closed its channel")
                .isEqualTo(channelsBeforeRecord);
        assertThat(heldFor(vox)).isEmpty();
    }

    @Test
    void aStartThatFailsClosesItsChannels() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        // A file where the track's directory belongs: the capture thread
        // cannot create the take's files, and the readiness turn abandons the start.
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN, pipeline -> {
            try {
                Files.createFile(pipeline.getTakeDirectory().resolve(vox.getId()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        int channelsBeforeRecord = fixture.openChannels();

        fixture.pressRecord();
        fixture.storage.runNext();
        SteppedTakeFixture.awaitOnFx(() -> !fixture.controller.isPreparingTake(), "the start failed");

        assertThat(SteppedTakeFixture.get(() -> fixture.controller.activeTakeDirectory()))
                .as("fixture: no take is recording").isEmpty();
        assertThat(fixture.shown.getEntries()).as("fixture: the failed start was reported")
                .anyMatch(entry -> entry.level() == NotificationLevel.ERROR
                        && entry.message().startsWith("Recording aborted"));
        assertThat(fixture.openChannels()).as("the failed start closed its channel").isEqualTo(channelsBeforeRecord);
    }

    @Test
    void aStartWhosePipelineCannotBeBuiltLeavesNoChannelOpen() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN, _ -> {
            throw new IllegalStateException("injected: the pipeline could not be set up");
        });
        int channelsBeforeRecord = fixture.openChannels();

        fixture.pressRecord();
        fixture.storage.runNext();
        SteppedTakeFixture.awaitOnFx(() -> !fixture.controller.isPreparingTake(), "the start failed");

        assertThat(fixture.openChannels()).as("the channels opened for the pipeline were closed")
                .isEqualTo(channelsBeforeRecord);
    }

    @Test
    void retiringTheControllerClosesTheChannelsOfATakeThatIsRecording() throws Exception {
        DawProject project = projectWithADirectory();
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        fixture = new SteppedTakeFixture(project, FORMAT, RoundTripLatency.UNKNOWN, _ -> { });
        int channelsBeforeRecord = fixture.openChannels();
        fixture.startRecording();
        fixture.feedRamp(1);
        fixture.pipeline().awaitFlushed(SteppedTakeFixture.GUARD);
        fixture.pulse();
        assertThat(heldFor(vox)).as("fixture: the take's peaks are live").isPresent();
        assertThat(fixture.openChannels()).isEqualTo(channelsBeforeRecord + 1);

        SteppedTakeFixture.onFx(fixture.controller::retire);

        assertThat(fixture.openChannels()).as("the retired controller left no channel open")
                .isEqualTo(channelsBeforeRecord);
        assertThat(heldFor(vox)).as("and holds no peaks as live").isEmpty();
    }

    @Test
    void aTrackIdThatIsRefusedLeavesNoChannelOpen() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicReference<Throwable> refused = new AtomicReference<>();
        AtomicInteger openAfterTheRefusal = new AtomicInteger(-1);

        SteppedTakeFixture.onFx(() -> {
            try {
                // The bad id comes after two good ones.
                LiveCapturePeaks.open(dispatcher, Arrays.asList("vox", "gtr", null));
            } catch (NullPointerException e) {
                refused.set(e);
            }
            openAfterTheRefusal.set(dispatcher.openChannelCount());
        });

        assertThat(refused.get()).as("a null track id is refused").isNotNull()
                .hasMessage("trackId must not be null");
        assertThat(openAfterTheRefusal).as("and no channel was opened for the ids before it").hasValue(0);
    }

    /** The smallest or largest sample {@code feedRamp} fed in {@code [from, to)}, across both channels. */
    private static float fedExtreme(int from, int to, boolean largest) {
        float extreme = largest ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        for (int channel = 0; channel < 2; channel++) {
            for (int frame = from; frame < to; frame++) {
                float value = SteppedTakeFixture.ramp(channel, frame) / 32767.0f;
                extreme = largest ? Math.max(extreme, value) : Math.min(extreme, value);
            }
        }
        return extreme;
    }
}
