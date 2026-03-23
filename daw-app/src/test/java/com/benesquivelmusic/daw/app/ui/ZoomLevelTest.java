package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ZoomLevelTest {

    @Test
    void defaultConstructorShouldStartAtDefaultZoom() {
        ZoomLevel zoom = new ZoomLevel();
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
    }

    @Test
    void parameterizedConstructorShouldSetLevel() {
        ZoomLevel zoom = new ZoomLevel(2.0);
        assertThat(zoom.getLevel()).isEqualTo(2.0);
    }

    @Test
    void constructorShouldClampBelowMin() {
        ZoomLevel zoom = new ZoomLevel(0.001);
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.MIN_ZOOM);
    }

    @Test
    void constructorShouldClampAboveMax() {
        ZoomLevel zoom = new ZoomLevel(200.0);
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.MAX_ZOOM);
    }

    @Test
    void zoomInShouldMultiplyByFactor() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.zoomIn();
        assertThat(zoom.getLevel()).isCloseTo(ZoomLevel.ZOOM_FACTOR, offset(0.0001));
    }

    @Test
    void zoomOutShouldDivideByFactor() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.zoomOut();
        assertThat(zoom.getLevel()).isCloseTo(1.0 / ZoomLevel.ZOOM_FACTOR, offset(0.0001));
    }

    @Test
    void zoomInShouldNotExceedMax() {
        ZoomLevel zoom = new ZoomLevel(ZoomLevel.MAX_ZOOM);
        zoom.zoomIn();
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.MAX_ZOOM);
    }

    @Test
    void zoomOutShouldNotGoBelowMin() {
        ZoomLevel zoom = new ZoomLevel(ZoomLevel.MIN_ZOOM);
        zoom.zoomOut();
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.MIN_ZOOM);
    }

    @Test
    void zoomToFitShouldResetToDefault() {
        ZoomLevel zoom = new ZoomLevel(5.0);
        zoom.zoomToFit();
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
    }

    @Test
    void setLevelShouldClampToRange() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.setLevel(-1.0);
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.MIN_ZOOM);

        zoom.setLevel(999.0);
        assertThat(zoom.getLevel()).isEqualTo(ZoomLevel.MAX_ZOOM);
    }

    @Test
    void setLevelShouldAcceptValidValues() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.setLevel(3.5);
        assertThat(zoom.getLevel()).isEqualTo(3.5);
    }

    @Test
    void canZoomInShouldBeTrueWhenBelowMax() {
        ZoomLevel zoom = new ZoomLevel();
        assertThat(zoom.canZoomIn()).isTrue();
    }

    @Test
    void canZoomInShouldBeFalseAtMax() {
        ZoomLevel zoom = new ZoomLevel(ZoomLevel.MAX_ZOOM);
        assertThat(zoom.canZoomIn()).isFalse();
    }

    @Test
    void canZoomOutShouldBeTrueWhenAboveMin() {
        ZoomLevel zoom = new ZoomLevel();
        assertThat(zoom.canZoomOut()).isTrue();
    }

    @Test
    void canZoomOutShouldBeFalseAtMin() {
        ZoomLevel zoom = new ZoomLevel(ZoomLevel.MIN_ZOOM);
        assertThat(zoom.canZoomOut()).isFalse();
    }

    @Test
    void toPercentageStringShouldFormatCorrectly() {
        ZoomLevel zoom = new ZoomLevel();
        assertThat(zoom.toPercentageString()).isEqualTo("100%");

        zoom.setLevel(2.5);
        assertThat(zoom.toPercentageString()).isEqualTo("250%");

        zoom.setLevel(0.5);
        assertThat(zoom.toPercentageString()).isEqualTo("50%");
    }

    @Test
    void multipleZoomInStepsShouldCompound() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.zoomIn();
        zoom.zoomIn();
        double expected = ZoomLevel.ZOOM_FACTOR * ZoomLevel.ZOOM_FACTOR;
        assertThat(zoom.getLevel()).isCloseTo(expected, offset(0.0001));
    }

    @Test
    void multipleZoomOutStepsShouldCompound() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.zoomOut();
        zoom.zoomOut();
        double expected = 1.0 / (ZoomLevel.ZOOM_FACTOR * ZoomLevel.ZOOM_FACTOR);
        assertThat(zoom.getLevel()).isCloseTo(expected, offset(0.0001));
    }

    @Test
    void zoomInThenZoomOutShouldReturnToOriginal() {
        ZoomLevel zoom = new ZoomLevel();
        zoom.zoomIn();
        zoom.zoomOut();
        assertThat(zoom.getLevel()).isCloseTo(ZoomLevel.DEFAULT_ZOOM, offset(0.0001));
    }

    @Test
    void constantsShouldHaveValidRange() {
        assertThat(ZoomLevel.MIN_ZOOM).isGreaterThan(0);
        assertThat(ZoomLevel.MAX_ZOOM).isGreaterThan(ZoomLevel.MIN_ZOOM);
        assertThat(ZoomLevel.DEFAULT_ZOOM).isBetween(ZoomLevel.MIN_ZOOM, ZoomLevel.MAX_ZOOM);
        assertThat(ZoomLevel.ZOOM_FACTOR).isGreaterThan(1.0);
    }
}
