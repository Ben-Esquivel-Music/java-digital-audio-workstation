package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossfadeManagerTest {

    private CrossfadeManager manager;
    private Track track;

    @BeforeEach
    void setUp() {
        manager = new CrossfadeManager();
        track = new Track("Audio 1", TrackType.AUDIO);
    }

    // ── Default curve type tests ────────────────────────────────────────────

    @Test
    void shouldDefaultToLinearCurveType() {
        assertThat(manager.getDefaultCurveType()).isEqualTo(CrossfadeCurve.LINEAR);
    }

    @Test
    void shouldUpdateDefaultCurveType() {
        manager.setDefaultCurveType(CrossfadeCurve.EQUAL_POWER);
        assertThat(manager.getDefaultCurveType()).isEqualTo(CrossfadeCurve.EQUAL_POWER);
    }

    @Test
    void shouldRejectNullDefaultCurveType() {
        assertThatThrownBy(() -> manager.setDefaultCurveType(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Add / remove / list tests ───────────────────────────────────────────

    @Test
    void shouldStartWithNoCrossfades() {
        assertThat(manager.getCrossfades()).isEmpty();
    }

    @Test
    void shouldAddCrossfade() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        manager.addCrossfade(crossfade);

        assertThat(manager.getCrossfades()).containsExactly(crossfade);
    }

    @Test
    void shouldRemoveCrossfade() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);
        manager.addCrossfade(crossfade);

        boolean removed = manager.removeCrossfade(crossfade);

        assertThat(removed).isTrue();
        assertThat(manager.getCrossfades()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentCrossfade() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThat(manager.removeCrossfade(crossfade)).isFalse();
    }

    @Test
    void shouldClearAllCrossfades() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        manager.addCrossfade(new ClipCrossfade(a, b, CrossfadeCurve.LINEAR));

        manager.clear();

        assertThat(manager.getCrossfades()).isEmpty();
    }

    @Test
    void shouldRejectNullCrossfadeOnAdd() {
        assertThatThrownBy(() -> manager.addCrossfade(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Find crossfade tests ────────────────────────────────────────────────

    @Test
    void shouldFindCrossfadeBetweenTwoClips() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);
        manager.addCrossfade(crossfade);

        Optional<ClipCrossfade> found = manager.findCrossfade(outgoing, incoming);
        assertThat(found).isPresent().containsSame(crossfade);
    }

    @Test
    void shouldFindCrossfadeRegardlessOfArgumentOrder() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);
        manager.addCrossfade(crossfade);

        // Arguments in reverse order
        Optional<ClipCrossfade> found = manager.findCrossfade(incoming, outgoing);
        assertThat(found).isPresent().containsSame(crossfade);
    }

    @Test
    void shouldReturnEmptyWhenNoCrossfadeExists() {
        AudioClip a = new AudioClip("A", 0.0, 4.0, null);
        AudioClip b = new AudioClip("B", 8.0, 4.0, null);

        assertThat(manager.findCrossfade(a, b)).isEmpty();
    }

    @Test
    void shouldFindCrossfadesForClip() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        AudioClip c = new AudioClip("C", 12.0, 8.0, null);
        ClipCrossfade xfadeAB = new ClipCrossfade(a, b, CrossfadeCurve.LINEAR);
        ClipCrossfade xfadeBC = new ClipCrossfade(b, c, CrossfadeCurve.LINEAR);
        manager.addCrossfade(xfadeAB);
        manager.addCrossfade(xfadeBC);

        List<ClipCrossfade> forB = manager.findCrossfadesForClip(b);
        assertThat(forB).containsExactly(xfadeAB, xfadeBC);

        List<ClipCrossfade> forA = manager.findCrossfadesForClip(a);
        assertThat(forA).containsExactly(xfadeAB);
    }

    @Test
    void shouldReturnEmptyCrossfadesForUnrelatedClip() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        AudioClip c = new AudioClip("C", 20.0, 4.0, null);
        manager.addCrossfade(new ClipCrossfade(a, b, CrossfadeCurve.LINEAR));

        assertThat(manager.findCrossfadesForClip(c)).isEmpty();
    }

    // ── Detect crossfades tests ─────────────────────────────────────────────

    @Test
    void shouldDetectOverlappingClips() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        track.addClip(a);
        track.addClip(b);

        List<ClipCrossfade> detected = manager.detectCrossfades(track);

        assertThat(detected).hasSize(1);
        assertThat(detected.get(0).getOutgoingClip()).isSameAs(a);
        assertThat(detected.get(0).getIncomingClip()).isSameAs(b);
    }

    @Test
    void shouldNotCreateDuplicateCrossfades() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        track.addClip(a);
        track.addClip(b);

        manager.detectCrossfades(track);
        manager.detectCrossfades(track);

        assertThat(manager.getCrossfades()).hasSize(1);
    }

    @Test
    void shouldDetectNoCrossfadesForNonOverlappingClips() {
        AudioClip a = new AudioClip("A", 0.0, 4.0, null);
        AudioClip b = new AudioClip("B", 8.0, 4.0, null);
        track.addClip(a);
        track.addClip(b);

        List<ClipCrossfade> detected = manager.detectCrossfades(track);

        assertThat(detected).isEmpty();
    }

    @Test
    void shouldRemoveCrossfadeWhenOverlapDisappears() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        track.addClip(a);
        track.addClip(b);

        manager.detectCrossfades(track);
        assertThat(manager.getCrossfades()).hasSize(1);

        // Move clip B far away so it no longer overlaps
        b.setStartBeat(20.0);
        manager.detectCrossfades(track);

        assertThat(manager.getCrossfades()).isEmpty();
    }

    @Test
    void shouldDetectMultipleOverlaps() {
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        AudioClip c = new AudioClip("C", 12.0, 8.0, null);
        track.addClip(a);
        track.addClip(b);
        track.addClip(c);

        List<ClipCrossfade> detected = manager.detectCrossfades(track);

        assertThat(detected).hasSize(2);
    }

    @Test
    void shouldUseDefaultCurveTypeForDetectedCrossfades() {
        manager.setDefaultCurveType(CrossfadeCurve.S_CURVE);
        AudioClip a = new AudioClip("A", 0.0, 8.0, null);
        AudioClip b = new AudioClip("B", 6.0, 8.0, null);
        track.addClip(a);
        track.addClip(b);

        List<ClipCrossfade> detected = manager.detectCrossfades(track);

        assertThat(detected.get(0).getCurveType()).isEqualTo(CrossfadeCurve.S_CURVE);
    }

    @Test
    void shouldAssignOutgoingAndIncomingByStartPosition() {
        // Add clips in reverse order — the one that starts earlier is outgoing
        AudioClip later = new AudioClip("Later", 6.0, 8.0, null);
        AudioClip earlier = new AudioClip("Earlier", 0.0, 8.0, null);
        track.addClip(later);
        track.addClip(earlier);

        List<ClipCrossfade> detected = manager.detectCrossfades(track);

        assertThat(detected).hasSize(1);
        assertThat(detected.get(0).getOutgoingClip()).isSameAs(earlier);
        assertThat(detected.get(0).getIncomingClip()).isSameAs(later);
    }

    @Test
    void shouldDetectNoCrossfadesForEmptyTrack() {
        assertThat(manager.detectCrossfades(track)).isEmpty();
    }

    @Test
    void shouldDetectNoCrossfadesForSingleClip() {
        track.addClip(new AudioClip("Solo", 0.0, 8.0, null));
        assertThat(manager.detectCrossfades(track)).isEmpty();
    }

    @Test
    void shouldRejectNullTrackOnDetect() {
        assertThatThrownBy(() -> manager.detectCrossfades(null))
                .isInstanceOf(NullPointerException.class);
    }
}
