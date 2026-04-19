package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.analysis.CompressionArtifactDetector.ArtifactKind;
import com.benesquivelmusic.daw.core.analysis.CompressionArtifactDetector.CodecType;
import com.benesquivelmusic.daw.core.analysis.CompressionArtifactDetector.Report;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressionArtifactDetectorTest {

    private static final double SAMPLE_RATE = 44100.0;

    private final CompressionArtifactDetector detector = new CompressionArtifactDetector();

    @Test
    void shouldRejectNullSamples() {
        assertThatThrownBy(() -> detector.analyze(null, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> detector.analyze(new float[1024], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldClassifyBroadbandNoiseAsLossless() {
        float[] samples = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 1L);

        Report report = detector.analyze(samples, SAMPLE_RATE);

        assertThat(report.codec()).isEqualTo(CodecType.LOSSLESS);
        assertThat(report.estimatedBitrateKbps()).isEqualTo(-1);
        assertThat(report.spectralCutoffHz()).isLessThan(0);
        assertThat(report.severityScore()).isLessThan(0.2);
        assertThat(report.artifactLocations()).isEmpty();
    }

    @Test
    void shouldDetectSharpSpectralCutoff() {
        // Simulate a 128 kbps MP3-style low-pass at ~16 kHz.
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 2L);
        float[] lowPassed = applyBrickwallLowPass(noise, SAMPLE_RATE, 16000.0);

        Report report = detector.analyze(lowPassed, SAMPLE_RATE);

        assertThat(report.spectralCutoffHz())
                .as("should detect a cutoff near 16 kHz")
                .isBetween(14000.0, 17500.0);
        assertThat(report.codec()).isNotEqualTo(CodecType.LOSSLESS);
        assertThat(report.estimatedBitrateKbps()).isGreaterThan(0);
        assertThat(report.severityScore()).isGreaterThan(0.2);
        assertThat(report.artifactLocations())
                .anyMatch(a -> a.kind() == ArtifactKind.SPECTRAL_CUTOFF);
    }

    @Test
    void shouldMapLowerCutoffToLowerBitrate() {
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 3L);
        float[] lp128 = applyBrickwallLowPass(noise, SAMPLE_RATE, 16000.0);
        float[] lp64  = applyBrickwallLowPass(noise, SAMPLE_RATE, 11000.0);

        int bitrate128 = detector.analyze(lp128, SAMPLE_RATE).estimatedBitrateKbps();
        int bitrate64  = detector.analyze(lp64,  SAMPLE_RATE).estimatedBitrateKbps();

        assertThat(bitrate64)
                .as("harsher cutoff should yield a lower estimated bitrate")
                .isLessThan(bitrate128);
    }

    @Test
    void shouldDetectBirdieArtifacts() {
        // Low-pass broadband noise to suppress natural high-frequency energy,
        // then inject isolated narrowband tones above the cutoff to simulate
        // codec birdies.
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 4L);
        float[] signal = applyBrickwallLowPass(noise, SAMPLE_RATE, 12000.0);
        injectTone(signal, SAMPLE_RATE, 14321.0, 0.05f);
        injectTone(signal, SAMPLE_RATE, 17017.0, 0.05f);

        Report report = detector.analyze(signal, SAMPLE_RATE);

        assertThat(report.birdieCount()).isGreaterThan(0);
        assertThat(report.artifactLocations())
                .anyMatch(a -> a.kind() == ArtifactKind.BIRDIE
                        && a.frequencyHz() > 10000.0);
    }

    @Test
    void shouldDetectPreEcho() {
        // Construct a signal with a low-level tonal "smear" immediately
        // before a strong transient onset — the canonical pre-echo pattern.
        int sr = (int) SAMPLE_RATE;
        float[] signal = new float[sr]; // 1 second of silence
        // Pre-echo: low-level tone in the 512 samples before the transient.
        int onset = sr / 2;
        for (int i = onset - 512; i < onset; i++) {
            signal[i] = (float) (0.06 * Math.sin(2 * Math.PI * 4000.0 * i / SAMPLE_RATE));
        }
        // Transient: sharp burst of noise.
        Random rng = new Random(5L);
        for (int i = onset; i < onset + 1024; i++) {
            signal[i] = (float) (0.6 * (rng.nextDouble() * 2 - 1));
        }

        Report report = detector.analyze(signal, SAMPLE_RATE);

        assertThat(report.preEchoCount()).isGreaterThan(0);
        assertThat(report.artifactLocations())
                .anyMatch(a -> a.kind() == ArtifactKind.PRE_ECHO);
    }

    @Test
    void severityScoreShouldBeInUnitInterval() {
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 6L);
        float[] lp = applyBrickwallLowPass(noise, SAMPLE_RATE, 8000.0);

        Report report = detector.analyze(lp, SAMPLE_RATE);

        assertThat(report.severityScore()).isBetween(0.0, 1.0);
    }

    @Test
    void artifactLocationsListShouldBeImmutable() {
        float[] noise = whiteNoise(SAMPLE_RATE, 0.5, 0.2f, 7L);

        Report report = detector.analyze(noise, SAMPLE_RATE);

        assertThatThrownBy(() -> report.artifactLocations()
                        .add(new CompressionArtifactDetector.ArtifactLocation(
                                ArtifactKind.BIRDIE, 0, 0, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -- Helpers ------------------------------------------------------------

    private static float[] whiteNoise(double sampleRate, double seconds,
                                      float amp, long seed) {
        int n = (int) Math.round(sampleRate * seconds);
        float[] out = new float[n];
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            out[i] = (float) ((rng.nextDouble() * 2 - 1) * amp);
        }
        return out;
    }

    /** Zero-phase brickwall low-pass via FFT → zero high bins → IFFT. */
    private static float[] applyBrickwallLowPass(float[] samples,
                                                 double sampleRate,
                                                 double cutoffHz) {
        int fftSize = Integer.highestOneBit(samples.length - 1) << 1;
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        System.arraycopy(floatToDouble(samples), 0, real, 0, samples.length);

        FftUtils.fft(real, imag);

        double binHz = sampleRate / fftSize;
        int cutoffBin = (int) Math.round(cutoffHz / binHz);
        for (int k = cutoffBin + 1; k < fftSize - cutoffBin; k++) {
            real[k] = 0.0;
            imag[k] = 0.0;
        }

        FftUtils.ifft(real, imag);

        float[] out = new float[samples.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) real[i];
        }
        return out;
    }

    private static double[] floatToDouble(float[] src) {
        double[] out = new double[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i];
        return out;
    }

    private static void injectTone(float[] samples, double sampleRate,
                                   double freqHz, float amp) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] += (float) (amp * Math.sin(2 * Math.PI * freqHz * i / sampleRate));
        }
    }
}
