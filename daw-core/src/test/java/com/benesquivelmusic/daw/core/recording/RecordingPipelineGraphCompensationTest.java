package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.startRecording;
import static com.benesquivelmusic.daw.core.recording.PipelineLifecycleTestSupport.stopRecording;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(CaptureFlushThreadLeakGuard.class)
class RecordingPipelineGraphCompensationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({
            "2400, 2400, true, false, normal",
            "2400, 2400, true, true, normal",
            "2400, 0, true, true, normal",
            "2400, 2400, false, true, normal",
            "2400, 2400, true, true, punch",
            "2400, 2400, true, true, loop"
    })
    void hardwareCompensationIsPerSourceForEveryTake(int inputLatency, int outputLatency,
                                                    boolean compensate, boolean mixed, String mode) {
        var format = new AudioFormat(48_000, 2, 24, 64);
        var engine = new AudioEngine(format);
        var transport = new Transport();
        transport.setPositionInBeats(4);
        var instrument = new Track("Instrument", TrackType.AUDIO);
        instrument.setInputRouting(InputRouting.NONE);
        instrument.setArmed(true);
        var physical = new Track("Physical", TrackType.AUDIO);
        physical.setArmed(true);
        var tracks = mixed ? List.of(instrument, physical) : List.of(instrument);
        var mixer = new Mixer();
        var instrumentChannel = new MixerChannel("Instrument");
        instrumentChannel.addInsert(new InsertSlot("Instrument", new ConstantInstrument()));
        mixer.addChannel(instrumentChannel);
        if (mixed) mixer.addChannel(new MixerChannel("Physical"));
        engine.setGraph(transport, mixer, tracks);
        if (mode.equals("punch")) transport.setPunchRegion(new PunchRegion(96_000, 96_256, true));
        if (mode.equals("loop")) {
            // 7.3 blocks: not block-aligned, so every wrap lands with a real
            // overshoot of 0.7 block inside (0, 1 block) — a block-aligned
            // loop would wrap to exactly the loop start and the overshoot
            // assertion below would be satisfied at its lower bound
            // (story 328 review round 2).
            transport.setLoopRegion(4, 4 + 7.3 * 64 / 24_000);
            transport.setLoopEnabled(true);
        }
        var recording = new RecordingPipeline(engine, transport, format, directory, tracks);
        recording.setReportedLatency(new RoundTripLatency(inputLatency, outputLatency, 0));
        recording.setApplyLatencyCompensation(compensate);
        recording.setLoopRecord(mode.equals("loop"));
        startRecording(recording);
        long expectedPhysical = compensate ? inputLatency + outputLatency : 0;
        try {
            assertThat(recording.getSession(instrument).getCompensationFrames()).isZero();
            if (mixed) assertThat(recording.getSession(physical).getCompensationFrames()).isEqualTo(expectedPhysical);
            float[][] input = new float[2][64];
            for (float[] samples : input) Arrays.fill(samples, 0.5f);
            int blocks = mode.equals("loop") ? 20 : 6;
            for (int block = 0; block < blocks; block++) engine.processBlock(input, new float[2][64], 64);
            recording.awaitFlushed();
            assertThat(recording.getSession(instrument).getCompensationFrames()).isZero();
            if (mixed) assertThat(recording.getSession(physical).getCompensationFrames()).isEqualTo(expectedPhysical);
            stopRecording(recording);

            assertThat(instrument.getClips()).hasSize(1);
            if (mixed) assertThat(physical.getClips()).hasSize(1);
            if (!mode.equals("loop")) {
                assertThat(instrument.getClips().getFirst().getStartBeat()).isEqualTo(4);
                if (mixed) {
                    assertThat(physical.getClips().getFirst().getStartBeat())
                            .isEqualTo(4 - expectedPhysical / 24_000.0);
                }
            } else {
                // Story 328 review: lap 1 starts at the record position; a
                // lap a wrap opened starts where its first block does — the
                // loop start plus the wrap's overshoot, under one block —
                // and every lap carries the track's own compensation.
                List<AudioClip> instrumentLaps = recording.getLapClips().get(instrument);
                assertThat(instrumentLaps.size()).isGreaterThan(1);
                assertThat(instrumentLaps.getFirst().getStartBeat()).isEqualTo(4);
                // Wrap 1 overshoots by 0.7 block, wrap 2 by 0.4 (8 and 15
                // blocks into a 7.3-block loop): strictly inside (0, 1 block).
                for (int lap = 1; lap < instrumentLaps.size(); lap++) {
                    assertThat(instrumentLaps.get(lap).getStartBeat()).as("lap %d", lap + 1)
                            .isGreaterThan(4.0)
                            .isLessThan(4 + 64 / 24_000.0);
                }
                List<AudioClip> physicalLaps = recording.getLapClips().get(physical);
                assertThat(physicalLaps).hasSameSizeAs(instrumentLaps);
                for (int lap = 0; lap < physicalLaps.size(); lap++) {
                    assertThat(physicalLaps.get(lap).getStartBeat()).as("lap %d, compensated", lap + 1)
                            .isCloseTo(instrumentLaps.get(lap).getStartBeat() - expectedPhysical / 24_000.0,
                                    offset(1e-9));
                }
                // The main lane holds the selected lap's comp slice, at that lap's own start.
                AudioClip instrumentComp = instrument.getClips().getFirst();
                assertThat(instrumentLaps).extracting(AudioClip::getStartBeat).contains(instrumentComp.getStartBeat());
                AudioClip physicalComp = physical.getClips().getFirst();
                assertThat(physicalLaps).extracting(AudioClip::getStartBeat).contains(physicalComp.getStartBeat());
            }
        } finally {
            if (recording.isActive()) stopRecording(recording);
            engine.stop();
            mixer.getDelayCompensation().close();
        }
    }

    private static final class ConstantInstrument implements AudioProcessor, DawPlugin {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (float[] channel : output) Arrays.fill(channel, 0, frames, 0.25f);
        }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public void reset() { }
        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("test.instrument", "Instrument", "1", "Test", PluginType.INSTRUMENT);
        }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
    }
}
