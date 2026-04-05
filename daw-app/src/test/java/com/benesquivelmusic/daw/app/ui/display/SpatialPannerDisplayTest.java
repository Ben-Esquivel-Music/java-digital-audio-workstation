package com.benesquivelmusic.daw.app.ui.display;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpatialPannerDisplayTest {

    // ── Coordinate mapping: spatialX ↔ pixelX ─────────────────────

    @Test
    void spatialXToPixelXShouldMapOriginToCenter() {
        double result = SpatialPannerDisplay.spatialXToPixelX(0.0, 200.0, 100.0);
        assertThat(result).isEqualTo(200.0);
    }

    @Test
    void spatialXToPixelXShouldMapPositiveRightward() {
        double result = SpatialPannerDisplay.spatialXToPixelX(
                SpatialPannerDisplay.MAX_DISPLAY_DISTANCE, 200.0, 100.0);
        assertThat(result).isEqualTo(300.0);
    }

    @Test
    void spatialXToPixelXShouldMapNegativeLeftward() {
        double result = SpatialPannerDisplay.spatialXToPixelX(
                -SpatialPannerDisplay.MAX_DISPLAY_DISTANCE, 200.0, 100.0);
        assertThat(result).isEqualTo(100.0);
    }

    @Test
    void spatialXToPixelXShouldMapHalfDistance() {
        double half = SpatialPannerDisplay.MAX_DISPLAY_DISTANCE / 2.0;
        double result = SpatialPannerDisplay.spatialXToPixelX(half, 200.0, 100.0);
        assertThat(result).isCloseTo(250.0, within(1e-9));
    }

    // ── Coordinate mapping: spatialY ↔ pixelY ─────────────────────

    @Test
    void spatialYToPixelYShouldMapOriginToCenter() {
        double result = SpatialPannerDisplay.spatialYToPixelY(0.0, 200.0, 100.0);
        assertThat(result).isEqualTo(200.0);
    }

    @Test
    void spatialYToPixelYShouldMapPositiveUpward() {
        double result = SpatialPannerDisplay.spatialYToPixelY(
                SpatialPannerDisplay.MAX_DISPLAY_DISTANCE, 200.0, 100.0);
        // Positive Y = front = upward on screen = lower pixel Y
        assertThat(result).isEqualTo(100.0);
    }

    @Test
    void spatialYToPixelYShouldMapNegativeDownward() {
        double result = SpatialPannerDisplay.spatialYToPixelY(
                -SpatialPannerDisplay.MAX_DISPLAY_DISTANCE, 200.0, 100.0);
        assertThat(result).isEqualTo(300.0);
    }

    // ── Inverse mapping: pixelX → spatialX ─────────────────────────

    @Test
    void pixelXToSpatialXShouldMapCenterToOrigin() {
        double result = SpatialPannerDisplay.pixelXToSpatialX(200.0, 200.0, 100.0);
        assertThat(result).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void pixelXToSpatialXShouldRoundTrip() {
        double original = 3.5;
        double pixel = SpatialPannerDisplay.spatialXToPixelX(original, 200.0, 100.0);
        double recovered = SpatialPannerDisplay.pixelXToSpatialX(pixel, 200.0, 100.0);
        assertThat(recovered).isCloseTo(original, within(1e-9));
    }

    @Test
    void pixelXToSpatialXShouldReturnZeroForZeroRadius() {
        double result = SpatialPannerDisplay.pixelXToSpatialX(200.0, 200.0, 0.0);
        assertThat(result).isEqualTo(0.0);
    }

    // ── Inverse mapping: pixelY → spatialY ─────────────────────────

    @Test
    void pixelYToSpatialYShouldMapCenterToOrigin() {
        double result = SpatialPannerDisplay.pixelYToSpatialY(200.0, 200.0, 100.0);
        assertThat(result).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void pixelYToSpatialYShouldRoundTrip() {
        double original = -2.0;
        double pixel = SpatialPannerDisplay.spatialYToPixelY(original, 200.0, 100.0);
        double recovered = SpatialPannerDisplay.pixelYToSpatialY(pixel, 200.0, 100.0);
        assertThat(recovered).isCloseTo(original, within(1e-9));
    }

    @Test
    void pixelYToSpatialYShouldReturnZeroForZeroRadius() {
        double result = SpatialPannerDisplay.pixelYToSpatialY(150.0, 200.0, 0.0);
        assertThat(result).isEqualTo(0.0);
    }

    // ── Side view: spatialZ ↔ pixelY ───────────────────────────────

    @Test
    void spatialZToPixelYShouldMapOriginToCenter() {
        double result = SpatialPannerDisplay.spatialZToPixelY(0.0, 200.0, 100.0);
        assertThat(result).isEqualTo(200.0);
    }

    @Test
    void spatialZToPixelYShouldMapPositiveUpward() {
        double result = SpatialPannerDisplay.spatialZToPixelY(
                SpatialPannerDisplay.MAX_DISPLAY_DISTANCE, 200.0, 100.0);
        assertThat(result).isEqualTo(100.0);
    }

    @Test
    void pixelYToSpatialZShouldRoundTrip() {
        double original = 4.0;
        double pixel = SpatialPannerDisplay.spatialZToPixelY(original, 200.0, 100.0);
        double recovered = SpatialPannerDisplay.pixelYToSpatialZ(pixel, 200.0, 100.0);
        assertThat(recovered).isCloseTo(original, within(1e-9));
    }

    @Test
    void pixelYToSpatialZShouldReturnZeroForZeroRadius() {
        double result = SpatialPannerDisplay.pixelYToSpatialZ(150.0, 200.0, 0.0);
        assertThat(result).isEqualTo(0.0);
    }

    // ── Clamp ──────────────────────────────────────────────────────

    @Test
    void clampShouldReturnValueWhenInRange() {
        assertThat(SpatialPannerDisplay.clamp(5.0, 0.0, 10.0)).isEqualTo(5.0);
    }

    @Test
    void clampShouldReturnMinWhenBelowRange() {
        assertThat(SpatialPannerDisplay.clamp(-1.0, 0.0, 10.0)).isEqualTo(0.0);
    }

    @Test
    void clampShouldReturnMaxWhenAboveRange() {
        assertThat(SpatialPannerDisplay.clamp(15.0, 0.0, 10.0)).isEqualTo(10.0);
    }

    @Test
    void clampShouldHandleEqualMinMax() {
        assertThat(SpatialPannerDisplay.clamp(5.0, 3.0, 3.0)).isEqualTo(3.0);
    }

    // ── Format helpers ────────────────────────────────────────────

    @Test
    void formatDegreesShouldIncludeDegreeSymbol() {
        String result = SpatialPannerDisplay.formatDegrees(45.0);
        assertThat(result).isEqualTo("45.0\u00B0");
    }

    @Test
    void formatDegreesShouldHandleNegative() {
        String result = SpatialPannerDisplay.formatDegrees(-30.0);
        assertThat(result).isEqualTo("-30.0\u00B0");
    }

    @Test
    void formatDegreesShouldHandleZero() {
        String result = SpatialPannerDisplay.formatDegrees(0.0);
        assertThat(result).isEqualTo("0.0\u00B0");
    }

    @Test
    void formatDistanceShouldIncludeUnitAndTwoDecimals() {
        String result = SpatialPannerDisplay.formatDistance(1.5);
        assertThat(result).isEqualTo("1.50 m");
    }

    @Test
    void formatDistanceShouldHandleZero() {
        String result = SpatialPannerDisplay.formatDistance(0.0);
        assertThat(result).isEqualTo("0.00 m");
    }

    @Test
    void formatDistanceShouldHandleLargeDistance() {
        String result = SpatialPannerDisplay.formatDistance(99.123);
        assertThat(result).isEqualTo("99.12 m");
    }

    // ── Layout constants ──────────────────────────────────────────

    @Test
    void topViewRatioAndSideViewRatioShouldSumToOne() {
        assertThat(SpatialPannerDisplay.TOP_VIEW_RATIO + SpatialPannerDisplay.SIDE_VIEW_RATIO)
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void maxDisplayDistanceShouldBePositive() {
        assertThat(SpatialPannerDisplay.MAX_DISPLAY_DISTANCE).isGreaterThan(0.0);
    }

    // ── getPannerData ─────────────────────────────────────────────

    @Test
    void getPannerDataShouldReturnNullWhenNotSet() {
        SpatialPannerDisplay display = new SpatialPannerDisplay();
        assertThat(display.getPannerData()).isNull();
    }

    // ── Symmetric round-trip across all axes ──────────────────────

    @Test
    void allAxisRoundTripsShouldBeSymmetric() {
        double cx = 300.0;
        double cy = 250.0;
        double r = 120.0;

        for (double value = -8.0; value <= 8.0; value += 2.0) {
            // X axis
            double px = SpatialPannerDisplay.spatialXToPixelX(value, cx, r);
            double recoveredX = SpatialPannerDisplay.pixelXToSpatialX(px, cx, r);
            assertThat(recoveredX).isCloseTo(value, within(1e-9));

            // Y axis
            double py = SpatialPannerDisplay.spatialYToPixelY(value, cy, r);
            double recoveredY = SpatialPannerDisplay.pixelYToSpatialY(py, cy, r);
            assertThat(recoveredY).isCloseTo(value, within(1e-9));

            // Z axis
            double pz = SpatialPannerDisplay.spatialZToPixelY(value, cy, r);
            double recoveredZ = SpatialPannerDisplay.pixelYToSpatialZ(pz, cy, r);
            assertThat(recoveredZ).isCloseTo(value, within(1e-9));
        }
    }
}
