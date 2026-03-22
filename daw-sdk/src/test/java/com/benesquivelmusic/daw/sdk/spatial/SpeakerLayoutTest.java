package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerLayoutTest {

    @Test
    void shouldCreate714Layout() {
        var layout = SpeakerLayout.LAYOUT_7_1_4;
        assertThat(layout.name()).isEqualTo("7.1.4");
        assertThat(layout.channelCount()).isEqualTo(12);
        assertThat(layout.speakers()).containsExactly(
                SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C, SpeakerLabel.LFE,
                SpeakerLabel.LS, SpeakerLabel.RS, SpeakerLabel.LRS, SpeakerLabel.RRS,
                SpeakerLabel.LTF, SpeakerLabel.RTF, SpeakerLabel.LTR, SpeakerLabel.RTR);
    }

    @Test
    void shouldCreate51Layout() {
        var layout = SpeakerLayout.LAYOUT_5_1;
        assertThat(layout.channelCount()).isEqualTo(6);
        assertThat(layout.speakers()).containsExactly(
                SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C, SpeakerLabel.LFE,
                SpeakerLabel.LS, SpeakerLabel.RS);
    }

    @Test
    void shouldCreateStereoLayout() {
        var layout = SpeakerLayout.LAYOUT_STEREO;
        assertThat(layout.channelCount()).isEqualTo(2);
        assertThat(layout.speakers()).containsExactly(SpeakerLabel.L, SpeakerLabel.R);
    }

    @Test
    void shouldFindSpeakerIndex() {
        var layout = SpeakerLayout.LAYOUT_7_1_4;
        assertThat(layout.indexOf(SpeakerLabel.L)).isEqualTo(0);
        assertThat(layout.indexOf(SpeakerLabel.C)).isEqualTo(2);
        assertThat(layout.indexOf(SpeakerLabel.LTF)).isEqualTo(8);
        assertThat(layout.indexOf(SpeakerLabel.RTR)).isEqualTo(11);
    }

    @Test
    void shouldReturnMinusOneForMissingSpeaker() {
        var layout = SpeakerLayout.LAYOUT_STEREO;
        assertThat(layout.indexOf(SpeakerLabel.C)).isEqualTo(-1);
        assertThat(layout.indexOf(SpeakerLabel.LFE)).isEqualTo(-1);
    }

    @Test
    void shouldCheckContains() {
        var layout = SpeakerLayout.LAYOUT_5_1;
        assertThat(layout.contains(SpeakerLabel.L)).isTrue();
        assertThat(layout.contains(SpeakerLabel.LFE)).isTrue();
        assertThat(layout.contains(SpeakerLabel.LTF)).isFalse();
    }

    @Test
    void shouldRejectEmptySpeakers() {
        assertThatThrownBy(() -> new SpeakerLayout("Empty", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new SpeakerLayout(null, List.of(SpeakerLabel.L)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldMakeDefensiveCopy() {
        var layout = SpeakerLayout.LAYOUT_7_1_4;
        // speakers() returns an unmodifiable list
        assertThatThrownBy(() -> layout.speakers().add(SpeakerLabel.L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
