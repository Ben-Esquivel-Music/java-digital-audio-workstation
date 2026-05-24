package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test
    void shouldPublishPluginBypassedOnExecuteAndUndo() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(slot);

        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            List<PluginEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);
            bus.on(PluginEvent.class, e -> {
                events.add(e);
                latch.countDown();
            });

            ToggleBypassAction action = new ToggleBypassAction(channel, 0, true);
            action.execute();
            action.undo();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(PluginEvent.Bypassed.class);
            assertThat(events.get(1)).isInstanceOf(PluginEvent.Bypassed.class);
            assertThat(((PluginEvent.Bypassed) events.get(0)).bypassed()).isTrue();
            assertThat(((PluginEvent.Bypassed) events.get(1)).bypassed()).isFalse();
            assertThat(events.get(0).pluginInstanceId())
                    .isEqualTo(slot.getPluginInstanceId());
            assertThat(events.get(1).pluginInstanceId())
                    .isEqualTo(slot.getPluginInstanceId());
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }
}
