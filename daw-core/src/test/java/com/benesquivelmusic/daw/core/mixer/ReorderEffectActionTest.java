package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReorderEffectActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        MixerChannel channel = new MixerChannel("Drums");
        assertThat(new ReorderEffectAction(channel, 0, 1).description())
                .isEqualTo("Reorder Effect");
    }

    @Test
    void shouldReorderEffectsOnExecute() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);

        ReorderEffectAction action = new ReorderEffectAction(channel, 0, 1);
        action.execute();

        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("EQ");
        assertThat(channel.getInsertSlot(1).getName()).isEqualTo("Compressor");
    }

    @Test
    void shouldRestoreOrderOnUndo() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);

        ReorderEffectAction action = new ReorderEffectAction(channel, 0, 1);
        action.execute();
        action.undo();

        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("Compressor");
        assertThat(channel.getInsertSlot(1).getName()).isEqualTo("EQ");
    }

    @Test
    void shouldWorkWithUndoManager() {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new ReorderEffectAction(channel, 0, 1));
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("EQ");

        undoManager.undo();
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("Compressor");

        undoManager.redo();
        assertThat(channel.getInsertSlot(0).getName()).isEqualTo("EQ");
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new ReorderEffectAction(null, 0, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPublishPluginUnloadedAndLoadedOnExecuteAndUndo() throws Exception {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        InsertSlot eq = new InsertSlot("EQ", createDummyProcessor());
        channel.addInsert(comp);
        channel.addInsert(eq);

        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            List<PluginEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(4);
            bus.on(PluginEvent.class, e -> {
                events.add(e);
                latch.countDown();
            });

            ReorderEffectAction action = new ReorderEffectAction(channel, 0, 1);
            action.execute();
            action.undo();

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).hasSize(4);
            // Execute: Unloaded + Loaded for comp (moved from 0 to 1)
            assertThat(events.get(0)).isInstanceOf(PluginEvent.Unloaded.class);
            assertThat(events.get(0).pluginInstanceId())
                    .isEqualTo(comp.getPluginInstanceId());
            assertThat(events.get(1)).isInstanceOf(PluginEvent.Loaded.class);
            assertThat(events.get(1).pluginInstanceId())
                    .isEqualTo(comp.getPluginInstanceId());
            // Undo: Unloaded + Loaded for comp (moved back from 1 to 0)
            assertThat(events.get(2)).isInstanceOf(PluginEvent.Unloaded.class);
            assertThat(events.get(2).pluginInstanceId())
                    .isEqualTo(comp.getPluginInstanceId());
            assertThat(events.get(3)).isInstanceOf(PluginEvent.Loaded.class);
            assertThat(events.get(3).pluginInstanceId())
                    .isEqualTo(comp.getPluginInstanceId());
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void shouldNotPublishEventsForNoOpReorder() throws Exception {
        MixerChannel channel = new MixerChannel("Drums");
        InsertSlot comp = new InsertSlot("Compressor", createDummyProcessor());
        channel.addInsert(comp);

        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            List<PluginEvent> events = new CopyOnWriteArrayList<>();
            bus.on(PluginEvent.class, events::add);

            ReorderEffectAction action = new ReorderEffectAction(channel, 0, 0);
            action.execute();
            action.undo();

            // Give a short window for any spurious events to arrive
            Thread.sleep(100);
            assertThat(events).isEmpty();
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    private static AudioProcessor createDummyProcessor() {
        return new AudioProcessor() {
            @Override public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) { }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
        };
    }
}
