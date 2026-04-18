package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationDataPluginLaneTest {

    private static final PluginParameterTarget THRESHOLD = new PluginParameterTarget(
            "compressor#1", 0, "Threshold", -60.0, 0.0, -20.0, "dB");

    private static final PluginParameterTarget RATIO = new PluginParameterTarget(
            "compressor#1", 1, "Ratio", 1.0, 20.0, 4.0, "");

    @Test
    void shouldCreatePluginLanesOnDemand() {
        AutomationData data = new AutomationData();

        AutomationLane lane = data.getOrCreatePluginLane(THRESHOLD);

        assertThat(lane).isNotNull();
        assertThat(lane.getTarget()).isSameAs(THRESHOLD);
        assertThat(data.getPluginLane(THRESHOLD)).isSameAs(lane);
        assertThat(data.getPluginLaneCount()).isEqualTo(1);
    }

    @Test
    void shouldReturnSameLaneForSameTarget() {
        AutomationData data = new AutomationData();

        AutomationLane first = data.getOrCreatePluginLane(THRESHOLD);
        AutomationLane second = data.getOrCreatePluginLane(THRESHOLD);

        assertThat(second).isSameAs(first);
        assertThat(data.getPluginLaneCount()).isEqualTo(1);
    }

    @Test
    void shouldTrackSeparateLanesForSeparateTargets() {
        AutomationData data = new AutomationData();

        data.getOrCreatePluginLane(THRESHOLD);
        data.getOrCreatePluginLane(RATIO);

        assertThat(data.getPluginLaneCount()).isEqualTo(2);
        assertThat(data.getPluginLanes()).containsOnlyKeys(THRESHOLD, RATIO);
    }

    @Test
    void shouldReportActiveAutomationOnlyWhenPointsExist() {
        AutomationData data = new AutomationData();
        data.getOrCreatePluginLane(THRESHOLD);

        assertThat(data.hasActiveAutomation(THRESHOLD)).isFalse();

        data.getPluginLane(THRESHOLD).addPoint(new AutomationPoint(0.0, -10.0));

        assertThat(data.hasActiveAutomation(THRESHOLD)).isTrue();
    }

    @Test
    void shouldEvaluatePluginLaneValueAtTime() {
        AutomationData data = new AutomationData();
        AutomationLane lane = data.getOrCreatePluginLane(THRESHOLD);
        lane.addPoint(new AutomationPoint(0.0, -40.0));
        lane.addPoint(new AutomationPoint(8.0, -10.0));

        assertThat(data.getValueAtTime(THRESHOLD, 4.0)).isEqualTo(-25.0);
    }

    @Test
    void shouldReturnDefaultValueWhenNoLaneExists() {
        AutomationData data = new AutomationData();

        assertThat(data.getValueAtTime(THRESHOLD, 3.0)).isEqualTo(-20.0);
    }

    @Test
    void shouldRemovePluginLane() {
        AutomationData data = new AutomationData();
        data.getOrCreatePluginLane(THRESHOLD);

        assertThat(data.removePluginLane(THRESHOLD)).isNotNull();
        assertThat(data.getPluginLaneCount()).isZero();
        assertThat(data.removePluginLane(THRESHOLD)).isNull();
    }

    @Test
    void mixerAndPluginLanesShouldCoexist() {
        AutomationData data = new AutomationData();
        data.getOrCreateLane(AutomationParameter.VOLUME)
                .addPoint(new AutomationPoint(0.0, 0.5));
        data.getOrCreatePluginLane(THRESHOLD)
                .addPoint(new AutomationPoint(0.0, -30.0));

        assertThat(data.getLaneCount()).isEqualTo(1);
        assertThat(data.getPluginLaneCount()).isEqualTo(1);
        assertThat(data.getValueAtTime(AutomationParameter.VOLUME, 0.0)).isEqualTo(0.5);
        assertThat(data.getValueAtTime(THRESHOLD, 0.0)).isEqualTo(-30.0);
    }
}
