package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ClipCrossfadeProcessorTest {

    // ── Linear crossfade tests ──────────────────────────────────────────────

    @Test
    void linearCrossfadeShouldBlendSmoothly() {
        int numSamples = 100;
        float[][] outgoing = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] incoming = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] output = new float[][] { new float[numSamples] };

        ClipCrossfadeProcessor.process(CrossfadeCurve.LINEAR,
                outgoing, incoming, output, numSamples);

        // Linear: fadeOut + fadeIn = 1.0, so output should be ~1.0 throughout
        for (int i = 0; i < numSamples; i++) {
            assertThat((double) output[0][i])
                    .as("Sample %d", i)
                    .isCloseTo(1.0, offset(0.01));
        }
    }

    @Test
    void linearCrossfadeShouldFadeOutToZero() {
        int numSamples = 100;
        float[][] outgoing = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] incoming = new float[][] { constantBuffer(0.0f, numSamples) };
        float[][] output = new float[][] { new float[numSamples] };

        ClipCrossfadeProcessor.process(CrossfadeCurve.LINEAR,
                outgoing, incoming, output, numSamples);

        // First sample should be ~1.0, last should be ~0.0
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.01));
        assertThat((double) output[0][numSamples - 1]).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void linearCrossfadeShouldFadeInFromZero() {
        int numSamples = 100;
        float[][] outgoing = new float[][] { constantBuffer(0.0f, numSamples) };
        float[][] incoming = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] output = new float[][] { new float[numSamples] };

        ClipCrossfadeProcessor.process(CrossfadeCurve.LINEAR,
                outgoing, incoming, output, numSamples);

        // First sample should be ~0.0, last should be ~1.0
        assertThat((double) output[0][0]).isCloseTo(0.0, offset(0.01));
        assertThat((double) output[0][numSamples - 1]).isCloseTo(1.0, offset(0.01));
    }

    // ── Multi-channel tests ─────────────────────────────────────────────────

    @Test
    void shouldProcessStereoBuffers() {
        int numSamples = 50;
        float[][] outgoing = new float[][] {
                constantBuffer(1.0f, numSamples),
                constantBuffer(0.5f, numSamples)
        };
        float[][] incoming = new float[][] {
                constantBuffer(0.0f, numSamples),
                constantBuffer(1.0f, numSamples)
        };
        float[][] output = new float[][] {
                new float[numSamples],
                new float[numSamples]
        };

        ClipCrossfadeProcessor.process(CrossfadeCurve.LINEAR,
                outgoing, incoming, output, numSamples);

        // Check first and last samples for both channels
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.01));
        assertThat((double) output[0][numSamples - 1]).isCloseTo(0.0, offset(0.01));
        assertThat((double) output[1][0]).isCloseTo(0.5, offset(0.01));
        assertThat((double) output[1][numSamples - 1]).isCloseTo(1.0, offset(0.01));
    }

    // ── All curve types produce output ──────────────────────────────────────

    @ParameterizedTest
    @EnumSource(CrossfadeCurve.class)
    void allCurveTypesShouldProduceValidOutput(CrossfadeCurve curve) {
        int numSamples = 100;
        float[][] outgoing = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] incoming = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] output = new float[][] { new float[numSamples] };

        ClipCrossfadeProcessor.process(curve, outgoing, incoming, output, numSamples);

        // All samples should be close to 1.0 (fadeOut * 1.0 + fadeIn * 1.0)
        for (int i = 0; i < numSamples; i++) {
            assertThat((double) output[0][i])
                    .as("Curve %s sample %d", curve, i)
                    .isBetween(0.9, 1.5);
        }
    }

    @ParameterizedTest
    @EnumSource(CrossfadeCurve.class)
    void allCurveTypesShouldTransitionFromOutgoingToIncoming(CrossfadeCurve curve) {
        int numSamples = 100;
        float[][] outgoing = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] incoming = new float[][] { constantBuffer(0.0f, numSamples) };
        float[][] output = new float[][] { new float[numSamples] };

        ClipCrossfadeProcessor.process(curve, outgoing, incoming, output, numSamples);

        // Start should be near 1.0, end should be near 0.0
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.01));
        assertThat((double) output[0][numSamples - 1]).isCloseTo(0.0, offset(0.01));
    }

    // ── Equal-power crossfade maintains energy ──────────────────────────────

    @Test
    void equalPowerCrossfadeShouldMaintainEnergy() {
        int numSamples = 100;
        float[][] outgoing = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] incoming = new float[][] { constantBuffer(1.0f, numSamples) };
        float[][] output = new float[][] { new float[numSamples] };

        ClipCrossfadeProcessor.process(CrossfadeCurve.EQUAL_POWER,
                outgoing, incoming, output, numSamples);

        // Equal-power: output should stay above ~1.0 throughout (no dip)
        for (int i = 0; i < numSamples; i++) {
            assertThat((double) output[0][i])
                    .as("Sample %d", i)
                    .isGreaterThanOrEqualTo(0.99);
        }
    }

    // ── Validation tests ────────────────────────────────────────────────────

    @Test
    void shouldRejectNullCurveType() {
        float[][] buf = new float[][] { new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(null, buf, buf, buf, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOutgoingAudio() {
        float[][] buf = new float[][] { new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(
                CrossfadeCurve.LINEAR, null, buf, buf, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullIncomingAudio() {
        float[][] buf = new float[][] { new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(
                CrossfadeCurve.LINEAR, buf, null, buf, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOutput() {
        float[][] buf = new float[][] { new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(
                CrossfadeCurve.LINEAR, buf, buf, null, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectZeroSamples() {
        float[][] buf = new float[][] { new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(
                CrossfadeCurve.LINEAR, buf, buf, buf, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeSamples() {
        float[][] buf = new float[][] { new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(
                CrossfadeCurve.LINEAR, buf, buf, buf, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMismatchedChannelCounts() {
        float[][] mono = new float[][] { new float[10] };
        float[][] stereo = new float[][] { new float[10], new float[10] };
        assertThatThrownBy(() -> ClipCrossfadeProcessor.process(
                CrossfadeCurve.LINEAR, mono, stereo, mono, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same number of channels");
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static float[] constantBuffer(float value, int length) {
        float[] buffer = new float[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = value;
        }
        return buffer;
    }
}
