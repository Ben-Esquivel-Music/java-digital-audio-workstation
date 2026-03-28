package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakeCompingTest {

    private TakeComping comping;

    @BeforeEach
    void setUp() {
        comping = new TakeComping();
    }

    // ── Take lane management ────────────────────────────────────────────────

    @Test
    void shouldStartWithNoTakeLanes() {
        assertThat(comping.getTakeLanes()).isEmpty();
        assertThat(comping.getTakeLaneCount()).isEqualTo(0);
        assertThat(comping.isActive()).isFalse();
    }

    @Test
    void shouldAddTakeLane() {
        TakeLane lane = new TakeLane("Take 1");
        comping.addTakeLane(lane);

        assertThat(comping.getTakeLanes()).containsExactly(lane);
        assertThat(comping.getTakeLaneCount()).isEqualTo(1);
        assertThat(comping.isActive()).isTrue();
    }

    @Test
    void shouldAddMultipleTakeLanes() {
        TakeLane lane1 = new TakeLane("Take 1");
        TakeLane lane2 = new TakeLane("Take 2");
        TakeLane lane3 = new TakeLane("Take 3");
        comping.addTakeLane(lane1);
        comping.addTakeLane(lane2);
        comping.addTakeLane(lane3);

        assertThat(comping.getTakeLanes()).containsExactly(lane1, lane2, lane3);
        assertThat(comping.getTakeLaneCount()).isEqualTo(3);
    }

    @Test
    void shouldRejectNullTakeLane() {
        assertThatThrownBy(() -> comping.addTakeLane(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldGetTakeLaneByIndex() {
        TakeLane lane = new TakeLane("Take 1");
        comping.addTakeLane(lane);

        assertThat(comping.getTakeLane(0)).isSameAs(lane);
    }

    @Test
    void shouldThrowOnInvalidTakeLaneIndex() {
        assertThatThrownBy(() -> comping.getTakeLane(0))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldRemoveTakeLane() {
        TakeLane lane1 = new TakeLane("Take 1");
        TakeLane lane2 = new TakeLane("Take 2");
        comping.addTakeLane(lane1);
        comping.addTakeLane(lane2);

        boolean removed = comping.removeTakeLane(lane1);

        assertThat(removed).isTrue();
        assertThat(comping.getTakeLanes()).containsExactly(lane2);
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentLane() {
        TakeLane lane = new TakeLane("Take 1");
        assertThat(comping.removeTakeLane(lane)).isFalse();
    }

    @Test
    void shouldRemoveCompRegionsWhenLaneRemoved() {
        TakeLane lane1 = new TakeLane("Take 1");
        TakeLane lane2 = new TakeLane("Take 2");
        comping.addTakeLane(lane1);
        comping.addTakeLane(lane2);

        // Add regions for both lanes
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 4.0));

        // Remove the first lane
        comping.removeTakeLane(lane1);

        // Region from lane 0 should be removed
        // Region from lane 1 should have its index adjusted to 0
        assertThat(comping.getCompRegions()).hasSize(1);
        CompRegion remaining = comping.getCompRegions().get(0);
        assertThat(remaining.takeIndex()).isEqualTo(0);
        assertThat(remaining.startBeat()).isEqualTo(4.0);
    }

    @Test
    void shouldReturnUnmodifiableTakeLanesList() {
        comping.addTakeLane(new TakeLane("Take 1"));

        assertThatThrownBy(() -> comping.getTakeLanes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Comp region management ──────────────────────────────────────────────

    @Test
    void shouldStartWithNoCompRegions() {
        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void shouldAddCompRegion() {
        comping.addTakeLane(new TakeLane("Take 1"));
        CompRegion region = new CompRegion(0, 0.0, 4.0);

        comping.addCompRegion(region);

        assertThat(comping.getCompRegions()).containsExactly(region);
    }

    @Test
    void shouldRejectNullCompRegion() {
        assertThatThrownBy(() -> comping.addCompRegion(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectCompRegionWithOutOfRangeTakeIndex() {
        comping.addTakeLane(new TakeLane("Take 1"));

        assertThatThrownBy(() -> comping.addCompRegion(new CompRegion(1, 0.0, 4.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRemoveOverlappingRegionsWhenAddingNewOne() {
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addTakeLane(new TakeLane("Take 2"));

        comping.addCompRegion(new CompRegion(0, 0.0, 8.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 4.0)); // overlaps [4,8)

        // The region from take 0 should be removed (overlaps [4,8))
        assertThat(comping.getCompRegions()).hasSize(1);
        assertThat(comping.getCompRegions().get(0).takeIndex()).isEqualTo(1);
    }

    @Test
    void shouldNotRemoveNonOverlappingRegions() {
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addTakeLane(new TakeLane("Take 2"));

        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 4.0)); // adjacent, not overlapping

        assertThat(comping.getCompRegions()).hasSize(2);
    }

    @Test
    void shouldRemoveSpecificCompRegion() {
        comping.addTakeLane(new TakeLane("Take 1"));
        CompRegion region = new CompRegion(0, 0.0, 4.0);
        comping.addCompRegion(region);

        boolean removed = comping.removeCompRegion(region);

        assertThat(removed).isTrue();
        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void shouldSetCompRegions() {
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addTakeLane(new TakeLane("Take 2"));

        List<CompRegion> regions = List.of(
                new CompRegion(0, 0.0, 4.0),
                new CompRegion(1, 4.0, 4.0));
        comping.setCompRegions(regions);

        assertThat(comping.getCompRegions()).isEqualTo(regions);
    }

    @Test
    void shouldClearCompRegions() {
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));

        comping.clearCompRegions();

        assertThat(comping.getCompRegions()).isEmpty();
    }

    @Test
    void shouldGetCompRegionsForTake() {
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addTakeLane(new TakeLane("Take 2"));

        CompRegion r1 = new CompRegion(0, 0.0, 4.0);
        CompRegion r2 = new CompRegion(1, 8.0, 4.0);
        comping.addCompRegion(r1);
        comping.addCompRegion(r2);

        assertThat(comping.getCompRegionsForTake(0)).containsExactly(r1);
        assertThat(comping.getCompRegionsForTake(1)).containsExactly(r2);
    }

    @Test
    void shouldReturnUnmodifiableCompRegionsList() {
        comping.addTakeLane(new TakeLane("Take 1"));
        comping.addCompRegion(new CompRegion(0, 0.0, 4.0));

        assertThatThrownBy(() -> comping.getCompRegions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── Compile ─────────────────────────────────────────────────────────────

    @Test
    void shouldCompileEmptyRegions() {
        assertThat(comping.compile()).isEmpty();
    }

    @Test
    void shouldCompileSingleRegionFromSingleTake() {
        TakeLane lane = new TakeLane("Take 1");
        AudioClip takeClip = new AudioClip("Recording", 0.0, 16.0, "/take1.wav");
        lane.addClip(takeClip);
        comping.addTakeLane(lane);

        comping.addCompRegion(new CompRegion(0, 0.0, 16.0));

        List<AudioClip> compiled = comping.compile();

        assertThat(compiled).hasSize(1);
        assertThat(compiled.get(0).getStartBeat()).isEqualTo(0.0);
        assertThat(compiled.get(0).getDurationBeats()).isEqualTo(16.0);
        assertThat(compiled.get(0).getSourceFilePath()).isEqualTo("/take1.wav");
    }

    @Test
    void shouldCompileMultipleRegionsFromDifferentTakes() {
        TakeLane lane1 = new TakeLane("Take 1");
        lane1.addClip(new AudioClip("Take 1 Rec", 0.0, 16.0, "/take1.wav"));
        TakeLane lane2 = new TakeLane("Take 2");
        lane2.addClip(new AudioClip("Take 2 Rec", 0.0, 16.0, "/take2.wav"));
        comping.addTakeLane(lane1);
        comping.addTakeLane(lane2);

        // Use first 8 beats from take 1, next 8 from take 2
        comping.addCompRegion(new CompRegion(0, 0.0, 8.0));
        comping.addCompRegion(new CompRegion(1, 8.0, 8.0));

        List<AudioClip> compiled = comping.compile();

        assertThat(compiled).hasSize(2);
        assertThat(compiled.get(0).getSourceFilePath()).isEqualTo("/take1.wav");
        assertThat(compiled.get(0).getStartBeat()).isEqualTo(0.0);
        assertThat(compiled.get(0).getDurationBeats()).isEqualTo(8.0);
        assertThat(compiled.get(1).getSourceFilePath()).isEqualTo("/take2.wav");
        assertThat(compiled.get(1).getStartBeat()).isEqualTo(8.0);
        assertThat(compiled.get(1).getDurationBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldCompilePartialRegion() {
        TakeLane lane = new TakeLane("Take 1");
        AudioClip takeClip = new AudioClip("Recording", 0.0, 16.0, "/take1.wav");
        lane.addClip(takeClip);
        comping.addTakeLane(lane);

        // Only select beats 4-12 from a clip spanning 0-16
        comping.addCompRegion(new CompRegion(0, 4.0, 8.0));

        List<AudioClip> compiled = comping.compile();

        assertThat(compiled).hasSize(1);
        assertThat(compiled.get(0).getStartBeat()).isEqualTo(4.0);
        assertThat(compiled.get(0).getDurationBeats()).isEqualTo(8.0);
        assertThat(compiled.get(0).getSourceOffsetBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldPreserveClipGainInCompiledOutput() {
        TakeLane lane = new TakeLane("Take 1");
        AudioClip takeClip = new AudioClip("Recording", 0.0, 8.0, "/take1.wav");
        takeClip.setGainDb(-3.0);
        lane.addClip(takeClip);
        comping.addTakeLane(lane);

        comping.addCompRegion(new CompRegion(0, 0.0, 8.0));

        List<AudioClip> compiled = comping.compile();

        assertThat(compiled).hasSize(1);
        assertThat(compiled.get(0).getGainDb()).isEqualTo(-3.0);
    }

    @Test
    void shouldReturnUnmodifiableCompiledList() {
        TakeLane lane = new TakeLane("Take 1");
        lane.addClip(new AudioClip("Recording", 0.0, 8.0, "/take1.wav"));
        comping.addTakeLane(lane);
        comping.addCompRegion(new CompRegion(0, 0.0, 8.0));

        List<AudioClip> compiled = comping.compile();

        assertThatThrownBy(() -> compiled.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldSkipRegionWhenNoMatchingClip() {
        TakeLane lane = new TakeLane("Take 1");
        // Clip covers [0,4), but comp region is [8,12)
        lane.addClip(new AudioClip("Recording", 0.0, 4.0, "/take1.wav"));
        comping.addTakeLane(lane);

        comping.addCompRegion(new CompRegion(0, 8.0, 4.0));

        List<AudioClip> compiled = comping.compile();

        assertThat(compiled).isEmpty();
    }

    // ── Solo take auditioning ───────────────────────────────────────────────

    @Test
    void shouldSoloTakeLane() {
        TakeLane lane1 = new TakeLane("Take 1");
        TakeLane lane2 = new TakeLane("Take 2");
        comping.addTakeLane(lane1);
        comping.addTakeLane(lane2);

        lane1.setSoloed(true);

        assertThat(lane1.isSoloed()).isTrue();
        assertThat(lane2.isSoloed()).isFalse();
    }
}
