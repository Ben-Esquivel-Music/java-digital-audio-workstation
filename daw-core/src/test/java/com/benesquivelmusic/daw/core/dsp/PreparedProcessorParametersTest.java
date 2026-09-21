package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PreparedProcessorParametersTest {
    @Test
    void chirpRecallPublishesTheLatestDurationAndBandwidthTogether() throws Exception {
        var processor = new ChirpPeakReducer(1, 8_000);
        var expected = new ChirpPeakReducer(1, 8_000);
        expected.setChirpDurationMs(4);
        expected.setChirpBandwidthHz(9_000);
        var slot = new InsertSlot("Chirp", processor);
        var kernelField = ChirpPeakReducer.class.getDeclaredField("chirpKernel");
        kernelField.setAccessible(true);
        try {
            var store = slot.getParameterStore();
            for (int i = 0; i < 100; i++) {
                store.writeFromUiById(1, 1 + i % 4);
                store.writeFromUiById(2, 8_000 + i * 10);
                slot.drainParametersToAudio();
            }
            store.writeFromUiById(1, 4);
            store.writeFromUiById(2, 9_000);
            store.writeFromUiById(3, 0.3);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                do {
                    slot.drainParametersToAudio();
                    Thread.sleep(5);
                } while (!Arrays.equals((double[]) kernelField.get(processor),
                        (double[]) kernelField.get(expected)));
            });
            assertThat(processor.getMix()).isEqualTo(0.3);
            assertThat(processor.getChirpDurationMs()).isEqualTo(4);
            assertThat(processor.getChirpBandwidthHz()).isEqualTo(9_000);
        } finally {
            slot.disposeAfterQuiescence();
        }
    }

    @Test
    void velvetRecallPublishesTheLatestDecayAndDensityTogether() throws Exception {
        var processor = new VelvetNoiseReverbProcessor(1, 8_000);
        var expected = new VelvetNoiseReverbProcessor(1, 8_000);
        expected.setDecayTime(0.2);
        expected.setDensity(0.8);
        var slot = new InsertSlot("Velvet", processor);
        var delays = VelvetNoiseReverbProcessor.class.getDeclaredField("lateDelays");
        delays.setAccessible(true);
        try {
            var store = slot.getParameterStore();
            store.writeFromUiById(0, 0.2);
            store.writeFromUiById(1, 0.8);
            store.writeFromUiById(4, 0.7);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                do {
                    slot.drainParametersToAudio();
                    Thread.sleep(5);
                } while (!Arrays.deepEquals((Object[]) delays.get(processor),
                        (Object[]) delays.get(expected)));
            });
            assertThat(processor.getMix()).isEqualTo(0.7);
        } finally {
            slot.disposeAfterQuiescence();
        }
    }
}
