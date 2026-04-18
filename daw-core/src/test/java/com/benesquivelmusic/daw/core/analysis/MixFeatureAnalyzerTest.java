package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixFeatureAnalyzerTest {

    private static final double SAMPLE_RATE = 44100.0;

    @Test
    void shouldReturnEmptyReportForEmptyTrackList() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        MixFeatureReport report = analyzer.analyze(List.of());

        assertThat(report.tracks()).isEmpty();
        assertThat(report.aggregate().trackCount()).isZero();
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new MixFeatureAnalyzer(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MixFeatureAnalyzer(SAMPLE_RATE, 1000.0, 500.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExtractLevelFeaturesFromSineTrack() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        // 440 Hz sine at 0.5 amplitude -> peak ≈ -6 dBFS, rms ≈ -9 dBFS, crest ≈ 3 dB
        float[] sine = generateSine(440.0, SAMPLE_RATE, (int) SAMPLE_RATE, 0.5f);

        MixFeatureReport report = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("sine", sine)));

        assertThat(report.tracks()).hasSize(1);
        MixFeatureReport.TrackFeatures t = report.tracks().get(0);
        assertThat(t.name()).isEqualTo("sine");
        assertThat(t.peakDb()).isCloseTo(-6.0, within(0.5));
        assertThat(t.rmsDb()).isCloseTo(-9.0, within(0.5));
        assertThat(t.crestFactorDb()).isCloseTo(3.0, within(0.5));
        // 440 Hz sine: centroid should be close to 440 Hz (within FFT bin resolution)
        assertThat(t.spectralCentroidHz()).isCloseTo(440.0, within(100.0));
        // Mono track: stereo width is 0
        assertThat(t.stereoWidth()).isCloseTo(0.0, within(1e-6));
        // Low-frequency sine: most energy is in the low band (< 250 Hz? 440 > 250, so mid)
        assertThat(t.bandEnergyRatios().mid()).isGreaterThan(0.9);
    }

    @Test
    void shouldMeasureStereoWidthForAntiPhaseSignal() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        float[] left = generateSine(440.0, SAMPLE_RATE, 4096, 0.5f);
        float[] right = new float[left.length];
        for (int i = 0; i < left.length; i++) right[i] = -left[i];

        MixFeatureReport report = analyzer.analyze(List.of(
                new MixFeatureAnalyzer.Track("antiphase", left, right)));

        // Pure anti-phase -> side-only -> width = 1
        assertThat(report.tracks().get(0).stereoWidth()).isCloseTo(1.0, within(1e-3));
    }

    @Test
    void shouldAggregateAcrossTracks() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        float[] loud = generateSine(440.0, SAMPLE_RATE, 8192, 0.8f);
        float[] soft = generateSine(880.0, SAMPLE_RATE, 8192, 0.1f);

        MixFeatureReport report = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("loud", loud),
                MixFeatureAnalyzer.Track.mono("soft", soft)));

        MixFeatureReport.AggregateFeatures agg = report.aggregate();
        assertThat(agg.trackCount()).isEqualTo(2);
        // Loudest peak (~-2 dB) minus softest RMS (~-23 dB) > 15 dB
        assertThat(agg.dynamicRangeDb()).isGreaterThan(15.0);
        // Band ratios should sum to ~1.0
        var r = agg.bandEnergyRatios();
        assertThat(r.low() + r.mid() + r.high()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void shouldDetectSpectralFluxForChangingSignal() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        // Steady sine has near-zero flux
        float[] steady = generateSine(440.0, SAMPLE_RATE, 8192, 0.5f);
        // Chirp-like signal: concat 440 Hz and 880 Hz -> non-zero flux
        float[] changing = new float[8192];
        float[] lo = generateSine(440.0, SAMPLE_RATE, 4096, 0.5f);
        float[] hi = generateSine(3000.0, SAMPLE_RATE, 4096, 0.5f);
        System.arraycopy(lo, 0, changing, 0, 4096);
        System.arraycopy(hi, 0, changing, 4096, 4096);

        MixFeatureReport report = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("steady", steady),
                MixFeatureAnalyzer.Track.mono("changing", changing)));

        double steadyFlux = report.tracks().get(0).spectralFlux();
        double changingFlux = report.tracks().get(1).spectralFlux();
        assertThat(changingFlux).isGreaterThan(steadyFlux);
    }

    @Test
    void shouldCompareTwoMixesWithDeltas() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        float[] quiet = generateSine(440.0, SAMPLE_RATE, 8192, 0.25f);
        float[] loudVersion = generateSine(440.0, SAMPLE_RATE, 8192, 0.5f);

        MixFeatureReport a = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("main", quiet)));
        MixFeatureReport b = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("main", loudVersion)));

        MixFeatureReport.Comparison cmp = MixFeatureAnalyzer.compare(a, b);

        assertThat(cmp.trackDeltas()).hasSize(1);
        MixFeatureReport.TrackFeatureDelta d = cmp.trackDeltas().get(0);
        assertThat(d.name()).isEqualTo("main");
        // b is louder by a factor of 2 -> +6 dB
        assertThat(d.rmsDbDelta()).isCloseTo(6.0, within(0.5));
        assertThat(d.peakDbDelta()).isCloseTo(6.0, within(0.5));
        // Aggregate delta should match the single-track delta
        assertThat(cmp.aggregateDelta().meanRmsDbDelta()).isCloseTo(6.0, within(0.5));
    }

    @Test
    void shouldIgnoreUnmatchedTracksInComparison() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        float[] sine = generateSine(440.0, SAMPLE_RATE, 4096, 0.5f);

        MixFeatureReport a = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("kick", sine),
                MixFeatureAnalyzer.Track.mono("snare", sine)));
        MixFeatureReport b = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("kick", sine),
                MixFeatureAnalyzer.Track.mono("hat", sine)));

        MixFeatureReport.Comparison cmp = MixFeatureAnalyzer.compare(a, b);
        assertThat(cmp.trackDeltas()).hasSize(1);
        assertThat(cmp.trackDeltas().get(0).name()).isEqualTo("kick");
    }

    @Test
    void shouldProduceImmutableTrackList() {
        MixFeatureAnalyzer analyzer = new MixFeatureAnalyzer(SAMPLE_RATE);
        float[] sine = generateSine(440.0, SAMPLE_RATE, 4096, 0.5f);

        MixFeatureReport report = analyzer.analyze(List.of(
                MixFeatureAnalyzer.Track.mono("t", sine)));

        assertThatThrownBy(() -> report.tracks().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void trackShouldRejectMismatchedChannelLengths() {
        assertThatThrownBy(() -> new MixFeatureAnalyzer.Track(
                "bad", new float[10], new float[20]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static float[] generateSine(double freq, double sampleRate, int length, float amp) {
        float[] s = new float[length];
        for (int i = 0; i < length; i++) {
            s[i] = (float) (amp * Math.sin(2.0 * Math.PI * freq * i / sampleRate));
        }
        return s;
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
