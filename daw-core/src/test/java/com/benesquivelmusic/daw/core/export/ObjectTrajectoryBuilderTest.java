package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectTrajectoryBuilderTest {

    private static final String OBJECT_ID = "obj-1";

    @Test
    void shouldReturnEmptyTrajectoryWhenNoLanes() {
        AutomationData data = new AutomationData();
        ObjectTrajectory trajectory = ObjectTrajectoryBuilder.build(
                data, OBJECT_ID, ObjectMetadata.DEFAULT, 120.0, 4.0);
        assertThat(trajectory.isEmpty()).isTrue();
    }

    @Test
    void shouldBuildFramesFromXLane() {
        AutomationData data = new AutomationData();
        AutomationLane xLane = data.getOrCreateObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.X));
        xLane.addPoint(new AutomationPoint(0.0, -1.0));
        xLane.addPoint(new AutomationPoint(2.0, 1.0));

        ObjectTrajectory trajectory = ObjectTrajectoryBuilder.build(
                data, OBJECT_ID, ObjectMetadata.DEFAULT, 120.0, 4.0);

        // 120 BPM => 0.5 seconds/beat, breakpoints at beats 0 and 2 → seconds 0 and 1.
        assertThat(trajectory.frames()).hasSize(2);
        assertThat(trajectory.frames().get(0).rtimeSeconds()).isEqualTo(0.0);
        assertThat(trajectory.frames().get(0).durationSeconds()).isEqualTo(1.0);
        assertThat(trajectory.frames().get(0).metadata().x()).isEqualTo(-1.0);
        assertThat(trajectory.frames().get(1).rtimeSeconds()).isEqualTo(1.0);
        assertThat(trajectory.frames().get(1).durationSeconds()).isEqualTo(3.0);
        assertThat(trajectory.frames().get(1).metadata().x()).isEqualTo(1.0);
    }

    @Test
    void shouldUnionBreakpointTimesAcrossLanes() {
        AutomationData data = new AutomationData();
        AutomationLane xLane = data.getOrCreateObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.X));
        AutomationLane yLane = data.getOrCreateObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.Y));
        xLane.addPoint(new AutomationPoint(0.0, 0.5));
        yLane.addPoint(new AutomationPoint(1.0, 0.25));

        ObjectTrajectory trajectory = ObjectTrajectoryBuilder.build(
                data, OBJECT_ID, ObjectMetadata.DEFAULT, 120.0, 2.0);

        // Frames at beats 0 and 1 → seconds 0 and 0.5.
        assertThat(trajectory.frames()).hasSize(2);
        // Frame 0: x = 0.5 from lane (only one point, applies everywhere).
        // y has its only breakpoint at beat 1.0 with value 0.25 — before
        // the first point a lane returns the first point's value.
        assertThat(trajectory.frames().get(0).metadata().x()).isEqualTo(0.5);
        assertThat(trajectory.frames().get(0).metadata().y()).isEqualTo(0.25);
        // Frame 1: x = 0.5, y = 0.25.
        assertThat(trajectory.frames().get(1).metadata().x()).isEqualTo(0.5);
        assertThat(trajectory.frames().get(1).metadata().y()).isEqualTo(0.25);
    }

    @Test
    void shouldFallBackToStaticMetadataForLanesWithoutBreakpoints() {
        AutomationData data = new AutomationData();
        AutomationLane xLane = data.getOrCreateObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.X));
        xLane.addPoint(new AutomationPoint(0.0, 0.7));

        ObjectMetadata stat = new ObjectMetadata(0.0, 0.3, -0.2, 0.4, 0.8);
        ObjectTrajectory trajectory = ObjectTrajectoryBuilder.build(
                data, OBJECT_ID, stat, 120.0, 1.0);

        assertThat(trajectory.frames()).hasSize(1);
        ObjectMetadata frame = trajectory.frames().get(0).metadata();
        assertThat(frame.x()).isEqualTo(0.7);
        assertThat(frame.y()).isEqualTo(stat.y());
        assertThat(frame.z()).isEqualTo(stat.z());
        assertThat(frame.size()).isEqualTo(stat.size());
        assertThat(frame.gain()).isEqualTo(stat.gain());
    }

    @Test
    void shouldFormatAdmTime() {
        assertThat(AdmBwfExporter.formatAdmTime(0.0)).isEqualTo("00:00:00.00000");
        assertThat(AdmBwfExporter.formatAdmTime(1.5)).isEqualTo("00:00:01.50000");
        assertThat(AdmBwfExporter.formatAdmTime(61.25)).isEqualTo("00:01:01.25000");
        assertThat(AdmBwfExporter.formatAdmTime(3661.0)).isEqualTo("01:01:01.00000");
    }
}
