package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsertSlotTest {

    @Test
    void shouldCreateSlotWithDefaults() {
        AudioProcessor processor = new StubProcessor();
        InsertSlot slot = new InsertSlot("Compressor", processor);

        assertThat(slot.getName()).isEqualTo("Compressor");
        assertThat(slot.getProcessor()).isSameAs(processor);
        assertThat(slot.isBypassed()).isFalse();
    }

    @Test
    void shouldToggleBypass() {
        InsertSlot slot = new InsertSlot("EQ", new StubProcessor());

        slot.setBypassed(true);
        assertThat(slot.isBypassed()).isTrue();

        slot.setBypassed(false);
        assertThat(slot.isBypassed()).isFalse();
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new InsertSlot(null, new StubProcessor()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullProcessor() {
        assertThatThrownBy(() -> new InsertSlot("EQ", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldExposeCapabilitiesOfProcessor() {
        InsertSlot slot = new InsertSlot("Stub", new StubProcessor());

        // Capabilities are reflectively introspected and never null.
        assertThat(slot.getCapabilities()).isNotNull();
        assertThat(slot.getCapabilities().processesAudio()).isTrue();
        assertThat(slot.getCapabilities().providesSidechainInput()).isFalse();
        assertThat(slot.getCapabilities().reportsGainReduction()).isFalse();
    }

    @Test
    void shouldDefaultExpensiveToFalseForRawProcessorConstructor() {
        // Story 129 (UI): a slot constructed without an effect type
        // (legacy two-arg constructor / direct AudioProcessor) defaults
        // to "not expensive" so existing built-in dynamics stay engaged
        // under the BypassExpensive policy.
        InsertSlot slot = new InsertSlot("Stub", new StubProcessor());
        assertThat(slot.isExpensive()).isFalse();
    }

    @Test
    void shouldDefaultExpensiveToTrueForLongTailReverbType() {
        // Story 129 (UI): convolution-style / long-tail reverbs are
        // flagged expensive by default so the BypassExpensive policy
        // has sensible candidates without manual user intervention.
        InsertSlot slot = new InsertSlot(
                "Conv Reverb", new StubProcessor(), InsertEffectType.CONVOLUTION_REVERB);
        assertThat(slot.isExpensive()).isTrue();
    }

    @Test
    void shouldDefaultExpensiveToFalseForCheapDynamics() {
        // Compressors / EQs / gates remain at false so they continue
        // to engage even when the per-track CPU budget trips.
        InsertSlot slot = new InsertSlot(
                "Comp", new StubProcessor(), InsertEffectType.COMPRESSOR);
        assertThat(slot.isExpensive()).isFalse();
    }

    @Test
    void shouldToggleExpensiveFlag() {
        InsertSlot slot = new InsertSlot(
                "Comp", new StubProcessor(), InsertEffectType.COMPRESSOR);
        slot.setExpensive(true);
        assertThat(slot.isExpensive()).isTrue();
        slot.setExpensive(false);
        assertThat(slot.isExpensive()).isFalse();
    }

    // --- Stub processor ---

    private static class StubProcessor implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
