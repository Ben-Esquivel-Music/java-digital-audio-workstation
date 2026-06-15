package com.benesquivelmusic.daw.core.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkingSessionTest {

    private static final Instant START = Instant.parse("2026-03-21T09:00:00Z");

    @Test
    void startCreatesActiveSessionWithDefaultName() {
        WorkingSession session = WorkingSession.start(START, ZoneOffset.UTC);

        assertThat(session.isActive()).isTrue();
        assertThat(session.endTime()).isNull();
        assertThat(session.recordedTime()).isEqualTo(Duration.ZERO);
        assertThat(session.id()).isNotBlank();
        assertThat(session.name()).isEqualTo("Working session \u2014 2026-03-21");
    }

    @Test
    void defaultNameFormatsDate() {
        assertThat(WorkingSession.defaultName(LocalDate.of(2026, 3, 21)))
                .isEqualTo("Working session \u2014 2026-03-21");
    }

    @Test
    void withersReturnNewInstances() {
        WorkingSession base = WorkingSession.start(START, ZoneOffset.UTC);

        WorkingSession renamed = base.withName("Tracking day 2");
        assertThat(renamed.name()).isEqualTo("Tracking day 2");
        assertThat(base.name()).isNotEqualTo("Tracking day 2");

        WorkingSession noted = base.withNotes("guitar comping");
        assertThat(noted.notes()).isEqualTo("guitar comping");
        assertThat(base.notes()).isEmpty();
    }

    @Test
    void sealingMarksSessionClosed() {
        Instant end = START.plus(Duration.ofHours(4));
        WorkingSession sealed = WorkingSession.start(START, ZoneOffset.UTC).sealed(end);

        assertThat(sealed.isActive()).isFalse();
        assertThat(sealed.endTime()).isEqualTo(end);
        assertThat(sealed.elapsed(null)).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void elapsedUsesNowWhileActive() {
        WorkingSession active = WorkingSession.start(START, ZoneOffset.UTC);
        assertThat(active.elapsed(START.plus(Duration.ofHours(2))))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void linkingArtifactsAppendsImmutably() {
        WorkingSession session = WorkingSession.start(START, ZoneOffset.UTC)
                .withTake("take-1")
                .withCheckpoint("checkpoint-1")
                .withJournalSegment("journal-001.bin");

        assertThat(session.takeIds()).containsExactly("take-1");
        assertThat(session.checkpointIds()).containsExactly("checkpoint-1");
        assertThat(session.journalSegments()).containsExactly("journal-001.bin");
    }

    @Test
    void linkListsAreUnmodifiable() {
        WorkingSession session = WorkingSession.start(START, ZoneOffset.UTC).withTake("take-1");
        assertThatThrownBy(() -> session.takeIds().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThatThrownBy(() -> WorkingSession.start(START, ZoneOffset.UTC)
                .sealed(START.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeRecordedTime() {
        assertThatThrownBy(() -> new WorkingSession("id", "n", START, null,
                Duration.ofSeconds(-1), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
