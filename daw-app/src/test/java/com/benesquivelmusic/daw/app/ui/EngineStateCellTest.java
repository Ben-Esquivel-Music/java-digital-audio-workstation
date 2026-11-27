package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session status strip's Engine cell over the production engine-state
 * wiring (story 338, the story's engine-state cell and watchdog tests,
 * rulings E4, E6, E26 and E27): the engine controller's states reach the
 * window's {@link EngineHealthStatus} through the bridge, after the
 * device-loss reactions, exactly as {@code MainController} wires it. The cell
 * tracks RUNNING → RECONFIGURING → RUNNING through a Settings apply — read
 * as the sequence the cell showed, not its last value — and shows the
 * warning treatment on DEVICE_LOST for a removal and for a stall, whose
 * notice offers Reconnect and whose cell reconnects.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class EngineStateCellTest {

    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(
            DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS);
    private static final AudioEngineController.Request MOCK_AT_256 = new AudioEngineController.Request(
            MockAudioBackend.NAME, "", "", SampleRate.HZ_44100, 256, 16);

    @TempDir
    Path projectDirectory;

    private DeviceLossRecordingFixture fixture;
    private AudioEngine engine;
    private DefaultAudioEngineController controller;
    private EngineStateBridge bridge;

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
        if (bridge != null) {
            bridge.cancel();
        }
        if (engine != null) {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void theCellTracksRunningReconfiguringRunningThroughASettingsApply() throws Exception {
        MockAudioBackend mock = new MockAudioBackend();
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        controller = new DefaultAudioEngineController(engine, null, new RecordingNotifications(),
                new AudioBackendSelector(Map.of(MockAudioBackend.NAME, () -> mock)));
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        bridge = new EngineStateBridge(new DeviceLossReactions(() -> null, controller::lostDriveEpoch,
                Optional::empty, null).andThen(model::onEngineStateChange), () -> null, new FxDispatcher());
        bridge.subscribeTo(controller);
        SessionStatusStrip strip = onFx(() -> realized(model));
        controller.applyConfiguration(MOCK_AT_256);
        engine.startAudioOutput();
        controller.applyConfiguration(MOCK_AT_256);
        awaitOnFx(() -> model.engineStateProperty().get().state() == EngineState.RUNNING, "premise: RUNNING");
        List<String> shown = Collections.synchronizedList(new ArrayList<>());
        runOnFx(() -> {
            StatusStripCell cell = strip.engineCell();
            shown.add(cell.isVisible() + ":" + cell.getText());
            cell.visibleProperty().addListener((_, _, visible) -> shown.add(visible + ":" + cell.getText()));
        });

        controller.applyConfiguration(MOCK_AT_256);
        await(() -> shown.size() >= 3, "the apply's two states reached the cell");
        runOnFx(() -> { });

        assertThat(shown).as("hidden while RUNNING, informational while RECONFIGURING, hidden again (ruling E26)")
                .containsExactly("false:", "true:Engine: reconfiguring…", "false:");
        awaitOnFx(() -> model.engineStateProperty().get().state() == EngineState.RUNNING,
                "the apply ended RUNNING in the model, not STOPPED (both hide the cell)");
        assertThat(controller.engineState()).isEqualTo(EngineState.RUNNING);
    }

    @Test
    void aRemovedDeviceShowsTheWarningTreatment() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
        SessionStatusStrip strip = onFx(fixture::mountTheStatusStrip);
        fixture.recordUntilABlockIsOnDisk();

        fixture.unplug();
        fixture.awaitRescued();
        StatusStripCell.Severity severity = onFx(() -> strip.engineCell().getSeverity());
        List<String> classes = onFx(() -> List.copyOf(strip.engineCell().getStyleClass()));
        String text = onFx(() -> strip.engineCell().getText());

        assertThat(severity).isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(classes).contains(StatusStripCell.SEVERITY_WARN_CLASS);
        assertThat(text).as("ruling E111").isEqualTo("Device lost: audio device");
        assertThat(onFx(() -> strip.engineCell().isVisible())).isTrue();
    }

    @Test
    void aStalledDeviceShowsTheWarningTreatmentItsNoticeOffersReconnectAndTheCellReconnects() throws Exception {
        fixture = DeviceLossRecordingFixture.withEngineBridge(projectDirectory);
        DefaultAudioEngineController engineController = fixture.engineController().orElseThrow();
        AtomicLong clock = new AtomicLong();
        engineController.setEngineHealthClock(clock::get);
        SessionStatusStrip strip = onFx(fixture::mountTheStatusStrip);
        fixture.recordUntilABlockIsOnDisk();
        DeviceId device = fixture.activeDevice();

        fixture.backend().setThreadCounters(devicePeriods(1));
        engineController.tickEngineHealthMonitor();
        fixture.backend().setThreadCounters(devicePeriods(2));
        for (int i = 0; i < 40 && engineController.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TICK_NANOS);
            engineController.tickEngineHealthMonitor();
        }
        fixture.awaitRescued();
        StatusStripCell.Severity severity = onFx(() -> strip.engineCell().getSeverity());
        String text = onFx(() -> strip.engineCell().getText());
        String tooltip = onFx(() -> strip.engineCell().getTooltip().getText());
        int before = fixture.shownCount();

        runOnFx(() -> strip.engineCell().fire());
        awaitOnFx(() -> fixture.coordinator().getState() == RecordState.IDLE, "the reopened device settled the take");
        awaitOnFx(() -> !strip.engineCell().isVisible(), "the cell has nothing to say once the device is back");

        assertThat(severity).as("the stall's warning treatment").isEqualTo(StatusStripCell.Severity.WARN);
        assertThat(text).as("ruling E111").isEqualTo("Device lost: audio device");
        assertThat(tooltip).startsWith("The audio device stopped responding or was disconnected.");
        assertThat(fixture.engineNotices()).first().isEqualTo("The audio device stopped responding — audio"
                + " stopped. Check that it is connected and powered, then choose Reconnect.");
        assertThat(fixture.engineNoticeActions()).first().as("the notice offers Reconnect (ruling E6)")
                .isEqualTo(Optional.of(DefaultAudioEngineController.RECONNECT_ACTION));
        assertThat(fixture.entriesSince(before)).extracting(NotificationEntry::message)
                .as("the cell's Reconnect read that the loss rescued the take")
                .contains("The audio device reconnected. Tracks are still armed; press Record to continue.");
        assertThat(fixture.engineNotices()).as("so the controller says nothing of its own on success")
                .noneMatch(notice -> notice.contains("is responding again"));
    }

    private static SessionStatusStrip realized(EngineHealthStatus model) {
        SessionStatusStrip strip = new SessionStatusStrip(new ProjectOperationProgress(new FxDispatcher()),
                MotionManager.getDefault(), model);
        VBox root = new VBox(strip);
        new Scene(root, 1400, 120);
        root.applyCss();
        root.layout();
        return strip;
    }

    private static BackendThreadCounters devicePeriods(long periods) {
        return new BackendThreadCounters(0, 0, 0, 0, OptionalLong.of(periods), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
