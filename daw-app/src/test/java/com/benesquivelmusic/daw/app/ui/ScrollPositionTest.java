package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ScrollPositionTest {

    @Test
    void defaultConstructorShouldStartAtOrigin() {
        ScrollPosition pos = new ScrollPosition();
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(0.0);
        assertThat(pos.getVerticalOffsetPixels()).isEqualTo(0.0);
    }

    @Test
    void setHorizontalOffsetShouldUpdateValue() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(10.0);
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(10.0);
    }

    @Test
    void setVerticalOffsetShouldUpdateValue() {
        ScrollPosition pos = new ScrollPosition();
        pos.setVerticalOffsetPixels(200.0);
        assertThat(pos.getVerticalOffsetPixels()).isEqualTo(200.0);
    }

    @Test
    void setHorizontalOffsetShouldClampNegativeToZero() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(-5.0);
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void setVerticalOffsetShouldClampNegativeToZero() {
        ScrollPosition pos = new ScrollPosition();
        pos.setVerticalOffsetPixels(-100.0);
        assertThat(pos.getVerticalOffsetPixels()).isEqualTo(0.0);
    }

    @Test
    void setMaxHorizontalShouldClampCurrentValue() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(50.0);
        pos.setMaxHorizontalBeats(30.0);
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(30.0);
    }

    @Test
    void setMaxVerticalShouldClampCurrentValue() {
        ScrollPosition pos = new ScrollPosition();
        pos.setVerticalOffsetPixels(500.0);
        pos.setMaxVerticalPixels(200.0);
        assertThat(pos.getVerticalOffsetPixels()).isEqualTo(200.0);
    }

    @Test
    void setHorizontalOffsetShouldClampToMax() {
        ScrollPosition pos = new ScrollPosition();
        pos.setMaxHorizontalBeats(100.0);
        pos.setHorizontalOffsetBeats(150.0);
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(100.0);
    }

    @Test
    void setVerticalOffsetShouldClampToMax() {
        ScrollPosition pos = new ScrollPosition();
        pos.setMaxVerticalPixels(500.0);
        pos.setVerticalOffsetPixels(600.0);
        assertThat(pos.getVerticalOffsetPixels()).isEqualTo(500.0);
    }

    @Test
    void scrollHorizontalShouldAddDelta() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(10.0);
        pos.scrollHorizontal(5.0);
        assertThat(pos.getHorizontalOffsetBeats()).isCloseTo(15.0, offset(0.001));
    }

    @Test
    void scrollHorizontalNegativeShouldSubtract() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(10.0);
        pos.scrollHorizontal(-3.0);
        assertThat(pos.getHorizontalOffsetBeats()).isCloseTo(7.0, offset(0.001));
    }

    @Test
    void scrollHorizontalShouldClampAtZero() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(2.0);
        pos.scrollHorizontal(-10.0);
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void scrollVerticalShouldAddDelta() {
        ScrollPosition pos = new ScrollPosition();
        pos.setVerticalOffsetPixels(100.0);
        pos.scrollVertical(50.0);
        assertThat(pos.getVerticalOffsetPixels()).isCloseTo(150.0, offset(0.001));
    }

    @Test
    void resetShouldClearBothOffsets() {
        ScrollPosition pos = new ScrollPosition();
        pos.setHorizontalOffsetBeats(10.0);
        pos.setVerticalOffsetPixels(200.0);
        pos.reset();
        assertThat(pos.getHorizontalOffsetBeats()).isEqualTo(0.0);
        assertThat(pos.getVerticalOffsetPixels()).isEqualTo(0.0);
    }

    @Test
    void setMaxHorizontalShouldNotAllowNegative() {
        ScrollPosition pos = new ScrollPosition();
        pos.setMaxHorizontalBeats(-10.0);
        assertThat(pos.getMaxHorizontalBeats()).isEqualTo(0.0);
    }

    @Test
    void setMaxVerticalShouldNotAllowNegative() {
        ScrollPosition pos = new ScrollPosition();
        pos.setMaxVerticalPixels(-10.0);
        assertThat(pos.getMaxVerticalPixels()).isEqualTo(0.0);
    }
}
