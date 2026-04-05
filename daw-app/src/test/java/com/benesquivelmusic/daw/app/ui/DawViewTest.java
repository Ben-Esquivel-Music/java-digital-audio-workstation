package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DawViewTest {

    @Test
    void shouldHaveFourViews() {
        assertThat(DawView.values()).hasSize(4);
    }

    @Test
    void shouldContainArrangementMixerEditorAndMastering() {
        assertThat(DawView.values())
                .containsExactly(DawView.ARRANGEMENT, DawView.MIXER, DawView.EDITOR, DawView.MASTERING);
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
