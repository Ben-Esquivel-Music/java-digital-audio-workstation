package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StereoModeTest {

    @Test
    void shouldHaveTwoModes() {
        assertThat(StereoMode.values()).hasSize(2);
    }

    @Test
    void shouldReturnDisplayNames() {
        assertThat(StereoMode.LEFT_RIGHT_OVERLAY.displayName()).isEqualTo("L/R Overlay");
        assertThat(StereoMode.LEFT_RIGHT_SPLIT.displayName()).isEqualTo("L/R Split");
    }

    @Test
    void shouldResolveFromName() {
        assertThat(StereoMode.valueOf("LEFT_RIGHT_OVERLAY")).isEqualTo(StereoMode.LEFT_RIGHT_OVERLAY);
        assertThat(StereoMode.valueOf("LEFT_RIGHT_SPLIT")).isEqualTo(StereoMode.LEFT_RIGHT_SPLIT);
    }
}
