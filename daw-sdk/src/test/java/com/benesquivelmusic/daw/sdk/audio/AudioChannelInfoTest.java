package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the driver-reported channel-name surface used by the per-track
 * I/O routing dropdowns — story 199.
 */
class AudioChannelInfoTest {

    // ── ChannelKindHeuristics ─────────────────────────────────────────────

    @Test
    void heuristicShouldClassifyMicLineAsMic() {
        // "Mic/Line" preamps log under Mic — the user wires a microphone
        // there 95% of the time.
        assertThat(ChannelKindHeuristics.infer("Mic/Line 1"))
                .isInstanceOf(ChannelKind.Mic.class);
    }

    @Test
    void heuristicShouldClassifyHiZAsInstrument() {
        assertThat(ChannelKindHeuristics.infer("Hi-Z Inst 3"))
                .isInstanceOf(ChannelKind.Instrument.class);
        assertThat(ChannelKindHeuristics.infer("HiZ 4"))
                .isInstanceOf(ChannelKind.Instrument.class);
        assertThat(ChannelKindHeuristics.infer("Inst 5"))
                .isInstanceOf(ChannelKind.Instrument.class);
    }

    @Test
    void heuristicShouldClassifyDigitalChannels() {
        assertThat(ChannelKindHeuristics.infer("S/PDIF L"))
                .isInstanceOf(ChannelKind.Digital.class);
        assertThat(ChannelKindHeuristics.infer("ADAT 1"))
                .isInstanceOf(ChannelKind.Digital.class);
        assertThat(ChannelKindHeuristics.infer("AES/EBU R"))
                .isInstanceOf(ChannelKind.Digital.class);
    }

    @Test
    void heuristicShouldClassifyMonitorAndHeadphone() {
        assertThat(ChannelKindHeuristics.infer("Main Out L"))
                .isInstanceOf(ChannelKind.Monitor.class);
        assertThat(ChannelKindHeuristics.infer("Monitor R"))
                .isInstanceOf(ChannelKind.Monitor.class);
        assertThat(ChannelKindHeuristics.infer("Phones 1 L"))
                .isInstanceOf(ChannelKind.Headphone.class);
        assertThat(ChannelKindHeuristics.infer("Headphone Out"))
                .isInstanceOf(ChannelKind.Headphone.class);
        assertThat(ChannelKindHeuristics.infer("Headphones"))
                .isInstanceOf(ChannelKind.Headphone.class);
        assertThat(ChannelKindHeuristics.infer("Headphones 1"))
                .isInstanceOf(ChannelKind.Headphone.class);
    }

    @Test
    void heuristicShouldClassifyLineLevel() {
        assertThat(ChannelKindHeuristics.infer("Line In 5"))
                .isInstanceOf(ChannelKind.Line.class);
    }

    @Test
    void heuristicShouldFallBackToGenericForUnknownNames() {
        assertThat(ChannelKindHeuristics.infer("Channel 7"))
                .isInstanceOf(ChannelKind.Generic.class);
        assertThat(ChannelKindHeuristics.infer("Bus A"))
                .isInstanceOf(ChannelKind.Generic.class);
    }

    // ── AudioChannelInfo record ───────────────────────────────────────────

