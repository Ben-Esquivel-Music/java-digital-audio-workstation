package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisModeTest {

    @Test
    void shouldHaveTwoModes() {
        assertThat(AnalysisMode.values()).hasSize(2);
    }

    @Test
    void shouldReturnDisplayNames() {
        assertThat(AnalysisMode.REAL_TIME.displayName()).isEqualTo("Real-Time");
        assertThat(AnalysisMode.POST_PLAYBACK.displayName()).isEqualTo("Post-Playback");
    }

    @Test
    void shouldResolveFromName() {
        assertThat(AnalysisMode.valueOf("REAL_TIME")).isEqualTo(AnalysisMode.REAL_TIME);
        assertThat(AnalysisMode.valueOf("POST_PLAYBACK")).isEqualTo(AnalysisMode.POST_PLAYBACK);
    }
}
