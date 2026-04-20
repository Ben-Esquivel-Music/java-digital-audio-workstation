package com.benesquivelmusic.daw.sdk.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClickOutputTest {

    @Test
    void shouldConstructWithValidValues() {
        ClickOutput output = new ClickOutput(3, 0.75, true, true);

        assertThat(output.hardwareChannelIndex()).isEqualTo(3);
        assertThat(output.gain()).isEqualTo(0.75);
        assertThat(output.mainMixEnabled()).isTrue();
        assertThat(output.sideOutputEnabled()).isTrue();
    }

    @Test
    void shouldAllowZeroChannelAndZeroGain() {
        ClickOutput output = new ClickOutput(0, 0.0, false, false);

        assertThat(output.hardwareChannelIndex()).isZero();
        assertThat(output.gain()).isZero();
    }

    @Test
    void shouldAllowUnityGain() {
        ClickOutput output = new ClickOutput(0, 1.0, true, true);

        assertThat(output.gain()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectNegativeHardwareChannelIndex() {
        assertThatThrownBy(() -> new ClickOutput(-1, 0.5, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hardwareChannelIndex");
    }

    @Test
    void shouldRejectNegativeGain() {
        assertThatThrownBy(() -> new ClickOutput(0, -0.01, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gain");
    }

    @Test
    void shouldRejectGainAboveOne() {
        assertThatThrownBy(() -> new ClickOutput(0, 1.01, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gain");
    }

    @Test
    void mainMixOnlyConstantShouldMatchLegacyBehaviour() {
        assertThat(ClickOutput.MAIN_MIX_ONLY.hardwareChannelIndex()).isZero();
        assertThat(ClickOutput.MAIN_MIX_ONLY.gain()).isEqualTo(1.0);
        assertThat(ClickOutput.MAIN_MIX_ONLY.mainMixEnabled()).isTrue();
        assertThat(ClickOutput.MAIN_MIX_ONLY.sideOutputEnabled()).isFalse();
    }

    @Test
    void withHardwareChannelIndexShouldReturnCopy() {
        ClickOutput original = new ClickOutput(0, 0.5, true, true);

        ClickOutput updated = original.withHardwareChannelIndex(5);

        assertThat(updated.hardwareChannelIndex()).isEqualTo(5);
        assertThat(updated.gain()).isEqualTo(0.5);
        assertThat(updated.mainMixEnabled()).isTrue();
        assertThat(updated.sideOutputEnabled()).isTrue();
        assertThat(original.hardwareChannelIndex()).isZero();
    }

    @Test
    void withGainShouldReturnCopy() {
        ClickOutput original = new ClickOutput(2, 0.3, true, true);

        ClickOutput updated = original.withGain(0.9);

        assertThat(updated.gain()).isEqualTo(0.9);
        assertThat(updated.hardwareChannelIndex()).isEqualTo(2);
        assertThat(original.gain()).isEqualTo(0.3);
    }

    @Test
    void withMainMixEnabledShouldReturnCopy() {
        ClickOutput original = new ClickOutput(2, 0.3, true, true);

        ClickOutput updated = original.withMainMixEnabled(false);

        assertThat(updated.mainMixEnabled()).isFalse();
        assertThat(updated.sideOutputEnabled()).isTrue();
        assertThat(original.mainMixEnabled()).isTrue();
    }

    @Test
    void withSideOutputEnabledShouldReturnCopy() {
        ClickOutput original = new ClickOutput(2, 0.3, true, true);

        ClickOutput updated = original.withSideOutputEnabled(false);

        assertThat(updated.sideOutputEnabled()).isFalse();
        assertThat(updated.mainMixEnabled()).isTrue();
        assertThat(original.sideOutputEnabled()).isTrue();
    }

    @Test
    void withReturnedCopiesShouldRejectInvalidValues() {
        ClickOutput base = new ClickOutput(2, 0.3, true, true);

        assertThatThrownBy(() -> base.withHardwareChannelIndex(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withGain(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
