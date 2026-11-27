package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.event.SubscriberFailureSink;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 338, through the production detection wiring (a real engine
 * controller, the engine-state bridge with the window's device-loss
 * reactions as its first listener, the host's removal from the mock): after a
 * device loss the transport, the playhead and the input meters tell the truth
 * (rulings E35, E36, E64–E66). A playback-only loss stops the transport where
 * the audio stopped, with return-to-start on, and announces one Stopped; a
 * loss that rescues a take leaves the rescue's own stop alone and puts the
 * playhead back where the loss stopped it, while the rescued clip stays at
 * the take's anchor; a MIDI take's count-in over a paused transport ends with the
 * transport stopped and the abort's message kept; the input meters fall to
 * the floor keeping their clip latch.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DeviceLossLeavesTheTransportAndMetersTruthfulTest {

    /** Where Play or Record starts: the anchor return-to-start would rewind to. */
    private static final double ANCHOR = 4.0;

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;
    private EventBus previousBus;
    private DefaultEventBus bus;
    private final List<TransportEvent> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void wireTheProductionDetectionPath() throws Exception {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus(SubscriberFailureSink.LOG_ONLY);
        bus.onPublication(TransportEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> fixture.project().getTransport().setPositionInBeats(ANCHOR));
    }

    @AfterEach
    void stopEverything() throws Exception {
        try {
            if (fixture != null) {
                fixture.close();
            }
        } finally {
            EventBusPublisher.setDefault(previousBus);
            bus.close();
        }
    }

    @Test
    void aPlaybackOnlyLossStopsTheTransportWhereTheAudioStoppedWithOneStoppedAnnounced() throws Exception {
        Transport transport = fixture.project().getTransport();
        assertThat(transport.isReturnToStartOnStop()).as("premise: return-to-start is on").isTrue();
        runOnFx(fixture.controller()::start);
        await(() -> transport.getPositionInBeats() >= ANCHOR + 1.0, "the playhead moved past the anchor");
        published.clear();

        double stoppedAt = loseTheDeviceAndReadThePlayheadOnceTheStreamClosed();
        awaitOnFx(() -> transport.getState() == TransportState.STOPPED, "the loss's delivery stopped the transport");
        runOnFx(() -> { });

        assertThat(stoppedAt).as("premise: the audio stopped past the anchor").isGreaterThan(ANCHOR + 0.5);
        assertThat(onFx(transport::getPositionInBeats)).as("the playhead stays where the audio stopped (ruling E65)")
                .isEqualTo(stoppedAt);
        assertThat(stoppedEvents()).as("one Stopped, at that position")
                .containsExactly(Math.round(stoppedAt * 60.0 / transport.getTempo() * 44_100.0));
        assertThat(onFx(() -> fixture.transportStatus().getText())).isEqualTo("STOPPED");
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo("Stopped");
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.IDLE);
        assertThat(fixture.engine().isStreamOpen()).as("the lost stream stays closed").isFalse();
    }

    @Test
    void aRescuedTakeKeepsItsOwnStopAndThePlayheadReturnsToWhereTheLossStoppedIt() throws Exception {
        Transport transport = fixture.project().getTransport();
        fixture.recordUntilABlockIsOnDisk();
        double anchor = fixture.pipeline().getRecordingStartBeat();
        await(() -> transport.getPositionInBeats() >= anchor + 1.0, "the take recorded past its anchor");
        published.clear();
        DeviceId device = fixture.activeDevice();

        double stoppedAt = loseTheDeviceAndReadThePlayheadOnceTheStreamClosed();
        fixture.awaitRescued();

        assertThat(stoppedAt).as("premise: the take stopped past its anchor").isGreaterThan(anchor + 0.5);
        assertThat(onFx(transport::getPositionInBeats))
                .as("after the rescue's stop the playhead is where the dropout happened (ruling E66)")
                .isEqualTo(stoppedAt);
        List<AudioClip> clips = onFx(() -> List.copyOf(fixture.guitar().getClips()));
        assertThat(clips).hasSize(1);
        assertThat(clips.getFirst().getStartBeat()).as("the rescued clip is placed at the take's anchor, not the playhead")
                .isCloseTo(anchor, within(0.05));
        assertThat(stoppedEvents()).as("the rescue's one Stopped and no second").hasSize(1);
        assertThat(onFx(() -> fixture.statusBar().getText())).as("the rescue's status is not overwritten")
                .isEqualTo(RecordCoordinator.deviceLostStatus(device, true));
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
    }

    @Test
    void aMidiCountInOverAPausedTransportEndsStoppedAndKeepsTheAbortsMessage() throws Exception {
        fixture.close();
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory, CountInMode.TWO_BARS);
        fixture.bindTheProjectToTheEngine();
        Transport transport = fixture.project().getTransport();
        RecordingInFlightFixture.StubMidiInput midiInput = new RecordingInFlightFixture.StubMidiInput();
        runOnFx(() -> {
            fixture.guitar().setArmed(false);
            Track keys = new Track("Keys", TrackType.MIDI);
            keys.setArmed(true);
            keys.setMidiInputDeviceName(RecordingInFlightFixture.STUB_MIDI_INPUT);
            fixture.project().addTrack(keys);
            fixture.controller().setMidiInputDeviceResolverForTest(
                    name -> RecordingInFlightFixture.STUB_MIDI_INPUT.equals(name) ? midiInput : null);
        });
        runOnFx(() -> {
            transport.setTempo(60);
            transport.setPositionInBeats(ANCHOR);
            fixture.controller().start();
        });
        await(() -> transport.getPositionInBeats() >= ANCHOR + 0.25, "the playhead moved");
        runOnFx(fixture.controller()::pause);
        runOnFx(fixture.controller()::toggleRecord);
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.COUNT_IN, "the take counts in");
        assertThat(onFx(transport::getState)).as("premise: a MIDI take counting in over a paused transport")
                .isEqualTo(TransportState.PAUSED);
        assertThat(onFx(fixture.controller()::isRecordingInFlight)).isTrue();
        assertThat(fixture.pipeline()).as("premise: no audio take, whose stop would stop the transport").isNull();

        fixture.unplug();
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.ABORTED
                || fixture.coordinator().getState() == RecordState.IDLE, "the loss ended the count-in");
        runOnFx(() -> { });

        assertThat(onFx(transport::getState)).as("never a paused transport over a closed stream (ruling E65)")
                .isEqualTo(TransportState.STOPPED);
        assertThat(onFx(() -> fixture.statusBar().getText())).as("the abort's message is not overwritten")
                .startsWith("Recording aborted — no take was started: the audio device");
    }

    @Test
    void theInputMetersFallToTheFloorAtTheLossAndKeepTheirClipLatch() throws Exception {
        InputLevelMonitor monitor = fixture.inputMeters().getOrCreate(fixture.guitar().getId());
        float[] overload = new float[256];
        Arrays.fill(overload, 1.2f);
        monitor.process(overload);
        InputLevelMeter before = monitor.snapshot();
        assertThat(before.peakDbfs()).as("premise: the meter shows a level").isGreaterThan(-6.0);
        assertThat(before.clippedSinceReset()).as("premise: the clip latch is lit").isTrue();
        runOnFx(fixture.controller()::start);
        int deliveries = fixture.engineStateDeliveries();

        fixture.unplug();
        awaitOnFx(() -> fixture.engineStateDeliveries() > deliveries
                && fixture.engineController().orElseThrow().engineState() == EngineState.DEVICE_LOST,
                "the loss was delivered");
        awaitOnFx(() -> fixture.project().getTransport().getState() == TransportState.STOPPED,
                "the loss's turn ran");

        InputLevelMeter after = monitor.snapshot();
        assertThat(after.peakDbfs()).as("never a frozen bar beside \"Device lost\" (ruling E64)")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(after.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(after.clippedSinceReset()).as("the clip latch is kept").isTrue();
    }

    /**
     * Holds the FX thread, unplugs the device, waits until the engine
     * controller closed the stream and declared the loss — the playhead stands
     * still from then on, and the loss's FX turn has not run — reads the
     * playhead, and releases the FX thread.
     */
    private double loseTheDeviceAndReadThePlayheadOnceTheStreamClosed() throws Exception {
        DefaultAudioEngineController engineController = fixture.engineController().orElseThrow();
        double stoppedAt;
        try (DeviceLossRecordingFixture.FxHold hold = DeviceLossRecordingFixture.holdTheFxThread()) {
            fixture.unplug();
            await(() -> engineController.engineState() == EngineState.DEVICE_LOST, "the controller declared the loss");
            assertThat(fixture.engine().isStreamOpen()).as("premise: the loss closed the stream").isFalse();
            stoppedAt = fixture.project().getTransport().getPositionInBeats();
            hold.release();
        }
        return stoppedAt;
    }

    private List<Long> stoppedEvents() {
        return published.stream().filter(TransportEvent.Stopped.class::isInstance)
                .map(event -> ((TransportEvent.Stopped) event).positionFrames()).toList();
    }
}
