package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 327 fix round: the end of a loss is identity-matched by the record
 * coordinator with the engine controller's own predicate
 * ({@link DefaultAudioEngineController#sameDevice}). An open-stream
 * {@code STOPPED} whose watched device is <em>another</em> device — the
 * instrument-audio open of {@code MainController.pluginGraphChanged} walks
 * the provision ladder while the interface is unplugged and opens whatever
 * rung it yields — ends the loss (the state settles to IDLE, Record is
 * available again, the armed set is kept) but is not the lost device's
 * return: nothing is seeked to the rescued take's anchor, and the INFO says
 * which device the audio is on now and that the lost one did not return. A
 * device that matches by friendly name alone — the predicate's documented
 * fallback — is the return, with the seek and the "reconnected" INFO.
 * Drives the controller's engine-state seam directly, without the bridge,
 * so the sequence is exact.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TheLossEndingOnAnotherDeviceIsNotTheLostDevicesReturnTest {

    @TempDir
    Path projectDirectory;

    private final DeviceId lost = new DeviceId("Mock", "Yanked Interface");
    private final DeviceId other = new DeviceId("Mock", "Built-in Output");
    private final DeviceId sameNameOtherId = new DeviceId("Other backend", lost.name());
    private DeviceLossRecordingFixture fixture;

    @BeforeEach
    void openTheProject() throws Exception {
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory);
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void anotherDeviceEndsTheLossWithoutASeekAndSaysTheLostDeviceDidNotReturn() throws Exception {
        assertThat(DefaultAudioEngineController.sameDevice(lost, other)).as("premise: not the lost device").isFalse();
        double parked = loseTheDeviceAndParkThePlayheadOffTheAnchor();
        int before = fixture.shownCount();

        runOnFx(() -> fixture.controller().onEngineState(EngineState.STOPPED, Optional.of(other)));
        runOnFx(() -> { }); // whatever that posted has run

        assertThat(onFx(() -> fixture.coordinator().getState())).as("the loss is over: settles to IDLE")
                .isEqualTo(RecordState.IDLE);
        assertThat(onFx(() -> fixture.project().getTransport().getPositionInBeats()))
                .as("no seek: the playhead belongs to whatever opened the stream").isCloseTo(parked, within(1e-9));
        assertThat(fixture.entriesSince(before))
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.INFO,
                        "Audio is now on " + other.name() + "; " + lost.name()
                                + " did not return. Tracks are still armed; press Record to continue."));
        assertThat(onFx(() -> fixture.coordinator().recordAvailableProperty().get())).isTrue();
        assertThat(onFx(() -> fixture.guitar().isArmed())).as("the armed set is kept").isTrue();
        assertThat(onFx(() -> fixture.transportStatus().getText())).isNotEqualTo("Device lost");
        assertThat(onFx(() -> List.copyOf(fixture.transportStatus().getStyleClass())))
                .doesNotContain("status-device-lost");
    }

    @Test
    void aDeviceMatchingByNameAloneIsTheReturnWithTheSeek() throws Exception {
        assertThat(DefaultAudioEngineController.sameDevice(lost, sameNameOtherId))
                .as("premise: the predicate's friendly-name fallback").isTrue();
        loseTheDeviceAndParkThePlayheadOffTheAnchor();
        double anchor = fixture.pipeline().getRecordingStartBeat();
        int before = fixture.shownCount();

        runOnFx(() -> fixture.controller().onEngineState(EngineState.STOPPED, Optional.of(sameNameOtherId)));
        runOnFx(() -> { }); // whatever that posted has run

        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.IDLE);
        assertThat(onFx(() -> fixture.project().getTransport().getPositionInBeats()))
                .as("seeked to the rescued take's anchor").isCloseTo(anchor, within(1e-9));
        assertThat(fixture.entriesSince(before))
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.INFO,
                        sameNameOtherId.name() + " reconnected. Tracks are still armed; press Record to continue."));
    }

    /**
     * Records until a block is on disk, reports the loss through the
     * controller's seam (so its latch is set as the bridge would set it),
     * waits for the rescue, parks the playhead five beats past the rescued
     * take's anchor and reopens the stream on the test thread, as the
     * engine controller's reopen would; returns the parked position.
     */
    private double loseTheDeviceAndParkThePlayheadOffTheAnchor() throws Exception {
        fixture.recordUntilABlockIsOnDisk();
        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.controller().onEngineState(EngineState.DEVICE_LOST, Optional.of(lost)));
        fixture.awaitRescued();
        double parked = fixture.pipeline().getRecordingStartBeat() + 5.0;
        runOnFx(() -> fixture.project().getTransport().setPositionInBeats(parked));
        fixture.engine().startAudioOutput();
        fixture.engine().pauseAudioOutput();
        assertThat(fixture.engine().isStreamOpen()).as("premise: the stream is open again").isTrue();
        return parked;
    }
}
