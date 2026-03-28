package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StemNamingConventionTest {

    @Test
    void shouldHaveThreeValues() {
        assertThat(StemNamingConvention.values()).hasSize(3);
    }

    @Test
    void shouldContainTrackName() {
        assertThat(StemNamingConvention.valueOf("TRACK_NAME"))
                .isEqualTo(StemNamingConvention.TRACK_NAME);
    }

    @Test
    void shouldContainProjectPrefix() {
        assertThat(StemNamingConvention.valueOf("PROJECT_PREFIX"))
                .isEqualTo(StemNamingConvention.PROJECT_PREFIX);
    }

    @Test
    void shouldContainNumbered() {
        assertThat(StemNamingConvention.valueOf("NUMBERED"))
                .isEqualTo(StemNamingConvention.NUMBERED);
    }
}
