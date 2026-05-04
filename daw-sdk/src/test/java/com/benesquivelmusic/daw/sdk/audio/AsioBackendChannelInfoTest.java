package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the FFM-backed
 * {@link AsioBackend#inputChannels(DeviceId)} and
 * {@link AsioBackend#outputChannels(DeviceId)} (story 215).
 *
 * <p>Tests inject a stub {@link AsioCapabilityShim} via
 * {@link AsioBackend#setCapabilityShimFactory} so the success / fallback
 * paths can be verified without requiring an actual ASIO driver / the
 * Steinberg SDK / a Windows host.</p>
 */
class AsioBackendChannelInfoTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Mock ASIO Device");

    @AfterEach
    void restoreFactory() {
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void inputChannelsReturnsEightSyntheticEntriesWithKindClassification() {
        // Synthetic 8-input layout that mirrors the issue acceptance test:
        // a Mic stereo pair (1L/1R), a Hi-Z instrument input, a Line input,
        // a S/PDIF stereo pair, a Headphone L/R pair, and a generic input.
        Map<Integer, AsioCapabilityShim.RawChannelInfo> ins = Map.of(
                0, raw(0, true, "Mic 1 L"),
                1, raw(1, true, "Mic 1 R"),
                2, raw(2, true, "Hi-Z Inst 3"),
                3, raw(3, true, "Line 4"),
                4, raw(4, true, "S/PDIF L"),
                5, raw(5, true, "S/PDIF R"),
                6, raw(6, false, "Mic 7"),  // driver disabled
                7, raw(7, true, "Aux 8"));
        Map<Integer, AsioCapabilityShim.RawChannelInfo> outs = Map.of(
                0, rawOut(0, true, "Main Out L"),
                1, rawOut(1, true, "Main Out R"),
                2, rawOut(2, true, "Phones 1 L"),
                3, rawOut(3, true, "Phones 1 R"));
        AsioBackend.setCapabilityShimFactory(() ->
                StubShim.withChannels(8, 4, ins, outs));

        AsioBackend backend = new AsioBackend();
        List<AudioChannelInfo> input = backend.inputChannels(DEVICE);

        assertThat(input).hasSize(8);
        assertThat(input.get(0)).isEqualTo(
                new AudioChannelInfo(0, "Mic 1 L", ChannelKind.Mic.INSTANCE, true));
        assertThat(input.get(1).displayName()).isEqualTo("Mic 1 R");
        assertThat(input.get(1).kind()).isInstanceOf(ChannelKind.Mic.class);
        assertThat(input.get(2).kind()).isInstanceOf(ChannelKind.Instrument.class);
        assertThat(input.get(3).kind()).isInstanceOf(ChannelKind.Line.class);
        assertThat(input.get(4).kind()).isInstanceOf(ChannelKind.Digital.class);
        assertThat(input.get(5).kind()).isInstanceOf(ChannelKind.Digital.class);
        // Inactive channel is included but flagged so the UI can grey it.
        assertThat(input.get(6).active()).isFalse();
        assertThat(input.get(6).kind()).isInstanceOf(ChannelKind.Mic.class);
        assertThat(input.get(7).kind()).isInstanceOf(ChannelKind.Generic.class);

        List<AudioChannelInfo> output = backend.outputChannels(DEVICE);
        assertThat(output).hasSize(4);
        assertThat(output.get(0).kind()).isInstanceOf(ChannelKind.Monitor.class);
        assertThat(output.get(2).kind()).isInstanceOf(ChannelKind.Headphone.class);
    }

    @Test
    void inputChannelsFallsBackToEmptyListWhenShimIsUnavailable() {
        AsioBackend.setCapabilityShimFactory(StubShim::unavailable);

        List<AudioChannelInfo> input = new AsioBackend().inputChannels(DEVICE);
        List<AudioChannelInfo> output = new AsioBackend().outputChannels(DEVICE);

        // Empty list keeps the UI on its legacy "Input N" / "Output N"
        // dropdowns — exactly the behaviour before story 215.
        assertThat(input).isEmpty();
        assertThat(output).isEmpty();
    }

    @Test
    void inputChannelsSubstitutesFallbackNameForBlankDriverName() {
        // A buggy driver returns a blank ASCII name; the backend
        // substitutes a stable "Input N" so AudioChannelInfo's non-blank
        // invariant is preserved.
        Map<Integer, AsioCapabilityShim.RawChannelInfo> ins = Map.of(
                0, raw(0, true, ""),
                1, raw(1, true, "   "));
        AsioBackend.setCapabilityShimFactory(() ->
                StubShim.withChannels(2, 0, ins, Map.of()));

        List<AudioChannelInfo> input = new AsioBackend().inputChannels(DEVICE);

        assertThat(input).hasSize(2);
        assertThat(input.get(0).displayName()).isEqualTo("Input 1");
        assertThat(input.get(1).displayName()).isEqualTo("Input 2");
    }

    @Test
    void inputChannelsStopsEnumeratingWhenDriverReportsErrorMidway() {
        Map<Integer, AsioCapabilityShim.RawChannelInfo> ins = Map.of(
                0, raw(0, true, "Mic 1"),
                1, raw(1, true, "Mic 2"));
        // Count claims 4 inputs but only 2 produce valid info — backend
        // must stop rather than fabricate entries.
        AsioBackend.setCapabilityShimFactory(() ->
                StubShim.withChannels(4, 0, ins, Map.of()));

        List<AudioChannelInfo> input = new AsioBackend().inputChannels(DEVICE);

        assertThat(input).hasSize(2);
        assertThat(input.get(0).displayName()).isEqualTo("Mic 1");
    }

    private static AsioCapabilityShim.RawChannelInfo raw(int idx, boolean active,
                                                          String name) {
        return new AsioCapabilityShim.RawChannelInfo(
                idx, true, active, /*group*/ idx / 2, /*type*/ 18, name);
    }

    private static AsioCapabilityShim.RawChannelInfo rawOut(int idx, boolean active,
                                                             String name) {
        return new AsioCapabilityShim.RawChannelInfo(
                idx, false, active, /*group*/ idx / 2, /*type*/ 18, name);
    }

    /**
     * Stub shim that answers {@link #isChannelInfoAvailable()} from a
     * caller-supplied flag and serves channel info from in-memory maps.
     * The base class library-lookup is permitted to fail silently —
     * the stub overrides the entire channel-info surface.
     */
    private static final class StubShim extends AsioCapabilityShim {
        private final boolean available;
        private final int inputs;
        private final int outputs;
        private final Map<Integer, RawChannelInfo> ins;
        private final Map<Integer, RawChannelInfo> outs;

        private StubShim(boolean available, int inputs, int outputs,
                         Map<Integer, RawChannelInfo> ins,
                         Map<Integer, RawChannelInfo> outs) {
            super();
            this.available = available;
            this.inputs = inputs;
            this.outputs = outputs;
            this.ins = ins;
            this.outs = outs;
        }

        static StubShim withChannels(int inputs, int outputs,
                                     Map<Integer, RawChannelInfo> ins,
                                     Map<Integer, RawChannelInfo> outs) {
            return new StubShim(true, inputs, outputs, ins, outs);
        }

        static StubShim unavailable() {
            return new StubShim(false, 0, 0, Map.of(), Map.of());
        }

        @Override boolean isAvailable() { return available; }
        @Override boolean isChannelInfoAvailable() { return available; }

        @Override
        Optional<int[]> getChannelCount() {
            return available ? Optional.of(new int[] {inputs, outputs})
                             : Optional.empty();
        }

        @Override
        Optional<RawChannelInfo> getChannelInfo(int channelIndex, boolean isInput) {
            if (!available) return Optional.empty();
            return Optional.ofNullable(
                    (isInput ? ins : outs).get(channelIndex));
        }

        @Override Optional<BufferSizeRange> getBufferSize() { return Optional.empty(); }
        @Override boolean canSampleRate(double rate) { return false; }
        @Override Optional<Double> getSampleRate() { return Optional.empty(); }
        @Override boolean setSampleRate(double rate) { return false; }
    }
}
