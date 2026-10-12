package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 327, book §5.2 "Record while lost": while the coordinator is
 * DEVICE_LOST, Record is refused before anything is touched, and the
 * refusal names the lost device and the three ways out — reconnecting it,
 * Stop, or another device in Audio Settings. It is not the generic "last
 * take not finished" refusal: the user
 * must learn which device is missing. No take is prepared, the rescued
 * take's directory stays the only one, and its manifest is untouched.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecordWhileTheDeviceIsLostIsRefusedNamingTheDeviceTest {

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
    void recordIsRefusedWithAWarningThatNamesTheLostDevice() throws Exception {
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.coordinator().onDeviceLost(lost));
        fixture.awaitPublished();
        int before = fixture.shownCount();

        runOnFx(fixture.controller()::toggleRecord);
        runOnFx(() -> { }); // whatever the refusal posted has run

        List<NotificationEntry> entries = fixture.entriesSince(before);
        assertThat(entries).as("one WARNING, nothing else").hasSize(1);
        NotificationEntry refusal = entries.getFirst();
        assertThat(refusal.level()).isEqualTo(NotificationLevel.WARNING);
        assertThat(refusal.message()).as("names the device and the ways out")
                .contains(lost.name()).contains("disconnected").contains("reconnect").contains("Stop");
        assertThat(onFx(() -> fixture.statusBar().getText())).isEqualTo(refusal.message());
        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.DEVICE_LOST);
        assertThat(onFx(() -> fixture.controller().isPreparingTake())).isFalse();
        assertThat(onFx(() -> fixture.controller().isRecordingInFlight())).isFalse();
        Path takesDirectory = TakeDirectories.takesDirectory(ProjectManager.audioDirectory(projectDirectory));
        try (var takes = Files.list(takesDirectory)) {
            assertThat(takes.toList()).as("no new take directory").containsExactly(takeDirectory);
        }
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).sealedBy())
                .as("the rescued take is untouched").contains(TakeManifest.SealedBy.DEVICE_LOSS);
    }
}
