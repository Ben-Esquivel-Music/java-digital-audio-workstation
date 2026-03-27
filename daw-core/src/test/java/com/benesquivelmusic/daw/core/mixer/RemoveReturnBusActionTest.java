package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveReturnBusActionTest {

    @Test
    void shouldRemoveReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        int countAfterAdd = mixer.getReturnBusCount();

        RemoveReturnBusAction action = new RemoveReturnBusAction(mixer, delayBus);
        action.execute();

        assertThat(mixer.getReturnBusCount()).isEqualTo(countAfterAdd - 1);
        assertThat(mixer.getReturnBuses()).doesNotContain(delayBus);
    }

    @Test
    void shouldUndoRemoveReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        int countBeforeRemove = mixer.getReturnBusCount();

        RemoveReturnBusAction action = new RemoveReturnBusAction(mixer, delayBus);
        action.execute();
        assertThat(mixer.getReturnBusCount()).isEqualTo(countBeforeRemove - 1);

        action.undo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(countBeforeRemove);
        assertThat(mixer.getReturnBuses()).contains(delayBus);
    }

    @Test
    void shouldRestoreSendsOnUndo() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");

        MixerChannel ch1 = new MixerChannel("Ch1");
        Send send1 = new Send(delayBus, 0.5, SendMode.POST_FADER);
        ch1.addSend(send1);
        mixer.addChannel(ch1);

        MixerChannel ch2 = new MixerChannel("Ch2");
        Send send2 = new Send(delayBus, 0.3, SendMode.PRE_FADER);
        ch2.addSend(send2);
        mixer.addChannel(ch2);

        RemoveReturnBusAction action = new RemoveReturnBusAction(mixer, delayBus);
        action.execute();

        // Sends should be removed
        assertThat(ch1.getSends()).isEmpty();
        assertThat(ch2.getSends()).isEmpty();

        action.undo();

        // Sends should be restored
        assertThat(ch1.getSends()).hasSize(1);
        assertThat(ch1.getSendForTarget(delayBus)).isNotNull();
        assertThat(ch1.getSendForTarget(delayBus).getLevel()).isEqualTo(0.5);

        assertThat(ch2.getSends()).hasSize(1);
        assertThat(ch2.getSendForTarget(delayBus)).isNotNull();
        assertThat(ch2.getSendForTarget(delayBus).getMode()).isEqualTo(SendMode.PRE_FADER);
    }

    @Test
    void shouldHaveCorrectDescription() {
        Mixer mixer = new Mixer();
        MixerChannel bus = mixer.addReturnBus("Bus");
        RemoveReturnBusAction action = new RemoveReturnBusAction(mixer, bus);

        assertThat(action.description()).isEqualTo("Remove Return Bus");
    }

    @Test
    void shouldWorkWithUndoManager() {
        Mixer mixer = new Mixer();
        UndoManager undoManager = new UndoManager();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        int countBeforeRemove = mixer.getReturnBusCount();

        RemoveReturnBusAction action = new RemoveReturnBusAction(mixer, delayBus);
        undoManager.execute(action);
        assertThat(mixer.getReturnBusCount()).isEqualTo(countBeforeRemove - 1);

        undoManager.undo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(countBeforeRemove);

        undoManager.redo();
        assertThat(mixer.getReturnBusCount()).isEqualTo(countBeforeRemove - 1);
    }
}
