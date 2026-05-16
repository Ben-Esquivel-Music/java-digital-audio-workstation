package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.geometry.Orientation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The fluent {@link LevelMeter.Builder} and the no-arg constructor are two
 * equivalent, independently usable construction paths.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterBuilderTest {

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
