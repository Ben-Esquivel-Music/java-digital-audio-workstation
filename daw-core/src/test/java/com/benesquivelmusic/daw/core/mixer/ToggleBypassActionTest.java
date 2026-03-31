package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToggleBypassActionTest {

    @Test
    void shouldToggleBypassState() {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(slot);

        ToggleBypassAction action = new ToggleBypassAction(channel, 0, true);
        action.execute();

        assertThat(slot.isBypassed()).isTrue();
    }

    @Test
    void shouldUndoBypassToggle() {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(slot);

        ToggleBypassAction action = new ToggleBypassAction(channel, 0, true);
        action.execute();
        assertThat(slot.isBypassed()).isTrue();

        action.undo();
        assertThat(slot.isBypassed()).isFalse();
    }

    @Test
    void shouldPreservePreviousState() {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.REVERB, 2, 44100.0);
        channel.addInsert(slot);
        channel.setInsertBypassed(0, true);

        ToggleBypassAction action = new ToggleBypassAction(channel, 0, false);
        action.execute();
        assertThat(slot.isBypassed()).isFalse();

        action.undo();
        assertThat(slot.isBypassed()).isTrue();
    }

    @Test
    void shouldHaveDescription() {
        MixerChannel channel = new MixerChannel("Test");
        channel.addInsert(InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0));

        ToggleBypassAction action = new ToggleBypassAction(channel, 0, true);
        assertThat(action.description()).isEqualTo("Toggle Bypass");
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new ToggleBypassAction(null, 0, true))
                .isInstanceOf(NullPointerException.class);
    }
}
