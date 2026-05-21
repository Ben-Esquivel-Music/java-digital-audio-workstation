package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.motion.OsMotionHint;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reduce Motion (story 279): with {@link Knob#isAnimated()} {@code false},
 * the {@code 0} / Ctrl+click reset snaps without instantiating a
 * transition timeline.
 *
 * <p>The {@code animated(true)} case asserts the detent timeline <em>does</em>
 * run — which depends on the combined gate ({@code localAnimated AND NOT
 * global Reduce Motion}). A deterministic {@link MotionManager} (Reduce
 * Motion off, OS hint stubbed to "undetected") is installed for the class
 * so the result does not depend on the host's OS-level animation setting
 * (e.g. Windows "Show animations").</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobReducedMotionTest {

    /** An OS hint reporting "undetected" — isolates the test from the real OS. */
    private static final OsMotionHint NO_HINT = Optional::empty;

    private static Preferences motionNode;

    @BeforeAll
    static void installDeterministicMotionManager() {
        motionNode = Preferences.userRoot()
                .node("knobReducedMotionTest_" + System.nanoTime());
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

    @Test
    void resetWithAnimatedFalseSnapsAndCreatesNoTimeline() {
        boolean[] running = new boolean[1];
        double[] finalValue = new double[1];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .animated(false).build();
            k.setValue(0.75);
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            skin.resetToDefault();
            running[0] = skin.isDetentAnimationRunning();
            finalValue[0] = k.getValue();
            return null;
        });
        assertThat(running[0])
                .as("no detent animation should be running with animated=false")
                .isFalse();
        assertThat(finalValue[0])
                .as("value should snap to default immediately")
                .isEqualTo(0.0);
    }

    @Test
    void resetWithAnimatedTrueStartsAnimationAndStillSnapsModelImmediately() {
        boolean[] running = new boolean[1];
        double[] finalValue = new double[1];
        runOnFxThread(() -> {
            Knob k = Knob.create()
                    .min(-1.0).max(1.0).defaultValue(0.0)
                    .animated(true).build();
            k.setValue(0.75);
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            skin.resetToDefault();
            running[0] = skin.isDetentAnimationRunning();
            finalValue[0] = k.getValue();
            return null;
        });
        assertThat(running[0])
                .as("detent timeline should be running with animated=true")
                .isTrue();
        assertThat(finalValue[0])
                .as("model value still snaps immediately; only the visual animates")
                .isEqualTo(0.0);
    }
}
