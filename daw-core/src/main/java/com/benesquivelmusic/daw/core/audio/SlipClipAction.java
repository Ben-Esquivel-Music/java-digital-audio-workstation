package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.clip.LockedClipException;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that slips an {@link AudioClip}'s source-offset window
 * without moving the clip on the timeline.
 *
 * <p>Slip editing slides the audio content inside a clip's fixed boundaries:
 * the clip's {@code startBeat} and {@code durationBeats} are left untouched
 * while {@code sourceOffsetBeats} shifts by the given delta. This is the
 * Pro-Tools "Slip" / Reaper "shift-drag contents" / Cubase
 * "Object Selection + drag-contents" primitive.</p>
 *
 * <p>The caller is responsible for clamping the delta so the resulting source
 * window stays inside the source audio — see
 * {@code SlipEditService.buildAudioSlip} in
 * {@code com.benesquivelmusic.daw.core.project.edit}. This action simply
 * applies the delta it was given and records the previous offset for undo.</p>
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
public final class SlipClipAction implements UndoableAction {

    private final AudioClip clip;
    private final double newSourceOffsetBeats;
    private double previousSourceOffsetBeats;

    /**
     * Creates a new slip action.
     *
     * @param clip                 the clip whose source offset is being slipped
     * @param newSourceOffsetBeats the new, already-clamped source offset in beats
     * @throws NullPointerException if {@code clip} is {@code null}
     */
    public SlipClipAction(AudioClip clip, double newSourceOffsetBeats) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newSourceOffsetBeats = newSourceOffsetBeats;
    }

    @Override
    public String description() {
        return "Slip Clip";
    }

    @Override
    public void execute() {
        LockedClipException.requireUnlocked("Slip", clip);
        previousSourceOffsetBeats = clip.getSourceOffsetBeats();
        clip.setSourceOffsetBeats(newSourceOffsetBeats);
    }

    @Override
    public void undo() {
        clip.setSourceOffsetBeats(previousSourceOffsetBeats);
    }
}
