package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationRecorderTest {

    private static final PluginParameterTarget THRESHOLD = new PluginParameterTarget(
            "compressor#1", 0, "Threshold", -60.0, 0.0, -20.0, "dB");

    @Test
    void shouldRejectNonWritingMode() {
        AutomationRecorder recorder = new AutomationRecorder(new AutomationData());

        assertThatThrownBy(() -> recorder.beginRecording(AutomationMode.READ))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.beginRecording(AutomationMode.OFF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnNullWhenNothingWasRecorded() {
        AutomationRecorder recorder = new AutomationRecorder(new AutomationData());
        recorder.beginRecording(AutomationMode.WRITE);

        assertThat(recorder.finishRecording("Record")).isNull();
    }

    @Test
    void writeModeShouldOverwriteExistingPointsInsideWindow() {
        AutomationData data = new AutomationData();
        AutomationLane lane = data.getOrCreateLane(AutomationParameter.VOLUME);
        // Pre-existing automation at beats 0.25 and 0.75 that must be replaced.
        lane.addPoint(new AutomationPoint(0.25, 0.25));
        lane.addPoint(new AutomationPoint(0.75, 0.25));
        // Pre-existing point outside the recording window must be preserved.
        lane.addPoint(new AutomationPoint(3.0, 0.1));

        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.WRITE);
        recorder.recordValue(AutomationParameter.VOLUME, 0.0, 0.5);
        recorder.recordValue(AutomationParameter.VOLUME, 0.5, 0.6);
        recorder.recordValue(AutomationParameter.VOLUME, 1.0, 0.7);
        UndoableAction undoable = recorder.finishRecording("Write Volume");

        assertThat(undoable).isNotNull();
        List<AutomationPoint> points = lane.getPoints();
        // New breakpoints at 0.0, 0.5, 1.0; preserved breakpoint at 3.0.
        assertThat(points).hasSize(4);
        assertThat(points.get(0).getValue()).isEqualTo(0.5);
        assertThat(points.get(1).getValue()).isEqualTo(0.6);
        assertThat(points.get(2).getValue()).isEqualTo(0.7);
        assertThat(points.get(3).getTimeInBeats()).isEqualTo(3.0);
    }

    @Test
    void latchModeShouldCaptureFromFirstTouchUntilStop() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.LATCH);

        // First touch starts writing at beat 2.0.
        recorder.recordValue(AutomationParameter.PAN, 2.0, -0.5);
        recorder.recordValue(AutomationParameter.PAN, 3.0, 0.25);
        recorder.recordValue(AutomationParameter.PAN, 4.0, 0.5);
        recorder.finishRecording("Record Pan");

        List<AutomationPoint> points = data.getLane(AutomationParameter.PAN).getPoints();
        assertThat(points).hasSize(3);
        assertThat(points.get(0).getTimeInBeats()).isEqualTo(2.0);
        assertThat(points.get(0).getValue()).isEqualTo(-0.5);
        assertThat(points.get(2).getValue()).isEqualTo(0.5);
    }

    @Test
    void touchModeShouldStopWritingAfterTouchEnd() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.TOUCH);

        recorder.recordValue(AutomationParameter.VOLUME, 1.0, 0.8);
        recorder.recordValue(AutomationParameter.VOLUME, 2.0, 0.9);
        recorder.touchEnd(AutomationParameter.VOLUME, 2.0);

        // After touchEnd, further values for the same target are ignored.
        recorder.recordValue(AutomationParameter.VOLUME, 3.0, 0.2);

        recorder.finishRecording("Touch");

        List<AutomationPoint> points = data.getLane(AutomationParameter.VOLUME).getPoints();
        assertThat(points).hasSize(2);
        assertThat(points.get(0).getTimeInBeats()).isEqualTo(1.0);
        assertThat(points.get(1).getTimeInBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldRecordPluginParameterAutomation() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.WRITE);

        recorder.recordValue(THRESHOLD, 0.0, -30.0);
        recorder.recordValue(THRESHOLD, 1.0, -25.0);
        UndoableAction undoable = recorder.finishRecording("Record Threshold");

        AutomationLane lane = data.getPluginLane(THRESHOLD);
        assertThat(lane.getPointCount()).isEqualTo(2);
        assertThat(lane.getPoints().get(0).getValue()).isEqualTo(-30.0);
        assertThat(lane.getPoints().get(1).getValue()).isEqualTo(-25.0);

        // The compound action has already been applied by the recorder, so
        // pushing it on an undo stack and undoing restores the original state.
        undoable.undo();
        assertThat(lane.getPointCount()).isZero();
    }

    @Test
    void shouldClampRecordedValueToTargetRange() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.WRITE);

        // Value of 2.0 is outside pan range [-1, 1].
        recorder.recordValue(AutomationParameter.PAN, 0.0, 2.0);
        recorder.finishRecording("Record Pan");

        AutomationPoint point = data.getLane(AutomationParameter.PAN).getPoints().getFirst();
        assertThat(point.getValue()).isEqualTo(1.0);
    }

    @Test
    void undoShouldRestoreOriginalLaneState() {
        AutomationData data = new AutomationData();
        AutomationLane lane = data.getOrCreateLane(AutomationParameter.VOLUME);
        AutomationPoint original = new AutomationPoint(0.5, 0.3);
        lane.addPoint(original);

        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.WRITE);
        recorder.recordValue(AutomationParameter.VOLUME, 0.0, 0.7);
        recorder.recordValue(AutomationParameter.VOLUME, 1.0, 0.9);
        UndoableAction undoable = recorder.finishRecording("Record");

        assertThat(lane.getPoints()).doesNotContain(original);

        undoable.undo();

        assertThat(lane.getPoints()).containsExactly(original);
    }

    @Test
    void redoAfterUndoShouldReapplyCompoundChanges() {
        AutomationData data = new AutomationData();
        AutomationRecorder recorder = new AutomationRecorder(data);
        recorder.beginRecording(AutomationMode.WRITE);
        recorder.recordValue(AutomationParameter.VOLUME, 0.0, 0.4);
        recorder.recordValue(AutomationParameter.VOLUME, 1.0, 0.8);
        UndoableAction undoable = recorder.finishRecording("Record");

        int sizeAfterRecord = data.getLane(AutomationParameter.VOLUME).getPointCount();
        undoable.undo();
        assertThat(data.getLane(AutomationParameter.VOLUME).getPointCount()).isZero();

        undoable.execute(); // redo
        assertThat(data.getLane(AutomationParameter.VOLUME).getPointCount())
                .isEqualTo(sizeAfterRecord);
    }
}
