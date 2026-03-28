package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerChannelTest {

    @Test
    void shouldCreateChannelWithDefaults() {
        MixerChannel channel = new MixerChannel("Guitar");

        assertThat(channel.getName()).isEqualTo("Guitar");
        assertThat(channel.getVolume()).isEqualTo(1.0);
        assertThat(channel.getPan()).isEqualTo(0.0);
        assertThat(channel.isMuted()).isFalse();
        assertThat(channel.isSolo()).isFalse();
        assertThat(channel.isPhaseInverted()).isFalse();
    }

    @Test
    void shouldSetVolumeWithinRange() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.setVolume(0.0);
        assertThat(channel.getVolume()).isEqualTo(0.0);
        channel.setVolume(1.0);
        assertThat(channel.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidVolume() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setVolume(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetPanWithinRange() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.setPan(-1.0);
        assertThat(channel.getPan()).isEqualTo(-1.0);
        channel.setPan(1.0);
        assertThat(channel.getPan()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidPan() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setPan(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setPan(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefaultSendLevelToZero() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThat(channel.getSendLevel()).isEqualTo(0.0);
    }

    @Test
    void shouldSetSendLevelWithinRange() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.setSendLevel(0.0);
        assertThat(channel.getSendLevel()).isEqualTo(0.0);
        channel.setSendLevel(0.5);
        assertThat(channel.getSendLevel()).isEqualTo(0.5);
        channel.setSendLevel(1.0);
        assertThat(channel.getSendLevel()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidSendLevel() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setSendLevel(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setSendLevel(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTogglePhaseInverted() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThat(channel.isPhaseInverted()).isFalse();
        channel.setPhaseInverted(true);
        assertThat(channel.isPhaseInverted()).isTrue();
        channel.setPhaseInverted(false);
        assertThat(channel.isPhaseInverted()).isFalse();
    }

    // ── Send management tests ───────────────────────────────────────────────

    @Test
    void shouldStartWithEmptySends() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThat(channel.getSends()).isEmpty();
    }

    @Test
    void shouldAddSend() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target, 0.5, SendMode.POST_FADER);

        channel.addSend(send);

        assertThat(channel.getSends()).hasSize(1);
        assertThat(channel.getSends().get(0)).isSameAs(send);
    }

    @Test
    void shouldRemoveSend() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target, 0.5, SendMode.POST_FADER);
        channel.addSend(send);

        boolean removed = channel.removeSend(send);

        assertThat(removed).isTrue();
        assertThat(channel.getSends()).isEmpty();
    }

    @Test
    void shouldGetSendForTarget() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel bus1 = new MixerChannel("Bus1");
        MixerChannel bus2 = new MixerChannel("Bus2");
        Send send1 = new Send(bus1, 0.3, SendMode.POST_FADER);
        Send send2 = new Send(bus2, 0.7, SendMode.PRE_FADER);
        channel.addSend(send1);
        channel.addSend(send2);

        assertThat(channel.getSendForTarget(bus1)).isSameAs(send1);
        assertThat(channel.getSendForTarget(bus2)).isSameAs(send2);
    }

    @Test
    void shouldReturnNullForUnknownSendTarget() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel unknownBus = new MixerChannel("Unknown");

        assertThat(channel.getSendForTarget(unknownBus)).isNull();
    }

    @Test
    void shouldReturnUnmodifiableSendList() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThatThrownBy(() -> channel.getSends().add(new Send(new MixerChannel("Bus"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullSend() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThatThrownBy(() -> channel.addSend(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Insert effect slot management tests ─────────────────────────────────

    @Test
    void shouldStartWithEmptyInserts() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThat(channel.getInsertSlots()).isEmpty();
        assertThat(channel.getInsertCount()).isZero();
        assertThat(channel.getEffectsChain().isEmpty()).isTrue();
    }

    @Test
    void shouldAddInsertSlot() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot = new InsertSlot("Compressor", new GainProcessor(0.5f));

        channel.addInsert(slot);

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getInsertSlot(0)).isSameAs(slot);
        assertThat(channel.getEffectsChain().size()).isEqualTo(1);
    }

    @Test
    void shouldInsertSlotAtIndex() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot1 = new InsertSlot("EQ", new GainProcessor(0.8f));
        InsertSlot slot2 = new InsertSlot("Compressor", new GainProcessor(0.5f));
        channel.addInsert(slot1);

        channel.insertInsert(0, slot2);

        assertThat(channel.getInsertCount()).isEqualTo(2);
        assertThat(channel.getInsertSlot(0)).isSameAs(slot2);
        assertThat(channel.getInsertSlot(1)).isSameAs(slot1);
    }

    @Test
    void shouldRemoveInsertByIndex() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot = new InsertSlot("EQ", new GainProcessor(0.8f));
        channel.addInsert(slot);

        InsertSlot removed = channel.removeInsert(0);

        assertThat(removed).isSameAs(slot);
        assertThat(channel.getInsertCount()).isZero();
        assertThat(channel.getEffectsChain().isEmpty()).isTrue();
    }

    @Test
    void shouldRemoveInsertByReference() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot = new InsertSlot("Reverb", new GainProcessor(0.3f));
        channel.addInsert(slot);

        boolean removed = channel.removeInsert(slot);

        assertThat(removed).isTrue();
        assertThat(channel.getInsertCount()).isZero();
    }

    @Test
    void shouldMoveInsert() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot1 = new InsertSlot("EQ", new GainProcessor(0.8f));
        InsertSlot slot2 = new InsertSlot("Compressor", new GainProcessor(0.5f));
        InsertSlot slot3 = new InsertSlot("Limiter", new GainProcessor(0.9f));
        channel.addInsert(slot1);
        channel.addInsert(slot2);
        channel.addInsert(slot3);

        channel.moveInsert(2, 0);

        assertThat(channel.getInsertSlot(0)).isSameAs(slot3);
        assertThat(channel.getInsertSlot(1)).isSameAs(slot1);
        assertThat(channel.getInsertSlot(2)).isSameAs(slot2);
    }

    @Test
    void shouldRejectMoveWithInvalidIndices() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.addInsert(new InsertSlot("EQ", new GainProcessor(0.8f)));

        assertThatThrownBy(() -> channel.moveInsert(-1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> channel.moveInsert(0, 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldBypassInsertAndUpdateEffectsChain() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot1 = new InsertSlot("EQ", new GainProcessor(0.8f));
        InsertSlot slot2 = new InsertSlot("Compressor", new GainProcessor(0.5f));
        channel.addInsert(slot1);
        channel.addInsert(slot2);

        assertThat(channel.getEffectsChain().size()).isEqualTo(2);

        channel.setInsertBypassed(0, true);

        assertThat(slot1.isBypassed()).isTrue();
        assertThat(channel.getEffectsChain().size()).isEqualTo(1);
        assertThat(channel.getEffectsChain().getProcessors().get(0))
                .isSameAs(slot2.getProcessor());
    }

    @Test
    void shouldUnbypassInsertAndUpdateEffectsChain() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot = new InsertSlot("EQ", new GainProcessor(0.8f));
        channel.addInsert(slot);
        channel.setInsertBypassed(0, true);

        assertThat(channel.getEffectsChain().isEmpty()).isTrue();

        channel.setInsertBypassed(0, false);

        assertThat(channel.getEffectsChain().size()).isEqualTo(1);
    }

    @Test
    void shouldEnforceMaxInsertSlots() {
        MixerChannel channel = new MixerChannel("Ch");
        for (int i = 0; i < MixerChannel.MAX_INSERT_SLOTS; i++) {
            channel.addInsert(new InsertSlot("Effect " + i, new GainProcessor(0.5f)));
        }

        assertThatThrownBy(() -> channel.addInsert(new InsertSlot("Overflow", new GainProcessor(0.5f))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldEnforceMaxInsertSlotsOnInsertInsert() {
        MixerChannel channel = new MixerChannel("Ch");
        for (int i = 0; i < MixerChannel.MAX_INSERT_SLOTS; i++) {
            channel.addInsert(new InsertSlot("Effect " + i, new GainProcessor(0.5f)));
        }

        assertThatThrownBy(() -> channel.insertInsert(0, new InsertSlot("Overflow", new GainProcessor(0.5f))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectNullInsertSlot() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThatThrownBy(() -> channel.addInsert(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnUnmodifiableInsertList() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThatThrownBy(() -> channel.getInsertSlots().add(
                new InsertSlot("EQ", new GainProcessor(0.5f))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldWireEffectsChainToProcessAudio() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.addInsert(new InsertSlot("Gain 0.5", new GainProcessor(0.5f)));
        channel.addInsert(new InsertSlot("Gain 0.5", new GainProcessor(0.5f)));

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        channel.getEffectsChain().process(input, output, 1);

        assertThat(output[0][0]).isEqualTo(0.25f);
    }

    @Test
    void shouldProcessBypassedChainAsPassthrough() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.addInsert(new InsertSlot("Gain", new GainProcessor(0.5f)));
        channel.setInsertBypassed(0, true);

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        channel.getEffectsChain().process(input, output, 1);

        // Bypassed slot means chain is empty → passthrough
        assertThat(output[0][0]).isEqualTo(1.0f);
    }

    @Test
    void shouldMoveInsertNoOpWhenSameIndex() {
        MixerChannel channel = new MixerChannel("Ch");
        InsertSlot slot = new InsertSlot("EQ", new GainProcessor(0.8f));
        channel.addInsert(slot);

        channel.moveInsert(0, 0);

        assertThat(channel.getInsertSlot(0)).isSameAs(slot);
    }

    // --- Test processor ---

    private record GainProcessor(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] = inputBuffer[ch][i] * gain;
                }
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 1;
        }

        @Override
        public int getOutputChannelCount() {
            return 1;
        }
    }
}