    @Test
    void audioChannelInfoShouldRejectNegativeIndex() {
        assertThatThrownBy(() -> new AudioChannelInfo(-1, "Mic 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void audioChannelInfoShouldRejectBlankDisplayName() {
        assertThatThrownBy(() -> new AudioChannelInfo(0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void audioChannelInfoShouldInferKindFromDisplayName() {
        AudioChannelInfo info = new AudioChannelInfo(2, "Hi-Z Inst 3");
        assertThat(info.kind()).isInstanceOf(ChannelKind.Instrument.class);
        assertThat(info.active()).isTrue();
    }

    // ── ChannelGrouping (stereo-pair auto-grouping) ───────────────────────

    @Test
    void groupingShouldCollapseConsecutiveLrPairsIntoStereoEntries() {
        // The user-story acceptance criterion: a fake backend reporting
        // "Mic 1 L" + "Mic 1 R" yields a single "Mic 1 (Stereo)" stereo
        // entry — alongside the still-individually-pickable mono entries.
        List<AudioChannelInfo> live = List.of(
                new AudioChannelInfo(0, "Mic 1 L"),
                new AudioChannelInfo(1, "Mic 1 R"),
                new AudioChannelInfo(2, "Hi-Z Inst 3"));

        List<ChannelGrouping.Option> options = ChannelGrouping.buildOptions(live);

        // mono L, mono R, stereo (Mic 1), mono Hi-Z = 4 entries
        assertThat(options).hasSize(4);
        assertThat(options.get(0).displayName()).isEqualTo("Mic 1 L");
        assertThat(options.get(0).channelCount()).isEqualTo(1);
        assertThat(options.get(1).displayName()).isEqualTo("Mic 1 R");
        assertThat(options.get(2).displayName()).isEqualTo("Mic 1 (Stereo)");
        assertThat(options.get(2).channelCount()).isEqualTo(2);
        assertThat(options.get(2).firstChannel()).isEqualTo(0);
        assertThat(options.get(3).displayName()).isEqualTo("Hi-Z Inst 3");
    }

    @Test
    void groupingShouldNotCollapseNonMatchingStems() {
        List<AudioChannelInfo> live = List.of(
                new AudioChannelInfo(0, "Mic 1 L"),
                new AudioChannelInfo(1, "Mic 2 R"));   // different stem

        List<ChannelGrouping.Option> options = ChannelGrouping.buildOptions(live);

        assertThat(options).hasSize(2);
        assertThat(options).extracting(ChannelGrouping.Option::channelCount)
                .containsExactly(1, 1);
    }

    @Test
    void groupingShouldRequireLrOrdering() {
        // R first, then L — must NOT collapse.
        List<AudioChannelInfo> live = List.of(
                new AudioChannelInfo(0, "Mic 1 R"),
                new AudioChannelInfo(1, "Mic 1 L"));

        List<ChannelGrouping.Option> options = ChannelGrouping.buildOptions(live);

        assertThat(options).hasSize(2);
        assertThat(options).allMatch(o -> o.channelCount() == 1);
    }

    @Test
    void groupingShouldFlagStereoPairInactiveWhenEitherChannelIsInactive() {
        List<AudioChannelInfo> live = List.of(
                new AudioChannelInfo(0, "S/PDIF L", false),
                new AudioChannelInfo(1, "S/PDIF R", true));

        List<ChannelGrouping.Option> options = ChannelGrouping.buildOptions(live);

        ChannelGrouping.Option stereo = options.stream()
                .filter(o -> o.channelCount() == 2)
                .findFirst()
                .orElseThrow();
        assertThat(stereo.active()).isFalse();
    }

    @Test
    void groupingShouldRequireConsecutiveIndices() {
        // Same stem but indices 0 and 2 — there is something between
        // them, so we cannot auto-group.
        List<AudioChannelInfo> live = List.of(
                new AudioChannelInfo(0, "Mic 1 L"),
                new AudioChannelInfo(2, "Mic 1 R"));

        List<ChannelGrouping.Option> options = ChannelGrouping.buildOptions(live);
        assertThat(options).extracting(ChannelGrouping.Option::channelCount)
                .containsOnly(1);
    }

    // ── MockAudioBackend wiring ──────────────────────────────────────────

    @Test
    void mockBackendShouldReportConfiguredChannelInfos() {
        MockAudioBackend backend = new MockAudioBackend();
        DeviceId device = new DeviceId("Mock", "Mock Device");
        backend.setInputChannels(List.of(
                new AudioChannelInfo(0, "Mic/Line 1"),
                new AudioChannelInfo(1, "Mic/Line 2"),
                new AudioChannelInfo(2, "Hi-Z Inst 3")));

        List<AudioChannelInfo> reported = backend.inputChannels(device);

        assertThat(reported).hasSize(3);
        assertThat(reported.get(0).displayName()).isEqualTo("Mic/Line 1");
        assertThat(reported.get(0).kind()).isInstanceOf(ChannelKind.Mic.class);
        assertThat(reported.get(2).kind()).isInstanceOf(ChannelKind.Instrument.class);
    }

    @Test
    void audioBackendDefaultInputAndOutputChannelsShouldBeEmpty() {
        // Backends that have not been wired through the FFM layer should
        // return an empty list — the UI then falls back to the legacy
        // generic "Input N" / "Output N" labels.
        AudioBackend backend = new MockAudioBackend();
        // No setInputChannels() called.
        assertThat(backend.inputChannels(new DeviceId("Mock", "Mock Device"))).isEmpty();
        assertThat(backend.outputChannels(new DeviceId("Mock", "Mock Device"))).isEmpty();
    }
}
