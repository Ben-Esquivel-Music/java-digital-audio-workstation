package com.benesquivelmusic.daw.sdk.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobProgressTest {

    @Test
    void rejectsPercentOutOfRange() {
        assertThatThrownBy(() -> new JobProgress("id", JobProgress.Phase.RUNNING, "x", -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProgress("id", JobProgress.Phase.RUNNING, "x", 1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProgress("id", JobProgress.Phase.RUNNING, "x", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalPhasesIdentifiedCorrectly() {
        assertThat(JobProgress.Phase.QUEUED.isTerminal()).isFalse();
        assertThat(JobProgress.Phase.RUNNING.isTerminal()).isFalse();
        assertThat(JobProgress.Phase.PAUSED.isTerminal()).isFalse();
        assertThat(JobProgress.Phase.COMPLETED.isTerminal()).isTrue();
        assertThat(JobProgress.Phase.FAILED.isTerminal()).isTrue();
        assertThat(JobProgress.Phase.CANCELLED.isTerminal()).isTrue();
    }
}
