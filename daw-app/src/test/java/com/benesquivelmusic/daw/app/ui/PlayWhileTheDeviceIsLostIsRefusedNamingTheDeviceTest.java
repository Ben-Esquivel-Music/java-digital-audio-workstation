package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.transport.TransportState;
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

/**
 * Story 327 fix round: while the coordinator is DEVICE_LOST, Play — plain
 * or with pre-roll — is refused before the engine is touched, with the
 * same WARNING shape as Record's refusal: it names the lost device and the
 * ways out (reconnecting it, Stop, another device in Audio Settings). The
 * provision ladder is never walked, so a fallback rung cannot open and
 * roll the transport under a status that says "Device lost": the transport
 * stays STOPPED, the stream stays closed, the status keeps the loss.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PlayWhileTheDeviceIsLostIsRefusedNamingTheDeviceTest {

    @TempDir
    Path projectDirectory;

    private final DeviceId lost = new DeviceId("Mock", "Yanked Interface");
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
    void playIsRefusedWithAWarningThatNamesTheLostDevice() throws Exception {
        assertPlayIsRefused(() -> fixture.controller().start());
    }

    @Test
    void playWithPreRollIsRefusedWithAWarningThatNamesTheLostDevice() throws Exception {
        assertPlayIsRefused(() -> fixture.controller().playWithPreRoll());
    }

    private void assertPlayIsRefused(Runnable play) throws Exception {
        fixture.recordUntilABlockIsOnDisk();
        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();
        assertThat(fixture.engine().isStreamOpen()).as("premise: the loss closed the stream").isFalse();
        int before = fixture.shownCount();

        runOnFx(play);
        runOnFx(() -> { }); // whatever the refusal posted has run

        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).as("one WARNING, nothing else").hasSize(1);
        NotificationEntry refusal = entries.getFirst();
        assertThat(refusal.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(refusal.message())
                .isEqualTo(RecordCoordinator.unavailableWhileDeviceLostMessage("Playback", Optional.of(lost)))
                .contains(lost.name()).contains("disconnected").contains("reconnect").contains("Stop")
                .contains("Audio Settings");
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(refusal.message());
        assertThat(onFx(() -> fixture.project().getTransport().getState())).isEqualTo(TransportState.STOPPED);
        assertThat(fixture.engine().isStreamOpen()).as("nothing opened").isFalse();
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(onFx(() -> fixture.transportStatus().getText())).isEqualTo("Device lost");
    }
}
