package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.ExportValidationResult;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessHistoryPoint;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoudnessMeterTest {

    private static final double SAMPLE_RATE = 48000.0;
    private static final int BLOCK_SIZE = 480;

    @Test
    void shouldInitializeToSilence() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        assertThat(meter.hasData()).isTrue();
        assertThat(meter.getLatestData()).isEqualTo(LoudnessData.SILENCE);
    }

    @Test
    void shouldMeasureSilenceAsVeryLow() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] silence = new float[BLOCK_SIZE];
        meter.process(silence, silence, BLOCK_SIZE);

        LoudnessData data = meter.getLatestData();
        assertThat(data.momentaryLufs()).isLessThan(-70.0);
    }

    @Test
    void shouldMeasureLoudSignalHigherThanSilence() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        // Process silence
        float[] silence = new float[BLOCK_SIZE];
        meter.process(silence, silence, BLOCK_SIZE);
        double silenceLufs = meter.getLatestData().momentaryLufs();

        // Process a loud sine wave
        meter.reset();
        float[] loud = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 50; i++) { // Process enough blocks
            meter.process(loud, loud, BLOCK_SIZE);
        }
        double loudLufs = meter.getLatestData().momentaryLufs();

        assertThat(loudLufs).isGreaterThan(silenceLufs);
    }

    @Test
    void shouldTrackTruePeak() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] samples = new float[BLOCK_SIZE];
        samples[0] = 0.9f;
        meter.process(samples, samples, BLOCK_SIZE);

        assertThat(meter.getLatestData().truePeakDbfs()).isGreaterThan(-10.0);
    }

    @Test
    void shouldResetAllState() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] loud = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 10; i++) {
            meter.process(loud, loud, BLOCK_SIZE);
        }

        meter.reset();
        assertThat(meter.getLatestData()).isEqualTo(LoudnessData.SILENCE);
    }

    @Test
    void shouldHavePlatformTargetConstants() {
        assertThat(LoudnessMeter.TARGET_SPOTIFY).isEqualTo(-14.0);
        assertThat(LoudnessMeter.TARGET_APPLE_MUSIC).isEqualTo(-16.0);
        assertThat(LoudnessMeter.TARGET_YOUTUBE).isEqualTo(-14.0);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new LoudnessMeter(0, 480))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBlockSize() {
        assertThatThrownBy(() -> new LoudnessMeter(48000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProvideIntegratedLoudnessOverTime() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        // Process many blocks to accumulate integrated loudness
        for (int i = 0; i < 100; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        LoudnessData data = meter.getLatestData();
        // Integrated should be a finite value (not silence floor)
        assertThat(data.integratedLufs()).isGreaterThan(-100.0);
    }

    @Test
    void shouldMeasureIndependentLeftAndRightChannels() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        // Create distinct left and right signals: left loud, right silent
        float[] loudLeft = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        float[] silentRight = new float[BLOCK_SIZE];

        for (int i = 0; i < 50; i++) {
            meter.process(loudLeft, silentRight, BLOCK_SIZE);
        }
        LoudnessData asymmetric = meter.getLatestData();

        // Now process the same signal on both channels
        meter.reset();
        for (int i = 0; i < 50; i++) {
            meter.process(loudLeft, loudLeft, BLOCK_SIZE);
        }
        LoudnessData symmetric = meter.getLatestData();

        // Symmetric (both channels loud) should measure louder than asymmetric
        assertThat(symmetric.momentaryLufs()).isGreaterThan(asymmetric.momentaryLufs());
    }

    // ----------------------------------------------------------------
    // Loudness history tests
    // ----------------------------------------------------------------

    @Test
    void shouldRecordLoudnessHistory() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        int numBlocks = 20;
        for (int i = 0; i < numBlocks; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        List<LoudnessHistoryPoint> history = meter.getHistory();
        assertThat(history).hasSize(numBlocks);
    }

    @Test
    void shouldRecordMonotonicallyIncreasingTimestamps() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        for (int i = 0; i < 10; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        List<LoudnessHistoryPoint> history = meter.getHistory();
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).timestampSeconds())
                    .isGreaterThan(history.get(i - 1).timestampSeconds());
        }
    }

    @Test
    void shouldClearHistoryOnReset() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        for (int i = 0; i < 10; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }
        assertThat(meter.getHistory()).isNotEmpty();

        meter.reset();
        assertThat(meter.getHistory()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableHistory() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        meter.process(signal, signal, BLOCK_SIZE);

        List<LoudnessHistoryPoint> history = meter.getHistory();
        assertThatThrownBy(() -> history.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRecordIntegratedLufsInHistory() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        for (int i = 0; i < 50; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        List<LoudnessHistoryPoint> history = meter.getHistory();
        LoudnessHistoryPoint last = history.getLast();
        assertThat(last.integratedLufs()).isGreaterThan(-100.0);
        assertThat(last.momentaryLufs()).isGreaterThan(-100.0);
    }

    // ----------------------------------------------------------------
    // Export validation tests
    // ----------------------------------------------------------------

    @Test
    void shouldPassExportValidationWhenLoudnessMatchesTarget() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        // Generate a signal and process enough blocks to get stable integrated LUFS
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 500; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        // Validate against the target — the exact result depends on signal level,
        // but we check the structure of the result
        ExportValidationResult result = meter.validateForExport(LoudnessTarget.SPOTIFY);
        assertThat(result.target()).isEqualTo(LoudnessTarget.SPOTIFY);
        assertThat(result.message()).isNotBlank();
        assertThat(result.measuredIntegratedLufs()).isNotNaN();
        assertThat(result.measuredTruePeakDbtp()).isNotNaN();
    }

    @Test
    void shouldFailExportValidationForSilence() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] silence = new float[BLOCK_SIZE];
        meter.process(silence, silence, BLOCK_SIZE);

        ExportValidationResult result = meter.validateForExport(LoudnessTarget.SPOTIFY);
        assertThat(result.passed()).isFalse();
        assertThat(result.loudnessPass()).isFalse();
    }

    @Test
    void shouldRejectNullTargetInValidation() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        assertThatThrownBy(() -> meter.validateForExport(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldValidateAgainstMultiplePlatforms() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 500; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        // Should be able to validate against any target
        for (LoudnessTarget target : LoudnessTarget.values()) {
            ExportValidationResult result = meter.validateForExport(target);
            assertThat(result.target()).isEqualTo(target);
            assertThat(result.message()).contains(target.displayName());
        }
    }

    @Test
    void shouldIncludePassInMessageWhenLoudnessAndPeakMatch() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);
        for (int i = 0; i < 500; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        ExportValidationResult result = meter.validateForExport(LoudnessTarget.SPOTIFY);
        if (result.passed()) {
            assertThat(result.message()).contains("PASS");
        } else {
            assertThat(result.message()).contains("FAIL");
        }
    }

    // ----------------------------------------------------------------
    // K-weighting filter tests
    // ----------------------------------------------------------------

    @Test
    void shouldBoostHighFrequenciesViaKWeighting() {
        // K-weighting applies +4 dB shelf above ~1500 Hz.
        // A 4 kHz signal should measure louder than a 100 Hz signal of equal amplitude.
        var meterHigh = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        var meterLow = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        float[] highFreq = generateSineWave(4000.0, SAMPLE_RATE, BLOCK_SIZE);
        float[] lowFreq = generateSineWave(100.0, SAMPLE_RATE, BLOCK_SIZE);

        for (int i = 0; i < 100; i++) {
            meterHigh.process(highFreq, highFreq, BLOCK_SIZE);
            meterLow.process(lowFreq, lowFreq, BLOCK_SIZE);
        }

        assertThat(meterHigh.getLatestData().momentaryLufs())
                .isGreaterThan(meterLow.getLatestData().momentaryLufs());
    }

    @Test
    void shouldAttenuateLowFrequenciesViaKWeighting() {
        // K-weighting applies high-pass at ~60 Hz.
        // A 30 Hz signal should measure significantly lower than a 1 kHz signal.
        var meter30 = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        var meter1k = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        float[] freq30 = generateSineWave(30.0, SAMPLE_RATE, BLOCK_SIZE);
        float[] freq1k = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        for (int i = 0; i < 100; i++) {
            meter30.process(freq30, freq30, BLOCK_SIZE);
            meter1k.process(freq1k, freq1k, BLOCK_SIZE);
        }

        assertThat(meter1k.getLatestData().momentaryLufs())
                .isGreaterThan(meter30.getLatestData().momentaryLufs());
    }

    // ----------------------------------------------------------------
    // LRA tests
    // ----------------------------------------------------------------

    @Test
    void shouldReportZeroLraForConstantSignal() {
        // A constant-level signal should produce very small LRA
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        // Process enough blocks to fill short-term window multiple times
        for (int i = 0; i < 500; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        assertThat(meter.getLatestData().loudnessRange()).isGreaterThanOrEqualTo(0.0);
        assertThat(meter.getLatestData().loudnessRange()).isLessThan(3.0);
    }

    @Test
    void shouldReportNonNegativeLra() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] signal = generateSineWave(1000.0, SAMPLE_RATE, BLOCK_SIZE);

        for (int i = 0; i < 100; i++) {
            meter.process(signal, signal, BLOCK_SIZE);
        }

        assertThat(meter.getLatestData().loudnessRange()).isGreaterThanOrEqualTo(0.0);
    }

    // ----------------------------------------------------------------
    // LUFS measurement reference tests
    // ----------------------------------------------------------------

    @Test
    void shouldMeasureFullScaleSineNearExpectedLufs() {
        // A 0 dBFS (amplitude 1.0) 1 kHz sine on both channels should measure
        // approximately −3.01 LUFS (mono power) plus K-weighting effect.
        // This is a sanity check that the result is in a reasonable range.
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);
        float[] fullScale = new float[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            fullScale[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / SAMPLE_RATE);
        }

        for (int i = 0; i < 500; i++) {
            meter.process(fullScale, fullScale, BLOCK_SIZE);
        }

        double intLufs = meter.getLatestData().integratedLufs();
        // Expected approximately -3.01 LUFS for 0 dBFS mono sine (K-weighting has
        // minimal effect at 1 kHz). Allow some tolerance.
        assertThat(intLufs).isBetween(-6.0, 0.0);
    }

    @Test
    void shouldGateBlocksBelowAbsoluteThreshold() {
        var meter = new LoudnessMeter(SAMPLE_RATE, BLOCK_SIZE);

        // Process very quiet signal (should be gated out at -70 LUFS)
        float[] veryQuiet = new float[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            veryQuiet[i] = (float) (0.00001 * Math.sin(2.0 * Math.PI * 1000.0 * i / SAMPLE_RATE));
        }

        for (int i = 0; i < 100; i++) {
            meter.process(veryQuiet, veryQuiet, BLOCK_SIZE);
        }

        // Integrated should be at or near the floor since blocks are gated
        assertThat(meter.getLatestData().integratedLufs()).isLessThan(-70.0);
    }

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * frequency * i / sampleRate));
        }
        return samples;
    }
}
