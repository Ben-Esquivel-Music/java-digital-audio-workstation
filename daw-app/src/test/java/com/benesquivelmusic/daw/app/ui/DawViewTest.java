package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DawViewTest {

    @Test
    void shouldHaveThreeViews() {
        assertThat(DawView.values()).hasSize(3);
    }

    @Test
    void shouldContainArrangementMixerAndEditor() {
        assertThat(DawView.values())
                .containsExactly(DawView.ARRANGEMENT, DawView.MIXER, DawView.EDITOR);
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
