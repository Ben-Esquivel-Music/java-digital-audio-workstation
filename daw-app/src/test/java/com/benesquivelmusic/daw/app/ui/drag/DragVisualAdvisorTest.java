package com.benesquivelmusic.daw.app.ui.drag;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link DragVisualAdvisor}. The advisor is intentionally
 * UI-toolkit-agnostic — these tests run headless with no JavaFX screen.
 *
 * <p>Covers the user-story-197 acceptance criteria:</p>
 * <ul>
 *   <li>start-drag produces a ghost</li>
 *   <li>valid / invalid targets highlight correctly</li>
 *   <li>Esc cancels and restores the source position</li>
 * </ul>
 */
class DragVisualAdvisorTest {

    @Test
    void beginDragProducesGhostWithSourceStyleAndProfileOpacity() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();

        DragVisualState s = advisor.beginDrag(
                DragSourceKind.CLIP, "Drum Loop", 100, 200, 80, 24);

        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.DRAGGING);
        assertThat(s.ghost().sourceKind()).isEqualTo(DragSourceKind.CLIP);
        assertThat(s.ghost().style()).isEqualTo(GhostStyle.WAVEFORM_OUTLINE);
        assertThat(s.ghost().label()).isEqualTo("Drum Loop");
        assertThat(s.ghost().opacity()).isEqualTo(AnimationProfile.DEFAULT.ghostOpacity());
        assertThat(s.highlight()).isEqualTo(DropTargetHighlight.NONE);
        assertThat(s.cursor()).isEqualTo(DragCursor.DEFAULT);
        assertThat(s.snap().visible()).isFalse();
    }

    @Test
    void pluginGhostUsesPluginCardStyleAndSampleUsesMiniWaveform() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        DragVisualState plugin = advisor.beginDrag(
                DragSourceKind.PLUGIN, "Reverb", 0, 0, 100, 40);
        assertThat(plugin.ghost().style()).isEqualTo(GhostStyle.PLUGIN_CARD);
        advisor.commit();

        DragVisualState sample = advisor.beginDrag(
                DragSourceKind.SAMPLE, "kick.wav", 0, 0, 80, 24);
        assertThat(sample.ghost().style()).isEqualTo(GhostStyle.WAVEFORM_MINI);
    }

    @Test
    void clipDroppedOnTrackLaneIsValidAndHighlightsWithTint() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(
                DropTargetKind.TRACK_LANE, 320.0, "1/4",
                EnumSet.noneOf(DragModifier.class));

        assertThat(s.highlight().kind()).isEqualTo(DropTargetKind.TRACK_LANE);
        assertThat(s.highlight().valid()).isTrue();
        assertThat(s.highlight().tintRgba()).hasSize(8);
        assertThat(s.cursor()).isEqualTo(DragCursor.DEFAULT);
        assertThat(s.snap().visible()).isTrue();
        assertThat(s.snap().snappedXPx()).isEqualTo(320.0);
        assertThat(s.snap().snapValueLabel()).isEqualTo("1/4");
    }

    @Test
    void clipDroppedOnInsertSlotIsInvalidAndShowsNoDropCursor() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(
                DropTargetKind.INSERT_SLOT, 0, "off",
                EnumSet.noneOf(DragModifier.class));

        assertThat(s.highlight().kind()).isEqualTo(DropTargetKind.INSERT_SLOT);
        assertThat(s.highlight().valid()).isFalse();
        assertThat(s.highlight().tintRgba()).isEmpty();
        assertThat(s.cursor()).isEqualTo(DragCursor.NO_DROP);
        assertThat(s.snap().visible()).isFalse();
    }

    @Test
    void pluginDroppedOnInsertSlotIsValidPluginOnTrackLaneInvalid() {
        assertThat(DragVisualAdvisor.canDropOn(DragSourceKind.PLUGIN, DropTargetKind.INSERT_SLOT))
                .isTrue();
        assertThat(DragVisualAdvisor.canDropOn(DragSourceKind.PLUGIN, DropTargetKind.SEND_SLOT))
                .isTrue();
        assertThat(DragVisualAdvisor.canDropOn(DragSourceKind.PLUGIN, DropTargetKind.TRACK_LANE))
                .isFalse();
        assertThat(DragVisualAdvisor.canDropOn(DragSourceKind.SAMPLE, DropTargetKind.TRACK_LANE))
                .isTrue();
        assertThat(DragVisualAdvisor.canDropOn(DragSourceKind.SAMPLE, DropTargetKind.INSERT_SLOT))
                .isFalse();
    }

    @Test
    void duplicateModifierShowsCopyCursorOnValidTarget() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(DropTargetKind.TRACK_LANE,
                100.0, "Bar", EnumSet.of(DragModifier.DUPLICATE));

        assertThat(s.cursor()).isEqualTo(DragCursor.COPY);
    }

    @Test
    void linkModifierShowsLinkCursor() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(DropTargetKind.TRACK_LANE,
                0, "1/8", EnumSet.of(DragModifier.LINK));

        assertThat(s.cursor()).isEqualTo(DragCursor.LINK);
    }

    @Test
    void shiftDisablesSnapAndShowsNoSnapCursor() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(DropTargetKind.TRACK_LANE,
                123.0, "1/16", EnumSet.of(DragModifier.DISABLE_SNAP));

        assertThat(s.cursor()).isEqualTo(DragCursor.NO_SNAP);
        assertThat(s.snap().visible()).isFalse();
    }

    @Test
    void invalidTargetOverridesAnyModifierCursor() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(DropTargetKind.INSERT_SLOT,
                0, "off",
                EnumSet.of(DragModifier.DUPLICATE, DragModifier.LINK));

        assertThat(s.cursor()).isEqualTo(DragCursor.NO_DROP);
    }

    @Test
    void duplicateBeatsLinkBeatsDisableSnapInCursorPriority() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState all = advisor.update(DropTargetKind.TRACK_LANE,
                0, "1/4",
                EnumSet.of(DragModifier.DUPLICATE,
                           DragModifier.LINK,
                           DragModifier.DISABLE_SNAP));
        assertThat(all.cursor()).isEqualTo(DragCursor.COPY);

        DragVisualState linkAndShift = advisor.update(DropTargetKind.TRACK_LANE,
                0, "1/4",
                EnumSet.of(DragModifier.LINK, DragModifier.DISABLE_SNAP));
        assertThat(linkAndShift.cursor()).isEqualTo(DragCursor.LINK);
    }

    @Test
    void escCancelRevertsToOriginAndUsesProfileDuration() {
        AnimationProfile profile = new AnimationProfile(
                Duration.ofMillis(50), Duration.ofMillis(50),
                Duration.ofMillis(220), Duration.ofMillis(80), 0.5);
        DragVisualAdvisor advisor = new DragVisualAdvisor(profile);
        advisor.beginDrag(DragSourceKind.CLIP, "C", 314.0, 159.0, 80, 24);

        DragVisualAdvisor.CancelRevert revert = advisor.cancel();

        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.REVERTING);
        assertThat(revert.targetX()).isEqualTo(314.0);
        assertThat(revert.targetY()).isEqualTo(159.0);
        assertThat(revert.duration()).isEqualTo(Duration.ofMillis(220));

        advisor.revertCompleted();
        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.IDLE);
        assertThat(advisor.sourceOrigin()).isEmpty();
    }

    @Test
    void commitClearsState() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.PLUGIN, "Reverb", 0, 0, 100, 40);
        advisor.commit();
        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.IDLE);
        assertThat(advisor.sourceOrigin()).isEmpty();
    }

    @Test
    void doubleBeginDragRejected() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);
        assertThatIllegalStateException().isThrownBy(() ->
                advisor.beginDrag(DragSourceKind.CLIP, "C2", 0, 0, 80, 24));
    }

    @Test
    void updateBeforeBeginRejected() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        assertThatIllegalStateException().isThrownBy(() -> advisor.update(
                DropTargetKind.TRACK_LANE, 0, "1/4",
                Set.of()));
    }

    @Test
    void cancelBeforeBeginRejected() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        assertThatIllegalStateException().isThrownBy(advisor::cancel);
    }

    @Test
    void invalidGhostSizeRejected() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        assertThatIllegalArgumentException().isThrownBy(() -> advisor.beginDrag(
                DragSourceKind.CLIP, "C", 0, 0, 0, 24));
    }

    @Test
    void noTargetEmitsNoneHighlightAndDefaultCursor() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState s = advisor.update(DropTargetKind.NONE, 0, "off",
                EnumSet.noneOf(DragModifier.class));

        assertThat(s.highlight()).isEqualTo(DropTargetHighlight.NONE);
        assertThat(s.cursor()).isEqualTo(DragCursor.NO_DROP);
        assertThat(s.snap().visible()).isFalse();
    }

    @Test
    void animationProfileSharedBetweenAdvisorAndCancelRevert() {
        AnimationProfile profile = AnimationProfile.DEFAULT;
        DragVisualAdvisor advisor = new DragVisualAdvisor(profile);
        assertThat(advisor.profile()).isSameAs(profile);
    }

    @Test
    void animationProfileDefaultsAreSensible() {
        AnimationProfile p = AnimationProfile.DEFAULT;
        assertThat(p.ghostOpacity()).isBetween(0.3, 0.8);
        assertThat(p.ghostFadeIn()).isLessThan(p.cancelRevert());
        assertThat(p.cancelRevert().toMillis()).isPositive();
    }

    @Test
    void animationProfileRejectsNegativeDurationAndOutOfRangeOpacity() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AnimationProfile(Duration.ofMillis(-1), Duration.ZERO,
                        Duration.ZERO, Duration.ZERO, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AnimationProfile(Duration.ZERO, Duration.ZERO,
                        Duration.ZERO, Duration.ZERO, 1.5));
    }
}
