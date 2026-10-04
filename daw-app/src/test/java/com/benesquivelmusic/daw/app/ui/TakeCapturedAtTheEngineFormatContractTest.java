package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A take recorded through the real {@link TransportController} is captured
 * at the format the engine is streaming, not the format the project was
 * created with (Recording Reliability book §2.7): the ring's slots hold the
 * engine's block, nothing is truncated, the segment files and the manifest
 * carry the engine's rate, and the clip declares that rate and is placed and
 * sized by it. The engine is stepped by hand ({@link SteppedTakeFixture}),
 * so each take holds exactly the blocks fed here.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TakeCapturedAtTheEngineFormatContractTest {

    private static final double BPM = 120.0;

    @TempDir
    Path projectDirectory;

    private SteppedTakeFixture fixture;

    @AfterEach
    void endTheTake() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    /**
     * The audio settings run the engine at a larger buffer than the
     * project's format names: every block lands whole.
     */
    @Test
    void aTakeRecordedWhileTheEngineRunsALargerBufferThanTheProjectsHoldsEveryFrameOfEveryBlock()
            throws Exception {
        int blocks = 6;
        AudioFormat projectFormat = new AudioFormat(48_000, 2, 16, 256);
        AudioFormat engineFormat = new AudioFormat(48_000, 2, 16, 1024);
        DawProject project = new DawProject("saved", projectFormat);
        SteppedTakeFixture.giveADirectory(project, projectDirectory);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        fixture = new SteppedTakeFixture(project, engineFormat, RoundTripLatency.UNKNOWN, _ -> { });

        fixture.startRecording();
        fixture.feedRamp(blocks);
        RecordingPipeline pipeline = fixture.pipeline();
        Path takeDirectory = pipeline.getTakeDirectory();
        fixture.stopAndAwaitThePublication();

        long expectedFrames = (long) blocks * engineFormat.bufferSize();
        assertThat(pipeline.getTruncatedFrames()).as("no frame of any block was cut off").isZero();
        assertThat(pipeline.getOverflowCount()).as("no block was dropped").isZero();
        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.ringFrames()).as("the ring's slots hold the engine's block").isEqualTo(1024);
        assertThat(manifest.truncatedFrames()).isZero();
        assertThat(manifest.gaps()).isEmpty();

        AudioClip clip = SteppedTakeFixture.get(() -> armed.getClips()).getFirst();
        assertThat(clip.getSourceRateMetadata())
                .as("the clip declares every frame of the %d blocks of 1024", blocks)
                .isEqualTo(new SourceRateMetadata(48_000, 2, expectedFrames));
        assertThat(clip.getDurationBeats()).as("its length in beats is that of %d frames at 48 kHz", expectedFrames)
                .isCloseTo(expectedFrames / 48_000.0 * (BPM / 60.0), within(1e-9));

        float[][] onDisk = SegmentFile.readFrames(clip.getSourceSegmentPaths().stream().map(Path::of).toList());
        assertThat(onDisk).hasNumberOfRows(2);
        assertThat(onDisk[0]).as("the segment holds what was fed, frame for frame").hasSize((int) expectedFrames);
        assertHoldsTheRamp(onDisk, 0);
    }

    /**
     * The project was created at 96 kHz and the engine streams at 48 kHz,
     * with a driver latency to compensate: the take is a 48 kHz take, and
     * the clip is published with the audio read back from its segment.
     */
    @Test
    void aTakeRecordedAtAnEngineRateOtherThanTheProjectsDeclaresTheEngineRateAndIsPlacedByIt() throws Exception {
        assertATakeIsCapturedDeclaredAndPlacedAtTheEngineRate(48_000);
    }

    /**
     * The same with the engine at 44.1 kHz: a rate that is neither the
     * project's nor 48 kHz, so a 48 kHz constant anywhere in the take's
     * arithmetic gives a different clip length, start and header.
     */
    @Test
    void aTakeRecordedAt44100HzInA96kHzProjectDeclares44100HzAndIsPlacedByIt() throws Exception {
        assertATakeIsCapturedDeclaredAndPlacedAtTheEngineRate(44_100);
    }

    private void assertATakeIsCapturedDeclaredAndPlacedAtTheEngineRate(int engineRate) throws Exception {
        int blocks = 5;
        double startBeat = 8.0;
        AudioFormat projectFormat = new AudioFormat(96_000, 2, 16, 256);
        AudioFormat engineFormat = new AudioFormat(engineRate, 2, 16, 512);
        DawProject project = new DawProject("saved", projectFormat);
        SteppedTakeFixture.giveADirectory(project, projectDirectory);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        project.getTransport().setTempo(BPM);
        project.getTransport().setPositionInBeats(startBeat);
        RoundTripLatency latency = RoundTripLatency.of(384, 576);
        fixture = new SteppedTakeFixture(project, engineFormat, latency, _ -> { });

        fixture.startRecording();
        fixture.feedRamp(blocks);
        RecordingPipeline pipeline = fixture.pipeline();
        Path takeDirectory = pipeline.getTakeDirectory();
        fixture.stopAndAwaitThePublication();

        long expectedFrames = (long) blocks * engineFormat.bufferSize();
        long compensationFrames = pipeline.getResolvedCompensationFrames();
        assertThat(compensationFrames).as("fixture: the driver latency is compensated")
                .isEqualTo(latency.totalFrames()).isPositive();
        assertThat(pipeline.getTruncatedFrames()).isZero();
        assertThat(pipeline.getOverflowCount()).isZero();

        AudioClip clip = SteppedTakeFixture.get(() -> armed.getClips()).getFirst();
        assertThat(clip.getSourceRateMetadata()).as("the clip declares the rate the take was captured at")
                .isEqualTo(new SourceRateMetadata(engineRate, 2, expectedFrames));
        assertThat(clip.getDurationBeats()).as("its length in beats is computed at %d Hz", engineRate)
                .isCloseTo(expectedFrames / (double) engineRate * (BPM / 60.0), within(1e-9));
        assertThat(clip.getStartBeat()).as("its start is pulled back by the latency, in beats at %d Hz", engineRate)
                .isCloseTo(startBeat - compensationFrames / (double) engineRate * (BPM / 60.0), within(1e-9));

        TakeManifest manifest = TakeManifest.read(TakeManifest.manifestPath(takeDirectory));
        assertThat(manifest.sampleRate()).as("the manifest names the engine's rate").isEqualTo((double) engineRate);
        assertThat(manifest.ringFrames()).isEqualTo(512);
        List<Path> segments = clip.getSourceSegmentPaths().stream().map(Path::of).toList();
        assertThat(segments).hasSize(1);
        SegmentFile.Description header = SegmentFile.describe(segments.getFirst());
        assertThat(header.sampleRate()).as("the sealed WAV header says %d Hz", engineRate)
                .isEqualTo((double) engineRate);
        assertThat(header.frameCount()).isEqualTo(expectedFrames);
        assertThat(header.sealed()).isTrue();
        assertHoldsTheRamp(SegmentFile.readFrames(segments), 0);
        assertHoldsTheRamp(clip.getAudioData(), 0);
    }

    /** Asserts that {@code audio} is the fixture's ramp from frame {@code firstFrame} on, as 16 bits decode it. */
    static void assertHoldsTheRamp(float[][] audio, long firstFrame) {
        for (int channel = 0; channel < audio.length; channel++) {
            for (int frame = 0; frame < audio[channel].length; frame++) {
                if (audio[channel][frame] != SteppedTakeFixture.decodedRamp(channel, firstFrame + frame)) {
                    assertThat(audio[channel][frame])
                            .as("channel %d, frame %d", channel, frame)
                            .isEqualTo(SteppedTakeFixture.decodedRamp(channel, firstFrame + frame));
                }
            }
        }
    }
}
