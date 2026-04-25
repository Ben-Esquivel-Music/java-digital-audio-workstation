package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationLaneObjectTargetTest {

    private static final ObjectParameterTarget TARGET =
            new ObjectParameterTarget("spatial-track-7", ObjectParameter.X);

    @Test
    void shouldConstructWithObjectParameterTarget() {
        AutomationLane lane = new AutomationLane(TARGET);

        assertThat(lane.getTarget()).isSameAs(TARGET);
        assertThat(lane.getPointCount()).isZero();
    }

    @Test
    void shouldReturnTargetDefaultWhenEmpty() {
        AutomationLane lane = new AutomationLane(TARGET);

        // X defaults to 0.0
        assertThat(lane.getValueAtTime(0.0)).isEqualTo(0.0);
        assertThat(lane.getValueAtTime(10.0)).isEqualTo(0.0);
    }

    @Test
    void shouldValidateValueAgainstParameterRange() {
        AutomationLane lane = new AutomationLane(TARGET);

        // X is in [-1.0, 1.0]
        assertThatThrownBy(() -> lane.addPoint(new AutomationPoint(1.0, 1.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the valid range");
    }

    @Test
    void getParameterShouldThrowForObjectTarget() {
        AutomationLane lane = new AutomationLane(TARGET);

        assertThatThrownBy(lane::getParameter)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldInterpolateBetweenObjectParameterPoints() {
        // Capture a simple left-to-right pan trajectory across four beats and
        // verify sample-accurate interpolation reproduces the path.
        AutomationLane lane = new AutomationLane(TARGET);
        lane.addPoint(new AutomationPoint(0.0, -1.0));
        lane.addPoint(new AutomationPoint(4.0, 1.0));

        assertThat(lane.getValueAtTime(0.0)).isEqualTo(-1.0);
        assertThat(lane.getValueAtTime(2.0)).isEqualTo(0.0);
        assertThat(lane.getValueAtTime(4.0)).isEqualTo(1.0);
        assertThat(lane.getValueAtTime(1.0)).isEqualTo(-0.5);
    }

    @Test
    void automationDataShouldStoreObjectLanesIndependentlyOfPluginAndMixerLanes() {
        AutomationData data = new AutomationData();
        ObjectParameterTarget xTarget =
                new ObjectParameterTarget("obj-1", ObjectParameter.X);
        ObjectParameterTarget yTarget =
                new ObjectParameterTarget("obj-1", ObjectParameter.Y);

        AutomationLane xLane = data.getOrCreateObjectLane(xTarget);
        AutomationLane yLane = data.getOrCreateObjectLane(yTarget);

        assertThat(xLane).isNotSameAs(yLane);
        assertThat(data.getObjectLaneCount()).isEqualTo(2);
        assertThat(data.getLaneCount()).isZero();
        assertThat(data.getPluginLaneCount()).isZero();
        assertThat(data.getOrCreateObjectLane(xTarget)).isSameAs(xLane);
        assertThat(data.getObjectLanes()).containsKeys(xTarget, yTarget);
    }

    @Test
    void automationDataShouldReportActiveObjectAutomationOnlyWhenPointsExist() {
        AutomationData data = new AutomationData();
        ObjectParameterTarget target =
                new ObjectParameterTarget("obj-1", ObjectParameter.SIZE);

        assertThat(data.hasActiveAutomation(target)).isFalse();
        assertThat(data.getValueAtTime(target, 0.0))
                .isEqualTo(ObjectParameter.SIZE.getDefaultValue());

        AutomationLane lane = data.getOrCreateObjectLane(target);
        lane.addPoint(new AutomationPoint(0.0, 0.25));
        lane.addPoint(new AutomationPoint(4.0, 0.75));

        assertThat(data.hasActiveAutomation(target)).isTrue();
        assertThat(data.getValueAtTime(target, 2.0)).isEqualTo(0.5);
    }

    @Test
    void removingObjectLaneShouldClearIt() {
        AutomationData data = new AutomationData();
        ObjectParameterTarget target =
                new ObjectParameterTarget("obj-1", ObjectParameter.GAIN);

        AutomationLane lane = data.getOrCreateObjectLane(target);
        assertThat(data.removeObjectLane(target)).isSameAs(lane);
        assertThat(data.getObjectLane(target)).isNull();
        assertThat(data.removeObjectLane(target)).isNull();
    }
}
