package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DawViewTest {

    @Test
    void shouldHaveFiveViews() {
        assertThat(DawView.values()).hasSize(5);
    }

    @Test
    void shouldContainArrangementMixerEditorTelemetryAndMastering() {
        assertThat(DawView.values())
                .containsExactly(DawView.ARRANGEMENT, DawView.MIXER, DawView.EDITOR, DawView.TELEMETRY, DawView.MASTERING);
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
