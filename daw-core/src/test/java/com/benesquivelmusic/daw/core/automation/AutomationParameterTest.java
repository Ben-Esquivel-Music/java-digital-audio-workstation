package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationParameterTest {

    @Test
    void volumeShouldHaveCorrectRange() {
        assertThat(AutomationParameter.VOLUME.getMinValue()).isEqualTo(0.0);
        assertThat(AutomationParameter.VOLUME.getMaxValue()).isEqualTo(1.0);
        assertThat(AutomationParameter.VOLUME.getDefaultValue()).isEqualTo(1.0);
    }

    @Test
    void panShouldHaveCorrectRange() {
        assertThat(AutomationParameter.PAN.getMinValue()).isEqualTo(-1.0);
        assertThat(AutomationParameter.PAN.getMaxValue()).isEqualTo(1.0);
        assertThat(AutomationParameter.PAN.getDefaultValue()).isEqualTo(0.0);
    }

    @Test
    void muteShouldHaveCorrectRange() {
        assertThat(AutomationParameter.MUTE.getMinValue()).isEqualTo(0.0);
        assertThat(AutomationParameter.MUTE.getMaxValue()).isEqualTo(1.0);
        assertThat(AutomationParameter.MUTE.getDefaultValue()).isEqualTo(0.0);
    }

    @Test
    void sendLevelShouldHaveCorrectRange() {
        assertThat(AutomationParameter.SEND_LEVEL.getMinValue()).isEqualTo(0.0);
        assertThat(AutomationParameter.SEND_LEVEL.getMaxValue()).isEqualTo(1.0);
        assertThat(AutomationParameter.SEND_LEVEL.getDefaultValue()).isEqualTo(0.0);
    }

    @Test
    void isValidValueShouldAcceptValuesInRange() {
        assertThat(AutomationParameter.VOLUME.isValidValue(0.0)).isTrue();
        assertThat(AutomationParameter.VOLUME.isValidValue(0.5)).isTrue();
        assertThat(AutomationParameter.VOLUME.isValidValue(1.0)).isTrue();
    }

    @Test
    void isValidValueShouldRejectValuesOutOfRange() {
        assertThat(AutomationParameter.VOLUME.isValidValue(-0.1)).isFalse();
        assertThat(AutomationParameter.VOLUME.isValidValue(1.1)).isFalse();
    }

    @Test
    void panShouldAcceptNegativeValues() {
        assertThat(AutomationParameter.PAN.isValidValue(-1.0)).isTrue();
        assertThat(AutomationParameter.PAN.isValidValue(-0.5)).isTrue();
    }

    @Test
    void panShouldRejectValuesOutOfRange() {
        assertThat(AutomationParameter.PAN.isValidValue(-1.1)).isFalse();
        assertThat(AutomationParameter.PAN.isValidValue(1.1)).isFalse();
    }
}
