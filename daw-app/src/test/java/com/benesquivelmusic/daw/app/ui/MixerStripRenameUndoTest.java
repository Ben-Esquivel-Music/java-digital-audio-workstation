package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round 1 (S4 / S5): a rename typed into the mixer strip's inline
 * name editor is ONE undo entry, recorded by the surface that owns the
 * {@code UndoManager} ({@code MixerView} wraps the {@code RenameTrackCommand}
 * before raising it through the intent path), and undo / redo move every
 * surface — the {@code Track}, the mixer strip and the arrangement lane's
 * label, which is a {@code TrackVM.name} subscriber. A rename the handler
 * refuses (blank) records nothing and the strip snaps back; every other strip
 * gesture passes through the sink without an entry.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerStripRenameUndoTest {

    @Test
    void aStripRenameIsOneUndoEntryThatMovesEverySurfaceAndABlankRenameRecordsNothing() throws Exception {
        DawProject project = new DawProject("Rename", Story322ContractRig.FORMAT);
        Track kick = project.createAudioTrack("Kick");

        Story322ContractRig.onFx(() -> {
            try (Story322ContractRig rig = new Story322ContractRig(project)) {
                TrackStripController.StripControls lane = rig.laneControls(kick);
                MixerChannelStrip strip = rig.strip(0);
                assertThat(strip.getChannelName()).isEqualTo("Kick");
                assertThat(lane.nameLabel().getText()).isEqualTo("Kick");
                int entriesBefore = rig.undoManager.undoSize();

                // The skin's inline editor commits into channelName.
                strip.setChannelName("Drums");
                assertThat(kick.getName()).as("the intent path renamed the Track").isEqualTo("Drums");
                assertThat(strip.getChannelName()).isEqualTo("Drums");
                assertThat(lane.nameLabel().getText()).as("arrangement lane follows TrackVM.name").isEqualTo("Drums");
                assertThat(rig.undoManager.undoSize()).as("one entry for the rename").isEqualTo(entriesBefore + 1);
                assertThat(rig.undoManager.undoDescription()).isEqualTo("Rename Track: Kick → Drums");

                assertThat(rig.undoManager.undo()).isTrue();
                assertThat(kick.getName()).as("Track after undo").isEqualTo("Kick");
                assertThat(strip.getChannelName()).as("strip after undo").isEqualTo("Kick");
                assertThat(lane.nameLabel().getText()).as("lane after undo").isEqualTo("Kick");

                assertThat(rig.undoManager.redo()).isTrue();
                assertThat(kick.getName()).as("Track after redo").isEqualTo("Drums");
                assertThat(strip.getChannelName()).as("strip after redo").isEqualTo("Drums");
                assertThat(lane.nameLabel().getText()).as("lane after redo").isEqualTo("Drums");

                // Blank: the handler's VALIDATE refuses it before any entry is
                // pushed, and the binder snaps the strip back to the VM's name.
                strip.setChannelName("   ");
                assertThat(kick.getName()).as("Track untouched by a blank rename").isEqualTo("Drums");
                assertThat(strip.getChannelName()).as("strip snapped back").isEqualTo("Drums");
                assertThat(lane.nameLabel().getText()).isEqualTo("Drums");
                assertThat(rig.undoManager.undoSize()).as("no entry for a refused rename").isEqualTo(entriesBefore + 1);

                // Every other command passes straight through: audible, no entry.
                strip.setMuted(true);
                assertThat(kick.isMuted()).isTrue();
                assertThat(project.getMixerChannelForTrack(kick).isMuted()).isTrue();
                assertThat(rig.undoManager.undoSize()).as("mute records no entry").isEqualTo(entriesBefore + 1);
            }
        });
    }

    /**
     * Story 322 fix round 2 (R2-2): a strip edit that differs from the track's
     * name only by surrounding whitespace changes nothing — the handler's
     * VALIDATE no-ops and the binder snaps the strip back — so {@code MixerView}
     * records NO entry and the redo stack survives (the round-1 wrapper pushed
     * a phantom "Rename Track: Kick → Kick" and cleared redo). A padded edit
     * that does change the name is exactly one entry whose description and
     * strip text carry the normalised name.
     */
    @Test
    void aWhitespaceOnlyRenameRecordsNoEntryAndKeepsRedoWhileAPaddedRenameIsOneNormalisedEntry()
            throws Exception {
        DawProject project = new DawProject("Rename", Story322ContractRig.FORMAT);
        Track kick = project.createAudioTrack("Kick");

        Story322ContractRig.onFx(() -> {
            try (Story322ContractRig rig = new Story322ContractRig(project)) {
                MixerChannelStrip strip = rig.strip(0);
                int entriesBefore = rig.undoManager.undoSize();

                strip.setChannelName("Drums");
                assertThat(rig.undoManager.undo()).isTrue();
                assertThat(kick.getName()).isEqualTo("Kick");
                assertThat(strip.getChannelName()).isEqualTo("Kick");
                assertThat(rig.undoManager.undoSize()).isEqualTo(entriesBefore);
                assertThat(rig.undoManager.canRedo()).as("redo available after the undo").isTrue();

                strip.setChannelName("Kick ");
                assertThat(kick.getName()).as("Track untouched by a whitespace-only edit").isEqualTo("Kick");
                assertThat(strip.getChannelName()).as("strip snapped back").isEqualTo("Kick");
                assertThat(rig.undoManager.undoSize()).as("no phantom entry").isEqualTo(entriesBefore);
                assertThat(rig.undoManager.canRedo()).as("redo stack intact").isTrue();

                strip.setChannelName(" Snare ");
                assertThat(kick.getName()).as("the intent path wrote the normalised name").isEqualTo("Snare");
                assertThat(strip.getChannelName()).as("strip shows the normalised name").isEqualTo("Snare");
                assertThat(rig.undoManager.undoSize()).as("exactly one entry").isEqualTo(entriesBefore + 1);
                assertThat(rig.undoManager.undoDescription()).isEqualTo("Rename Track: Kick → Snare");
                assertThat(rig.undoManager.canRedo()).as("a real rename branches the timeline").isFalse();
            }
        });
    }
}
