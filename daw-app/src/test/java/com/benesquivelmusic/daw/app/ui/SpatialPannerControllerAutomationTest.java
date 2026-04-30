package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationRecorder;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.core.spatial.panner.VbapPanner;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the object-parameter automation features added to
 * {@link SpatialPannerController} for story 172 (record-trajectory mode,
 * "Automate &lt;param&gt;" context-menu items, ADM-bound trajectory
 * lanes).
 */
class SpatialPannerControllerAutomationTest {

    private static final String OBJECT_ID = "spatial-track-7";

    private static VbapPanner createStereoPanner() {
        return new VbapPanner(List.of(
                new SpatialPosition(30, 0, 1.0),
                new SpatialPosition(330, 0, 1.0)));
    }

    private static SpatialPannerController createController() {
        return new SpatialPannerController(createStereoPanner(), "Test");
    }

    private static SpatialPannerController createControllerWithContext(
            AutomationData data, AutomationRecorder recorder) {
        SpatialPannerController controller = createController();
        controller.setAutomationContext(data, recorder, OBJECT_ID, 4.0);
        return controller;
    }

    @Test
    void buildAutomationMenuItemsAlwaysReturnsAllParameters() {
        SpatialPannerController controller = createController();

        List<MenuItem> items = controller.buildAutomationMenuItems();

        assertThat(items).hasSize(ObjectParameter.values().length);
        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).getText())
                    .isEqualTo("Automate " + ObjectParameter.values()[i].displayName());
        }
    }

    @Test
    void targetForShouldThrowWhenNoContextWired() {
        SpatialPannerController controller = createController();
        assertThatThrownBy(() -> controller.targetFor(ObjectParameter.X))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void automateParameterShouldCreateLane() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        AutomationLane lane = controller.automateParameter(ObjectParameter.X);

        assertThat(lane).isNotNull();
        assertThat(lane.isVisible()).isTrue();
        assertThat(data.getObjectLane(new ObjectParameterTarget(OBJECT_ID, ObjectParameter.X)))
                .isSameAs(lane);
    }

    @Test
    void automateParameterIsIdempotent() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        AutomationLane first = controller.automateParameter(ObjectParameter.Y);
        AutomationLane second = controller.automateParameter(ObjectParameter.Y);

        assertThat(first).isSameAs(second);
        assertThat(data.getObjectLaneCount()).isEqualTo(1);
    }

    @Test
    void clickingMenuItemCreatesLane() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        MenuItem zItem = controller.buildAutomationMenuItems().stream()
                .filter(it -> it.getText().equals("Automate Z"))
                .findFirst().orElseThrow();
        zItem.fire();

        assertThat(data.getObjectLane(new ObjectParameterTarget(OBJECT_ID, ObjectParameter.Z)))
                .isNotNull();
    }

    @Test
    void recordTrajectoryArmShouldStartAndStopRecorder() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        assertThat(controller.isRecordTrajectoryArmed()).isFalse();
        controller.setRecordTrajectoryArmed(true);
        assertThat(controller.isRecordTrajectoryArmed()).isTrue();
        assertThat(recorder.isRecording()).isTrue();

        UndoableAction action = controller.setRecordTrajectoryArmed(false);
        assertThat(controller.isRecordTrajectoryArmed()).isFalse();
        assertThat(recorder.isRecording()).isFalse();
        // No frames captured — finishRecording returns null.
        assertThat(action).isNull();
    }

    @Test
    void recordTrajectoryShouldCaptureMouseDragSequence() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        // Simulate the host's transport — beats advance with each frame.
        AtomicReference<Double> playhead = new AtomicReference<>(0.0);
        controller.setPlayheadBeatsSupplier(playhead::get);

        controller.setRecordTrajectoryMode(AutomationMode.WRITE);
        controller.setRecordTrajectoryArmed(true);

        // Three drag frames: "user" moves the panner from (-0.5, -0.5) to
        // (0.5, 0.5) while transport runs.
        playhead.set(0.0);
        controller.setSourcePosition(SpatialPosition.fromCartesian(-0.5, -0.5, 0.0));
        playhead.set(1.0);
        controller.setSourcePosition(SpatialPosition.fromCartesian(0.0, 0.0, 0.25));
        playhead.set(2.0);
        controller.setSourcePosition(SpatialPosition.fromCartesian(0.5, 0.5, 0.5));

        UndoableAction action = controller.setRecordTrajectoryArmed(false);

        // X / Y / Z lanes each have three captured points.
        AutomationLane xLane = data.getObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.X));
        AutomationLane zLane = data.getObjectLane(
                new ObjectParameterTarget(OBJECT_ID, ObjectParameter.Z));
        assertThat(xLane.getPointCount()).isEqualTo(3);
        assertThat(zLane.getPointCount()).isEqualTo(3);
        assertThat(xLane.getPoints().get(0).getValue()).isCloseTo(-0.5, within(1e-6));
        assertThat(xLane.getPoints().get(2).getValue()).isCloseTo(0.5, within(1e-6));
        assertThat(zLane.getPoints().get(2).getValue()).isCloseTo(0.5, within(1e-6));
        assertThat(action).isNotNull();
    }

    @Test
    void captureTrajectoryFrameIsNoOpWhenNotArmed() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        // Not armed — moving the panner must not create lanes or points.
        controller.setSourcePosition(SpatialPosition.fromCartesian(0.5, 0.5, 0.5));

        assertThat(data.getObjectLaneCount()).isZero();
    }

    @Test
    void recordTrajectoryWithoutContextIsNoOp() {
        SpatialPannerController controller = createController();
        // Should not throw — graceful no-op.
        UndoableAction action = controller.setRecordTrajectoryArmed(true);
        assertThat(action).isNull();
        assertThat(controller.isRecordTrajectoryArmed()).isFalse();
    }

    @Test
    void setRecordTrajectoryModeRejectsReadMode() {
        SpatialPannerController controller = createController();
        assertThatThrownBy(() -> controller.setRecordTrajectoryMode(AutomationMode.READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTrajectoryOverlayReturnsNullWithoutContext() {
        SpatialPannerController controller = createController();
        assertThat(controller.getTrajectoryOverlay()).isNull();
    }

    @Test
    void getTrajectoryOverlayReturnsSamplerWithContext() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        SpatialPannerController controller = createControllerWithContext(data, recorder);

        assertThat(controller.getTrajectoryOverlay()).isNotNull();
        assertThat(controller.getObjectInstanceId()).isEqualTo(OBJECT_ID);
        assertThat(controller.getAutomationData()).isSameAs(data);
    }
}
