package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 327 fix round, book §5.2 "Device lost" — the third way out of
 * DEVICE_LOST, an Audio Settings apply: the configuration guard
 * ({@link RecordCoordinator#requestConfigurationChange}) finds nothing in
 * flight, leaves DEVICE_LOST through FINALIZING, settles to IDLE and grants
 * the permit; so the {@code STOPPED} the apply publishes afterwards meets
 * an IDLE coordinator and is not the lost device's return — even delivered
 * with the stream open, it shows no INFO and seeks nothing. Drives the
 * controller's engine-state seam directly, without the bridge.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ASettingsApplyLeavesDeviceLostThroughFinalizingTest {

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
    void theConfigurationPermitIsGrantedFromIdleAndTheAppliesStoppedIsNotTheReturn() throws Exception {
        fixture.recordUntilABlockIsOnDisk();
        fixture.closeTheStreamAsTheLossDoes();
        runOnFx(() -> fixture.controller().onEngineState(EngineState.DEVICE_LOST, Optional.of(lost)));
        fixture.awaitRescued();
        double parked = fixture.pipeline().getRecordingStartBeat() + 5.0;
        runOnFx(() -> fixture.project().getTransport().setPositionInBeats(parked));
        List<RecordState> states = new ArrayList<>();
        runOnFx(() -> fixture.coordinator().stateProperty().addListener((_, _, state) -> states.add(state)));
        assertThat(onFx(() -> fixture.coordinator().getState())).as("premise").isEqualTo(RecordState.DEVICE_LOST);

        CompletableFuture<Runnable> permit = fixture.coordinator().requestConfigurationChange().toCompletableFuture();
        Runnable release = permit.get(5, TimeUnit.SECONDS);

        assertThat(onFx(() -> fixture.coordinator().getState())).as("IDLE when the permit completes")
                .isEqualTo(RecordState.IDLE);
        assertThat(onFx(() -> List.copyOf(states))).as("left DEVICE_LOST through FINALIZING")
                .containsExactly(RecordState.FINALIZING, RecordState.IDLE);
        assertThat(onFx(() -> fixture.coordinator().recordAvailableProperty().get()))
                .as("Record is held back while the configuration is reserved").isFalse();

        release.run();
        runOnFx(() -> { }); // the release's FX turn has run
        assertThat(onFx(() -> fixture.coordinator().recordAvailableProperty().get())).isTrue();

        // The apply's own STOPPED: here delivered with the stream open, the
        // stronger case — a real apply after a loss restores nothing and
        // publishes it with the stream closed.
        fixture.engine().startAudioOutput();
        fixture.engine().pauseAudioOutput();
        assertThat(fixture.engine().isStreamOpen()).as("premise: the stream is open").isTrue();
        int before = fixture.shownCount();

        runOnFx(() -> fixture.controller().onEngineState(EngineState.STOPPED, Optional.of(lost)));
        runOnFx(() -> { }); // whatever that posted has run

        assertThat(onFx(() -> fixture.coordinator().getState())).isEqualTo(RecordState.IDLE);
        assertThat(fixture.entriesSince(before)).as("no reconnected INFO: the coordinator was not DEVICE_LOST")
                .isEmpty();
        assertThat(onFx(() -> fixture.project().getTransport().getPositionInBeats()))
                .as("no seek to the rescued take's anchor").isCloseTo(parked, within(1e-9));
        assertThat(onFx(() -> fixture.guitar().isArmed())).as("the armed set is kept").isTrue();
    }
}
