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
    void singleDifferingPixelExceedsDefaultFraction() {
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
        assertThatThrownBy(() -> new ImageDiff(0, 0.0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultShiftRadiusAbsorbsSubPixelTextDrift() {
        // Simulates the 1-3 px vertical font-metric drift seen across
        // JavaFX rendering environments: a 3-px-wide white-on-black
        // "glyph stroke" shifted down by 3 rows in actual. Without shift
        // tolerance every glyph edge is a fully-saturated pixel diff
        // (white↔black, Δ=255) and the bidirectional check sees both
        // vanished and new pixels. With the default radius the windowed
        // lookup finds matching neighbours and reports zero diffs.
        BufferedImage e = solid(20, 20, 0xff000000);
        BufferedImage a = solid(20, 20, 0xff000000);
        for (int y = 5; y < 15; y++) {
            for (int x = 9; x < 12; x++) {
                e.setRGB(x, y, 0xffffffff);          // expected: rows 5-14
                a.setRGB(x, y + 3, 0xffffffff);      // actual:   rows 8-17
            }
        }
        ImageDiff.Result rDefault = new ImageDiff().compare(e, a);
        assertThat(rDefault.differingPixels()).isZero();
        assertThat(rDefault.passed()).isTrue();

        // Strict pixel-pair comparison surfaces the drift as a real diff.
        ImageDiff.Result rStrict = new ImageDiff(4, 0.005, 0).compare(e, a);
        assertThat(rStrict.differingPixels()).isPositive();
        assertThat(rStrict.passed()).isFalse();
    }

    @Test
    void defaultShiftRadiusStillCatchesLargerDrift() {
        // 6-pixel shift exceeds the default window — must still fail.
        BufferedImage e = solid(30, 30, 0xff000000);
        BufferedImage a = solid(30, 30, 0xff000000);
        for (int y = 5; y < 15; y++) {
            for (int x = 14; x < 17; x++) {
                e.setRGB(x, y, 0xffffffff);
                a.setRGB(x, y + 6, 0xffffffff);
            }
        }
        ImageDiff.Result r = new ImageDiff().compare(e, a);
        assertThat(r.differingPixels()).isPositive();
        assertThat(r.passed()).isFalse();
    }

    @Test
    void shiftRadiusOneCatchesDisappearingContent() {
        // A pixel present in expected but absent in actual must surface
        // as a diff under bidirectional comparison — a forward-only
        // (actual-into-expected) check would miss it because the new
        // background pixel finds plenty of background neighbours in
        // expected.
        BufferedImage e = solid(10, 10, 0xff000000);
        BufferedImage a = solid(10, 10, 0xff000000);
        for (int y = 4; y < 7; y++) {
            for (int x = 4; x < 7; x++) {
                e.setRGB(x, y, 0xffffffff);  // expected has a 3x3 white block
                // actual stays all-black: the block "disappeared"
            }
        }
        ImageDiff.Result r = new ImageDiff().compare(e, a);
        assertThat(r.differingPixels()).isPositive();
        assertThat(r.passed()).isFalse();
    }

    @Test
    void shiftRadiusZeroPreservesStrictPixelPairBehaviour() {
        // r=0 must reproduce the pre-shift-tolerance algorithm: 1 white
        // pixel out of 100 = 1.0% > default 0.5% → fails.
        BufferedImage e = solid(10, 10, 0xff000000);
        BufferedImage a = solid(10, 10, 0xff000000);
        a.setRGB(0, 0, 0xffffffff);
        ImageDiff.Result r = new ImageDiff(4, 0.005, 0).compare(e, a);
        assertThat(r.differingPixels()).isEqualTo(1);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void renderDiffHonoursShiftTolerance() {
        // 1-pixel vertical shift of a white pixel: at the default radius
        // it finds a white neighbour and is NOT marked red.
        BufferedImage e = solid(7, 7, 0xff000000);
        BufferedImage a = solid(7, 7, 0xff000000);
        e.setRGB(3, 3, 0xffffffff);
        a.setRGB(3, 4, 0xffffffff);
        BufferedImage diff = new ImageDiff().renderDiff(e, a);
        // Position (3,4) in actual matches (3,3) in expected within the
        // window — should not be marked red.
        assertThat(diff.getRGB(3, 4)).isNotEqualTo(0xffff0000);
        // With strict comparison, the same shift IS marked red.
        BufferedImage strictDiff = new ImageDiff(4, 0.005, 0).renderDiff(e, a);
        assertThat(strictDiff.getRGB(3, 4)).isEqualTo(0xffff0000);
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
