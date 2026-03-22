package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class TpdfDithererTest {

    @Test
    void shouldQuantizeToTargetBitDepthRange() {
        TpdfDitherer ditherer = new TpdfDitherer(42L);
        double maxVal16 = (1L << 15) - 1; // 32767

        for (int i = 0; i < 1000; i++) {
            double sample = (i / 500.0) - 1.0; // Sweep from -1.0 to 1.0
            double quantized = ditherer.dither(sample, 16);
            assertThat(quantized).isBetween(-32768.0, 32767.0);
        }
    }

    @Test
    void shouldPreserveSilenceApproximately() {
        TpdfDitherer ditherer = new TpdfDitherer(42L);
        double sum = 0.0;
        int count = 100_000;

        for (int i = 0; i < count; i++) {
            sum += ditherer.dither(0.0, 16);
        }

        // Mean of dithered silence should be near zero
        double mean = sum / count;
        assertThat(mean).isCloseTo(0.0, offset(2.0));
    }

    @Test
    void shouldProduceNoiseFloorAtExpectedLevel() {
        // TPDF dithering on 16-bit should produce noise at roughly 1 LSB RMS
        TpdfDitherer ditherer = new TpdfDitherer(42L);
        double sumSquared = 0.0;
        int count = 100_000;

        for (int i = 0; i < count; i++) {
            double quantized = ditherer.dither(0.0, 16);
            sumSquared += quantized * quantized;
        }

        double rms = Math.sqrt(sumSquared / count);
        // TPDF noise should be ~0.5-1.0 LSB RMS
        assertThat(rms).isBetween(0.3, 1.5);
    }

    @Test
    void shouldWorkWith24BitTarget() {
        TpdfDitherer ditherer = new TpdfDitherer(42L);
        double maxVal24 = (1L << 23) - 1;

        double quantized = ditherer.dither(0.5, 24);
        assertThat(quantized).isBetween(-maxVal24 - 1, maxVal24);

        // Half-scale should be approximately half of max
        assertThat(quantized).isCloseTo(maxVal24 * 0.5, offset(2.0));
    }

    @Test
    void shouldClampExtremeValues() {
        TpdfDitherer ditherer = new TpdfDitherer(42L);
        double maxVal16 = (1L << 15) - 1;

        double quantizedMax = ditherer.dither(1.0, 16);
        assertThat(quantizedMax).isLessThanOrEqualTo(maxVal16);

        double quantizedMin = ditherer.dither(-1.0, 16);
        assertThat(quantizedMin).isGreaterThanOrEqualTo(-maxVal16 - 1);
    }
}
