package com.benesquivelmusic.daw.app.ui.inspector;

import java.util.UUID;

/**
 * Sealed selection type for the unified Inspector drawer (UI Design
 * Book §5.6, story 272).
 *
 * <p>The Inspector is the single answer to "what was just selected?" —
 * every selectable surface (arrangement-view track, clip, mixer insert,
 * mixer send, bus) publishes a typed selection through the
 * {@link InspectorSelectionModel}. The model holds exactly one
 * selection at a time; the {@link Empty} singleton represents the
 * "no selection" placeholder state from §5.6.
 *
 * <p>Per the issue, this lives as a simple sealed interface in this PR
 * until story 202 lands its canonical selection sealed type at the
 * domain layer; the variants below mirror the IDs already used by the
 * source-side controls (story 270 {@code TrackStrip}, story 271
 * {@code MixerChannelStrip}, {@code ClipInteractionController}).
 */
public sealed interface InspectorSelection
        permits InspectorSelection.TrackSelection,
                InspectorSelection.ClipSelection,
                InspectorSelection.InsertSelection,
                InspectorSelection.SendSelection,
                InspectorSelection.BusSelection,
                InspectorSelection.Empty {

    /** The arrangement-view selected a track. */
    record TrackSelection(UUID trackId) implements InspectorSelection {}

    /** The arrangement-canvas / {@code ClipInteractionController} selected a clip. */
    record ClipSelection(UUID clipId) implements InspectorSelection {}

    /** A mixer channel-strip insert slot was clicked. */
    record InsertSelection(UUID trackId, int insertIndex) implements InspectorSelection {}

    /** A mixer channel-strip send slot was clicked. */
    record SendSelection(UUID trackId, int sendIndex) implements InspectorSelection {}

    /** A bus was selected (routing matrix or master section). */
    record BusSelection(UUID busId) implements InspectorSelection {}

    /**
     * Singleton "no selection" state — the inspector body shows the
     * "{@code inspector.placeholder.empty}" placeholder string and all
     * sections collapse to their default state.
     */
    enum Empty implements InspectorSelection {
        /** Sole instance. */
        INSTANCE;
    }

    /** @return the canonical "no selection" value. */
    static InspectorSelection empty() {
        return Empty.INSTANCE;
    }
}
