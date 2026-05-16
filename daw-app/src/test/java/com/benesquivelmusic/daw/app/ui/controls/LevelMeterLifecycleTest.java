package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;

import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic lifecycle: timer gating on scene attach/detach, the
 * not-animated path, and {@link LevelMeterSkin#dispose()} fully stopping
 * the timer and removing every listener. PASS/FAIL comes from explicit
 * flags — never from {@code System.gc()} + weak-ref finalisation.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterLifecycleTest {

    @Test
    void timerRunsWhenSceneAttachedAndStopsWhenDetached() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        StackPane root = runOnFxThread(() -> new StackPane(m));

        runOnFxThread(() -> {
            Scene scene = new Scene(root, 80, 240);
            root.applyCss();
            root.layout();
            return null;
        });
        LevelMeterSkin skin = runOnFxThread(() -> (LevelMeterSkin) m.getSkin());
        assertThat(skin.isTimerRunning()).isTrue();

        // Detach: move the control out of the scene graph.
        runOnFxThread(() -> {
            root.getChildren().remove(m);
            root.applyCss();
            root.layout();
            return null;
        });
        assertThat(skin.isTimerRunning()).isFalse();

        long before = skin.frameCount();
        // Two FX "pulses" worth of round-trips; a stopped timer must not
        // advance the frame counter.
        runOnFxThread(() -> null);
        runOnFxThread(() -> null);
        assertThat(skin.frameCount()).isEqualTo(before);
    }

    @Test
    void notAnimatedSkipsTheTimerButStillRelaysOnValueChange() {
        LevelMeter m = runOnFxThread(() -> {
            LevelMeter lm = new LevelMeter();
            lm.setAnimated(false);
            return lm;
        });
        LevelMeterSkin skin = LevelMeterTest.attach(m);
        assertThat(skin.isTimerRunning()).isFalse();

        // The value path still drives peak-hold + repaint without a timer.
        runOnFxThread(() -> {
            skin.setClock(() -> 7_000_000_000L);
            m.setPeakDb(-2.0);
            return null;
        });
        // peak-hold tracked via the relayAndRepaint listener path.
        assertThat(skin.currentPeakHoldDb(7_000_000_000L)).isEqualTo(-2.0);
    }

    @Test
    void disposeStopsTimerAndRemovesEveryListener() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        LevelMeterSkin skin = LevelMeterTest.attach(m);
        assertThat(skin.isTimerRunning()).isTrue();
        assertThat(skin.registeredListenerCount()).isGreaterThan(0);

        runOnFxThread(() -> {
            m.setSkin(null);
            return null;
        });

        assertThat(skin.isDisposed()).isTrue();
        assertThat(skin.isTimerRunning()).isFalse();
        assertThat(skin.registeredListenerCount()).isZero();

        // Mutating control properties after dispose must not throw and must
        // not resurrect the timer or advance the frame counter.
        long before = skin.frameCount();
        runOnFxThread(() -> {
            m.setPeakDb(-1.0);
            m.setChannelCount(6);
            m.setOrientation(Orientation.HORIZONTAL);
            return null;
        });
        assertThat(skin.frameCount()).isEqualTo(before);
        assertThat(skin.isTimerRunning()).isFalse();
    }
}
