package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoSaveConfigTest {

    @Test
    void shouldCreateDefaultConfig() {
        AutoSaveConfig config = AutoSaveConfig.DEFAULT;

        assertThat(config.autoSaveInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.maxCheckpoints()).isEqualTo(50);
        assertThat(config.enabled()).isTrue();
    }

    @Test
    void shouldCreateLongSessionConfig() {
        AutoSaveConfig config = AutoSaveConfig.LONG_SESSION;

        assertThat(config.autoSaveInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxCheckpoints()).isEqualTo(200);
        assertThat(config.enabled()).isTrue();
    }

    @Test
    void shouldToggleEnabled() {
        AutoSaveConfig config = AutoSaveConfig.DEFAULT.withEnabled(false);

        assertThat(config.enabled()).isFalse();
        assertThat(config.autoSaveInterval()).isEqualTo(AutoSaveConfig.DEFAULT.autoSaveInterval());
    }

    @Test
    void shouldRejectNullInterval() {
        assertThatThrownBy(() -> new AutoSaveConfig(null, 10, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectZeroInterval() {
        assertThatThrownBy(() -> new AutoSaveConfig(Duration.ZERO, 10, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeInterval() {
        assertThatThrownBy(() -> new AutoSaveConfig(Duration.ofSeconds(-1), 10, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroMaxCheckpoints() {
        assertThatThrownBy(() -> new AutoSaveConfig(Duration.ofMinutes(1), 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
