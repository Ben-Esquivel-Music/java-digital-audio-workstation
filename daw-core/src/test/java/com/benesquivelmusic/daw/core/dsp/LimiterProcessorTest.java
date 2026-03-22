package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.mastering.TruePeakCeilingPreset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimiterProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var limiter = new LimiterProcessor(2, 44100.0);
        assertThat(limiter.getInputChannelCount()).isEqualTo(2);
        assertThat(limiter.getOutputChannelCount()).isEqualTo(2);
        assertThat(limiter.getCeilingDb()).isEqualTo(-0.3);
    }

    @Test
    void shouldLimitPeaksAboveCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(-6.0); // Ceiling at -6 dBFS (~0.5 linear)

        int blockSize = 4096;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.9f; // Well above ceiling
        }
        limiter.process(input, output, blockSize);

        // Output peaks (after settling) should not exceed ceiling
        double ceiling = Math.pow(10.0, -6.0 / 20.0);
        for (int i = blockSize / 2; i < blockSize; i++) {
            assertThat(Math.abs(output[0][i])).isLessThanOrEqualTo((float) (ceiling + 0.05));
        }
    }

    @Test
    void shouldPassThroughBelowCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(0.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.1f;
        }
        limiter.process(input, output, 256);

        // The limiter has a look-ahead delay, so output is delayed but not attenuated
        // After look-ahead period, output should match input
        int lookAhead = limiter.getLookAheadSamples();
        for (int i = lookAhead + 1; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i - lookAhead],
                    org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    @Test
    void shouldReportGainReduction() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(-6.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.9f;
        }
        limiter.process(input, output, 256);

        assertThat(limiter.getGainReductionDb()).isLessThan(0.0);
    }

    @Test
    void shouldResetState() {
        var limiter = new LimiterProcessor(1, 44100.0);
        float[][] buf = {{0.9f, 0.8f, 0.7f}};
        limiter.process(buf, new float[1][3], 3);
        limiter.reset();

        assertThat(limiter.getGainReductionDb()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectPositiveCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        assertThatThrownBy(() -> limiter.setCeilingDb(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new LimiterProcessor(0, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimiterProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- True Peak Detection Tests ---

    @Test
    void shouldReportTruePeakValues() {
        var limiter = new LimiterProcessor(1, 44100.0);
        int blockSize = 512;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.8f;
        }
        limiter.process(input, output, blockSize);

        assertThat(limiter.getTruePeakLinear()).isGreaterThanOrEqualTo(0.8);
        assertThat(limiter.getTruePeakDbtp()).isGreaterThanOrEqualTo(-2.0);
    }

    @Test
    void shouldResetTruePeakMetering() {
        var limiter = new LimiterProcessor(1, 44100.0);
        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.9f;
        }
        limiter.process(input, output, 256);
        assertThat(limiter.getTruePeakLinear()).isGreaterThan(0.0);

        limiter.resetTruePeakMetering();
        assertThat(limiter.getTruePeakLinear()).isEqualTo(0.0);
        assertThat(limiter.getTruePeakDbtp()).isEqualTo(-120.0);
    }

    @Test
    void truePeakShouldBeAtLeastAsBigAsSamplePeak() {
        var limiter = new LimiterProcessor(1, 44100.0);
        int blockSize = 1024;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Generate a sine wave that has known intersample peaks
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * i / 10.0) * 0.9);
        }
        limiter.process(input, output, blockSize);

        // True peak from interpolation should be >= sample peak
        double samplePeak = 0.0;
        for (int i = 0; i < blockSize; i++) {
            samplePeak = Math.max(samplePeak, Math.abs(input[0][i]));
        }
        assertThat(limiter.getTruePeakLinear()).isGreaterThanOrEqualTo(samplePeak);
    }

    // --- Look-Ahead Tests ---

    @Test
    void shouldReportLatencyInSamples() {
        var limiter = new LimiterProcessor(1, 44100.0);
        assertThat(limiter.getLatencySamples()).isEqualTo(limiter.getLookAheadSamples());
        assertThat(limiter.getLatencySamples()).isGreaterThan(0);
    }

    @Test
    void shouldReportLatencyInSeconds() {
        var limiter = new LimiterProcessor(1, 44100.0);
        double expectedSeconds = limiter.getLookAheadSamples() / 44100.0;
        assertThat(limiter.getLatencySeconds()).isCloseTo(expectedSeconds,
                org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void shouldAllowConfigurableLookAheadMs() {
        var limiter = new LimiterProcessor(1, 44100.0);

        limiter.setLookAheadMs(3.0);
        int expectedSamples = (int) (0.003 * 44100.0);
        assertThat(limiter.getLookAheadSamples()).isEqualTo(expectedSamples);
    }

    @Test
    void shouldClampLookAheadMsToRange() {
        var limiter = new LimiterProcessor(1, 44100.0);

        // Below min (1ms) → should clamp to 1ms
        limiter.setLookAheadMs(0.1);
        int minSamples = (int) (0.001 * 44100.0);
        assertThat(limiter.getLookAheadSamples()).isEqualTo(minSamples);

        // Above max (5ms) → should clamp to 5ms
        limiter.setLookAheadMs(10.0);
        int maxSamples = (int) (0.005 * 44100.0);
        assertThat(limiter.getLookAheadSamples()).isEqualTo(maxSamples);
    }

    @Test
    void shouldHandleTransientWithLookAhead() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(-6.0);

        int blockSize = 4096;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Quiet section followed by a loud transient
        for (int i = 0; i < blockSize; i++) {
            if (i < blockSize / 2) {
                input[0][i] = 0.1f;
            } else {
                input[0][i] = 0.95f; // Loud transient
            }
        }
        limiter.process(input, output, blockSize);

        // After settling, output should be limited below ceiling
        double ceiling = Math.pow(10.0, -6.0 / 20.0);
        for (int i = blockSize * 3 / 4; i < blockSize; i++) {
            assertThat(Math.abs(output[0][i])).isLessThanOrEqualTo((float) (ceiling + 0.05));
        }
    }

    // --- Gain Smoothing (Attack/Release) Tests ---

    @Test
    void shouldAllowSettingAttackMs() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setAttackMs(0.5);
        assertThat(limiter.getAttackMs()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectNegativeAttackMs() {
        var limiter = new LimiterProcessor(1, 44100.0);
        assertThatThrownBy(() -> limiter.setAttackMs(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowSettingReleaseMs() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setReleaseMs(100.0);
        assertThat(limiter.getReleaseMs()).isEqualTo(100.0);
    }

    @Test
    void shouldRejectNegativeReleaseMs() {
        var limiter = new LimiterProcessor(1, 44100.0);
        assertThatThrownBy(() -> limiter.setReleaseMs(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Auto-Release Tests ---

    @Test
    void shouldSupportAutoReleaseMode() {
        var limiter = new LimiterProcessor(1, 44100.0);
        assertThat(limiter.isAutoRelease()).isFalse(); // Off by default

        limiter.setAutoRelease(true);
        assertThat(limiter.isAutoRelease()).isTrue();

        // Set ceiling below input to trigger gain reduction
        limiter.setCeilingDb(-6.0);

        // Verify it processes without error and applies gain reduction
        int blockSize = 1024;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.9f;
        }
        limiter.process(input, output, blockSize);
        assertThat(limiter.getGainReductionDb()).isLessThan(0.0);
    }

    // --- Platform Preset Tests ---

    @Test
    void shouldApplySpotifyPreset() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingPreset(TruePeakCeilingPreset.SPOTIFY);
        assertThat(limiter.getCeilingDb()).isEqualTo(-1.0);
    }

    @Test
    void shouldApplyBroadcastPreset() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingPreset(TruePeakCeilingPreset.BROADCAST_STRICT);
        assertThat(limiter.getCeilingDb()).isEqualTo(-0.5);
    }

    // --- Gain Reduction Transparency Tests ---

    @Test
    void shouldNotDistortSignalBelowCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(0.0);

        int blockSize = 1024;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];

        // Generate a sine wave below the ceiling
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = (float) (Math.sin(2 * Math.PI * i / 100.0) * 0.5);
        }
        limiter.process(input, output, blockSize);

        // After the look-ahead delay, output should closely match input
        int lookAhead = limiter.getLookAheadSamples();
        for (int i = lookAhead + 1; i < blockSize; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i - lookAhead],
                    org.assertj.core.data.Offset.offset(0.02f));
        }
    }

    @Test
    void shouldResetTruePeakOnFullReset() {
        var limiter = new LimiterProcessor(1, 44100.0);
        float[][] input = {{0.9f, 0.8f, 0.7f}};
        limiter.process(input, new float[1][3], 3);
        assertThat(limiter.getTruePeakLinear()).isGreaterThan(0.0);

        limiter.reset();
        assertThat(limiter.getTruePeakLinear()).isEqualTo(0.0);
        assertThat(limiter.getTruePeakDbtp()).isEqualTo(-120.0);
    }

    @Test
    void shouldHandleStereoInput() {
        var limiter = new LimiterProcessor(2, 44100.0);
        int blockSize = 512;
        float[][] input = new float[2][blockSize];
        float[][] output = new float[2][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.8f;
            input[1][i] = -0.8f;
        }
        limiter.setCeilingDb(-6.0);
        limiter.process(input, output, blockSize);

        assertThat(limiter.getGainReductionDb()).isLessThan(0.0);
        assertThat(limiter.getTruePeakLinear()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void shouldGetLookAheadMs() {
        var limiter = new LimiterProcessor(1, 44100.0);
        // Default is 5ms
        assertThat(limiter.getLookAheadMs()).isCloseTo(5.0,
                org.assertj.core.data.Offset.offset(0.1));
    }
}
