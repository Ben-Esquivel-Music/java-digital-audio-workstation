package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendDropoutCountersTest {

    @Test
    void noneIsZeroCountsWithoutAStarvationSignal() {
        assertThat(BackendDropoutCounters.NONE)
                .isEqualTo(new BackendDropoutCounters(0L, 0L, 0L, 0L, 0L, false));
    }

    @Test
    void backendsWithoutAnObservedDropoutSignalReportTheInterfaceDefault() {
        assertThat(new WasapiBackend().dropoutCounters()).isSameAs(BackendDropoutCounters.NONE);
        assertThat(new CoreAudioBackend().dropoutCounters()).isSameAs(BackendDropoutCounters.NONE);
        assertThat(new JackBackend().dropoutCounters()).isSameAs(BackendDropoutCounters.NONE);
    }

    @Test
    void everyCountRejectsANegativeValue() {
        assertThatThrownBy(() -> new BackendDropoutCounters(-1L, 0L, 0L, 0L, 0L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputStarvedPeriods");
        assertThatThrownBy(() -> new BackendDropoutCounters(0L, -1L, 0L, 0L, 0L, true))
                .hasMessageContaining("outputDiscardedBlocks");
        assertThatThrownBy(() -> new BackendDropoutCounters(0L, 0L, -1L, 0L, 0L, true))
                .hasMessageContaining("driverOverloads");
        assertThatThrownBy(() -> new BackendDropoutCounters(0L, 0L, 0L, -1L, 0L, true))
                .hasMessageContaining("driverResyncs");
        assertThatThrownBy(() -> new BackendDropoutCounters(0L, 0L, 0L, 0L, -1L, true))
                .hasMessageContaining("inputBlocksDropped");
    }

    @Test
    void plusAddsEachCountAndReportsStarvationWhenEitherSideDoes() {
        BackendDropoutCounters adapter = new BackendDropoutCounters(1L, 2L, 0L, 0L, 3L, true);
        BackendDropoutCounters delegate = new BackendDropoutCounters(10L, 20L, 30L, 40L, 50L, false);

        assertThat(delegate.plus(adapter))
                .isEqualTo(new BackendDropoutCounters(11L, 22L, 30L, 40L, 53L, true));
        assertThat(BackendDropoutCounters.NONE.plus(BackendDropoutCounters.NONE))
                .isEqualTo(BackendDropoutCounters.NONE);
    }
}
