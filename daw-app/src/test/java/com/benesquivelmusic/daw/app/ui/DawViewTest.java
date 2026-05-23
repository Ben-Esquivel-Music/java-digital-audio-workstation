package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DawViewTest {

    @Test
    void shouldHaveSixViews() {
        // Story 280 added PERFORMANCE_STAGE alongside the four standard views;
        // story 281 added WORKSHOP.
        assertThat(DawView.values()).hasSize(6);
    }

    @Test
    void shouldContainArrangementMixerEditorMasteringPerformanceStageAndWorkshop() {
        assertThat(DawView.values())
                .containsExactly(DawView.ARRANGEMENT, DawView.MIXER, DawView.EDITOR,
                        DawView.MASTERING, DawView.PERFORMANCE_STAGE, DawView.WORKSHOP);
    }

    @ParameterizedTest
    @EnumSource(DawView.class)
    void valueOfShouldRoundTrip(DawView view) {
        assertThat(DawView.valueOf(view.name())).isEqualTo(view);
    }

    @Test
    void arrangementShouldBeFirstValue() {
        assertThat(DawView.values()[0]).isEqualTo(DawView.ARRANGEMENT);
    }
}
