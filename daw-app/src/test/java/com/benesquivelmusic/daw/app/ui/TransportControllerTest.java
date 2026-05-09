package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link TransportController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 *
 * <p>The {@code formatTime} tests have been moved to {@link AnimationControllerTest}
 * after the time-ticker logic was extracted into {@link AnimationController}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportControllerTest {

    @Test
    void shouldCreateTransportControllerClass() {
        // Verify the TransportController class is loadable and its Host interface is accessible
        Class<?> hostClass = TransportController.Host.class;
        assertThat(hostClass).isNotNull();
        assertThat(hostClass.isInterface()).isTrue();
    }

    // ── Pre-Roll / Post-Roll (Story 134) ─────────────────────────────────────

    private TransportController newController(DawProject project) throws Exception {
        AtomicReference<TransportController> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            AudioEngine engine = new AudioEngine(project.getFormat());
            UndoManager undo = new UndoManager();
            NotificationBar nb = new NotificationBar();
            Label statusLabel = new Label();
            Label timeDisplay = new Label();
            Label statusBarLabel = new Label();
            Label recIndicator = new Label();
            Button play = new Button(), pause = new Button(), stop = new Button(),
                    record = new Button(), loop = new Button();
            TransportController.Host host = new TransportController.Host() {
                @Override public boolean isSnapEnabled() { return false; }
                @Override public GridResolution gridResolution() { return GridResolution.QUARTER; }
                @Override public Metronome metronome() { return null; }
                @Override public CountInMode countInMode() { return CountInMode.OFF; }
                @Override public void startTimeTicker() { }
                @Override public void pauseTimeTicker() { }
                @Override public void stopTimeTicker() { }
                @Override public void flashMidiActivity(Track track) { }
            };
            ref.set(new TransportController(project, engine, undo, nb,
                    statusLabel, timeDisplay, statusBarLabel, recIndicator,
                    play, pause, stop, record, loop, host));
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    @Test
    void onTogglePreRollShouldEnableWithDefaultBars() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.onTogglePreRoll();
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.enabled()).isTrue();
        assertThat(prpr.preBars()).isEqualTo(TransportController.DEFAULT_BARS);
        assertThat(prpr.postBars()).isEqualTo(0);
    }

    @Test
    void onTogglePreRollTwiceShouldDisablePreservingBarCounts() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        project.getTransport().setPreRollPostRoll(PreRollPostRoll.enabled(3, 1));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.onTogglePreRoll(); // toggles enabled flag → false
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.enabled()).isFalse();
        assertThat(prpr.preBars()).isEqualTo(3);
        assertThat(prpr.postBars()).isEqualTo(1);
    }

    @Test
    void onPlayWithPreRollShouldSeekBackByConfiguredBars() throws Exception {
        // Issue test: enable pre-roll with preBars=2, set playhead at bar 25,
        // press Shift+Space, assert transport seeks to bar 23 and plays.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        // 4/4 time signature; bar 25 = beat 96 (zero-indexed: 24 bars × 4 beats).
        int beatsPerBar = transport.getTimeSignatureNumerator(); // 4
        transport.setPositionInBeats(24.0 * beatsPerBar);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));

        TransportController controller = newController(project);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                controller.onPlayWithPreRoll();
            } catch (RuntimeException e) {
                // Audio engine may fail to open in a headless environment;
                // that does not affect the transport position assertion.
            }
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Seeked back by 2 × 4 = 8 beats: bar 23 = beat 88.
        assertThat(transport.getPositionInBeats())
                .isEqualTo((24.0 - 2.0) * beatsPerBar);
        // The transport must report it is in pre-roll so the recording
        // pipeline suppresses input capture during bars 23–24.
        assertThat(transport.isInPreRoll()).isTrue();
        assertThat(transport.isInputCaptureGated()).isTrue();
    }

    @Test
    void createPreRollPostRollControlsShouldWireSpinnersToTransport() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.createPreRollPostRollControls();
            // Simulate the user typing "5" into the pre-roll spinner.
            controller.preRollSpinnerForTest().getValueFactory().setValue(5);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Spinner edits propagate to the transport configuration without
        // changing the (currently disabled) enabled flag.
        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.preBars()).isEqualTo(5);
        assertThat(prpr.enabled()).isFalse();
    }
}
