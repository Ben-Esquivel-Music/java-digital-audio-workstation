package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

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
    void anOfflineSlotPassesThroughAndExposesItsSavedElement() {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("effect-type", "NOT_A_TYPE");
        attributes.put("name", "Old FX");
        SavedInsert saved = new SavedInsert(attributes, List.of(new SavedInsertChild(
                SavedInsertChild.Tag.PARAMETER, new LinkedHashMap<>(Map.of("id", "3")), "")));
        OfflineInsert descriptor = new OfflineInsert(new OfflineCause.UnknownEffectType("NOT_A_TYPE"), saved, false);

        InsertSlot slot = InsertSlot.ofOffline("Old FX", 2, descriptor);

        assertThat(slot.offline()).contains(descriptor);
        assertThat(slot.offline().orElseThrow().saved().attribute("effect-type")).contains("NOT_A_TYPE");
        assertThat(slot.getProcessor()).isEqualTo(new PassThroughProcessor(2));
        assertThat(slot.getPlugin()).isNull();
        assertThat(slot.isInstrument()).isFalse();
        assertThat(slot.isExpensive()).isFalse();
        assertThat(slot.getCapabilities().providesSidechainInput()).isFalse();
        assertThat(slot.getProcessor().getLatencySamples()).isZero();
        float[][] input = {{0.25f, -0.5f, 1f}, {-0.75f, 0.125f, 0f}};
        float[][] output = new float[2][3];
        slot.getProcessor().process(input, output, 3);
        assertThat(output).as("output equals input").isDeepEqualTo(input);
        assertThat(new InsertSlot("Compressor", new StubProcessor()).offline()).as("a working slot").isEmpty();
    }

    @Test
    void anOfflineSlotPassesTheDoubleBusThroughUnrounded() {
        AudioProcessor processor = offlineSlot().getProcessor();
        double[][] input = {{0.1, -1.0 / 3.0, Math.PI / 4}};
        double[][] wide = {{9, 9, 9}, {9, 9, 9}};

        processor.processDouble(input, wide, 3);

        assertThat(processor.supportsDouble()).as("a native 64-bit path").isTrue();
        assertThat(wide[0]).as("not rounded to float").containsExactly(input[0]);
        assertThat(wide[1]).as("an output channel beyond the input is silent").containsOnly(0.0);
    }

    @Test
    void anOfflineSlotLeavesOutAnInputChannelBeyondItsOutputOnTheDoubleBus() {
        AudioProcessor processor = offlineSlot().getProcessor();
        double[][] input = {{0.1, -1.0 / 3.0}, {Math.PI / 4, Math.E / 3}};
        double[][] narrow = new double[1][2];

        processor.processDouble(input, narrow, 2);

        assertThat(narrow[0]).as("the first input channel, not rounded to float").containsExactly(input[0]);
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

    private static InsertSlot offlineSlot() {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("effect-type", "NOT_A_TYPE");
        attributes.put("name", "Old FX");
        return InsertSlot.ofOffline("Old FX", 2, new OfflineInsert(
                new OfflineCause.UnknownEffectType("NOT_A_TYPE"), new SavedInsert(attributes, List.of()), false));
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
