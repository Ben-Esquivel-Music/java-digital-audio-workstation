package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the three undoable VCA actions described in the issue:
 * {@link CreateVcaGroupAction}, {@link SetVcaGainAction}, and
 * {@link AssignVcaMemberAction}.
 */
class VcaGroupActionsTest {

    @Test
    void createVcaGroupActionAddsAndUndoesGroup() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        UUID snare = UUID.randomUUID();

        CreateVcaGroupAction action =
                new CreateVcaGroupAction(manager, "Drums", List.of(kick, snare));
        action.execute();

        assertThat(action.getGroup()).isNotNull();
        assertThat(manager.getVcaGroups()).hasSize(1);
        assertThat(action.getGroup().memberChannelIds()).containsExactly(kick, snare);

        action.undo();
        assertThat(manager.getVcaGroups()).isEmpty();

        // Redo preserves the same id.
        UUID idBeforeRedo = action.getGroup().id();
        action.execute();
        assertThat(manager.getVcaGroups()).hasSize(1);
        assertThat(manager.getById(idBeforeRedo)).isNotNull();
    }

    @Test
    void setVcaGainActionAppliesAndRestoresPreviousGain() {
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup drums = manager.createVcaGroup("Drums");
        manager.setMasterGainDb(drums.id(), -2.0);

        SetVcaGainAction action = new SetVcaGainAction(manager, drums.id(), 4.0);
        action.execute();
        assertThat(manager.getById(drums.id()).masterGainDb()).isEqualTo(4.0);

        action.undo();
        assertThat(manager.getById(drums.id()).masterGainDb()).isEqualTo(-2.0);

        action.execute();
        assertThat(manager.getById(drums.id()).masterGainDb()).isEqualTo(4.0);
    }

    @Test
    void assignVcaMemberActionAssignsAndUndoesMembership() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        VcaGroup drums = manager.createVcaGroup("Drums");

        AssignVcaMemberAction assign =
                new AssignVcaMemberAction(manager, drums.id(), kick, true);
        assign.execute();
        assertThat(manager.getById(drums.id()).hasMember(kick)).isTrue();

        assign.undo();
        assertThat(manager.getById(drums.id()).hasMember(kick)).isFalse();
    }

    @Test
    void assignVcaMemberActionRemovesAndRestoresMembership() {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        VcaGroup drums = manager.createVcaGroup("Drums", List.of(kick));

        AssignVcaMemberAction unassign =
                new AssignVcaMemberAction(manager, drums.id(), kick, false);
        unassign.execute();
        assertThat(manager.getById(drums.id()).hasMember(kick)).isFalse();

        unassign.undo();
        assertThat(manager.getById(drums.id()).hasMember(kick)).isTrue();
    }

    @Test
    void createVcaGroupActionAppliesColorAndPreservesAcrossUndoRedo() {
        // The 4-arg (manager, name, color, members) constructor matches the
        // call shape in the Story 153 issue: the right-click "Create VCA
        // from selection" UX prompts for a name+color and dispatches this
        // exact action through the UndoManager.
        VcaGroupManager manager = new VcaGroupManager();
        UUID kick = UUID.randomUUID();
        com.benesquivelmusic.daw.core.track.TrackColor color =
                com.benesquivelmusic.daw.core.track.TrackColor.MAGENTA;

        CreateVcaGroupAction action = new CreateVcaGroupAction(
                manager, "Drums", color, List.of(kick));
        action.execute();

        VcaGroup created = action.getGroup();
        assertThat(created).isNotNull();
        assertThat(created.color()).isEqualTo(color);
        assertThat(created.memberChannelIds()).containsExactly(kick);

        // Undo removes the group; redo restores it with the same id and color.
        action.undo();
        assertThat(manager.getVcaGroups()).isEmpty();
        action.execute();
        VcaGroup redone = manager.getById(created.id());
        assertThat(redone).isNotNull();
        assertThat(redone.color()).isEqualTo(color);
    }
}
