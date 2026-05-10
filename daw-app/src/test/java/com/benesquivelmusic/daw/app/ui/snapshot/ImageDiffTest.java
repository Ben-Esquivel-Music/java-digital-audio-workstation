package com.benesquivelmusic.daw.app.ui.snapshot;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link ImageDiff} — no JavaFX, no rendering.
 *
 * <p>These exercise the diff algorithm so future changes to tolerances
 * or pixel-walk loops don't silently start hiding regressions.</p>
 */
class ImageDiffTest {

    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    @Test
    void identicalImagesPass() {
        BufferedImage a = solid(10, 10, 0xff112233);
        ImageDiff.Result r = new ImageDiff().compare(a, solid(10, 10, 0xff112233));
        assertThat(r.passed()).isTrue();
        assertThat(r.differingPixels()).isZero();
        assertThat(r.maxObservedDelta()).isZero();
    }

    @Test
    void smallChannelDeltaWithinTolerance() {
        // Δ = 3 per channel, below default tolerance of 4 → 0 differing
        // pixels, passes.
        BufferedImage e = solid(20, 20, 0xff808080);
        BufferedImage a = solid(20, 20, 0xff838383);
        ImageDiff.Result r = new ImageDiff().compare(e, a);
        assertThat(r.maxObservedDelta()).isEqualTo(3);
        assertThat(r.differingPixels()).isZero();
        assertThat(r.passed()).isTrue();
    }

    @Test
    void channelDeltaAboveToleranceCountsButPassesIfFewPixels() {
        // 1 differing pixel out of 100 = 1.0% > 0.5% → fails by default.
        BufferedImage e = solid(10, 10, 0xff000000);
        BufferedImage a = solid(10, 10, 0xff000000);
        a.setRGB(0, 0, 0xffffffff);
        ImageDiff.Result r = new ImageDiff().compare(e, a);
        assertThat(r.differingPixels()).isEqualTo(1);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void looserToleranceCanAcceptDifferences() {
        BufferedImage e = solid(10, 10, 0xff000000);
        BufferedImage a = solid(10, 10, 0xff000000);
        a.setRGB(0, 0, 0xffffffff);
        // Allow up to 5% differing pixels.
        ImageDiff.Result r = new ImageDiff(4, 0.05).compare(e, a);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void sizeMismatchFails() {
        ImageDiff.Result r = new ImageDiff().compare(solid(10, 10, 0), solid(11, 10, 0));
        assertThat(r.sizeMismatch()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(r.describe()).contains("size mismatch");
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new ImageDiff(-1, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageDiff(0, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageDiff(0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renderDiffMarksDifferingPixelsRed() {
        BufferedImage e = solid(4, 4, 0xff000000);
        BufferedImage a = solid(4, 4, 0xff000000);
        a.setRGB(2, 2, 0xffffffff);
        BufferedImage diff = new ImageDiff().renderDiff(e, a);
        assertThat(diff.getRGB(2, 2)).isEqualTo(0xffff0000);
        // Non-differing pixels are rendered as desaturated grayscale of expected.
        assertThat(diff.getRGB(0, 0)).isEqualTo(0xff000000);
    }
}
