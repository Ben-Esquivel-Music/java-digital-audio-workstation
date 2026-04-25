package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the automation lane rendering and visibility features of
 * {@link ArrangementCanvas}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AutomationLaneCanvasTest {

    // ── Automation lane visibility toggle ────────────────────────────────────

    @Test
    void shouldToggleAutomationLaneVisibility() throws Exception {

        AtomicReference<ArrangementCanvas> ref = new AtomicReference<>();
        Track track = new Track("Audio 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(track));
                ref.set(canvas);

                assertThat(canvas.isAutomationLaneVisible(track)).isFalse();
                canvas.toggleAutomationLane(track);
                assertThat(canvas.isAutomationLaneVisible(track)).isTrue();
                canvas.toggleAutomationLane(track);
                assertThat(canvas.isAutomationLaneVisible(track)).isFalse();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldDefaultToVolumeParameter() throws Exception {

        Track track = new Track("Audio 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AutomationParameter> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(track));
                canvas.toggleAutomationLane(track);
                ref.set(canvas.getAutomationParameter(track));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(AutomationParameter.VOLUME);
    }

    @Test
    void shouldChangeAutomationParameter() throws Exception {

        Track track = new Track("Audio 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AutomationParameter> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(track));
                canvas.toggleAutomationLane(track);
                canvas.setAutomationParameter(track, AutomationParameter.PAN);
                ref.set(canvas.getAutomationParameter(track));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(AutomationParameter.PAN);
    }

    @Test
    void shouldReturnNullParameterWhenLaneNotVisible() throws Exception {

        Track track = new Track("Audio 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AutomationParameter> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(track));
                ref.set(canvas.getAutomationParameter(track));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNull();
    }

    // ── Track Y-position accounting for automation lanes ────────────────────

    @Test
    void shouldResolveTrackIndexWithNoAutomationLanes() throws Exception {

        Track t1 = new Track("Track 1", TrackType.AUDIO);
        Track t2 = new Track("Track 2", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1, t2));
                canvas.setTrackHeight(80.0);

                assertThat(canvas.trackIndexAtY(10.0)).isEqualTo(0);
                assertThat(canvas.trackIndexAtY(85.0)).isEqualTo(1);
                assertThat(canvas.trackIndexAtY(170.0)).isEqualTo(-1);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldResolveTrackIndexWithAutomationLaneExpanded() throws Exception {

        Track t1 = new Track("Track 1", TrackType.AUDIO);
        Track t2 = new Track("Track 2", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1, t2));
                canvas.setTrackHeight(80.0);
                canvas.toggleAutomationLane(t1); // t1 now has 80 + 60 = 140px

                // In track 1's clip area
                assertThat(canvas.trackIndexAtY(10.0)).isEqualTo(0);
                // In track 1's automation lane
                assertThat(canvas.trackIndexAtY(100.0)).isEqualTo(0);
                // In track 2
                assertThat(canvas.trackIndexAtY(145.0)).isEqualTo(1);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldDetectYInAutomationLane() throws Exception {

        Track t1 = new Track("Track 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1));
                canvas.setTrackHeight(80.0);
                canvas.toggleAutomationLane(t1);

                // In the clip area (y=50, within [0, 80])
                assertThat(canvas.isYInAutomationLane(50.0)).isFalse();
                // In the automation sub-lane (y=100, within [80, 140])
                assertThat(canvas.isYInAutomationLane(100.0)).isTrue();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldReturnFalseForYInAutomationLaneWhenNotExpanded() throws Exception {

        Track t1 = new Track("Track 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1));
                canvas.setTrackHeight(80.0);

                assertThat(canvas.isYInAutomationLane(50.0)).isFalse();
                assertThat(canvas.isYInAutomationLane(100.0)).isFalse();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    // ── Rendering with automation data ──────────────────────────────────────

    @Test
    void shouldRenderAutomationEnvelope() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                Track track = new Track("Audio 1", TrackType.AUDIO);

                // Add automation points
                var automationData = track.getAutomationData();
                var lane = automationData.getOrCreateLane(AutomationParameter.VOLUME);
                lane.addPoint(new AutomationPoint(0.0, 1.0));
                lane.addPoint(new AutomationPoint(4.0, 0.5));
                lane.addPoint(new AutomationPoint(8.0, 0.8));

                canvas.setTracks(List.of(track));
                canvas.toggleAutomationLane(track);
                canvas.setPixelsPerBeat(40.0);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldRenderCurvedAutomationEnvelope() throws Exception {

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                Track track = new Track("Audio 1", TrackType.AUDIO);

                var lane = track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME);
                lane.addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.CURVED));
                lane.addPoint(new AutomationPoint(8.0, 1.0));

                canvas.setTracks(List.of(track));
                canvas.toggleAutomationLane(track);
                canvas.refresh();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldReturnAutomationLaneYForExpandedTrack() throws Exception {

        Track t1 = new Track("Track 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Double> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1));
                canvas.setTrackHeight(80.0);
                canvas.toggleAutomationLane(t1);
                ref.set(canvas.automationLaneY(0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(80.0);
    }

    @Test
    void shouldReturnNegativeOneLaneYForCollapsedTrack() throws Exception {

        Track t1 = new Track("Track 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Double> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1));
                ref.set(canvas.automationLaneY(0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(-1.0);
    }

    // ── Lane folding (Issue 568) ───────────────────────────────────────────

    @Test
    void foldedAutomationLaneCollapsesToSummaryStripHeight() throws Exception {
        Track t1 = new Track("Track 1", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Double> expandedH = new AtomicReference<>();
        AtomicReference<Double> foldedH = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1));
                canvas.setTrackHeight(80.0);
                canvas.toggleAutomationLane(t1);
                expandedH.set(canvas.automationLaneHeight(0));
                canvas.toggleAutomationFold(t1);
                foldedH.set(canvas.automationLaneHeight(0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(expandedH.get()).isEqualTo(AutomationLaneRenderer.AUTOMATION_LANE_HEIGHT);
        assertThat(foldedH.get()).isEqualTo(
                com.benesquivelmusic.daw.core.track.TrackFoldState.SUMMARY_STRIP_HEIGHT_PX);
    }

    @Test
    void hiddenAutomationLaneRendersZeroHeightRegardlessOfFoldFlag() throws Exception {
        // A track without a visible automation lane contributes 0 height
        // even if its fold flag is set — we don't hallucinate a strip
        // for data that does not exist.
        Track t1 = new Track("Track 1", TrackType.AUDIO);
        t1.setFoldState(t1.getFoldState().withAutomationFolded(true));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Double> ref = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1));
                canvas.setTrackHeight(80.0);
                ref.set(canvas.automationLaneHeight(0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(0.0);
    }

    @Test
    void hitTestAfterFoldingMatchesCollapsedHeight() throws Exception {
        Track t1 = new Track("Track 1", TrackType.AUDIO);
        Track t2 = new Track("Track 2", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> indexBefore = new AtomicReference<>();
        AtomicReference<Integer> indexAfter = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1, t2));
                canvas.setTrackHeight(80.0);
                canvas.toggleAutomationLane(t1);
                // Expanded: t1 occupies [0, 140); t2 starts at y=140.
                indexBefore.set(canvas.trackIndexAtY(141.0));
                canvas.toggleAutomationFold(t1);
                // Folded: t1 occupies [0, 83); t2 starts at y=83.
                indexAfter.set(canvas.trackIndexAtY(85.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(indexBefore.get()).isEqualTo(1);
        assertThat(indexAfter.get()).isEqualTo(1);
    }

    @Test
    void toggleFoldAllAutomationFoldsThenUnfoldsEveryTrack() throws Exception {
        Track t1 = new Track("T1", TrackType.AUDIO);
        Track t2 = new Track("T2", TrackType.AUDIO);
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] passes = {false, false};
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(List.of(t1, t2));
                canvas.toggleFoldAllAutomation();
                passes[0] = t1.getFoldState().automationFolded()
                        && t2.getFoldState().automationFolded();
                canvas.toggleFoldAllAutomation();
                passes[1] = !t1.getFoldState().automationFolded()
                        && !t2.getFoldState().automationFolded();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(passes[0]).isTrue();
        assertThat(passes[1]).isTrue();
    }
}
