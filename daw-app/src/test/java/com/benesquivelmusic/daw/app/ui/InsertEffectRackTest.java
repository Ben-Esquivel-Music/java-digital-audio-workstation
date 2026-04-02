package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class InsertEffectRackTest {

    private InsertEffectRack createOnFxThread(MixerChannel channel, UndoManager undoManager)
            throws Exception {
        AtomicReference<InsertEffectRack> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new InsertEffectRack(channel, 2, 44100.0, undoManager));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNullChannel() {
        assertThatThrownBy(() -> new InsertEffectRack(null, 2, 44100.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRenderEmptySlotsForNewChannel() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertEffectRack rack = createOnFxThread(channel, null);

        assertThat(rack).isNotNull();
        // 1 header label + 8 empty slots = 9 children
        assertThat(rack.getChildren()).hasSize(9);
    }

    @Test
    void shouldRenderPopulatedAndEmptySlots() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(slot);

        InsertEffectRack rack = createOnFxThread(channel, null);

        assertThat(rack).isNotNull();
        // 1 header + 1 populated + 7 empty = 9 children
        assertThat(rack.getChildren()).hasSize(9);
    }

    @Test
    void shouldRebuildSlotsAfterAddingEffect() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertEffectRack rack = createOnFxThread(channel, null);

        // Add an effect externally and rebuild
        channel.addInsert(InsertEffectFactory.createSlot(
                InsertEffectType.REVERB, 2, 44100.0));
        runOnFxThread(rack::rebuildSlots);

        // Still 9 children (1 header + 1 populated + 7 empty)
        assertThat(rack.getChildren()).hasSize(9);
        assertThat(channel.getInsertCount()).isEqualTo(1);
    }

    @Test
    void shouldHaveInsertEffectsRackStyleClass() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertEffectRack rack = createOnFxThread(channel, null);

        assertThat(rack.getStyleClass()).contains("insert-effects-rack");
    }

    @Test
    void shouldWorkWithUndoManager() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        UndoManager undoManager = new UndoManager();
        InsertEffectRack rack = createOnFxThread(channel, undoManager);

        assertThat(rack).isNotNull();
        assertThat(rack.getChannel()).isSameAs(channel);
    }

    @Test
    void shouldWireEffectsToChannelEffectsChain() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(slot);

        InsertEffectRack rack = createOnFxThread(channel, null);

        // The effects chain should contain the compressor processor
        assertThat(channel.getEffectsChain().getProcessors()).hasSize(1);
        assertThat(channel.getEffectsChain().getProcessors().getFirst())
                .isSameAs(slot.getProcessor());
    }

    @Test
    void shouldBypassedEffectNotInChain() throws Exception {
        MixerChannel channel = new MixerChannel("Test");
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(slot);
        channel.setInsertBypassed(0, true);

        InsertEffectRack rack = createOnFxThread(channel, null);

        // Bypassed effect should not be in the processing chain
        assertThat(channel.getEffectsChain().getProcessors()).isEmpty();
    }
}
