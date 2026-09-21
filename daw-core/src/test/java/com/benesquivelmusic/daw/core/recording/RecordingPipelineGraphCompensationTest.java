package com.benesquivelmusic.daw.core.recording;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
            transport.setLoopRegion(4, 4 + 128.0 / 24_000);
            transport.setLoopEnabled(true);
        }
        var recording = new RecordingPipeline(engine, transport, format, directory, tracks);
        recording.setReportedLatency(new RoundTripLatency(inputLatency, outputLatency, 0));
        recording.setApplyLatencyCompensation(compensate);
        recording.setLoopRecord(mode.equals("loop"));
        recording.start();
        long expectedPhysical = compensate ? inputLatency + outputLatency : 0;
        try {
            assertThat(recording.getSession(instrument).getCompensationFrames()).isZero();
            if (mixed) assertThat(recording.getSession(physical).getCompensationFrames()).isEqualTo(expectedPhysical);
            float[][] input = new float[2][64];
            for (float[] samples : input) Arrays.fill(samples, 0.5f);
            for (int block = 0; block < 6; block++) engine.processBlock(input, new float[2][64], 64);
            assertThat(recording.getSession(instrument).getCompensationFrames()).isZero();
            if (mixed) assertThat(recording.getSession(physical).getCompensationFrames()).isEqualTo(expectedPhysical);
            recording.stop();

            assertThat(instrument.getClips()).hasSize(1);
            assertThat(instrument.getClips().getFirst().getStartBeat()).isEqualTo(4);
            if (mixed) {
                assertThat(physical.getClips()).hasSize(1);
                assertThat(physical.getClips().getFirst().getStartBeat())
                        .isEqualTo(4 - expectedPhysical / 24_000.0);
            }
            if (mode.equals("loop")) {
                assertThat(recording.getTakeGroups().get(instrument).size()).isGreaterThan(1);
                assertThat(recording.getTakeGroups().get(instrument).takes())
                        .allSatisfy(take -> assertThat(take.clip().getStartBeat()).isEqualTo(4));
                assertThat(recording.getTakeGroups().get(physical).takes())
                        .allSatisfy(take -> assertThat(take.clip().getStartBeat())
                                .isEqualTo(4 - expectedPhysical / 24_000.0));
            }
        } finally {
            if (recording.isActive()) recording.stop();
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
