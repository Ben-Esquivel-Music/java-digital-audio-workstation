package com.benesquivelmusic.daw.sdk.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreRollPostRollTest {

    @Test
    void shouldConstructWithValidValues() {
        PreRollPostRoll config = new PreRollPostRoll(2, 1, true);

        assertThat(config.preBars()).isEqualTo(2);
        assertThat(config.postBars()).isEqualTo(1);
        assertThat(config.enabled()).isTrue();
    }

    @Test
    void shouldAllowZeroBars() {
        PreRollPostRoll config = new PreRollPostRoll(0, 0, true);

        assertThat(config.preBars()).isZero();
        assertThat(config.postBars()).isZero();
    }

    @Test
    void shouldRejectNegativePreBars() {
        assertThatThrownBy(() -> new PreRollPostRoll(-1, 0, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preBars");
    }

    @Test
    void shouldRejectNegativePostBars() {
        assertThatThrownBy(() -> new PreRollPostRoll(0, -1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postBars");
    }

    @Test
    void disabledConstantShouldBeZeroAndDisabled() {
        assertThat(PreRollPostRoll.DISABLED.preBars()).isZero();
        assertThat(PreRollPostRoll.DISABLED.postBars()).isZero();
        assertThat(PreRollPostRoll.DISABLED.enabled()).isFalse();
    }

    @Test
    void enabledFactoryShouldSetEnabledTrue() {
        PreRollPostRoll config = PreRollPostRoll.enabled(2, 1);

        assertThat(config.enabled()).isTrue();
        assertThat(config.preBars()).isEqualTo(2);
        assertThat(config.postBars()).isEqualTo(1);
    }

    @Test
    void withEnabledShouldReturnCopyWithFlagChanged() {
        PreRollPostRoll original = new PreRollPostRoll(2, 1, true);

        PreRollPostRoll disabled = original.withEnabled(false);

        assertThat(disabled.preBars()).isEqualTo(2);
        assertThat(disabled.postBars()).isEqualTo(1);
        assertThat(disabled.enabled()).isFalse();
        assertThat(original.enabled()).isTrue();
    }

    @Test
    void preRollBeatsShouldScaleWithBeatsPerBar() {
        PreRollPostRoll config = PreRollPostRoll.enabled(2, 1);

        assertThat(config.preRollBeats(4)).isEqualTo(8.0);
        assertThat(config.preRollBeats(3)).isEqualTo(6.0);
    }

    @Test
    void postRollBeatsShouldScaleWithBeatsPerBar() {
        PreRollPostRoll config = PreRollPostRoll.enabled(2, 3);

        assertThat(config.postRollBeats(4)).isEqualTo(12.0);
        assertThat(config.postRollBeats(3)).isEqualTo(9.0);
    }

    @Test
    void preRollBeatsShouldRejectNonPositiveBeatsPerBar() {
        PreRollPostRoll config = PreRollPostRoll.enabled(2, 1);

        assertThatThrownBy(() -> config.preRollBeats(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.postRollBeats(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
