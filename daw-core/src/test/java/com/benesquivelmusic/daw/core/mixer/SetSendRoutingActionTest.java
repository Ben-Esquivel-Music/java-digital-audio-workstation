package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetSendRoutingActionTest {

    @Test
    void shouldAddNewSend() {
        MixerChannel channel = new MixerChannel("Vocals");
        MixerChannel target = new MixerChannel("Reverb Return");

        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.5, SendMode.POST_FADER);
        action.execute();

        assertThat(channel.getSends()).hasSize(1);
        Send send = channel.getSendForTarget(target);
        assertThat(send).isNotNull();
        assertThat(send.getLevel()).isEqualTo(0.5);
        assertThat(send.getMode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void shouldUpdateExistingSend() {
        MixerChannel channel = new MixerChannel("Vocals");
        MixerChannel target = new MixerChannel("Reverb Return");
        Send existingSend = new Send(target, 0.3, SendMode.POST_FADER);
        channel.addSend(existingSend);

        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.8, SendMode.PRE_FADER);
        action.execute();

        assertThat(channel.getSends()).hasSize(1);
        Send send = channel.getSendForTarget(target);
        assertThat(send.getLevel()).isEqualTo(0.8);
        assertThat(send.getMode()).isEqualTo(SendMode.PRE_FADER);
    }

    @Test
    void shouldUndoNewSend() {
        MixerChannel channel = new MixerChannel("Vocals");
        MixerChannel target = new MixerChannel("Reverb Return");

        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.5, SendMode.POST_FADER);
        action.execute();
        assertThat(channel.getSends()).hasSize(1);

        action.undo();
        assertThat(channel.getSends()).isEmpty();
    }

    @Test
    void shouldUndoUpdateToExistingSend() {
        MixerChannel channel = new MixerChannel("Vocals");
        MixerChannel target = new MixerChannel("Reverb Return");
        Send existingSend = new Send(target, 0.3, SendMode.POST_FADER);
        channel.addSend(existingSend);

        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.8, SendMode.PRE_FADER);
        action.execute();

        action.undo();

        Send send = channel.getSendForTarget(target);
        assertThat(send).isNotNull();
        assertThat(send.getLevel()).isEqualTo(0.3);
        assertThat(send.getMode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel target = new MixerChannel("Bus");
        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.5, SendMode.POST_FADER);

        assertThat(action.description()).isEqualTo("Set Send Routing");
    }

    @Test
    void shouldRejectInvalidLevel() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel target = new MixerChannel("Bus");

        assertThatThrownBy(() -> new SetSendRoutingAction(channel, target, -0.1, SendMode.POST_FADER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SetSendRoutingAction(channel, target, 1.1, SendMode.POST_FADER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Vocals");
        MixerChannel target = new MixerChannel("Reverb Return");
        UndoManager undoManager = new UndoManager();

        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.5, SendMode.PRE_FADER);
        undoManager.execute(action);
        assertThat(channel.getSends()).hasSize(1);

        undoManager.undo();
        assertThat(channel.getSends()).isEmpty();

        undoManager.redo();
        assertThat(channel.getSends()).hasSize(1);
        assertThat(channel.getSendForTarget(target).getLevel()).isEqualTo(0.5);
    }

    @Test
    void shouldPreservePreInsertsTapWhenOnlyLevelChanges() {
        // Regression: SetSendRoutingAction used to call setMode() on every
        // execute(), and Send.setMode(PRE_FADER) collapses PRE_INSERTS back
        // to PRE_FADER. The action must skip the mode update when the
        // existing mode already matches the requested one (which is what
        // the slider's level-commit path always does).
        MixerChannel channel = new MixerChannel("Vocals");
        MixerChannel target = new MixerChannel("Cue");
        Send existing = new Send(target, 0.3, SendTap.PRE_INSERTS);
        channel.addSend(existing);

        // The MixerView slider release path passes back the existing
        // send.getMode() (which collapses PRE_INSERTS to PRE_FADER).
        SetSendRoutingAction action = new SetSendRoutingAction(
                channel, target, 0.7, existing.getMode());
        action.execute();

        Send updated = channel.getSendForTarget(target);
        assertThat(updated.getLevel()).isEqualTo(0.7);
        // The PRE_INSERTS tap must survive the level-only edit.
        assertThat(updated.getTap()).isEqualTo(SendTap.PRE_INSERTS);

        action.undo();
        Send restored = channel.getSendForTarget(target);
        assertThat(restored.getLevel()).isEqualTo(0.3);
        assertThat(restored.getTap()).isEqualTo(SendTap.PRE_INSERTS);
    }
}
