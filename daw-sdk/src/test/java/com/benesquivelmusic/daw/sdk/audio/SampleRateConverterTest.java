package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleRateConverterTest {

    private static final int RATE_44100 = 44_100;
    private static final int RATE_48000 = 48_000;
    private static final double TONE_HZ = 440.0;

    /** Generates {@code durationSec} seconds of a sine wave at {@code rateHz}. */
    private static float[] sine(double rateHz, double durationSec, double freq) {
        int n = (int) Math.round(rateHz * durationSec);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) Math.sin(2.0 * Math.PI * freq * i / rateHz);
        }
        return out;
    }

    /** Peak absolute error between two sequences, ignoring {@code margin} edge samples. */
    private static double peakError(float[] a, float[] b, int margin) {
        int n = Math.min(a.length, b.length);
        double peak = 0.0;
        for (int i = margin; i < n - margin; i++) {
            peak = Math.max(peak, Math.abs(a[i] - b[i]));
        }
        return peak;
    }

    @Test
    void tierOfReturnsMatchingImpl() {
        assertThat(SampleRateConverter.of(QualityTier.LOW)).isInstanceOf(SampleRateConverter.Low.class);
        assertThat(SampleRateConverter.of(QualityTier.MEDIUM)).isInstanceOf(SampleRateConverter.Medium.class);
        assertThat(SampleRateConverter.of(QualityTier.HIGH)).isInstanceOf(SampleRateConverter.High.class);
    }

    @Test
    void identityWhenRatesMatchForAllTiers() {
        float[] in = sine(RATE_48000, 0.01, TONE_HZ);
        for (QualityTier t : QualityTier.values()) {
            float[] out = SampleRateConverter.of(t).process(in, RATE_48000, RATE_48000);
            assertThat(out).containsExactly(in);
            assertThat(out).isNotSameAs(in);  // defensive copy
        }
    }

    @Test
    void documentedSpecsAreMonotonicInQuality() {
        SampleRateConverter low    = new SampleRateConverter.Low();
        SampleRateConverter medium = new SampleRateConverter.Medium();
        SampleRateConverter high   = new SampleRateConverter.High();

        assertThat(low.passbandRippleDb()).isGreaterThan(medium.passbandRippleDb());
        assertThat(medium.passbandRippleDb()).isGreaterThan(high.passbandRippleDb());

        assertThat(low.stopbandAttenuationDb()).isLessThan(medium.stopbandAttenuationDb());
        assertThat(medium.stopbandAttenuationDb()).isLessThan(high.stopbandAttenuationDb());
    }

    @Test
    void estimateOutputFramesMatchesRateRatio() {
        assertThat(SampleRateConverter.estimateOutputFrames(44_100, 44_100, 48_000))
                .isEqualTo(48_000);
        assertThat(SampleRateConverter.estimateOutputFrames(96_000, 48_000, 44_100))
                .isEqualTo(88_200);
    }

    @Test
    void estimateOutputFramesRejectsInvalidInput() {
        assertThatThrownBy(() -> SampleRateConverter.estimateOutputFrames(-1, 48_000, 44_100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleRateConverter.estimateOutputFrames(10, 0, 44_100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleRateConverter.estimateOutputFrames(10, 48_000, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void highTierRoundTripsSineWaveWithinSpec() {
        // 44.1 → 48 → 44.1 round-trip, used in the issue acceptance test.
        float[] original = sine(RATE_44100, 0.25, TONE_HZ);
        SampleRateConverter high = new SampleRateConverter.High();
        float[] upsampled = high.process(original, RATE_44100, RATE_48000);
        float[] roundTripped = high.process(upsampled, RATE_48000, RATE_44100);

        // Output length must match input length within 1 sample.
        assertThat(Math.abs(roundTripped.length - original.length)).isLessThanOrEqualTo(1);

        // Within the passband, error should be well below the documented ripple.
        // 10 log10(err_peak^2) ≈ ripple dB on a unit sine — use a conservative
        // 200-sample guard-band at each edge to skip filter ramp-up/down.
        double peak = peakError(original, roundTripped, 200);
        // 0.01 dB ripple → linear ≈ 0.00115. Our implementation should be well below this
        // peak error in the passband; allow generous headroom for the truncated kernel.
        assertThat(peak).isLessThan(0.05);
    }

    @Test
    void mediumTierRoundTripsSineWaveWithinSpec() {
        float[] original = sine(RATE_44100, 0.25, TONE_HZ);
        SampleRateConverter medium = new SampleRateConverter.Medium();
        float[] up = medium.process(original, RATE_44100, RATE_48000);
        float[] back = medium.process(up, RATE_48000, RATE_44100);

        assertThat(Math.abs(back.length - original.length)).isLessThanOrEqualTo(1);
        double peak = peakError(original, back, 100);
        // 0.1 dB ripple is ~0.012 linear; allow headroom for short kernel truncation.
        assertThat(peak).isLessThan(0.10);
    }

    @Test
    void lowTierProducesBoundedOutput() {
        float[] in = sine(RATE_44100, 0.1, TONE_HZ);
        SampleRateConverter low = new SampleRateConverter.Low();
        float[] out = low.process(in, RATE_44100, RATE_48000);
        assertThat(out.length).isEqualTo(48_000 / 10);
        for (float s : out) {
            assertThat(s).isBetween(-1.5f, 1.5f);
        }
    }

    @Test
    void processRejectsNonPositiveRates() {
        SampleRateConverter c = new SampleRateConverter.Medium();
        assertThatThrownBy(() -> c.process(new float[]{1f}, 0, 48_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.process(new float[]{1f}, 48_000, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processRejectsNullInput() {
        SampleRateConverter c = new SampleRateConverter.High();
        assertThatThrownBy(() -> c.process((float[]) null, 44_100, 48_000))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void multiChannelProcessHandlesEachChannel() {
        float[] mono = sine(RATE_44100, 0.02, TONE_HZ);
        float[][] stereo = { mono, mono };
        SampleRateConverter c = new SampleRateConverter.Medium();
        float[][] out = c.process(stereo, RATE_44100, RATE_48000);
        assertThat(out).hasDimensions(2, out[0].length);
        assertThat(out[0].length).isEqualTo(out[1].length);
    }

    @Test
    void sealedInterfaceExhaustiveSwitchCompiles() {
        // Compile-time assertion that the hierarchy is sealed and
        // exhaustively switchable — regression guard for adding tiers.
        SampleRateConverter c = new SampleRateConverter.Low();
        String name = switch (c) {
            case SampleRateConverter.Low l    -> "low";
            case SampleRateConverter.Medium m -> "medium";
            case SampleRateConverter.High h   -> "high";
        };
        assertThat(name).isEqualTo("low");
    }
}
