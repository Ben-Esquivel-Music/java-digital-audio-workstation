package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipCrossfadeTest {

    // ── Construction tests ──────────────────────────────────────────────────

    @Test
    void shouldCreateCrossfadeBetweenOverlappingClips() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);

        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThat(crossfade.getId()).isNotNull();
        assertThat(crossfade.getOutgoingClip()).isSameAs(outgoing);
        assertThat(crossfade.getIncomingClip()).isSameAs(incoming);
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.LINEAR);
    }

    @Test
    void shouldRejectNonOverlappingClips() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 4.0, null);
        AudioClip incoming = new AudioClip("Incoming", 8.0, 4.0, null);

        assertThatThrownBy(() -> new ClipCrossfade(outgoing, incoming, CrossfadeCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not overlap");
    }

    @Test
    void shouldRejectSameClipForBothSides() {
        AudioClip clip = new AudioClip("Clip", 0.0, 8.0, null);

        assertThatThrownBy(() -> new ClipCrossfade(clip, clip, CrossfadeCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void shouldRejectNullOutgoingClip() {
        AudioClip incoming = new AudioClip("Incoming", 0.0, 4.0, null);

        assertThatThrownBy(() -> new ClipCrossfade(null, incoming, CrossfadeCurve.LINEAR))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullIncomingClip() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 4.0, null);

        assertThatThrownBy(() -> new ClipCrossfade(outgoing, null, CrossfadeCurve.LINEAR))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCurveType() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);

        assertThatThrownBy(() -> new ClipCrossfade(outgoing, incoming, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAdjacentClipsThatDoNotOverlap() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 4.0, null);
        AudioClip incoming = new AudioClip("Incoming", 4.0, 4.0, null);

        assertThatThrownBy(() -> new ClipCrossfade(outgoing, incoming, CrossfadeCurve.LINEAR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Region computation tests ────────────────────────────────────────────

    @Test
    void shouldComputeCrossfadeRegion() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);

        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThat(crossfade.getStartBeat()).isEqualTo(6.0);
        assertThat(crossfade.getEndBeat()).isEqualTo(8.0);
        assertThat(crossfade.getDurationBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldComputeRegionWhenIncomingStartsAtOutgoingStart() {
        AudioClip outgoing = new AudioClip("Outgoing", 2.0, 6.0, null);
        AudioClip incoming = new AudioClip("Incoming", 4.0, 6.0, null);

        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.EQUAL_POWER);

        assertThat(crossfade.getStartBeat()).isEqualTo(4.0);
        assertThat(crossfade.getEndBeat()).isEqualTo(8.0);
        assertThat(crossfade.getDurationBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldComputeRegionWhenOneClipFullyContainsAnother() {
        AudioClip outgoing = new AudioClip("Long", 0.0, 16.0, null);
        AudioClip incoming = new AudioClip("Short", 4.0, 4.0, null);

        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.S_CURVE);

        assertThat(crossfade.getStartBeat()).isEqualTo(4.0);
        assertThat(crossfade.getEndBeat()).isEqualTo(8.0);
        assertThat(crossfade.getDurationBeats()).isEqualTo(4.0);
    }

    // ── Curve type tests ────────────────────────────────────────────────────

    @Test
    void shouldUpdateCurveType() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);

        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        crossfade.setCurveType(CrossfadeCurve.EQUAL_POWER);
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.EQUAL_POWER);

        crossfade.setCurveType(CrossfadeCurve.S_CURVE);
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.S_CURVE);
    }

    @Test
    void shouldRejectNullCurveTypeOnSet() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThatThrownBy(() -> crossfade.setCurveType(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Overlap detection tests ─────────────────────────────────────────────

    @Test
    void shouldDetectOverlap() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThat(crossfade.hasOverlap()).isTrue();
    }

    @Test
    void shouldDetectLostOverlapWhenClipMoved() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        // Move incoming clip far away
        incoming.setStartBeat(20.0);
        assertThat(crossfade.hasOverlap()).isFalse();
    }

    // ── involvesClip tests ──────────────────────────────────────────────────

    @Test
    void shouldDetectInvolvedClip() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);
        AudioClip other = new AudioClip("Other", 20.0, 4.0, null);

        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThat(crossfade.involvesClip(outgoing)).isTrue();
        assertThat(crossfade.involvesClip(incoming)).isTrue();
        assertThat(crossfade.involvesClip(other)).isFalse();
    }

    // ── Unique ID test ──────────────────────────────────────────────────────

    @Test
    void shouldHaveUniqueIds() {
        AudioClip outgoing = new AudioClip("Outgoing", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("Incoming", 6.0, 8.0, null);

        ClipCrossfade crossfade1 = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);
        ClipCrossfade crossfade2 = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThat(crossfade1.getId()).isNotEqualTo(crossfade2.getId());
    }
}
