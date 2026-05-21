package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.motion.OsMotionHint;

import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core behaviour of {@link LevelMeter}: defaults, property accessors, the
 * audio→FX relay, the CssMetaData contract, and the draw-model seams
 * exercised under a live {@link Scene}.
 *
 * <p>{@code defaultsAreSane} asserts the default {@code isAnimated()} — the
 * combined gate ({@code localAnimated AND NOT global Reduce Motion}, story
 * 279). A deterministic {@link MotionManager} (Reduce Motion off, OS hint
 * stubbed to "undetected") is installed for the class so the result does
 * not depend on the host's OS-level animation setting.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterTest {

    /** An OS hint reporting "undetected" — isolates the test from the real OS. */
    private static final OsMotionHint NO_HINT = Optional::empty;

    private static Preferences motionNode;

    @BeforeAll
    static void installDeterministicMotionManager() {
        motionNode = Preferences.userRoot()
                .node("levelMeterTest_" + System.nanoTime());
        MotionManager motion = new MotionManager(motionNode, NO_HINT);
        motion.setReduceMotion(false);
        MotionManager.setDefaultForTest(motion);
    }

    @AfterAll
    static void clearDeterministicMotionManager() throws BackingStoreException {
        MotionManager.setDefaultForTest(null);
        if (motionNode != null) {
            motionNode.removeNode();
        }
    }

    /** Meters created by helpers; disposed in {@link #cleanup()} to stop
     *  every attached {@link javafx.animation.AnimationTimer} so timers
     *  do not leak between tests in the shared JavaFX toolkit.
     *  Wrapped in {@code synchronizedList} as a belt-and-braces guard:
     *  the JavaFX toolkit serialises FX-thread work, but the helpers
     *  may be invoked from JUnit's own test-runner thread. */
    private static final List<LevelMeter> CREATED =
            java.util.Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        runOnFxThread(() -> {
            synchronized (CREATED) {
                for (LevelMeter m : CREATED) {
                    if (m.getSkin() != null) {
                        m.setSkin(null);
                    }
                    if (m.getParent() instanceof javafx.scene.Parent && m.getScene() != null) {
                        javafx.scene.Parent p = (javafx.scene.Parent) m.getParent();
                        if (p instanceof javafx.scene.layout.Pane pane) {
                            pane.getChildren().remove(m);
                        }
                    }
                }
                CREATED.clear();
            }
            return null;
        });
    }

    @Test
    void defaultsAreSane() {
        LevelMeter m = newMeter();
        assertThat(m.getOrientation()).isEqualTo(Orientation.VERTICAL);
        assertThat(m.getChannelCount()).isEqualTo(2);
        assertThat(m.isAnimated()).isTrue();
        assertThat(m.getStyleClass()).contains("level-meter");
        assertThat(m.getMeterLow()).isEqualTo(Color.web("#3FBF7F"));
        assertThat(m.getMeterClip()).isEqualTo(Color.web("#E5484D"));
        assertThat(m.getMeterBackground()).isEqualTo(Color.web("#1D1F26"));
        assertThat(m.getUserAgentStylesheet()).endsWith("level-meter.css");
    }

    @Test
    void channelCountIsClampedToOneThroughEight() {
        LevelMeter m = newMeter();
        m.setChannelCount(0);
        assertThat(m.getChannelCount()).isEqualTo(1);
        m.setChannelCount(99);
        assertThat(m.getChannelCount()).isEqualTo(8);
        m.setChannelCount(4);
        assertThat(m.getChannelCount()).isEqualTo(4);
    }

    @Test
    void channelCountClampIsEnforcedAtThePropertyLevel() {
        // Direct property set / bind paths must respect the same [1, 8]
        // range as setChannelCount, otherwise the skin can lay out more
        // columns than the per-channel atomics support.
        LevelMeter m = newMeter();
        m.channelCountProperty().set(0);
        assertThat(m.getChannelCount()).isEqualTo(1);
        m.channelCountProperty().set(99);
        assertThat(m.getChannelCount()).isEqualTo(8);
    }

    @Test
    void channelCountClampSurvivesBinding() {
        // Binding takes a different code path than .set(int): the bound
        // source's value is read on demand. The clamp must apply on the
        // read path too — the skin only ever calls getChannelCount(), so
        // out-of-range values from a binding source must never be visible.
        LevelMeter m = newMeter();
        javafx.beans.property.IntegerProperty src =
                new javafx.beans.property.SimpleIntegerProperty(99);
        m.channelCountProperty().bind(src);
        assertThat(m.getChannelCount()).isEqualTo(8);
        src.set(0);
        assertThat(m.getChannelCount()).isEqualTo(1);
        src.set(4);
        assertThat(m.getChannelCount()).isEqualTo(4);
        m.channelCountProperty().unbind();
    }

    @Test
    void cssMetaDataExposesAllEightStyleableProperties() {
        var names = LevelMeter.getClassCssMetaData().stream()
                .map(javafx.css.CssMetaData::getProperty)
                .toList();
        assertThat(names).contains(
                "-lm-low", "-lm-mid", "-lm-hi", "-lm-clip",
                "-lm-background", "-lm-segment-gap",
                "-lm-segment-height", "-lm-tick-marks");
    }

    @Test
    void submitLevelsIsRelayedToPropertiesByTheSkinTimer() {
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);

        m.submitLevels(-3.0, -9.0);
        // Pump a few timer pulses on the FX thread.
        runOnFxThread(() -> {
            for (int i = 0; i < 5; i++) {
                pulse(skin);
            }
            return null;
        });
        assertThat(m.getPeakDb()).isEqualTo(-3.0);
        assertThat(m.getRmsDb()).isEqualTo(-9.0);
    }

    @Test
    void drawModelLightsSegmentsAccordingToPeak() {
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);

        runOnFxThread(() -> {
            m.setPeakDb(-60.0);
            return null;
        });
        layout(m);
        int floorLit = runOnFxThread(() -> skin.topLitSegmentIndex(0, 4, 200));

        runOnFxThread(() -> {
            m.setPeakDb(0.0);
            return null;
        });
        layout(m);
        int fullLit = runOnFxThread(() -> skin.topLitSegmentIndex(0, 4, 200));

        assertThat(fullLit).isGreaterThan(floorLit);
    }

    @Test
    void colorAtUsesHiAtZeroDbfsAndLowColourNearFloor() {
        // Exactly 0 dBFS sits in the -meter-hi band per UI Design Book
        // §5.7 ("-6 to 0: -meter-hi"). The -meter-clip colour is reserved
        // for values STRICTLY above 0 — see colorAtUsesClipAboveZeroDbfs.
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);
        runOnFxThread(() -> {
            m.setPeakDb(0.0);
            return null;
        });
        layout(m);

        Color near0 = runOnFxThread(() -> {
            int n = skin.segmentCount(4, 200);
            return skin.colorAt(0, n - 1, 4, 200);
        });
        Color nearFloor = runOnFxThread(() -> skin.colorAt(0, 0, 4, 200));

        assertThat(near0).isEqualTo(m.getMeterHi());
        assertThat(nearFloor).isEqualTo(m.getMeterLow());
    }

    @Test
    void colorAtUsesClipAboveZeroDbfs() {
        // The clip colour is shown only for peaks STRICTLY above 0 dBFS
        // (UI Design Book §5.7 "Above 0: -meter-clip").
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);
        runOnFxThread(() -> {
            m.setPeakDb(3.0);
            return null;
        });
        layout(m);

        Color top = runOnFxThread(() -> {
            int n = skin.segmentCount(4, 200);
            return skin.colorAt(0, n - 1, 4, 200);
        });
        assertThat(top).isEqualTo(m.getMeterClip());
    }

    @Test
    void aggregateSubmitLevelsClearsStalePerChannelData() {
        // The skin prefers a non-NaN per-channel value over the aggregate.
        // If submitLevels(channel, ...) is called and then later the meter
        // switches to the aggregate-only API submitLevels(peak, rms), the
        // aggregate must be authoritative — otherwise the stale per-
        // channel reading would keep being displayed.
        LevelMeter m = newMeter();
        m.submitLevels(0, -3.0, -9.0);
        m.submitLevels(1, -6.0, -12.0);
        assertThat(m.consumeSubmittedPeakDb(0)).isEqualTo(-3.0);
        assertThat(m.consumeSubmittedPeakDb(1)).isEqualTo(-6.0);

        // Switch to aggregate-only feed: per-channel slots reset to NaN
        // so the skin falls back to the aggregate via the documented
        // "NaN means no per-channel feed" sentinel.
        m.submitLevels(-20.0, -30.0);
        assertThat(m.consumeSubmittedPeakDb()).isEqualTo(-20.0);
        assertThat(m.consumeSubmittedRmsDb()).isEqualTo(-30.0);
        for (int ch = 0; ch < LevelMeter.MAX_CHANNELS; ch++) {
            assertThat(m.consumeSubmittedPeakDb(ch))
                    .as("per-channel peak %d after aggregate submit", ch)
                    .isNaN();
            assertThat(m.consumeSubmittedRmsDb(ch))
                    .as("per-channel rms %d after aggregate submit", ch)
                    .isNaN();
        }
    }

    @Test
    void verticalOrientationPaintsLitColumnFromTheBottom() {
        // Pixel-level smoke test for the Canvas renderer: a vertical meter
        // at high level must paint a pixel near the bottom of the channel
        // column with one of the lit-segment colours. Catches a coordinate
        // / orientation / fillRect regression that the colorAt model seam
        // would not.
        LevelMeter m = newMeter();
        runOnFxThread(() -> {
            m.setOrientation(Orientation.VERTICAL);
            m.setChannelCount(1);
            m.setPrefSize(8, 200);
            m.setMinSize(8, 200);
            m.setMaxSize(8, 200);
            return null;
        });
        attach(m);
        runOnFxThread(() -> {
            m.setPeakDb(-3.0); // high-band signal: lit ~all the way up
            m.applyCss();
            m.layout();
            ((LevelMeterSkin) m.getSkin()).pumpOnce(System.nanoTime());
            return null;
        });

        javafx.scene.image.WritableImage snapshot = runOnFxThread(() ->
                m.snapshot(new javafx.scene.SnapshotParameters(), null));

        Color background = m.getMeterBackground();
        // Snapshot dimensions should reflect the forced 8x200 sizing.
        assertThat((int) snapshot.getHeight()).isGreaterThanOrEqualTo(100);
        // A pixel near the bottom of the column centre must be lit (not
        // background). For a 1-channel vertical meter the lit column
        // covers the full width minus the small column inset.
        int litX = (int) snapshot.getWidth() / 2;
        int litY = (int) snapshot.getHeight() - 2;
        Color litPixel = snapshot.getPixelReader().getColor(litX, litY);
        assertThat(litPixel)
                .as("vertical meter at -3 dBFS must light the bottom of the column "
                        + "(image %dx%d, sampling (%d,%d))",
                        (int) snapshot.getWidth(), (int) snapshot.getHeight(),
                        litX, litY)
                .isNotEqualTo(background);
    }

    @Test
    void horizontalOrientationPaintsLitRowFromTheLeft() {
        LevelMeter m = newMeter();
        runOnFxThread(() -> {
            m.setOrientation(Orientation.HORIZONTAL);
            m.setChannelCount(1);
            m.setPrefSize(200, 8);
            m.setMinSize(200, 8);
            m.setMaxSize(200, 8);
            return null;
        });
        attach(m);
        runOnFxThread(() -> {
            m.setPeakDb(-3.0);
            m.applyCss();
            m.layout();
            ((LevelMeterSkin) m.getSkin()).pumpOnce(System.nanoTime());
            return null;
        });

        javafx.scene.image.WritableImage snapshot = runOnFxThread(() ->
                m.snapshot(new javafx.scene.SnapshotParameters(), null));

        Color background = m.getMeterBackground();
        assertThat((int) snapshot.getWidth()).isGreaterThanOrEqualTo(100);
        // Sample at X=1 (within the first 2px-wide segment for the
        // default seg=2 / gap=1 layout) on the centre row.
        int litY = (int) snapshot.getHeight() / 2;
        Color litPixel = snapshot.getPixelReader().getColor(1, litY);
        assertThat(litPixel)
                .as("horizontal meter at -3 dBFS must light the left edge of the row "
                        + "(image %dx%d)", (int) snapshot.getWidth(), (int) snapshot.getHeight())
                .isNotEqualTo(background);
    }

    @Test
    void accessibleTextUpdatesWithLevelChanges() {
        // Screen readers should hear the current peak/RMS/clip state, not
        // just the static "Level meter" description.
        LevelMeter m = newMeter();
        LevelMeterSkin skin = attach(m);

        // Initial static text before any tick.
        assertThat(m.getAccessibleText()).contains("no signal");

        // A tick at the -120 dBFS sentinel should still say "no signal".
        runOnFxThread(() -> {
            skin.tick(System.nanoTime());
            return null;
        });
        assertThat(m.getAccessibleText()).contains("no signal");

        // After a tick with a level set, the accessible text should reflect
        // the current peak/RMS values.
        runOnFxThread(() -> {
            m.setPeakDb(-12.0);
            m.setRmsDb(-18.0);
            skin.tick(System.nanoTime());
            return null;
        });
        String text = m.getAccessibleText();
        assertThat(text).contains("-12.0");
        assertThat(text).contains("-18.0");
        assertThat(text).doesNotContain("CLIPPING");

        // When clipping (above 0 dBFS), the accessible text should indicate it.
        runOnFxThread(() -> {
            m.setPeakDb(2.0);
            skin.tick(System.nanoTime());
            return null;
        });
        assertThat(m.getAccessibleText()).contains("CLIPPING");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static LevelMeter newMeter() {
        LevelMeter m = runOnFxThread(LevelMeter::new);
        CREATED.add(m);
        return m;
    }

    static LevelMeterSkin attach(LevelMeter m) {
        return runOnFxThread(() -> {
            StackPane root = new StackPane(m);
            Scene scene = new Scene(root, 80, 240);
            root.applyCss();
            root.layout();
            return (LevelMeterSkin) m.getSkin();
        });
    }

    static void layout(LevelMeter m) {
        runOnFxThread(() -> {
            m.getScene().getRoot().applyCss();
            m.getScene().getRoot().layout();
            return null;
        });
    }

    static void pulse(LevelMeterSkin skin) {
        // Equivalent to one AnimationTimer.handle() without depending on
        // pulse scheduling: relay + tick + paint via the seam path.
        skin.pumpOnce(System.nanoTime());
    }
}
