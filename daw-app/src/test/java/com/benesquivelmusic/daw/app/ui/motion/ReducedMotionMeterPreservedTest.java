package com.benesquivelmusic.daw.app.ui.motion;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 279 — the real-time exemption: with global Reduce Motion enabled,
 * a {@link LevelMeter}'s continuous signal-level rendering is <strong>not
 * gated</strong>. The meter is <em>information</em>, not animation; its
 * per-frame relay must keep advancing frame-by-frame regardless of Reduce
 * Motion (UI Design Book §3.5, MotionManager class Javadoc cut/keep rule).
 *
 * <p>This is the counterpart to {@code ReduceMotionFlagAppliedTest} (which
 * proves a <em>transition</em> is cut): here we prove a <em>real-time</em>
 * surface is preserved. The meter's combined {@code isAnimated()} reads
 * {@code false} under Reduce Motion — but {@code LevelMeterSkin}'s
 * continuous pump runs whenever the control is attached, independent of
 * that flag, so peak values still track the audio feed.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ReducedMotionMeterPreservedTest {

    /** An OS hint reporting "undetected" — isolates the test from the real OS. */
    private static final OsMotionHint NO_HINT = Optional::empty;

    @Test
    void meterStillAdvancesFrameByFrameUnderReduceMotion()
            throws BackingStoreException {
        Preferences node = Preferences.userRoot()
                .node("reduceMotionMeterTest_" + System.nanoTime());
        try {
            MotionManager motion = new MotionManager(node, NO_HINT);
            motion.setReduceMotion(true);
            MotionManager.setDefaultForTest(motion);

            LevelMeter meter = runOnFxThread(LevelMeter::new);
            try {
                // Attach the meter to a Scene so the skin's continuous
                // per-frame pump is live (mirrors LevelMeterTest.attach).
                LevelMeterSkin skin = runOnFxThread(() -> {
                    StackPane root = new StackPane(meter);
                    new Scene(root, 80, 240);
                    root.applyCss();
                    root.layout();
                    return (LevelMeterSkin) meter.getSkin();
                });

                // Sanity: global Reduce Motion gates the combined flag.
                runOnFxThread(() -> {
                    assertThat(meter.isAnimated())
                            .as("global Reduce Motion gates the meter's combined "
                                    + "animated flag to false")
                            .isFalse();
                    return null;
                });

                // Drive peakDb from -infinity up to -3 dBFS over a 200 ms
                // ramp of synthetic frames. After each frame the relayed
                // peak property must have advanced — proving the real-time
                // pump is NOT gated by Reduce Motion.
                int frames = 20;
                long startNanos = 1_000_000_000L;
                long frameStepNanos = 10_000_000L; // 10 ms → 200 ms total
                double targetDb = -3.0;
                double[] observed = new double[frames];

                for (int i = 0; i < frames; i++) {
                    final int frame = i;
                    runOnFxThread(() -> {
                        // Ramp: frame 0 starts near the noise floor and
                        // climbs linearly to the target.
                        double db = -120.0
                                + (targetDb + 120.0) * (frame + 1) / frames;
                        meter.submitLevels(db, db);
                        skin.pumpOnce(startNanos + frame * frameStepNanos);
                        observed[frame] = meter.getPeakDb();
                        return null;
                    });
                }

                // Every frame must show a strictly higher peak than the
                // previous one — the meter advanced continuously.
                for (int i = 1; i < frames; i++) {
                    assertThat(observed[i])
                            .as("meter peak must advance at frame %d "
                                    + "(real-time rendering is exempt from "
                                    + "Reduce Motion)", i)
                            .isGreaterThan(observed[i - 1]);
                }
                // And the final frame reached the target.
                assertThat(observed[frames - 1])
                        .as("meter reaches the ramp target under Reduce Motion")
                        .isEqualTo(targetDb);
            } finally {
                runOnFxThread(() -> {
                    if (meter.getSkin() != null) {
                        meter.setSkin(null);
                    }
                    if (meter.getParent()
                            instanceof javafx.scene.layout.Pane pane) {
                        pane.getChildren().remove(meter);
                    }
                    return null;
                });
            }
        } finally {
            MotionManager.setDefaultForTest(null);
            node.removeNode();
        }
    }
}
