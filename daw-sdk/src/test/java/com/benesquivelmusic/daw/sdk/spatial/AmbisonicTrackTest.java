package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmbisonicTrackTest {

    @Test
    void shouldCreateFoaTrackWithDefaultAssignments() {
        AmbisonicTrack track = new AmbisonicTrack(
                "Ambi 1", AmbisonicOrder.FIRST, new AmbisonicDecoder.StereoUhj());
        assertThat(track.name()).isEqualTo("Ambi 1");
        assertThat(track.order()).isEqualTo(AmbisonicOrder.FIRST);
        assertThat(track.channelCount()).isEqualTo(4);
        assertThat(track.channelAssignments()).containsExactly(0, 1, 2, 3);
        assertThat(track.decoder()).isInstanceOf(AmbisonicDecoder.StereoUhj.class);
    }

    @Test
    void shouldCreateThirdOrderTrackWithDefaultAssignments() {
        AmbisonicTrack track = new AmbisonicTrack(
                "Ambi", AmbisonicOrder.THIRD, new AmbisonicDecoder.Binaural5());
        assertThat(track.channelCount()).isEqualTo(16);
        assertThat(track.channelAssignments()).hasSize(16);
    }

    @Test
    void shouldAcceptCustomChannelAssignments() {
        AmbisonicTrack track = new AmbisonicTrack(
                "Ambi", AmbisonicOrder.FIRST, List.of(4, 5, 6, 7),
                new AmbisonicDecoder.StereoUhj());
        assertThat(track.channelAssignments()).containsExactly(4, 5, 6, 7);
    }

    @Test
    void shouldRejectAssignmentsWithWrongSize() {
        // FIRST order requires 4 channels — the issue's "order-mismatched
        // decoder" precondition: a clear error must be raised.
        assertThatThrownBy(() -> new AmbisonicTrack(
                "Ambi", AmbisonicOrder.FIRST, List.of(0, 1, 2),
                new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 4")
                .hasMessageContaining("got 3");

        assertThatThrownBy(() -> new AmbisonicTrack(
                "Ambi", AmbisonicOrder.SECOND, List.of(0, 1, 2, 3),
                new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 9")
                .hasMessageContaining("got 4");
    }

    @Test
    void shouldRejectNegativeAssignments() {
        assertThatThrownBy(() -> new AmbisonicTrack(
                "Ambi", AmbisonicOrder.FIRST, List.of(0, 1, 2, -1),
                new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new AmbisonicTrack(
                "  ", AmbisonicOrder.FIRST, new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> new AmbisonicTrack(
                null, AmbisonicOrder.FIRST, new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AmbisonicTrack(
                "Ambi", null, new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AmbisonicTrack(
                "Ambi", AmbisonicOrder.FIRST, (AmbisonicDecoder) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void channelAssignmentsShouldBeImmutable() {
        java.util.List<Integer> mutable = new java.util.ArrayList<>(List.of(0, 1, 2, 3));
        AmbisonicTrack track = new AmbisonicTrack(
                "Ambi", AmbisonicOrder.FIRST, mutable, new AmbisonicDecoder.StereoUhj());
        mutable.set(0, 99);
        assertThat(track.channelAssignments().get(0)).isEqualTo(0);
        assertThatThrownBy(() -> track.channelAssignments().set(0, 99))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withDecoderShouldReturnUpdatedCopy() {
        AmbisonicTrack track = new AmbisonicTrack(
                "Ambi", AmbisonicOrder.FIRST, new AmbisonicDecoder.StereoUhj());
        AmbisonicDecoder rig = new AmbisonicDecoder.LoudspeakerRig(SpeakerLayout.LAYOUT_7_1_4);
        AmbisonicTrack updated = track.withDecoder(rig);
        assertThat(updated.decoder()).isSameAs(rig);
        assertThat(updated.name()).isEqualTo("Ambi");
        assertThat(updated.channelAssignments()).isEqualTo(track.channelAssignments());
        assertThat(track.decoder()).isInstanceOf(AmbisonicDecoder.StereoUhj.class);
    }
}
