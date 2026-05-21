package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.motion.OsMotionHint;

import javafx.geometry.Orientation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The fluent {@link LevelMeter.Builder} and the no-arg constructor are two
 * equivalent, independently usable construction paths.
 *
 * <p>The default-{@code isAnimated()} assertion is the combined gate
 * ({@code localAnimated AND NOT global Reduce Motion}, story 279); a
 * deterministic {@link MotionManager} (Reduce Motion off, OS hint stubbed)
 * is installed for the class so it does not depend on the host's OS-level
 * animation setting.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterBuilderTest {

    /** An OS hint reporting "undetected" — isolates the test from the real OS. */
    private static final OsMotionHint NO_HINT = Optional::empty;

    private static Preferences motionNode;

    @BeforeAll
    static void installDeterministicMotionManager() {
        motionNode = Preferences.userRoot()
                .node("levelMeterBuilderTest_" + System.nanoTime());
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
    void builderConfiguresChannelsOrientationAndSizeClass() {
        LevelMeter m = runOnFxThread(() -> LevelMeter.create()
                .channels(4)
                .orientation(Orientation.HORIZONTAL)
                .size("master")
                .build());

        assertThat(m.getChannelCount()).isEqualTo(4);
        assertThat(m.getOrientation()).isEqualTo(Orientation.HORIZONTAL);
        assertThat(m.getStyleClass()).contains("level-meter", "size-master");
        assertThat(m.isAnimated()).isTrue();
    }

    @Test
    void builderAnimatedFlagIsHonoured() {
        LevelMeter m = runOnFxThread(() -> LevelMeter.create()
                .animated(false)
                .build());
        assertThat(m.isAnimated()).isFalse();
    }

    @Test
    void noArgConstructorPlusSettersProducesAnEquivalentIndependentInstance() {
        LevelMeter built = runOnFxThread(() -> LevelMeter.create()
                .channels(4)
                .orientation(Orientation.HORIZONTAL)
                .build());

        LevelMeter manual = runOnFxThread(() -> {
            LevelMeter lm = new LevelMeter();
            lm.setChannelCount(4);
            lm.setOrientation(Orientation.HORIZONTAL);
            return lm;
        });

        assertThat(manual.getChannelCount()).isEqualTo(built.getChannelCount());
        assertThat(manual.getOrientation()).isEqualTo(built.getOrientation());
        assertThat(manual.getStyleClass()).contains("level-meter");

        // Independence: mutating one must not affect the other.
        runOnFxThread(() -> {
            manual.setChannelCount(8);
            return null;
        });
        assertThat(built.getChannelCount()).isEqualTo(4);
    }

    @Test
    void builderRejectsNullOrientationAndSizeName() {
        assertThatNullPointerException()
                .isThrownBy(() -> LevelMeter.create().orientation(null));
        assertThatNullPointerException()
                .isThrownBy(() -> LevelMeter.create().size(null));
    }
}
