package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class TrackHeightZoomTest {

    @Test
    void defaultConstructorShouldStartAtDefaultHeight() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.DEFAULT_TRACK_HEIGHT);
    }

    @Test
    void parameterizedConstructorShouldSetHeight() {
        TrackHeightZoom zoom = new TrackHeightZoom(120.0);
        assertThat(zoom.getTrackHeight()).isEqualTo(120.0);
    }

    @Test
    void constructorShouldClampBelowMin() {
        TrackHeightZoom zoom = new TrackHeightZoom(5.0);
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.MIN_TRACK_HEIGHT);
    }

    @Test
    void constructorShouldClampAboveMax() {
        TrackHeightZoom zoom = new TrackHeightZoom(500.0);
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.MAX_TRACK_HEIGHT);
    }

    @Test
    void zoomInShouldIncreaseHeight() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        double before = zoom.getTrackHeight();
        zoom.zoomIn();
        assertThat(zoom.getTrackHeight()).isGreaterThan(before);
    }

    @Test
    void zoomInShouldMultiplyByFactor() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        zoom.zoomIn();
        double expected = TrackHeightZoom.DEFAULT_TRACK_HEIGHT * TrackHeightZoom.ZOOM_FACTOR;
        assertThat(zoom.getTrackHeight()).isCloseTo(expected, offset(0.001));
    }

    @Test
    void zoomOutShouldDecreaseHeight() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        double before = zoom.getTrackHeight();
        zoom.zoomOut();
        assertThat(zoom.getTrackHeight()).isLessThan(before);
    }

    @Test
    void zoomOutShouldDivideByFactor() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        zoom.zoomOut();
        double expected = TrackHeightZoom.DEFAULT_TRACK_HEIGHT / TrackHeightZoom.ZOOM_FACTOR;
        assertThat(zoom.getTrackHeight()).isCloseTo(expected, offset(0.001));
    }

    @Test
    void zoomInShouldNotExceedMax() {
        TrackHeightZoom zoom = new TrackHeightZoom(TrackHeightZoom.MAX_TRACK_HEIGHT);
        zoom.zoomIn();
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.MAX_TRACK_HEIGHT);
    }

    @Test
    void zoomOutShouldNotGoBelowMin() {
        TrackHeightZoom zoom = new TrackHeightZoom(TrackHeightZoom.MIN_TRACK_HEIGHT);
        zoom.zoomOut();
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.MIN_TRACK_HEIGHT);
    }

    @Test
    void resetToDefaultShouldRestoreDefaultHeight() {
        TrackHeightZoom zoom = new TrackHeightZoom(150.0);
        zoom.resetToDefault();
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.DEFAULT_TRACK_HEIGHT);
    }

    @Test
    void canZoomInShouldBeTrueWhenBelowMax() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        assertThat(zoom.canZoomIn()).isTrue();
    }

    @Test
    void canZoomInShouldBeFalseAtMax() {
        TrackHeightZoom zoom = new TrackHeightZoom(TrackHeightZoom.MAX_TRACK_HEIGHT);
        assertThat(zoom.canZoomIn()).isFalse();
    }

    @Test
    void canZoomOutShouldBeTrueWhenAboveMin() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        assertThat(zoom.canZoomOut()).isTrue();
    }

    @Test
    void canZoomOutShouldBeFalseAtMin() {
        TrackHeightZoom zoom = new TrackHeightZoom(TrackHeightZoom.MIN_TRACK_HEIGHT);
        assertThat(zoom.canZoomOut()).isFalse();
    }

    @Test
    void setHeightShouldClampToRange() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        zoom.setTrackHeight(-10.0);
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.MIN_TRACK_HEIGHT);

        zoom.setTrackHeight(1000.0);
        assertThat(zoom.getTrackHeight()).isEqualTo(TrackHeightZoom.MAX_TRACK_HEIGHT);
    }

    @Test
    void toPercentageStringShouldFormatCorrectly() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        assertThat(zoom.toPercentageString()).isEqualTo("100%");

        zoom.setTrackHeight(TrackHeightZoom.DEFAULT_TRACK_HEIGHT * 2);
        assertThat(zoom.toPercentageString()).isEqualTo("200%");

        zoom.setTrackHeight(TrackHeightZoom.DEFAULT_TRACK_HEIGHT / 2);
        assertThat(zoom.toPercentageString()).isEqualTo("50%");
    }

    @Test
    void multipleZoomStepsShouldCompound() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        zoom.zoomIn();
        zoom.zoomIn();
        double expected = TrackHeightZoom.DEFAULT_TRACK_HEIGHT
                * TrackHeightZoom.ZOOM_FACTOR * TrackHeightZoom.ZOOM_FACTOR;
        assertThat(zoom.getTrackHeight()).isCloseTo(expected, offset(0.001));
    }

    @Test
    void zoomInThenZoomOutShouldReturnToOriginal() {
        TrackHeightZoom zoom = new TrackHeightZoom();
        zoom.zoomIn();
        zoom.zoomOut();
        assertThat(zoom.getTrackHeight()).isCloseTo(TrackHeightZoom.DEFAULT_TRACK_HEIGHT, offset(0.001));
    }

    @Test
    void constantsShouldHaveValidRange() {
        assertThat(TrackHeightZoom.MIN_TRACK_HEIGHT).isGreaterThan(0);
        assertThat(TrackHeightZoom.MAX_TRACK_HEIGHT).isGreaterThan(TrackHeightZoom.MIN_TRACK_HEIGHT);
        assertThat(TrackHeightZoom.DEFAULT_TRACK_HEIGHT).isBetween(
                TrackHeightZoom.MIN_TRACK_HEIGHT, TrackHeightZoom.MAX_TRACK_HEIGHT);
        assertThat(TrackHeightZoom.ZOOM_FACTOR).isGreaterThan(1.0);
    }
}
