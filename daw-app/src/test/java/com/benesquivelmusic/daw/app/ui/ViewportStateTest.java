package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ViewportStateTest {

    @Test
    void constructorShouldCaptureAllFields() {
        ViewportState state = new ViewportState(2.0, 100.0, 10.0, 50.0);
        assertThat(state.getHorizontalZoom()).isEqualTo(2.0);
        assertThat(state.getTrackHeight()).isEqualTo(100.0);
        assertThat(state.getScrollXBeats()).isEqualTo(10.0);
        assertThat(state.getScrollYPixels()).isEqualTo(50.0);
    }

    @Test
    void defaultStateShouldReturnDefaultValues() {
        ViewportState state = ViewportState.defaultState();
        assertThat(state.getHorizontalZoom()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
        assertThat(state.getTrackHeight()).isEqualTo(TrackHeightZoom.DEFAULT_TRACK_HEIGHT);
        assertThat(state.getScrollXBeats()).isEqualTo(0.0);
        assertThat(state.getScrollYPixels()).isEqualTo(0.0);
    }

    @Test
    void equalsShouldReturnTrueForIdenticalStates() {
        ViewportState state1 = new ViewportState(1.5, 80.0, 5.0, 25.0);
        ViewportState state2 = new ViewportState(1.5, 80.0, 5.0, 25.0);
        assertThat(state1).isEqualTo(state2);
    }

    @Test
    void equalsShouldReturnFalseForDifferentStates() {
        ViewportState state1 = new ViewportState(1.5, 80.0, 5.0, 25.0);
        ViewportState state2 = new ViewportState(2.0, 80.0, 5.0, 25.0);
        assertThat(state1).isNotEqualTo(state2);
    }

    @Test
    void equalsShouldReturnFalseForNull() {
        ViewportState state = new ViewportState(1.0, 80.0, 0.0, 0.0);
        assertThat(state).isNotEqualTo(null);
    }

    @Test
    void equalsShouldReturnFalseForDifferentType() {
        ViewportState state = new ViewportState(1.0, 80.0, 0.0, 0.0);
        assertThat(state).isNotEqualTo("not a ViewportState");
    }

    @Test
    void hashCodeShouldBeEqualForEqualStates() {
        ViewportState state1 = new ViewportState(1.5, 80.0, 5.0, 25.0);
        ViewportState state2 = new ViewportState(1.5, 80.0, 5.0, 25.0);
        assertThat(state1.hashCode()).isEqualTo(state2.hashCode());
    }

    @Test
    void toStringShouldContainAllValues() {
        ViewportState state = new ViewportState(1.5, 80.0, 5.0, 25.0);
        String str = state.toString();
        assertThat(str).contains("1.50");
        assertThat(str).contains("80.0");
        assertThat(str).contains("5.00");
        assertThat(str).contains("25.0");
    }

    @Test
    void equalsShouldReturnTrueForSameInstance() {
        ViewportState state = new ViewportState(1.0, 80.0, 0.0, 0.0);
        assertThat(state).isEqualTo(state);
    }

    @Test
    void differentTrackHeightShouldMakeStatesNotEqual() {
        ViewportState state1 = new ViewportState(1.0, 80.0, 0.0, 0.0);
        ViewportState state2 = new ViewportState(1.0, 100.0, 0.0, 0.0);
        assertThat(state1).isNotEqualTo(state2);
    }

    @Test
    void differentScrollXShouldMakeStatesNotEqual() {
        ViewportState state1 = new ViewportState(1.0, 80.0, 0.0, 0.0);
        ViewportState state2 = new ViewportState(1.0, 80.0, 5.0, 0.0);
        assertThat(state1).isNotEqualTo(state2);
    }

    @Test
    void differentScrollYShouldMakeStatesNotEqual() {
        ViewportState state1 = new ViewportState(1.0, 80.0, 0.0, 0.0);
        ViewportState state2 = new ViewportState(1.0, 80.0, 0.0, 50.0);
        assertThat(state1).isNotEqualTo(state2);
    }
}
