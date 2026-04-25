package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TrackFoldState} — the per-track fold/collapse model
 * for automation, take, and MIDI lane groups.
 */
class TrackFoldStateTest {

    @Test
    void unfoldedConstantHasAllFlagsClearAndZeroOverride() {
        TrackFoldState state = TrackFoldState.UNFOLDED;

        assertThat(state.automationFolded()).isFalse();
        assertThat(state.takesFolded()).isFalse();
        assertThat(state.midiFolded()).isFalse();
        assertThat(state.headerHeightOverride()).isEqualTo(0.0);
        assertThat(state.isAnyFolded()).isFalse();
        assertThat(state.isFullyFolded()).isFalse();
    }

    @Test
    void allFoldedConstantHasEveryGroupCollapsed() {
        TrackFoldState state = TrackFoldState.ALL_FOLDED;

        assertThat(state.isFullyFolded()).isTrue();
        assertThat(state.isAnyFolded()).isTrue();
    }

    @Test
    void witherProducesIndependentState() {
        TrackFoldState start = TrackFoldState.UNFOLDED;
        TrackFoldState withAutomation = start.withAutomationFolded(true);

        assertThat(start.automationFolded()).isFalse();
        assertThat(withAutomation.automationFolded()).isTrue();
        assertThat(withAutomation.takesFolded()).isFalse();
        assertThat(withAutomation.midiFolded()).isFalse();
    }

    @Test
    void shouldRejectNegativeHeaderOverride() {
        assertThatThrownBy(() -> new TrackFoldState(false, false, false, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonFiniteHeaderOverride() {
        assertThatThrownBy(() -> new TrackFoldState(false, false, false, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackFoldState(false, false, false, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectiveLaneHeightCollapsesToSummaryStripWhenFolded() {
        double folded = TrackFoldState.effectiveLaneHeight(true, 60.0);
        double expanded = TrackFoldState.effectiveLaneHeight(false, 60.0);

        assertThat(folded).isEqualTo(TrackFoldState.SUMMARY_STRIP_HEIGHT_PX);
        assertThat(folded).isEqualTo(3.0);
        assertThat(expanded).isEqualTo(60.0);
    }

    @Test
    void effectiveLaneHeightStaysZeroWhenLaneHasNoContent() {
        // A track with no automation lanes should render no strip even
        // when the fold flag is set: we don't lie about data existing.
        assertThat(TrackFoldState.effectiveLaneHeight(true, 0.0)).isEqualTo(0.0);
        assertThat(TrackFoldState.effectiveLaneHeight(false, 0.0)).isEqualTo(0.0);
    }

    @Test
    void effectiveLaneHeightRejectsNegativeExpandedHeight() {
        assertThatThrownBy(() -> TrackFoldState.effectiveLaneHeight(true, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trackDefaultsToUnfoldedFoldState() {
        Track track = new Track("Vocals", TrackType.AUDIO);

        assertThat(track.getFoldState()).isEqualTo(TrackFoldState.UNFOLDED);
    }

    @Test
    void settingFoldStateDoesNotMutateClipOrAutomationData() {
        // The issue requires: "toggling fold preserves contained
        // clip/automation data bit-exact". This test asserts exactly
        // that — the data references on the track are unchanged after
        // any number of fold-state transitions.
        Track track = new Track("Synth", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Lead", 0.0, 4.0, "/tmp/lead.wav");
        track.addClip(clip);

        AutomationLane volLane = track.getAutomationData()
                .getOrCreateLane(AutomationParameter.VOLUME);
        volLane.addPoint(new AutomationPoint(0.0, 1.0));
        volLane.addPoint(new AutomationPoint(2.5, 0.5));

        // Capture identity / data before folding.
        AudioClip clipBefore = track.getClips().getFirst();
        int pointsBefore = volLane.getPoints().size();
        double firstValueBefore = volLane.getPoints().getFirst().getValue();
        double secondTimeBefore = volLane.getPoints().get(1).getTimeInBeats();

        // Toggle fold every which way.
        track.setFoldState(TrackFoldState.ALL_FOLDED);
        track.setFoldState(TrackFoldState.UNFOLDED);
        track.setFoldState(track.getFoldState().withAutomationFolded(true));
        track.setFoldState(track.getFoldState().withTakesFolded(true));
        track.setFoldState(track.getFoldState().withMidiFolded(true));
        track.setFoldState(track.getFoldState().withHeaderHeightOverride(40.0));

        // Bit-exact data preservation.
        AudioClip clipAfter = track.getClips().getFirst();
        assertThat(clipAfter).isSameAs(clipBefore);
        assertThat(clipAfter.getName()).isEqualTo("Lead");
        assertThat(clipAfter.getStartBeat()).isEqualTo(0.0);
        assertThat(clipAfter.getDurationBeats()).isEqualTo(4.0);
        AutomationLane laneAfter = track.getAutomationData()
                .getOrCreateLane(AutomationParameter.VOLUME);
        assertThat(laneAfter).isSameAs(volLane);
        assertThat(laneAfter.getPoints()).hasSize(pointsBefore);
        assertThat(laneAfter.getPoints().getFirst().getValue()).isEqualTo(firstValueBefore);
        assertThat(laneAfter.getPoints().get(1).getTimeInBeats()).isEqualTo(secondTimeBefore);

        // And the fold state itself is what we last set.
        TrackFoldState finalState = track.getFoldState();
        assertThat(finalState.automationFolded()).isTrue();
        assertThat(finalState.takesFolded()).isTrue();
        assertThat(finalState.midiFolded()).isTrue();
        assertThat(finalState.headerHeightOverride()).isEqualTo(40.0);
    }

    @Test
    void shouldRejectNullFoldState() {
        Track track = new Track("Drums", TrackType.AUDIO);

        assertThatThrownBy(() -> track.setFoldState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void layoutHeightsMatchConfiguredFoldStateAcrossLaneGroups() {
        // The issue requires: "the layout heights match the configured
        // fold state". Verify that effectiveLaneHeight applied per
        // lane-group flag yields the expected heights for both an
        // expanded and a fully-folded track.
        TrackFoldState expanded = TrackFoldState.UNFOLDED;
        double automationExpandedPx = 60.0;
        double takesExpandedPx = 80.0;
        double midiExpandedPx = 120.0;

        assertThat(TrackFoldState.effectiveLaneHeight(
                expanded.automationFolded(), automationExpandedPx)).isEqualTo(60.0);
        assertThat(TrackFoldState.effectiveLaneHeight(
                expanded.takesFolded(), takesExpandedPx)).isEqualTo(80.0);
        assertThat(TrackFoldState.effectiveLaneHeight(
                expanded.midiFolded(), midiExpandedPx)).isEqualTo(120.0);

        TrackFoldState folded = TrackFoldState.ALL_FOLDED;
        assertThat(TrackFoldState.effectiveLaneHeight(
                folded.automationFolded(), automationExpandedPx)).isEqualTo(3.0);
        assertThat(TrackFoldState.effectiveLaneHeight(
                folded.takesFolded(), takesExpandedPx)).isEqualTo(3.0);
        assertThat(TrackFoldState.effectiveLaneHeight(
                folded.midiFolded(), midiExpandedPx)).isEqualTo(3.0);
    }
}
