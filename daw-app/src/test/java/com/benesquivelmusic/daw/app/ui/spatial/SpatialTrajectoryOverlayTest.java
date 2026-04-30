package com.benesquivelmusic.daw.app.ui.spatial;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Headless tests for {@link SpatialTrajectoryOverlay}. These rely only on
 * core types (no JavaFX), so they can run without a display.
 */
class SpatialTrajectoryOverlayTest {

    private static final String OBJECT_ID = "obj-1";

    @Test
    void shouldRejectNullArguments() {
        AutomationData data = new AutomationData();
        assertThatThrownBy(() -> new SpatialTrajectoryOverlay(null, OBJECT_ID, 4.0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpatialTrajectoryOverlay(data, null, 4.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidBeatsPerBar() {
        AutomationData data = new AutomationData();
        assertThatThrownBy(() -> new SpatialTrajectoryOverlay(data, OBJECT_ID, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpatialTrajectoryOverlay(data, OBJECT_ID, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeDefaultConfiguration() {
        SpatialTrajectoryOverlay overlay = new SpatialTrajectoryOverlay(
                new AutomationData(), OBJECT_ID, 4.0);
        assertThat(overlay.getLookAheadBars())
                .isEqualTo(SpatialTrajectoryOverlay.DEFAULT_LOOK_AHEAD_BARS);
        assertThat(overlay.getLookBehindBars())
                .isEqualTo(SpatialTrajectoryOverlay.DEFAULT_LOOK_BEHIND_BARS);
        assertThat(overlay.getResolution())
                .isEqualTo(SpatialTrajectoryOverlay.DEFAULT_RESOLUTION);
    }

    @Test
    void shouldReturnDefaultsWhenNoLanes() {
        SpatialTrajectoryOverlay overlay = new SpatialTrajectoryOverlay(
                new AutomationData(), OBJECT_ID, 4.0);
        overlay.setResolution(4);

        List<SpatialTrajectoryOverlay.Point3D> points = overlay.sampleFuture(0.0);

        assertThat(points).hasSize(4);
        for (SpatialTrajectoryOverlay.Point3D p : points) {
            assertThat(p.x()).isEqualTo(ObjectParameter.X.getDefaultValue());
            assertThat(p.y()).isEqualTo(ObjectParameter.Y.getDefaultValue());
            assertThat(p.z()).isEqualTo(ObjectParameter.Z.getDefaultValue());
        }
    }

    @Test
    void shouldSampleFutureFromLanes() {
        AutomationData data = new AutomationData();
        AutomationLane xLane = data.getOrCreateObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.X));
        // Linear ramp from -1 (beat 0) to 1 (beat 16) — 4 bars at 4/4.
        xLane.addPoint(new AutomationPoint(0.0, -1.0));
        xLane.addPoint(new AutomationPoint(16.0, 1.0));

        SpatialTrajectoryOverlay overlay = new SpatialTrajectoryOverlay(data, OBJECT_ID, 4.0);
        overlay.setResolution(5);
        overlay.setLookAheadBars(4.0);

        List<SpatialTrajectoryOverlay.Point3D> points = overlay.sampleFuture(0.0);

        assertThat(points).hasSize(5);
        assertThat(points.get(0).timeInBeats()).isCloseTo(0.0, within(1e-9));
        assertThat(points.get(4).timeInBeats()).isCloseTo(16.0, within(1e-9));
        // X interpolates linearly: at beat 8 (midway), X=0.
        assertThat(points.get(2).x()).isCloseTo(0.0, within(1e-9));
        assertThat(points.get(0).x()).isCloseTo(-1.0, within(1e-9));
        assertThat(points.get(4).x()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldClampPastSampleAtZero() {
        SpatialTrajectoryOverlay overlay = new SpatialTrajectoryOverlay(
                new AutomationData(), OBJECT_ID, 4.0);
        overlay.setResolution(4);
        overlay.setLookBehindBars(2.0);

        // Playhead at 0 — past window must not go negative.
        List<SpatialTrajectoryOverlay.Point3D> past = overlay.samplePast(0.0);
        for (SpatialTrajectoryOverlay.Point3D p : past) {
            assertThat(p.timeInBeats()).isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    void shouldRejectInvalidConfiguration() {
        SpatialTrajectoryOverlay overlay = new SpatialTrajectoryOverlay(
                new AutomationData(), OBJECT_ID, 4.0);
        assertThatThrownBy(() -> overlay.setLookAheadBars(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overlay.setLookBehindBars(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overlay.setResolution(1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
