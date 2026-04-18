package com.benesquivelmusic.daw.core.track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationModeTest {

    @Test
    void shouldExposeReadModesForPlayback() {
        assertThat(AutomationMode.READ.readsAutomation()).isTrue();
        assertThat(AutomationMode.WRITE.readsAutomation()).isTrue();
        assertThat(AutomationMode.LATCH.readsAutomation()).isTrue();
        assertThat(AutomationMode.TOUCH.readsAutomation()).isTrue();
        assertThat(AutomationMode.OFF.readsAutomation()).isFalse();
    }

    @Test
    void shouldExposeWriteModesForRecording() {
        assertThat(AutomationMode.WRITE.writesAutomation()).isTrue();
        assertThat(AutomationMode.LATCH.writesAutomation()).isTrue();
        assertThat(AutomationMode.TOUCH.writesAutomation()).isTrue();
        assertThat(AutomationMode.READ.writesAutomation()).isFalse();
        assertThat(AutomationMode.OFF.writesAutomation()).isFalse();
    }

    @Test
    void shouldEnumerateAllFiveModes() {
        assertThat(AutomationMode.values())
                .containsExactly(
                        AutomationMode.READ,
                        AutomationMode.WRITE,
                        AutomationMode.LATCH,
                        AutomationMode.TOUCH,
                        AutomationMode.OFF);
    }
}
