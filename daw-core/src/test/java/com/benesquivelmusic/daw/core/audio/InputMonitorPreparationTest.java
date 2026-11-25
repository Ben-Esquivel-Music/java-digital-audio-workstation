package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InputMonitorPreparationTest {
    @Test
    void noneRoutesAndNewGraphTracksArePreparedBeforeTheyReachTheCallback() {
        var format = new AudioFormat(48000, 2, 16, 256);
        var engine = new AudioEngine(format);
        var registry = new InputLevelMonitorRegistry();
        var track = new Track("Initially disconnected", TrackType.AUDIO);
        track.setInputRouting(InputRouting.NONE);
        track.setArmed(true);
        engine.setInputLevelMonitorRegistry(registry);
        var transport = new Transport();
        engine.setGraph(transport, new Mixer(), List.of(track));
        var initial = registry.get(track.getId());
        assertThat(initial).isNotNull();
        engine.start();
        transport.play();
        try {
            track.setInputRouting(new InputRouting(0, 1));
            float[][] input = new float[1][256];
            Arrays.fill(input[0], 0.5f);
            engine.processBlock(input, new float[2][256], 256);
            assertThat(registry.get(track.getId())).isSameAs(initial);
            assertThat(initial.snapshot().peakDbfs()).isCloseTo(-6.0206, org.assertj.core.data.Offset.offset(0.001));
            var added = new Track("New graph track", TrackType.AUDIO);
            added.setArmed(true);
            engine.setTracks(List.of(track, added));
            assertThat(registry.get(added.getId())).isNotNull();
            var replacement = new InputLevelMonitorRegistry();
            engine.setInputLevelMonitorRegistry(replacement);
            assertThat(replacement.size()).isEqualTo(2);
            var replacementInitial = replacement.get(track.getId());
            var replacementAdded = replacement.get(added.getId());
            float[][] replacementInput = new float[2][256];
            for (float[] lane : replacementInput) Arrays.fill(lane, 0.25f);
            engine.processBlock(replacementInput, new float[2][256], 256);
            assertThat(replacement.get(track.getId())).isSameAs(replacementInitial);
            assertThat(replacement.get(added.getId())).isSameAs(replacementAdded);
            assertThat(replacementInitial.snapshot().peakDbfs()).isCloseTo(-12.0412, org.assertj.core.data.Offset.offset(0.001));
            assertThat(replacementAdded.snapshot().peakDbfs()).isCloseTo(-12.0412, org.assertj.core.data.Offset.offset(0.001));
            replacement.clear();
            engine.processBlock(input, new float[2][256], 256);
            assertThat(replacement.size()).as("the callback never recreates removed monitors").isZero();
        } finally { engine.shutdown(); }
    }

    /**
     * A device loss mutes the input meters through the lost render drive
     * (story 338, ruling E175); the engine lifts that mute where it builds a
     * new drive, so a stream's start lifts it and nothing before it does.
     */
    @Test
    void aStreamStartLiftsTheDeviceLossMuteOfTheInputMeters() {
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000, 2, 16, 256));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        engine.setStreamingProvision(mockProvision());
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        try {
            registry.silenceAll(engine.audioHealth().renderDriveEpoch());
            monitor.process(loud());
            assertThat(monitor.snapshot().peakDbfs()).as("muted while no drive has started since the loss")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);

            engine.startAudioOutput();
            assertThat(monitor.snapshot().peakDbfs()).as("the start shows no level by itself")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);
            monitor.process(loud());

            assertThat(monitor.snapshot().peakDbfs()).as("the started drive lifted the mute")
                    .isCloseTo(-6.0206, org.assertj.core.data.Offset.offset(0.001));
        } finally {
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    @Test
    void aResumeAfterAPauseLiftsTheDeviceLossMuteOfTheInputMeters() {
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000, 2, 16, 256));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        engine.setStreamingProvision(mockProvision());
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        try {
            engine.startAudioOutput();
            OpenedStream opened = engine.lastOpenedStream().orElseThrow();
            engine.pauseAudioOutput();
            registry.silenceAll(engine.audioHealth().renderDriveEpoch());
            monitor.process(loud());
            assertThat(monitor.snapshot().peakDbfs()).as("muted through the paused drive")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);

            engine.startAudioOutput();
            assertThat(engine.lastOpenedStream()).as("premise: a resume, not a reopen").contains(opened);
            monitor.process(loud());

            assertThat(monitor.snapshot().peakDbfs()).as("the resumed drive lifted the mute")
                    .isCloseTo(-6.0206, org.assertj.core.data.Offset.offset(0.001));
        } finally {
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * A reconnect whose open lands on another device than the lost one, and
     * which closes it again at once, does not end the loss for the input
     * meters: from that close they read the floor, clip latch kept, until the
     * next stream's first block (story 338, ruling E185). The block that
     * stream rendered is processed into the monitor after the reconnect
     * returns: the mock's render thread cannot be held to one block inside
     * the reconnect's lock section, and the snapshot is the same either side
     * of the close.
     */
    @Test
    void aReconnectClosedAgainOnAnotherDeviceKeepsTheMetersAtTheFloorUntilTheNextStream() {
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000, 2, 16, 256));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        engine.setStreamingProvision(mockProvision());
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        try {
            engine.startAudioOutput();
            monitor.process(clipping());
            long lostDrive = engine.audioHealth().renderDriveEpoch();
            engine.stopAudioOutput();
            registry.silenceAll(lostDrive);

            assertThat(engine.reopenAndPark(new DeviceId("Mock", "Another Interface")))
                    .as("premise: the reconnect opened another device and closed it again")
                    .isEqualTo(AudioEngine.ParkedReopen.CLOSED_ON_ANOTHER_DEVICE);
            assertThat(engine.isStreamOpen()).as("premise: no stream is open, so the loss is not over").isFalse();
            monitor.process(constant(0.1f));

            InputLevelMeter afterTheClose = monitor.snapshot();
            assertThat(afterTheClose.peakDbfs()).as("that stream's level is not left on the meter")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(afterTheClose.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(afterTheClose.clippedSinceReset()).as("the clip latch is kept").isTrue();

            engine.startAudioOutput();
            monitor.process(constant(0.1f));
            assertThat(monitor.snapshot().peakDbfs()).as("the next stream's first block reads its own level")
                    .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.01));
        } finally {
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    private static StreamingProvision mockProvision() {
        MockAudioBackend backend = new MockAudioBackend();
        return new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
    }

    private static float[] loud() {
        return constant(0.5f);
    }

    private static float[] constant(float value) {
        float[] block = new float[256];
        Arrays.fill(block, value);
        return block;
    }

    private static float[] clipping() {
        float[] block = new float[256];
        for (int i = 0; i < block.length; i++) {
            block[i] = (i % 2 == 0) ? 1.2f : -1.2f;
        }
        return block;
    }
}
