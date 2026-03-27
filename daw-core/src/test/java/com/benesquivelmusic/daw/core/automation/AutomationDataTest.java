package com.benesquivelmusic.daw.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationDataTest {

    @Test
    void shouldStartEmpty() {
        AutomationData data = new AutomationData();

        assertThat(data.getLaneCount()).isZero();
        assertThat(data.getLanes()).isEmpty();
    }

    @Test
    void shouldReturnNullForAbsentLane() {
        AutomationData data = new AutomationData();
        assertThat(data.getLane(AutomationParameter.VOLUME)).isNull();
    }

    @Test
    void shouldCreateLaneOnDemand() {
        AutomationData data = new AutomationData();

        AutomationLane lane = data.getOrCreateLane(AutomationParameter.VOLUME);

        assertThat(lane).isNotNull();
        assertThat(lane.getParameter()).isEqualTo(AutomationParameter.VOLUME);
        assertThat(data.getLaneCount()).isEqualTo(1);
    }

    @Test
    void shouldReturnExistingLane() {
        AutomationData data = new AutomationData();
        AutomationLane first = data.getOrCreateLane(AutomationParameter.VOLUME);
        AutomationLane second = data.getOrCreateLane(AutomationParameter.VOLUME);

        assertThat(first).isSameAs(second);
        assertThat(data.getLaneCount()).isEqualTo(1);
    }

    @Test
    void shouldManageMultipleLanes() {
        AutomationData data = new AutomationData();
        data.getOrCreateLane(AutomationParameter.VOLUME);
        data.getOrCreateLane(AutomationParameter.PAN);
        data.getOrCreateLane(AutomationParameter.SEND_LEVEL);

        assertThat(data.getLaneCount()).isEqualTo(3);
    }

    @Test
    void shouldRemoveLane() {
        AutomationData data = new AutomationData();
        data.getOrCreateLane(AutomationParameter.VOLUME);

        AutomationLane removed = data.removeLane(AutomationParameter.VOLUME);

        assertThat(removed).isNotNull();
        assertThat(data.getLane(AutomationParameter.VOLUME)).isNull();
        assertThat(data.getLaneCount()).isZero();
    }

    @Test
    void shouldReturnNullWhenRemovingAbsentLane() {
        AutomationData data = new AutomationData();
        assertThat(data.removeLane(AutomationParameter.VOLUME)).isNull();
    }

    @Test
    void shouldReturnDefaultValueWhenNoLaneExists() {
        AutomationData data = new AutomationData();

        assertThat(data.getValueAtTime(AutomationParameter.VOLUME, 0.0)).isEqualTo(1.0);
        assertThat(data.getValueAtTime(AutomationParameter.PAN, 0.0)).isEqualTo(0.0);
        assertThat(data.getValueAtTime(AutomationParameter.SEND_LEVEL, 5.0)).isEqualTo(0.0);
    }

    @Test
    void shouldDelegateValueLookupToLane() {
        AutomationData data = new AutomationData();
        AutomationLane lane = data.getOrCreateLane(AutomationParameter.VOLUME);
        lane.addPoint(new AutomationPoint(0.0, 1.0));
        lane.addPoint(new AutomationPoint(4.0, 0.0));

        assertThat(data.getValueAtTime(AutomationParameter.VOLUME, 2.0)).isEqualTo(0.5);
    }

    @Test
    void shouldReturnUnmodifiableLanesMap() {
        AutomationData data = new AutomationData();
        data.getOrCreateLane(AutomationParameter.VOLUME);

        assertThat(data.getLanes()).hasSize(1);
        // The map should be unmodifiable
        assertThat(data.getLanes().getClass().getName()).contains("Unmodifiable");
    }
}
