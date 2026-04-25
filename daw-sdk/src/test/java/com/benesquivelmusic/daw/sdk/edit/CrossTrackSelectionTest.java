package com.benesquivelmusic.daw.sdk.edit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossTrackSelectionTest {

    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();
    private static final UUID T3 = UUID.randomUUID();

    @Test
    void shouldConstructWithValidArguments() {
        CrossTrackSelection sel = new CrossTrackSelection(100L, 500L, Set.of(T1, T2));

        assertThat(sel.startFrames()).isEqualTo(100L);
        assertThat(sel.endFrames()).isEqualTo(500L);
        assertThat(sel.trackIds()).containsExactlyInAnyOrder(T1, T2);
        assertThat(sel.durationFrames()).isEqualTo(400L);
        assertThat(sel.isEmpty()).isFalse();
    }

    @Test
    void shouldRejectNegativeStart() {
        assertThatThrownBy(() -> new CrossTrackSelection(-1L, 100L, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startFrames");
    }

    @Test
    void shouldRejectEndLessThanOrEqualToStart() {
        assertThatThrownBy(() -> new CrossTrackSelection(100L, 100L, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endFrames");
        assertThatThrownBy(() -> new CrossTrackSelection(100L, 50L, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endFrames");
    }

    @Test
    void shouldRejectNullTrackIds() {
        assertThatThrownBy(() -> new CrossTrackSelection(0L, 10L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("trackIds");
    }

    @Test
    void shouldAllowEmptyTrackSetAsNoOp() {
        CrossTrackSelection sel = new CrossTrackSelection(0L, 10L, Set.of());

        assertThat(sel.isEmpty()).isTrue();
        assertThat(sel.trackIds()).isEmpty();
    }

    @Test
    void shouldDefensivelyCopyTrackIds() {
        Set<UUID> mutable = new HashSet<>();
        mutable.add(T1);
        mutable.add(T2);

        CrossTrackSelection sel = new CrossTrackSelection(0L, 10L, mutable);

        // Mutating the source set must not affect the selection.
        mutable.add(T3);
        assertThat(sel.trackIds()).containsExactlyInAnyOrder(T1, T2);

        // The exposed set must be unmodifiable.
        assertThatThrownBy(() -> sel.trackIds().add(T3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofSingleTrackShouldBuildOneTrackSelection() {
        CrossTrackSelection sel = CrossTrackSelection.ofSingleTrack(0L, 100L, T1);

        assertThat(sel.trackIds()).containsExactly(T1);
        assertThat(sel.startFrames()).isZero();
        assertThat(sel.endFrames()).isEqualTo(100L);
    }

    @Test
    void containsFrameShouldBeHalfOpen() {
        CrossTrackSelection sel = new CrossTrackSelection(100L, 200L, Set.of(T1));

        assertThat(sel.containsFrame(99L)).isFalse();
        assertThat(sel.containsFrame(100L)).isTrue();   // inclusive start
        assertThat(sel.containsFrame(150L)).isTrue();
        assertThat(sel.containsFrame(199L)).isTrue();
        assertThat(sel.containsFrame(200L)).isFalse();  // exclusive end
    }

    @Test
    void containsTrackShouldHandleNullAndMembership() {
        CrossTrackSelection sel = new CrossTrackSelection(0L, 10L, Set.of(T1, T2));

        assertThat(sel.containsTrack(T1)).isTrue();
        assertThat(sel.containsTrack(T2)).isTrue();
        assertThat(sel.containsTrack(T3)).isFalse();
        assertThat(sel.containsTrack(null)).isFalse();
    }

    @Test
    void intersectsShouldUseHalfOpenSemantics() {
        CrossTrackSelection sel = new CrossTrackSelection(100L, 200L, Set.of(T1));

        // Wholly outside.
        assertThat(sel.intersects(0L, 100L)).isFalse();    // touches at start, exclusive
        assertThat(sel.intersects(200L, 300L)).isFalse();  // touches at end, exclusive
        // Partial overlaps — these are exactly the cases that drive splitClip.
        assertThat(sel.intersects(50L, 150L)).isTrue();    // straddles start
        assertThat(sel.intersects(150L, 250L)).isTrue();   // straddles end
        // Fully contained.
        assertThat(sel.intersects(120L, 180L)).isTrue();
        // Fully containing.
        assertThat(sel.intersects(0L, 1000L)).isTrue();
    }

    @Test
    void intersectsBoundariesAreSampleAccurate() {
        // Sample-accurate boundary check at the exact split points: a clip
        // ending at startFrames does NOT intersect; one ending at startFrames+1
        // does. This is what guarantees splitClip occurs at the precise frame.
        CrossTrackSelection sel = new CrossTrackSelection(1_000L, 2_000L, Set.of(T1));

        assertThat(sel.intersects(0L, 1_000L)).isFalse();
        assertThat(sel.intersects(0L, 1_001L)).isTrue();
        assertThat(sel.intersects(2_000L, 2_001L)).isFalse();
        assertThat(sel.intersects(1_999L, 2_001L)).isTrue();
    }

    @Test
    void intersectsShouldRejectInvalidClipRange() {
        CrossTrackSelection sel = new CrossTrackSelection(0L, 10L, Set.of(T1));

        assertThatThrownBy(() -> sel.intersects(100L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sel.intersects(100L, 50L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withRangeShouldReplaceTimeAndKeepTracks() {
        CrossTrackSelection original = new CrossTrackSelection(100L, 200L, Set.of(T1, T2));

        CrossTrackSelection moved = original.withRange(500L, 800L);

        assertThat(moved.startFrames()).isEqualTo(500L);
        assertThat(moved.endFrames()).isEqualTo(800L);
        assertThat(moved.trackIds()).containsExactlyInAnyOrder(T1, T2);
        // Original unchanged.
        assertThat(original.startFrames()).isEqualTo(100L);
        assertThat(original.endFrames()).isEqualTo(200L);
    }

    @Test
    void withTrackIdsShouldReplaceTracksAndKeepRange() {
        CrossTrackSelection original = new CrossTrackSelection(100L, 200L, Set.of(T1));

        CrossTrackSelection retargeted = original.withTrackIds(Set.of(T2, T3));

        assertThat(retargeted.startFrames()).isEqualTo(100L);
        assertThat(retargeted.endFrames()).isEqualTo(200L);
        assertThat(retargeted.trackIds()).containsExactlyInAnyOrder(T2, T3);
        // Original unchanged.
        assertThat(original.trackIds()).containsExactly(T1);
    }

    @Test
    void shiftedByShouldTranslateRangeAndPreserveDuration() {
        // Paste preserves relative inter-track offsets: shifting the source
        // selection by a delta keeps duration constant, so a paste at +10_000
        // frames places content at the destination while the per-track
        // relative timing inside the selection is untouched.
        CrossTrackSelection source = new CrossTrackSelection(1_000L, 5_000L, Set.of(T1, T2));

        CrossTrackSelection shifted = source.shiftedBy(10_000L);

        assertThat(shifted.startFrames()).isEqualTo(11_000L);
        assertThat(shifted.endFrames()).isEqualTo(15_000L);
        assertThat(shifted.durationFrames()).isEqualTo(source.durationFrames());
        assertThat(shifted.trackIds()).isEqualTo(source.trackIds());
    }

    @Test
    void shiftedByShouldAcceptNegativeDeltaWhenResultIsNonNegative() {
        CrossTrackSelection source = new CrossTrackSelection(1_000L, 2_000L, Set.of(T1));

        CrossTrackSelection shifted = source.shiftedBy(-1_000L);

        assertThat(shifted.startFrames()).isZero();
        assertThat(shifted.endFrames()).isEqualTo(1_000L);
    }

    @Test
    void shiftedByShouldRejectDeltaThatGoesNegative() {
        CrossTrackSelection source = new CrossTrackSelection(1_000L, 2_000L, Set.of(T1));

        assertThatThrownBy(() -> source.shiftedBy(-1_001L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shiftedByShouldRejectOverflow() {
        // endFrames + deltaFrames would overflow Long.MAX_VALUE.
        CrossTrackSelection source = new CrossTrackSelection(0L, Long.MAX_VALUE - 10L, Set.of(T1));

        assertThatThrownBy(() -> source.shiftedBy(100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void recordEqualityIsByValue() {
        CrossTrackSelection a = new CrossTrackSelection(0L, 10L, Set.of(T1, T2));
        CrossTrackSelection b = new CrossTrackSelection(0L, 10L, Set.of(T2, T1));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
