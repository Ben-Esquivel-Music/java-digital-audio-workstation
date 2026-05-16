package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.benesquivelmusic.daw.app.ui.controls.LevelMeterTest.attach;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic peak-hold decay using the synthetic-timestamp seam and a
 * synthetic clock — NO real {@code Thread.sleep} (forbidden by project
 * test policy: flaky in Surefire). The synthetic clock makes the
 * value-change relay path (which also advances peak-hold) use the same
 * timeline as the explicit {@link LevelMeterSkin#tick(long)} calls.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterPeakHoldTest {

    /** Meters created via {@code attach()}; disposed in {@link #cleanup()}
     *  so the skin's {@code AnimationTimer} cannot leak between tests in
     *  the shared JavaFX toolkit (and across other JavaFX test classes). */
    private final List<LevelMeter> created = new ArrayList<>();

    private LevelMeter newMeter() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        created.add(m);
        return m;
    }

    @AfterEach
    void cleanup() {
        runOnFxThread(() -> {
            for (LevelMeter m : created) {
                if (m.getSkin() != null) {
                    m.setSkin(null);
                }
                if (m.getParent() instanceof javafx.scene.layout.Pane pane) {
                    pane.getChildren().remove(m);
                }
            }
            created.clear();
            return null;
        });
    }

    @Test
    void heldPeakSticksForTwoSecondsThenFallsOff() {
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        runOnFxThread(() -> {
            skin.setClock(now::get);
            return null;
        });

        long t = 1_000_000_000L;

        runOnFxThread(() -> {
            now.set(t);
            m.setPeakDb(2.0); // +2 dBFS at t (relay listener ticks at `now`)
            skin.tick(t);
            return null;
        });
        assertThat(skin.currentPeakHoldDb(t)).isEqualTo(2.0);

        // Peak drops back, but the hold must stay at +2 within the window.
        runOnFxThread(() -> {
            now.set(t + 1_500_000_000L);
            m.setPeakDb(-30.0);
            skin.tick(t + 1_500_000_000L); // +1.5 s
            return null;
        });
        assertThat(skin.currentPeakHoldDb(t + 1_500_000_000L)).isEqualTo(2.0);

        // After 2 s the hold expires and tracks the live peak again.
        runOnFxThread(() -> {
            now.set(t + 2_500_000_000L);
            skin.tick(t + 2_500_000_000L); // +2.5 s
            return null;
        });
        assertThat(skin.currentPeakHoldDb(t + 2_500_000_000L)).isEqualTo(-30.0);
    }

    @Test
    void aHigherPeakReArmsTheHoldAndResetsTheTimer() {
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);
        AtomicLong now = new AtomicLong(5_000_000_000L);
        runOnFxThread(() -> {
            skin.setClock(now::get);
            return null;
        });

        long t = 5_000_000_000L;
        runOnFxThread(() -> {
            now.set(t);
            m.setPeakDb(-12.0);
            skin.tick(t);
            return null;
        });
        assertThat(skin.currentPeakHoldDb(t)).isEqualTo(-12.0);

        // A louder transient re-arms the hold at the new (higher) value.
        runOnFxThread(() -> {
            now.set(t + 1_000_000_000L);
            m.setPeakDb(-3.0);
            skin.tick(t + 1_000_000_000L);
            return null;
        });
        assertThat(skin.currentPeakHoldDb(t + 1_000_000_000L)).isEqualTo(-3.0);
        // Window restarts from the re-arm: still held 1.9 s later.
        assertThat(skin.currentPeakHoldDb(t + 1_000_000_000L + 1_900_000_000L))
                .isEqualTo(-3.0);
    }

    @Test
    void holdExpiresExactlyAtTheTwoSecondBoundary() {
        // 2_000_000_000L == LevelMeterSkin.PEAK_HOLD_NANOS (package-private
        // across the controls/skin boundary, so spelled out literally).
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);
        long t = 9_000_000_000L;
        long hold = 2_000_000_000L;

        runOnFxThread(() -> {
            skin.setClock(() -> t);
            m.setPeakDb(-4.0);
            skin.tick(t);          // captures the hold at t
            m.setPeakDb(-40.0);    // live signal drops; the hold must stand
            return null;
        });

        // One nanosecond before the boundary the hold still stands.
        assertThat(skin.currentPeakHoldDb(t + hold - 1)).isEqualTo(-4.0);
        // At exactly PEAK_HOLD_NANOS the hold expires (>= boundary) and the
        // displayed value tracks the live peak again.
        assertThat(skin.currentPeakHoldDb(t + hold)).isEqualTo(-40.0);
    }

    @Test
    void perChannelPeakHoldIsIndependent() {
        // A stereo/surround meter fed only through submitLevels(channel, ...)
        // must hold each channel's peaks independently. The aggregate
        // peakHoldDb is irrelevant when a per-channel feed is active.
        LevelMeter m = newMeter();
        runOnFxThread(() -> {
            m.setChannelCount(2);
            return null;
        });
        LevelMeterSkin skin = attach(m);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        runOnFxThread(() -> {
            skin.setClock(now::get);
            return null;
        });

        long t = 1_000_000_000L;

        // Submit per-channel peaks: ch0 at -3, ch1 at -12.
        runOnFxThread(() -> {
            now.set(t);
            m.submitLevels(0, -3.0, -9.0);
            m.submitLevels(1, -12.0, -18.0);
            skin.pumpOnce(t);
            return null;
        });
        assertThat(skin.currentChannelPeakHoldDb(0, t)).isEqualTo(-3.0);
        assertThat(skin.currentChannelPeakHoldDb(1, t)).isEqualTo(-12.0);

        // Drop both signals; holds must stay within the 2 s window.
        runOnFxThread(() -> {
            now.set(t + 1_500_000_000L);
            m.submitLevels(0, -60.0, -60.0);
            m.submitLevels(1, -60.0, -60.0);
            skin.pumpOnce(t + 1_500_000_000L);
            return null;
        });
        assertThat(skin.currentChannelPeakHoldDb(0, t + 1_500_000_000L))
                .isEqualTo(-3.0);
        assertThat(skin.currentChannelPeakHoldDb(1, t + 1_500_000_000L))
                .isEqualTo(-12.0);

        // After 2 s the hold expires and tracks the live channel peak.
        runOnFxThread(() -> {
            now.set(t + 2_500_000_000L);
            skin.pumpOnce(t + 2_500_000_000L);
            return null;
        });
        assertThat(skin.currentChannelPeakHoldDb(0, t + 2_500_000_000L))
                .isEqualTo(-60.0);
        assertThat(skin.currentChannelPeakHoldDb(1, t + 2_500_000_000L))
                .isEqualTo(-60.0);
    }
}
