package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SlipClipAction}. Verifies that execute / undo correctly
 * snapshot and restore {@code sourceOffsetBeats} and leave the timeline
 * position and duration untouched.
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
class SlipClipActionTest {

    @Test
    void executeSetsSourceOffsetAndUndoRestoresIt() {
        AudioClip clip = new AudioClip("vox", 8.0, 4.0, null);
        clip.setSourceOffsetBeats(2.0);

        SlipClipAction action = new SlipClipAction(clip, 3.5);
        action.execute();
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(3.5);

        action.undo();
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void slipDoesNotChangeStartBeatOrDuration() {
        AudioClip clip = new AudioClip("drums", 12.0, 8.0, null);
        clip.setSourceOffsetBeats(1.0);

        new SlipClipAction(clip, 5.0).execute();

        assertThat(clip.getStartBeat()).isEqualTo(12.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getEndBeat()).isEqualTo(20.0);
    }

    @Test
    void redoAfterUndoReappliesOffset() {
        AudioClip clip = new AudioClip("guitar", 0.0, 2.0, null);
        clip.setSourceOffsetBeats(0.5);

        SlipClipAction action = new SlipClipAction(clip, 1.5);
        action.execute();
        action.undo();
        action.execute();

        assertThat(clip.getSourceOffsetBeats()).isEqualTo(1.5);
    }

    @Test
    void descriptionIsStable() {
        AudioClip clip = new AudioClip("pad", 0.0, 1.0, null);
        assertThat(new SlipClipAction(clip, 0.0).description())
                .isEqualTo("Slip Clip");
    }
}
