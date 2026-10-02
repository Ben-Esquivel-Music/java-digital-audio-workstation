package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seal a stop requests, over two tracks, where only the second lane's
 * seal fails: a file already sits where its segment is to be sealed, and a
 * segment is never overwritten. The first lane is sealed as its {@code .wav};
 * the second is left as its {@code .part}, the file in its way untouched; the
 * pipeline reports the failure with not every segment sealed, signals no
 * early seal, writes an aborted manifest, and still returns both tracks'
 * clips — the second without a sealed file behind it. Stereo, 16-bit, 48 kHz,
 * 256-frame blocks, each track on its own input channel.
 */
class StopSealOfTheSecondOfTwoLanesTest {

    private static final int FRAMES = 256;
    private static final AudioFormat STEREO = new AudioFormat(48_000.0, 2, 16, FRAMES);
    private static final String IN_THE_WAY = "already here";

    @TempDir
    Path takeDirectory;

    @Test
    void onlyTheSecondLaneIsLeftUnsealedAndTheStopReportsItsFailure() throws Exception {
        AudioEngine engine = new AudioEngine(STEREO);
        Transport transport = new Transport();
        Track left = armedOn("Left", 0);
        Track right = armedOn("Right", 1);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, STEREO, takeDirectory,
                List.of(left, right));
        pipeline.start();
        float[][] output = new float[2][FRAMES];
        for (int block = 0; block < 3; block++) {
            engine.processBlock(stereoBlock(block), output, FRAMES);
            transport.advancePosition(FRAMES / (STEREO.sampleRate() * 60.0 / transport.getTempo()));
        }
        pipeline.awaitFlushed();
        Path rightSealed = takeDirectory.resolve(right.getId()).resolve("segment-000.wav");
        Files.writeString(rightSealed, IN_THE_WAY, StandardCharsets.UTF_8);

        List<AudioClip> clips = pipeline.stop();

        StopSealFailure failure = pipeline.stopSealFailure().orElseThrow(
                () -> new AssertionError("the stop reports the second lane's failed seal"));
        assertThat(failure.failure()).isInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(FileAlreadyExistsException.class);
        assertThat(failure.everySegmentSealed()).as("the second lane's segment is not sealed").isFalse();
        assertThat(pipeline.earlySeal().toCompletableFuture().isDone()).as("no early seal").isFalse();

        Path leftSealed = takeDirectory.resolve(left.getId()).resolve("segment-000.wav");
        assertThat(leftSealed).as("the first lane is sealed").isRegularFile();
        assertThat(leftSealed.resolveSibling("segment-000.wav.part")).doesNotExist();
        assertThat(rightSealed.resolveSibling("segment-000.wav.part")).as("the second is left as its .part")
                .isRegularFile();
        assertThat(Files.readString(rightSealed, StandardCharsets.UTF_8)).as("what was in its way is untouched")
                .isEqualTo(IN_THE_WAY);

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.sealStatus()).isEqualTo(SealStatus.ABORTED);
        assertThat(manifest.sealedBy()).contains(SealedBy.WRITE_FAILURE);

        assertThat(clips).as("both tracks' clips are returned").hasSize(2);
        assertThat(left.getClips()).singleElement()
                .satisfies(clip -> assertThat(clip.getSourceFilePath()).isEqualTo(leftSealed.toString()));
        assertThat(right.getClips()).singleElement()
                .satisfies(clip -> assertThat(clip.getSourceFilePath()).as("no sealed file behind it").isNull());
    }

    private static Track armedOn(String name, int inputChannel) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputRouting(new InputRouting(inputChannel, 1));
        return track;
    }

    /** A stereo block: a rising line on the left channel, a falling one on the right. */
    private static float[][] stereoBlock(int block) {
        float[][] input = new float[2][FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            float t = (block * FRAMES + i) / (float) (3 * FRAMES);
            input[0][i] = 0.5f * t;
            input[1][i] = -0.5f * t;
        }
        return input;
    }
}
