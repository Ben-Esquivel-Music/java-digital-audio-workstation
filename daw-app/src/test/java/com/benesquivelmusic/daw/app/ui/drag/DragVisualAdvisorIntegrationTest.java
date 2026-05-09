package com.benesquivelmusic.daw.app.ui.drag;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless integration tests for the {@link DragVisualAdvisor} additions
 * required by the user-story-197 wiring (issue: "Integrate
 * DragVisualAdvisor into Clip / Plugin / Sample Drag Sources").
 *
 * <p>The wiring tests for the actual UI call sites
 * ({@code ClipInteractionController}, {@code InsertEffectRack},
 * {@code BrowserPanel}) require a JavaFX scene graph; per the issue
 * note ("daw-app tests require a screen … otherwise tests get stuck")
 * they are exercised manually. The pure-Java contract that the call
 * sites depend on — accessors for the current state, source kind, and
 * ghost preview — is verified here without any JavaFX dependency.</p>
 */
class DragVisualAdvisorIntegrationTest {

    /**
     * The issue's primary acceptance criterion: when a clip drag starts,
     * the shared advisor reports {@code dragging}, source kind
     * {@code CLIP}, and exposes a non-null ghost preview.
     */
    @Test
    void clipDragMakesAdvisorReportDraggingWithClipKindAndNonNullGhost() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();

        advisor.beginDrag(DragSourceKind.CLIP, "Drum Loop", 100, 200, 80, 24);

        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.DRAGGING);
        assertThat(advisor.currentSourceKind()).contains(DragSourceKind.CLIP);
        assertThat(advisor.currentVisualState()).isPresent();
        assertThat(advisor.currentVisualState().orElseThrow().ghost()).isNotNull();
        assertThat(advisor.currentVisualState().orElseThrow().ghost().sourceKind())
                .isEqualTo(DragSourceKind.CLIP);
    }

    /**
     * Valid drop target (track lane for a clip drag) gains the highlight
     * with a non-empty tint while an invalid target (insert slot) shows
     * the {@code NO_DROP} cursor — i.e. the call site can ask the
     * advisor whether to render a {@code drop-target-active} class or
     * a {@code no-drop} cursor.
     */
    @Test
    void validTargetHighlightsAndInvalidTargetShowsNoDropCursor() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        DragVisualState valid = advisor.update(DropTargetKind.TRACK_LANE,
                100.0, "1/4", EnumSet.noneOf(DragModifier.class));
        assertThat(valid.highlight().valid()).isTrue();
        assertThat(valid.highlight().tintRgba()).isNotEmpty();
        assertThat(valid.cursor()).isEqualTo(DragCursor.DEFAULT);

        DragVisualState invalid = advisor.update(DropTargetKind.INSERT_SLOT,
                0, "off", EnumSet.noneOf(DragModifier.class));
        assertThat(invalid.highlight().valid()).isFalse();
        assertThat(invalid.cursor()).isEqualTo(DragCursor.NO_DROP);

        // currentVisualState() should reflect the most recent update.
        assertThat(advisor.currentVisualState().orElseThrow().cursor())
                .isEqualTo(DragCursor.NO_DROP);
    }

    /**
     * Esc cancellation: the call site (e.g. {@code ClipInteractionController})
     * runs the cancel-revert flow. The advisor reports the fade duration
     * sourced from the shared {@link AnimationProfile} so the same profile
     * is used across the app.
     */
    @Test
    void escCancelRevertsAndReportsProfileFadeDuration() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 314.0, 159.0, 80, 24);

        DragVisualAdvisor.CancelRevert revert = advisor.cancel();

        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.REVERTING);
        assertThat(revert.targetX()).isEqualTo(314.0);
        assertThat(revert.targetY()).isEqualTo(159.0);
        assertThat(revert.duration())
                .isEqualTo(AnimationProfile.DEFAULT.cancelRevert());

        advisor.revertCompleted();
        assertThat(advisor.state()).isEqualTo(DragVisualAdvisor.State.IDLE);
        assertThat(advisor.currentVisualState()).isEmpty();
        assertThat(advisor.currentSourceKind()).isEmpty();
    }

    /**
     * Sample drag: the {@code BrowserPanel} call site uses
     * {@code DragSourceKind.SAMPLE}; valid target is a track lane.
     */
    @Test
    void sampleDragOverTrackLaneIsValidWithMiniWaveformGhost() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.SAMPLE, "kick.wav", 0, 0, 80, 24);

        assertThat(advisor.currentSourceKind()).contains(DragSourceKind.SAMPLE);
        assertThat(advisor.currentVisualState().orElseThrow().ghost().style())
                .isEqualTo(GhostStyle.WAVEFORM_MINI);

        DragVisualState s = advisor.update(DropTargetKind.TRACK_LANE,
                10, "1/4", EnumSet.noneOf(DragModifier.class));
        assertThat(s.highlight().valid()).isTrue();
    }

    /**
     * Plugin drag: the {@code InsertEffectRack} call site uses
     * {@code DragSourceKind.PLUGIN}; valid targets are insert and send
     * slots; track lane is invalid.
     */
    @Test
    void pluginDragOverInsertSlotIsValidWithPluginCardGhost() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.PLUGIN, "Reverb", 0, 0, 100, 40);

        assertThat(advisor.currentSourceKind()).contains(DragSourceKind.PLUGIN);
        assertThat(advisor.currentVisualState().orElseThrow().ghost().style())
                .isEqualTo(GhostStyle.PLUGIN_CARD);

        DragVisualState insert = advisor.update(DropTargetKind.INSERT_SLOT,
                0, "off", EnumSet.noneOf(DragModifier.class));
        assertThat(insert.highlight().valid()).isTrue();

        DragVisualState lane = advisor.update(DropTargetKind.TRACK_LANE,
                0, "off", EnumSet.noneOf(DragModifier.class));
        assertThat(lane.highlight().valid()).isFalse();
        assertThat(lane.cursor()).isEqualTo(DragCursor.NO_DROP);
    }

    /**
     * Modifier-key cursors documented in the issue: Ctrl→duplicate (copy),
     * Alt→link, Shift→no-snap. The advisor's
     * {@link DragVisualAdvisor#update} is the single source of truth so
     * every call site reports the same cursor for a given modifier set.
     */
    @Test
    void modifierKeysProduceExpectedCursors() {
        DragVisualAdvisor advisor = new DragVisualAdvisor();
        advisor.beginDrag(DragSourceKind.CLIP, "C", 0, 0, 80, 24);

        assertThat(advisor.update(DropTargetKind.TRACK_LANE, 0, "1/4",
                EnumSet.of(DragModifier.DUPLICATE)).cursor())
                .isEqualTo(DragCursor.COPY);
        assertThat(advisor.update(DropTargetKind.TRACK_LANE, 0, "1/4",
                EnumSet.of(DragModifier.LINK)).cursor())
                .isEqualTo(DragCursor.LINK);
        assertThat(advisor.update(DropTargetKind.TRACK_LANE, 0, "1/4",
                EnumSet.of(DragModifier.DISABLE_SNAP)).cursor())
                .isEqualTo(DragCursor.NO_SNAP);
    }
}
