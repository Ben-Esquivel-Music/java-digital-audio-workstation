package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.CapturedInputStarvation;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the recording callback tells its capture ring about a block the full ring drops (story 338, rulings E273,
 * E277 and E281): delivered input, a recorded instrument's audio, the silence after a declared stop, or an
 * input-starved block, each counted by its kind. The test is the ring's consumer; the flush service is never
 * started.
 */
class CaptureCallbackDropCountsTest {

    private static final int BLOCK = 8;
    private static final double SAMPLE_RATE = 48_000.0;
    private static final double TEMPO = 120.0;
    private static final AudioFormat MONO = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK);
    private static final long GIB = 1L << 30;

    @TempDir
    Path takeDir;

    private final Transport transport = new Transport();
    private final AudioEngine engine = new AudioEngine(MONO);
    private final CaptureCallback.StartGate gate = new CaptureCallback.StartGate();
    private final float[][] delivered = new float[1][BLOCK];
    private final float[][] stopped = new float[0][];

    @BeforeEach
    void openTheGateAtBeatZero() {
        transport.setPositionInBeats(0.0);
        gate.beat = 0.0;
        gate.positionSeek = transport.getPositionSeek();
        gate.seekPending = false;
        gate.active = true;
    }

    @Test
    void aDeliveredBlockTheFullRingDropsCarriedAudio() {
        CaptureRing ring = new CaptureRing(BLOCK, 1, 1, CaptureRing.MIN_SLOTS);
        CaptureCallback stream = streamCallback(ring);
        fillTheRing(stream);

        stream.onAudioCaptured(delivered, BLOCK);

        assertThat(ring.dropCounts()).isEqualTo(new CaptureRing.DropCounts(1, 1, 0, 0, 0, 0));
    }

    @Test
    void aZeroWidthBlockWithNoRecordedInstrumentThatTheFullRingDropsCarriedOnlySilence() {
        CaptureRing ring = new CaptureRing(BLOCK, 1, 1, CaptureRing.MIN_SLOTS);
        CaptureCallback stream = streamCallback(ring);
        fillTheRing(stream);

        stream.onAudioCaptured(stopped, BLOCK);

        assertThat(ring.dropCounts()).isEqualTo(new CaptureRing.DropCounts(1, 0, 0, 1, 0, 0));
    }

    /** Ruling E281: the render's silence for an input still live is counted as input-starved, not as audio. */
    @Test
    void aStarvedBlockTheFullRingDropsIsCountedAsStarved() {
        CaptureRing ring = new CaptureRing(BLOCK, 1, 1, CaptureRing.MIN_SLOTS);
        CaptureCallback stream = streamCallback(ring);
        fillTheRing(stream);

        CapturedInputStarvation.during(engine, () -> stream.onAudioCaptured(delivered, BLOCK));

        assertThat(ring.dropCounts()).isEqualTo(new CaptureRing.DropCounts(1, 0, 1, 0, 1, 0));
    }

    /** Ruling E277: a zero-width block with a recorded instrument's audio is no silence-only block. */
    @Test
    void aZeroWidthBlockWithARecordedInstrumentsAudioThatTheFullRingDropsCarriedAudio() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel("Synth");
        channel.addInsert(new InsertSlot("Synth", new ConstantInstrument()));
        mixer.addChannel(channel);
        engine.setGraph(transport, mixer, List.of(synth));
        engine.start();
        try {
            CaptureRing ring = new CaptureRing(BLOCK, 1, 2, CaptureRing.MIN_SLOTS);
            CaptureCallback stream = new CaptureCallback(ring, unstartedFlushService(ring), new Track[] {synth},
                    transport, engine, SAMPLE_RATE, SAMPLE_RATE, TEMPO, gate);
            fillTheRing(stream);
            engine.setRecordingCallback((input, frames) -> stream.onAudioCaptured(stopped, BLOCK));

            engine.processBlock(null, new float[1][BLOCK], BLOCK);

            assertThat(ring.dropCounts()).as("audio, and no channel of input")
                    .isEqualTo(new CaptureRing.DropCounts(1, 1, 0, 1, 0, 0));
        } finally {
            engine.setRecordingCallback(null);
            engine.stop();
        }
    }

    @Test
    void theBlockAfterTheDropsCarriesTheirCounts() {
        CaptureRing ring = new CaptureRing(BLOCK, 1, 1, CaptureRing.MIN_SLOTS);
        CaptureCallback stream = streamCallback(ring);
        fillTheRing(stream);
        stream.onAudioCaptured(delivered, BLOCK);
        stream.onAudioCaptured(stopped, BLOCK);
        ring.release();

        stream.onAudioCaptured(stopped, BLOCK);

        assertThat(ring.stampOf(ring.peekAt(8))).isEqualTo(new CaptureRing.DropCounts(2, 1, 0, 1, 0, 0));
    }

    private void fillTheRing(CaptureCallback stream) {
        for (int block = 0; block < CaptureRing.MIN_SLOTS; block++) {
            stream.onAudioCaptured(delivered, BLOCK);
        }
    }

    private CaptureCallback streamCallback(CaptureRing ring) {
        return new CaptureCallback(ring, unstartedFlushService(ring), new Track[0], transport, engine,
                SAMPLE_RATE, SAMPLE_RATE, TEMPO, gate);
    }

    private CaptureFlushService unstartedFlushService(CaptureRing ring) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, MONO, TEMPO, 0.0, 0L,
                null, false, Duration.ofSeconds(5), Instant.parse("2026-11-22T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20, Duration.ZERO,
                System::nanoTime, warning -> { });
        Track track = new Track("Take", TrackType.AUDIO);
        TrackCapture capture = new TrackCapture(track, new InputRouting(0, 1), -1, 1, BLOCK, 0, SAMPLE_RATE, TEMPO,
                takeDir.resolve(track.getId()), (t, dir) -> new RecordingSession(MONO, dir));
        return new CaptureFlushService(ring, config, List.of(capture), watch, warning -> { }, System::nanoTime);
    }

    /** A mono instrument whose every frame is 0.25. */
    private record ConstantInstrument() implements AudioProcessor, DawPlugin {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (float[] channel : output) {
                Arrays.fill(channel, 0, frames, 0.25f);
            }
        }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
        @Override public void reset() { }
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.instrument", "Test Instrument", "1", "Test", PluginType.INSTRUMENT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }
}
