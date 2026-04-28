package com.benesquivelmusic.daw.sdk.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupRetentionPolicyTest {

    @Test
    void defaultPolicyMatchesIssueSpec() {
        BackupRetentionPolicy d = BackupRetentionPolicy.DEFAULT;
        assertThat(d.keepRecent()).isEqualTo(10);
        assertThat(d.keepHourly()).isEqualTo(24);
        assertThat(d.keepDaily()).isEqualTo(14);
        assertThat(d.keepWeekly()).isEqualTo(8);
        assertThat(d.maxAge()).isEqualTo(Duration.ofDays(30));
        assertThat(d.maxBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(d.enforcesMaxAge()).isTrue();
        assertThat(d.enforcesMaxBytes()).isTrue();
    }

    @Test
    void rejectsNegativeBucketCounts() {
        assertThatThrownBy(() -> new BackupRetentionPolicy(-1, 0, 0, 0, Duration.ZERO, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupRetentionPolicy(0, -1, 0, 0, Duration.ZERO, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupRetentionPolicy(0, 0, -1, 0, Duration.ZERO, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupRetentionPolicy(0, 0, 0, -1, Duration.ZERO, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackupRetentionPolicy(0, 0, 0, 0, Duration.ZERO, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroDurationDisablesAgeCap() {
        BackupRetentionPolicy p = new BackupRetentionPolicy(1, 1, 1, 1, Duration.ZERO, 0L);
        assertThat(p.enforcesMaxAge()).isFalse();
        assertThat(p.enforcesMaxBytes()).isFalse();
    }

    @Test
    void withersReturnUpdatedCopies() {
        BackupRetentionPolicy d = BackupRetentionPolicy.DEFAULT;
        assertThat(d.withKeepRecent(3).keepRecent()).isEqualTo(3);
        assertThat(d.withKeepHourly(0).keepHourly()).isZero();
        assertThat(d.withKeepDaily(7).keepDaily()).isEqualTo(7);
        assertThat(d.withKeepWeekly(2).keepWeekly()).isEqualTo(2);
        assertThat(d.withMaxAge(Duration.ofDays(7)).maxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(d.withMaxBytes(1024L).maxBytes()).isEqualTo(1024L);
    }
}
