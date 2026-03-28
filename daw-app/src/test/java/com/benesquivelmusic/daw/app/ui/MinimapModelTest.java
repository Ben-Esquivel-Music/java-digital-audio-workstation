package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class MinimapModelTest {

    @Test
    void constructorShouldSetDurationAndTrackCount() {
        MinimapModel model = new MinimapModel(128.0, 8);
        assertThat(model.getTotalDurationBeats()).isEqualTo(128.0);
        assertThat(model.getTrackCount()).isEqualTo(8);
    }

    @Test
    void constructorShouldRejectZeroDuration() {
        assertThatThrownBy(() -> new MinimapModel(0.0, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorShouldRejectNegativeDuration() {
        assertThatThrownBy(() -> new MinimapModel(-10.0, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorShouldRejectNegativeTrackCount() {
        assertThatThrownBy(() -> new MinimapModel(128.0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorShouldAllowZeroTrackCount() {
        MinimapModel model = new MinimapModel(128.0, 0);
        assertThat(model.getTrackCount()).isEqualTo(0);
    }

    @Test
    void initialViewportShouldSpanFullSession() {
        MinimapModel model = new MinimapModel(128.0, 8);
        assertThat(model.getViewportStartFraction()).isCloseTo(0.0, offset(0.001));
        assertThat(model.getViewportEndFraction()).isCloseTo(1.0, offset(0.001));
        assertThat(model.getViewportWidthFraction()).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void updateViewportShouldComputeCorrectFractions() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(25.0, 50.0);
        assertThat(model.getViewportStartFraction()).isCloseTo(0.25, offset(0.001));
        assertThat(model.getViewportEndFraction()).isCloseTo(0.75, offset(0.001));
        assertThat(model.getViewportWidthFraction()).isCloseTo(0.50, offset(0.001));
    }

    @Test
    void updateViewportAtStartShouldBeZeroBased() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(0.0, 25.0);
        assertThat(model.getViewportStartFraction()).isCloseTo(0.0, offset(0.001));
        assertThat(model.getViewportEndFraction()).isCloseTo(0.25, offset(0.001));
    }

    @Test
    void updateViewportShouldClampToOne() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(80.0, 50.0);
        assertThat(model.getViewportEndFraction()).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void clickToScrollOffsetShouldCenterViewport() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(0.0, 20.0);
        // Viewport width is 20% of total. Clicking at 50% should center viewport
        // at 50%, so start = 50% - 10% = 40% => 40 beats.
        double scrollOffset = model.clickToScrollOffset(0.5);
        assertThat(scrollOffset).isCloseTo(40.0, offset(0.001));
    }

    @Test
    void clickToScrollOffsetAtStartShouldClampToZero() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(0.0, 20.0);
        double scrollOffset = model.clickToScrollOffset(0.0);
        assertThat(scrollOffset).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void clickToScrollOffsetAtEndShouldClampToMax() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(0.0, 20.0);
        double scrollOffset = model.clickToScrollOffset(1.0);
        // Max start = 1.0 - 0.2 = 0.8 => 80 beats
        assertThat(scrollOffset).isCloseTo(80.0, offset(0.001));
    }

    @Test
    void dragToScrollDeltaShouldScaleByTotalDuration() {
        MinimapModel model = new MinimapModel(200.0, 4);
        double delta = model.dragToScrollDelta(0.1);
        assertThat(delta).isCloseTo(20.0, offset(0.001));
    }

    @Test
    void dragToScrollDeltaNegativeShouldScaleCorrectly() {
        MinimapModel model = new MinimapModel(200.0, 4);
        double delta = model.dragToScrollDelta(-0.05);
        assertThat(delta).isCloseTo(-10.0, offset(0.001));
    }

    @Test
    void setTotalDurationShouldUpdate() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.setTotalDurationBeats(200.0);
        assertThat(model.getTotalDurationBeats()).isEqualTo(200.0);
    }

    @Test
    void setTotalDurationShouldRejectZero() {
        MinimapModel model = new MinimapModel(100.0, 4);
        assertThatThrownBy(() -> model.setTotalDurationBeats(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTrackCountShouldUpdate() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.setTrackCount(12);
        assertThat(model.getTrackCount()).isEqualTo(12);
    }

    @Test
    void setTrackCountShouldRejectNegative() {
        MinimapModel model = new MinimapModel(100.0, 4);
        assertThatThrownBy(() -> model.setTrackCount(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewportWidthFractionShouldMatchStartAndEnd() {
        MinimapModel model = new MinimapModel(100.0, 4);
        model.updateViewport(10.0, 30.0);
        double width = model.getViewportWidthFraction();
        double computedWidth = model.getViewportEndFraction() - model.getViewportStartFraction();
        assertThat(width).isCloseTo(computedWidth, offset(0.0001));
    }
}
