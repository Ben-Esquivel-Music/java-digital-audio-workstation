package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputMonitoringModeTest {

    @Test
    void shouldHaveCorrectEnumValues() {
        assertThat(InputMonitoringMode.values()).containsExactly(
                InputMonitoringMode.OFF,
                InputMonitoringMode.AUTO,
                InputMonitoringMode.ALWAYS,
                InputMonitoringMode.TAPE);
    }

    @Test
    void shouldResolveFromName() {
        assertThat(InputMonitoringMode.valueOf("OFF")).isEqualTo(InputMonitoringMode.OFF);
        assertThat(InputMonitoringMode.valueOf("AUTO")).isEqualTo(InputMonitoringMode.AUTO);
        assertThat(InputMonitoringMode.valueOf("ALWAYS")).isEqualTo(InputMonitoringMode.ALWAYS);
        assertThat(InputMonitoringMode.valueOf("TAPE")).isEqualTo(InputMonitoringMode.TAPE);
    }
}
