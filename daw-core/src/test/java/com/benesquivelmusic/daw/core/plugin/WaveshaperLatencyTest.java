package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.dsp.WaveshaperProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class WaveshaperLatencyTest {
    @Test
    void fixedLatencyPaddingPreservesTheWetPathsHighFrequencyMagnitude() {
        for (var factor : WaveshaperProcessor.OversampleFactor.values()) {
            var raw = new WaveshaperProcessor(1, 48_000);
            var fixed = new WaveshaperProcessor(1, 48_000);
            raw.setOversampleFactor(factor);
            fixed.setOversampleFactor(factor);
            raw.setTransferFunction(WaveshaperProcessor.TransferFunction.HARD_CLIP);
            fixed.setTransferFunction(WaveshaperProcessor.TransferFunction.HARD_CLIP);
            fixed.enableFixedLatency();
            var input = new float[1][4_800];
            var rawOutput = new float[1][4_800];
            var fixedOutput = new float[1][4_800];
            for (int i = 0; i < input[0].length; i++) {
                input[0][i] = (float) (0.1 * Math.sin(2 * Math.PI * 20_000 * i / 48_000));
            }
            raw.process(input, rawOutput, input[0].length);
            fixed.process(input, fixedOutput, input[0].length);
            double rawEnergy = 0;
            double fixedEnergy = 0;
            for (int i = 1_200; i < input[0].length; i++) {
                rawEnergy += rawOutput[0][i] * rawOutput[0][i];
                fixedEnergy += fixedOutput[0][i] * fixedOutput[0][i];
            }
            assertThat(fixedEnergy / rawEnergy).as(factor.name()).isCloseTo(1, offset(0.0001));
        }
    }

    @Test
    void liveOversamplingChangesKeepWetDryAndOtherTracksAlignedAcrossShortBlocks() {
        var plugin = new WaveshaperPlugin();
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 8; }
            @Override public int getAudioChannels() { return 2; }
            @Override public void log(String message) { }
        });
        var slot = new InsertSlot("Waveshaper", plugin.getProcessor(), null, plugin);
        var channel = new MixerChannel("Shaped");
        channel.addInsert(slot);
        var mixer = new Mixer();
        mixer.addChannel(channel);
        mixer.addChannel(new MixerChannel("Reference"));
        mixer.prepareForPlayback(2, 8);
        var store = slot.getParameterStore();
        store.writeFromUiById(4, 1); // Hard clip is linear at this test's small amplitude.

        try {
            for (int factor = 0; factor < 4; factor++) {
                for (double mix : new double[]{0, 0.5, 1}) {
                    store.writeFromUiById(3, factor);
                    store.writeFromUiById(1, mix);
                    slot.drainParametersToAudio();
                    mixer.getDelayCompensation().reset();
                    assertThat(mixer.getDelayCompensation().getChannelCompensationSamples(1)).isEqualTo(26);
                    double[][] sum = new double[2][2];
                    double[][] moment = new double[2][2];
                    for (int start = 0; start < 128; start += 8) {
                        var inputs = new float[2][2][8];
                        if (start == 0) {
                            for (int track = 0; track < 2; track++) {
                                inputs[track][0][0] = 0.1f;
                                inputs[track][1][3] = -0.05f;
                            }
                        }
                        mixer.mixDown(inputs, new float[2][8], 8);
                        for (int track = 0; track < 2; track++) {
                            for (int audioChannel = 0; audioChannel < 2; audioChannel++) {
                                for (int frame = 0; frame < 8; frame++) {
                                    double value = inputs[track][audioChannel][frame];
                                    sum[track][audioChannel] += value;
                                    moment[track][audioChannel] += value * (start + frame);
                                }
                            }
                        }
                    }
                    for (int track = 0; track < 2; track++) {
                        assertThat(moment[track][0] / sum[track][0]).isCloseTo(26, offset(0.001));
                        assertThat(moment[track][1] / sum[track][1]).isCloseTo(29, offset(0.001));
                    }
                }
            }
        } finally {
            slot.disposeAfterQuiescence();
        }
    }
}
