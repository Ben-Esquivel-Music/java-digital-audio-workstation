package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ArrangementNavigatorTest {

    @Test
    void defaultConstructorShouldInitializeComponents() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThat(navigator.getHorizontalZoom()).isNotNull();
        assertThat(navigator.getVerticalZoom()).isNotNull();
        assertThat(navigator.getScrollPosition()).isNotNull();
        assertThat(navigator.getMinimapModel()).isNotNull();
    }

    @Test
    void defaultZoomShouldBeAtDefaultLevel() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThat(navigator.getHorizontalZoom().getLevel()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
        assertThat(navigator.getVerticalZoom().getTrackHeight()).isEqualTo(TrackHeightZoom.DEFAULT_TRACK_HEIGHT);
    }

    @Test
    void defaultScrollShouldBeAtOrigin() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThat(navigator.getScrollPosition().getHorizontalOffsetBeats()).isEqualTo(0.0);
        assertThat(navigator.getScrollPosition().getVerticalOffsetPixels()).isEqualTo(0.0);
    }

    // ── Horizontal zoom at cursor ──────────────────────────────────────────

    @Test
    void zoomHorizontalAtShouldZoomInAndAdjustScroll() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);

        double scrollBefore = navigator.getScrollPosition().getHorizontalOffsetBeats();
        double zoomBefore = navigator.getHorizontalZoom().getLevel();

        navigator.zoomHorizontalAt(1, 400.0);

        assertThat(navigator.getHorizontalZoom().getLevel()).isGreaterThan(zoomBefore);
    }

    @Test
    void zoomHorizontalAtShouldPreserveCursorPosition() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        navigator.getScrollPosition().setHorizontalOffsetBeats(10.0);

        double cursorX = 400.0;
        double ppbBefore = navigator.currentPixelsPerBeat();
        double cursorBeatBefore = navigator.getScrollPosition().getHorizontalOffsetBeats()
                + cursorX / ppbBefore;

        navigator.zoomHorizontalAt(1, cursorX);

        double ppbAfter = navigator.currentPixelsPerBeat();
        double cursorBeatAfter = navigator.getScrollPosition().getHorizontalOffsetBeats()
                + cursorX / ppbAfter;

        assertThat(cursorBeatAfter).isCloseTo(cursorBeatBefore, offset(0.01));
    }

    @Test
    void zoomHorizontalAtWithZeroStepsShouldNotChange() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        double zoomBefore = navigator.getHorizontalZoom().getLevel();
        navigator.zoomHorizontalAt(0, 400.0);
        assertThat(navigator.getHorizontalZoom().getLevel()).isEqualTo(zoomBefore);
    }

    @Test
    void zoomHorizontalAtNegativeStepsShouldZoomOut() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        double zoomBefore = navigator.getHorizontalZoom().getLevel();

        navigator.zoomHorizontalAt(-1, 400.0);

        assertThat(navigator.getHorizontalZoom().getLevel()).isLessThan(zoomBefore);
    }

    @Test
    void zoomHorizontalShouldZoomAtCenter() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        double zoomBefore = navigator.getHorizontalZoom().getLevel();

        navigator.zoomHorizontal(2);

        assertThat(navigator.getHorizontalZoom().getLevel()).isGreaterThan(zoomBefore);
    }

    // ── Vertical zoom ──────────────────────────────────────────────────────

    @Test
    void zoomVerticalShouldIncreaseTrackHeight() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        double heightBefore = navigator.getVerticalZoom().getTrackHeight();

        navigator.zoomVertical(1);

        assertThat(navigator.getVerticalZoom().getTrackHeight()).isGreaterThan(heightBefore);
    }

    @Test
    void zoomVerticalNegativeShouldDecreaseTrackHeight() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        double heightBefore = navigator.getVerticalZoom().getTrackHeight();

        navigator.zoomVertical(-1);

        assertThat(navigator.getVerticalZoom().getTrackHeight()).isLessThan(heightBefore);
    }

    @Test
    void zoomVerticalMultipleStepsShouldCompound() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        double heightBefore = navigator.getVerticalZoom().getTrackHeight();

        navigator.zoomVertical(3);

        double expected = heightBefore
                * TrackHeightZoom.ZOOM_FACTOR
                * TrackHeightZoom.ZOOM_FACTOR
                * TrackHeightZoom.ZOOM_FACTOR;
        assertThat(navigator.getVerticalZoom().getTrackHeight()).isCloseTo(expected, offset(0.01));
    }

    // ── Keyboard zoom ──────────────────────────────────────────────────────

    @Test
    void keyboardZoomInShouldIncreaseZoom() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        double zoomBefore = navigator.getHorizontalZoom().getLevel();

        navigator.keyboardZoomIn();

        assertThat(navigator.getHorizontalZoom().getLevel()).isGreaterThan(zoomBefore);
    }

    @Test
    void keyboardZoomOutShouldDecreaseZoom() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        double zoomBefore = navigator.getHorizontalZoom().getLevel();

        navigator.keyboardZoomOut();

        assertThat(navigator.getHorizontalZoom().getLevel()).isLessThan(zoomBefore);
    }

    @Test
    void fitAllShouldResetToDefaults() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);

        navigator.zoomHorizontal(5);
        navigator.zoomVertical(3);
        navigator.getScrollPosition().setHorizontalOffsetBeats(50.0);

        navigator.fitAll();

        assertThat(navigator.getHorizontalZoom().getLevel()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
        assertThat(navigator.getVerticalZoom().getTrackHeight()).isEqualTo(TrackHeightZoom.DEFAULT_TRACK_HEIGHT);
        assertThat(navigator.getScrollPosition().getHorizontalOffsetBeats()).isEqualTo(0.0);
        assertThat(navigator.getScrollPosition().getVerticalOffsetPixels()).isEqualTo(0.0);
    }

    // ── Minimap navigation ─────────────────────────────────────────────────

    @Test
    void navigateToMinimapPositionShouldUpdateScroll() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(128.0);

        navigator.navigateToMinimapPosition(0.5);

        assertThat(navigator.getScrollPosition().getHorizontalOffsetBeats()).isGreaterThan(0.0);
    }

    @Test
    void applyMinimapDragShouldScrollHorizontally() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(128.0);

        navigator.applyMinimapDrag(0.1);

        assertThat(navigator.getScrollPosition().getHorizontalOffsetBeats()).isGreaterThan(0.0);
    }

    // ── Viewport state persistence ─────────────────────────────────────────

    @Test
    void captureStateShouldReturnCurrentState() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);

        navigator.zoomHorizontal(2);
        navigator.zoomVertical(1);
        navigator.getScrollPosition().setHorizontalOffsetBeats(10.0);
        navigator.getScrollPosition().setVerticalOffsetPixels(50.0);

        ViewportState state = navigator.captureState();
        assertThat(state.getHorizontalZoom()).isEqualTo(navigator.getHorizontalZoom().getLevel());
        assertThat(state.getTrackHeight()).isEqualTo(navigator.getVerticalZoom().getTrackHeight());
        assertThat(state.getScrollXBeats()).isEqualTo(navigator.getScrollPosition().getHorizontalOffsetBeats());
        assertThat(state.getScrollYPixels()).isEqualTo(navigator.getScrollPosition().getVerticalOffsetPixels());
    }

    @Test
    void restoreStateShouldRestoreAllValues() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        navigator.setTrackCount(20);

        ViewportState state = new ViewportState(2.0, 120.0, 30.0, 100.0);
        navigator.restoreState(state);

        assertThat(navigator.getHorizontalZoom().getLevel()).isEqualTo(2.0);
        assertThat(navigator.getVerticalZoom().getTrackHeight()).isEqualTo(120.0);
        assertThat(navigator.getScrollPosition().getHorizontalOffsetBeats()).isCloseTo(30.0, offset(0.01));
        assertThat(navigator.getScrollPosition().getVerticalOffsetPixels()).isCloseTo(100.0, offset(0.01));
    }

    @Test
    void restoreStateShouldRejectNull() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThatThrownBy(() -> navigator.restoreState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void captureAndRestoreShouldRoundTrip() {
        ArrangementNavigator navigator1 = new ArrangementNavigator();
        navigator1.setViewportWidthPixels(800.0);
        navigator1.setTotalSessionBeats(256.0);
        navigator1.zoomHorizontal(3);
        navigator1.zoomVertical(2);
        navigator1.getScrollPosition().setHorizontalOffsetBeats(20.0);

        ViewportState captured = navigator1.captureState();

        ArrangementNavigator navigator2 = new ArrangementNavigator();
        navigator2.setViewportWidthPixels(800.0);
        navigator2.setTotalSessionBeats(256.0);
        navigator2.restoreState(captured);

        assertThat(navigator2.getHorizontalZoom().getLevel())
                .isCloseTo(navigator1.getHorizontalZoom().getLevel(), offset(0.001));
        assertThat(navigator2.getVerticalZoom().getTrackHeight())
                .isCloseTo(navigator1.getVerticalZoom().getTrackHeight(), offset(0.001));
    }

    // ── Status display ─────────────────────────────────────────────────────

    @Test
    void getZoomPercentageStringShouldDelegateToHorizontalZoom() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThat(navigator.getZoomPercentageString()).isEqualTo("100%");
    }

    @Test
    void currentPixelsPerBeatShouldScaleWithZoom() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        double basePpb = navigator.currentPixelsPerBeat();
        assertThat(basePpb).isCloseTo(ArrangementNavigator.BASE_PIXELS_PER_BEAT, offset(0.001));

        navigator.getHorizontalZoom().setLevel(2.0);
        assertThat(navigator.currentPixelsPerBeat())
                .isCloseTo(ArrangementNavigator.BASE_PIXELS_PER_BEAT * 2.0, offset(0.001));
    }

    @Test
    void getVisibleBeatsShouldDependOnViewportWidth() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        double visibleBeats = navigator.getVisibleBeats();
        assertThat(visibleBeats).isCloseTo(800.0 / ArrangementNavigator.BASE_PIXELS_PER_BEAT, offset(0.001));
    }

    @Test
    void getVisibleBeatsShouldDecreaseWhenZoomedIn() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        double beatsBefore = navigator.getVisibleBeats();

        navigator.getHorizontalZoom().zoomIn();
        double beatsAfter = navigator.getVisibleBeats();

        assertThat(beatsAfter).isLessThan(beatsBefore);
    }

    @Test
    void setTotalSessionBeatsShouldRejectZero() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThatThrownBy(() -> navigator.setTotalSessionBeats(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTotalSessionBeatsShouldRejectNegative() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThatThrownBy(() -> navigator.setTotalSessionBeats(-10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTrackCountShouldRejectNegative() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        assertThatThrownBy(() -> navigator.setTrackCount(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setTrackCountShouldUpdateMinimapModel() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setTrackCount(8);
        assertThat(navigator.getMinimapModel().getTrackCount()).isEqualTo(8);
    }

    @Test
    void minimapShouldSyncAfterZoom() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);

        navigator.zoomHorizontal(3);

        // After zooming in, visible beats decrease, so viewport fraction should decrease
        assertThat(navigator.getMinimapModel().getViewportWidthFraction()).isLessThan(1.0);
    }

    @Test
    void minimapShouldSyncAfterScroll() {
        ArrangementNavigator navigator = new ArrangementNavigator();
        navigator.setViewportWidthPixels(800.0);
        navigator.setTotalSessionBeats(256.0);
        navigator.getHorizontalZoom().setLevel(4.0);

        navigator.navigateToMinimapPosition(0.5);

        assertThat(navigator.getMinimapModel().getViewportStartFraction()).isGreaterThan(0.0);
    }
}
