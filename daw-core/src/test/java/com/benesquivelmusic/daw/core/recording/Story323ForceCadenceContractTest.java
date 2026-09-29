package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.advanceOneBlock;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.rampBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 proof — the force cadence (book §2.1, §4.3, §5.3 "Bounded
 * risk"): un-forced data per active segment is bounded by the configured
 * cadence, 5 s by default, measured on an injected clock.
 */
class Story323ForceCadenceContractTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * Story323TestSupport.BYTES_PER_FRAME_MONO_16;
    private static final int SECONDS = 20;

    @TempDir
    Path tempDir;

    @Test
    void theDefaultCadenceIsFiveSeconds() {
        assertThat(SegmentWriter.DEFAULT_FORCE_CADENCE).isEqualTo(Duration.ofSeconds(5));
        Track track = Story323TestSupport.armedMonoTrack("Bass");
        RecordingPipeline pipeline = new RecordingPipeline(new AudioEngine(MONO_16), new Transport(), MONO_16,
                tempDir.resolve("default"), List.of(track));
        assertThat(pipeline.getForceCadence()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void unforcedBytesNeverExceedFiveSecondsAndForcesLandExactlyOnTheBoundaries() throws IOException {
        long forcesAtFive = recordOneBlockPerSecond(tempDir.resolve("five"), Duration.ofSeconds(5));
        long forcesAtOne = recordOneBlockPerSecond(tempDir.resolve("one"), Duration.ofSeconds(1));

        assertThat(forcesAtFive).isEqualTo(SECONDS / 5);
        assertThat(forcesAtOne).as("a 1 s cadence forces five times as often").isEqualTo(5 * forcesAtFive);
    }

    @Test
    void aConfiguredCadenceReachesTheWriterThroughThePipelinesOwnSessionFactory() {
        // Nothing here builds a session: cadence, clock and channel opener
        // are set on the pipeline, and its own factory has to carry all
        // three to the writer.
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = Story323TestSupport.armedMonoTrack("Keys");
        AtomicLong clock = new AtomicLong(0);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16,
                tempDir.resolve("configured"), List.of(track));
        Duration cadence = Duration.ofSeconds(1);
        assertThat(cadence).as("fixture: not the default").isNotEqualTo(SegmentWriter.DEFAULT_FORCE_CADENCE);
        pipeline.setForceCadence(cadence);
        pipeline.setNanoClock(clock::get);
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        pipeline.start();

        assertThat(pipeline.getSession(track).getForceCadence()).isEqualTo(cadence);
        SegmentWriter writer = pipeline.getSession(track).getCurrentWriter();
        assertThat(writer.forceCadence()).as("the writer's cadence is the pipeline's").isEqualTo(cadence);
        assertThat(journal.events()).as("the segment was opened through the pipeline's opener").isNotEmpty();
        assertThat(journal.forces(false)).isZero();

        float[][] output = new float[1][BLOCK_FRAMES];
        for (int second = 1; second <= 3; second++) {
            clock.set(second * SECOND);
            engine.processBlock(rampBlock((long) (second - 1) * BLOCK_FRAMES), output, BLOCK_FRAMES);
            advanceOneBlock(transport);
            pipeline.awaitFlushed();
            assertThat(journal.forces(false))
                    .as("force(false) calls that reached the channel at t=%ds on the injected clock", second)
                    .isEqualTo(second);
        }

        assertThat(writer.forceCount()).isEqualTo(3);
        assertThat(writer.bytesSinceForce()).isZero();
        pipeline.stop();
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(3);
    }

    /**
     * Feeds one block per simulated second for {@link #SECONDS} seconds and
     * asserts, after every block, that the active writer's un-forced bytes
     * stay within one cadence of data and that {@code forceCount()} equals
     * the number of whole cadences elapsed.
     *
     * @return the writer's final force count
     */
    private long recordOneBlockPerSecond(Path takeDir, Duration cadence) throws IOException {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = Story323TestSupport.armedMonoTrack("Vocal");
        AtomicLong clock = new AtomicLong(0);
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setNanoClock(clock::get);
        pipeline.setForceCadence(cadence);
        // The segment's channel is wrapped so the test counts the force
        // CALLS that reach it; the session is the one the pipeline's own
        // factory builds, which carries cadence, clock and opener to it.
        ObservedFileChannel.Journal journal = new ObservedFileChannel.Journal();
        pipeline.setChannelOpener(journal.opener(SegmentWriter.CREATE_NEW_CHANNEL));
        pipeline.start();
        assertThat(journal.forces(false)).as("no force before the first cadence boundary").isZero();

        long cadenceSeconds = cadence.toSeconds();
        long maxUnforcedBytes = cadenceSeconds * BLOCK_BYTES; // one block per second
        float[][] output = new float[1][BLOCK_FRAMES];
        long frame = 0;
        long forces = 0;
        for (int second = 1; second <= SECONDS; second++) {
            clock.set(second * SECOND);
            engine.processBlock(rampBlock(frame), output, BLOCK_FRAMES);
            advanceOneBlock(transport);
            frame += BLOCK_FRAMES;
            pipeline.awaitFlushed();

            SegmentWriter writer = pipeline.getSession(track).getCurrentWriter();
            assertThat(writer.forceCadence()).isEqualTo(cadence);
            assertThat(writer.bytesSinceForce())
                    .as("un-forced bytes at t=%ds (cadence %ds)", second, cadenceSeconds)
                    .isLessThanOrEqualTo(maxUnforcedBytes);
            long expectedForces = second / cadenceSeconds;
            assertThat(writer.forceCount())
                    .as("forces at t=%ds (cadence %ds)", second, cadenceSeconds)
                    .isEqualTo(expectedForces);
            assertThat(journal.forces(false))
                    .as("force(false) calls that reached the channel at t=%ds (cadence %ds)", second, cadenceSeconds)
                    .isEqualTo(expectedForces);
            if (second % cadenceSeconds == 0) {
                assertThat(writer.bytesSinceForce()).as("just forced at t=%ds", second).isZero();
            } else {
                assertThat(writer.bytesSinceForce()).isEqualTo((second % cadenceSeconds) * BLOCK_BYTES);
            }
            forces = writer.forceCount();
        }

        TakeManifest manifest = TakeManifest.read(pipeline.getTakeManifestPath());
        assertThat(manifest.forceCadenceMillis()).isEqualTo(cadence.toMillis());
        assertThat(journal.forces(true)).as("no metadata force while the segment streams").isZero();
        pipeline.stop();
        assertThat(journal.forces(false)).as("the seal adds no cadence force").isEqualTo(forces);
        assertThat(journal.forces(true)).as("the seal forces the data, then the patched header").isEqualTo(2);
        return forces;
    }
}
