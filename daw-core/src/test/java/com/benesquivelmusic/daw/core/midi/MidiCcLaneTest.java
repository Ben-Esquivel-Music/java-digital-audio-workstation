package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiCcLaneTest {

    @Test
    void velocityLanePresetHasExpectedDefaults() {
        MidiCcLane lane = MidiCcLane.velocity();
        assertThat(lane.getType()).isEqualTo(MidiCcLaneType.VELOCITY);
        assertThat(lane.getCcNumber()).isEqualTo(-1);
        assertThat(lane.isHighResolution()).isFalse();
        assertThat(lane.getEvents()).isEmpty();
        assertThat(lane.getHeightRatio()).isEqualTo(MidiCcLane.DEFAULT_HEIGHT_RATIO);
    }

    @Test
    void modWheelPresetUsesCc1AndAllowsHighResolution() {
        MidiCcLane lane = MidiCcLane.preset(MidiCcLaneType.MOD_WHEEL, true);
        assertThat(lane.getCcNumber()).isEqualTo(1);
        assertThat(lane.isHighResolution()).isTrue();
        // CC1 pairs with CC33 for 14-bit
        assertThat(MidiCcLaneType.MOD_WHEEL.defaultLsbCcNumber()).isEqualTo(33);
    }

    @Test
    void sustainCannotBeHighResolution() {
        // Sustain (CC 64) is a switch controller — no LSB pair.
        assertThat(MidiCcLaneType.SUSTAIN.defaultLsbCcNumber()).isEqualTo(-1);
        assertThat(MidiCcLaneType.SUSTAIN.supportsHighResolution()).isFalse();
        assertThatThrownBy(() -> MidiCcLane.preset(MidiCcLaneType.SUSTAIN, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arbitraryCcRequiresValidCcNumber() {
        MidiCcLane lane = new MidiCcLane(MidiCcLaneType.ARBITRARY_CC, 7, false, 2);
        assertThat(lane.getCcNumber()).isEqualTo(7);
        assertThat(lane.getChannel()).isEqualTo(2);
        assertThatThrownBy(() ->
                new MidiCcLane(MidiCcLaneType.ARBITRARY_CC, 200, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void breakpointsAreSortedByColumnOnInsert() {
        MidiCcLane lane = MidiCcLane.preset(MidiCcLaneType.MOD_WHEEL, false);
        lane.addEvent(new MidiCcEvent(8, 100));
        lane.addEvent(new MidiCcEvent(2, 30));
        lane.addEvent(new MidiCcEvent(4, 60));

        assertThat(lane.getEvents())
                .extracting(MidiCcEvent::column)
                .containsExactly(2, 4, 8);
    }

    @Test
    void valueAtInterpolatesLinearlyBetweenBreakpoints() {
        MidiCcLane lane = MidiCcLane.preset(MidiCcLaneType.MOD_WHEEL, false);
        lane.addEvent(new MidiCcEvent(0, 0));
        lane.addEvent(new MidiCcEvent(10, 100));

        assertThat(lane.valueAt(0)).isEqualTo(0);
        assertThat(lane.valueAt(5)).isEqualTo(50);
        assertThat(lane.valueAt(10)).isEqualTo(100);
        // Holds at the endpoint values outside the range.
        assertThat(lane.valueAt(-3)).isEqualTo(0);
        assertThat(lane.valueAt(50)).isEqualTo(100);
    }

    @Test
    void valueAtReturnsZeroWhenEmpty() {
        MidiCcLane lane = MidiCcLane.preset(MidiCcLaneType.EXPRESSION, false);
        assertThat(lane.valueAt(5)).isEqualTo(0);
    }

    @Test
    void msbLsbDecomposes14BitValueCorrectly() {
        // 14-bit value 8192 = MSB 64, LSB 0 (pitch-bend centre)
        MidiCcEvent centre = MidiCcEvent.ofMsbLsb(0, 64, 0);
        assertThat(centre.value()).isEqualTo(8192);
        assertThat(centre.msb()).isEqualTo(64);
        assertThat(centre.lsb()).isEqualTo(0);

        MidiCcEvent maxed = new MidiCcEvent(0, MidiCcEvent.MAX_14BIT);
        assertThat(maxed.msb()).isEqualTo(127);
        assertThat(maxed.lsb()).isEqualTo(127);
    }

    @Test
    void heightRatioMustBePositive() {
        MidiCcLane lane = MidiCcLane.velocity();
        lane.setHeightRatio(2.5);
        assertThat(lane.getHeightRatio()).isEqualTo(2.5);
        assertThatThrownBy(() -> lane.setHeightRatio(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lane.setHeightRatio(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
