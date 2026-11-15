package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SDK value a backend reports its own threads' failure counters with
 * (story 337, ruling E4): "no own threads, zero counts" by default, counts
 * that are never negative, and a sum an adapter uses to add its drain's
 * counts to the backend it wraps.
 */
class BackendThreadCountersTest {

    @Test
    void noneIsZeroCountsWithNoHeartbeatAndNoFaults() {
        BackendThreadCounters none = BackendThreadCounters.NONE;

        assertThat(none.callbackFaults()).isZero();
        assertThat(none.inputBlocksLost()).isZero();
        assertThat(none.inputHalts()).isZero();
        assertThat(none.deviceNotificationFaults()).isZero();
        assertThat(none.deviceCallbackPeriods()).isEmpty();
        assertThat(none.lastCallbackFault()).isEmpty();
        assertThat(none.lastInputFault()).isEmpty();
        assertThat(none.lastDeviceNotificationFault()).isEmpty();
    }

    @Test
    void aBackendWithNoThreadsOfItsOwnReportsNone() {
        assertThat(new MockAudioBackend().threadCounters()).isEqualTo(BackendThreadCounters.NONE);
    }

    @Test
    void negativeCountsAreRejected() {
        assertThatThrownBy(() -> counters(-1, 0, 0, 0, OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callbackFaults");
        assertThatThrownBy(() -> counters(0, -1, 0, 0, OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputBlocksLost");
        assertThatThrownBy(() -> counters(0, 0, -1, 0, OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputHalts");
        assertThatThrownBy(() -> counters(0, 0, 0, -1, OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceNotificationFaults");
        assertThatThrownBy(() -> counters(0, 0, 0, 0, OptionalLong.of(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceCallbackPeriods");
    }

    @Test
    void nullComponentsAreRejected() {
        assertThatThrownBy(() -> new BackendThreadCounters(0, 0, 0, 0, null,
                Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BackendThreadCounters(0, 0, 0, 0, OptionalLong.empty(),
                null, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void plusSumsTheCountsAndTheHeartbeatsThatArePresent() {
        IllegalStateException callback = new IllegalStateException("callback");
        IllegalStateException input = new IllegalStateException("input");
        BackendThreadCounters delegate = new BackendThreadCounters(3, 1, 0, 2, OptionalLong.of(100),
                Optional.of(callback), Optional.empty(), Optional.empty());
        BackendThreadCounters drain = new BackendThreadCounters(0, 4, 1, 0, OptionalLong.empty(),
                Optional.empty(), Optional.of(input), Optional.empty());

        BackendThreadCounters sum = delegate.plus(drain);

        assertThat(sum.callbackFaults()).isEqualTo(3);
        assertThat(sum.inputBlocksLost()).isEqualTo(5);
        assertThat(sum.inputHalts()).isEqualTo(1);
        assertThat(sum.deviceNotificationFaults()).isEqualTo(2);
        assertThat(sum.deviceCallbackPeriods())
                .as("present when either side has a device heartbeat")
                .hasValue(100);
        assertThat(sum.lastCallbackFault()).containsSame(callback);
        assertThat(sum.lastInputFault()).as("the other side's fault when this side has none")
                .containsSame(input);
        assertThat(counters(0, 0, 0, 0, OptionalLong.of(5))
                .plus(counters(0, 0, 0, 0, OptionalLong.of(7))).deviceCallbackPeriods())
                .hasValue(12);
        assertThat(BackendThreadCounters.NONE.plus(BackendThreadCounters.NONE))
                .isEqualTo(BackendThreadCounters.NONE);
    }

    @Test
    void plusPrefersThisSidesLastFault() {
        IllegalStateException mine = new IllegalStateException("mine");
        IllegalStateException theirs = new IllegalStateException("theirs");
        BackendThreadCounters left = new BackendThreadCounters(1, 0, 0, 0, OptionalLong.empty(),
                Optional.of(mine), Optional.empty(), Optional.empty());
        BackendThreadCounters right = new BackendThreadCounters(1, 0, 0, 0, OptionalLong.empty(),
                Optional.of(theirs), Optional.empty(), Optional.empty());

        assertThat(left.plus(right).lastCallbackFault()).containsSame(mine);
    }

    private static BackendThreadCounters counters(long callback, long lost, long halts, long device,
                                                  OptionalLong periods) {
        return new BackendThreadCounters(callback, lost, halts, device, periods,
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
