package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Inspect events view ("Captured events") is worded from the recovery
 * summary only, by a pure builder: the changes journaled after the recovery
 * point, the journal's segment-file count, and their types — never a session
 * record — or, when the replay cannot start, the changes that cannot be
 * replayed and why, or, when an interrupted save is promoted, that no
 * journaled change is part of the recovery.
 */
class RecoveryInspectEventsTextTest {

    private static final DateTimeFormatter UTC = RecoveryDialog.clock(ZoneOffset.UTC);
    private static final Instant LAST = Instant.parse("2026-03-21T14:48:30Z");
    private static final Instant WRITTEN = Instant.parse("2026-03-21T14:52:00Z");
    private static final List<String> SESSION_RECORDS = List.of("$session-start", "$checkpoint", "$session-seal");

    @Test
    void aReplayListsItsChangesAfterTheRecoveryPointTheJournalsSegmentFileCountAndTheirTypesOnly() {
        RecoverySummary.Changes two = new RecoverySummary.Changes(2L, 0L, Duration.ofSeconds(40),
                Optional.of("ClipEvent.Moved"), Optional.of(LAST), List.of("MixerEvent.GainChanged", "ClipEvent.Moved"));
        RecoverySummary summary = summary(replay(two), List.of("segment-000.bin", "segment-001.bin"));

        assertThat(RecoveryDialog.buildInspectEventsText(summary, UTC)).isEqualTo(
                "2 changes journaled after the recovery point; the journal has 2 segment files.\n\n"
                        + "Change types:\n  • MixerEvent.GainChanged\n  • ClipEvent.Moved");
        assertThat(RecoveryDialog.inspectEventsText(summary)).as("the public entry renders the same body")
                .isEqualTo(RecoveryDialog.buildInspectEventsText(summary, UTC)).doesNotContain("$");
    }

    @Test
    void aReplayWithNothingAfterTheRecoveryPointSaysSoInTheSingular() {
        RecoverySummary.Changes none = new RecoverySummary.Changes(0L, 0L, Duration.ZERO, Optional.empty(),
                Optional.empty(), List.of());

        assertThat(RecoveryDialog.buildInspectEventsText(summary(replay(none), List.of("segment-000.bin")), UTC))
                .isEqualTo("No changes journaled after the recovery point; the journal has 1 segment file.\n\n"
                        + "Change types:\n  (none)");
    }

    @Test
    void aReplayThatCannotStartListsTheChangesThatCannotBeReplayedAndWhy() {
        RecoverySummary.Changes one = new RecoverySummary.Changes(1L, 0L, Duration.ZERO,
                Optional.of("TrackEvent.Renamed"), Optional.of(LAST), List.of("TrackEvent.Renamed"));
        RecoverySummary summary = summary(new RecoverySummary.ReplayFails(new RecoverySummary.BaseChanged(), one,
                Optional.empty()), List.of("segment-000.bin"));

        assertThat(RecoveryDialog.buildInspectEventsText(summary, UTC)).isEqualTo(
                "1 change journaled; this journal cannot be replayed: the copy of the project kept when this"
                        + " session opened has changed.\n\nChange types:\n  • TrackEvent.Renamed");
    }

    @Test
    void aPromotedInterruptedSaveSaysNoJournaledChangeIsPartOfTheRecovery() {
        RecoverySummary summary = new RecoverySummary(new RecoverySummary.PromoteNewerHead(WRITTEN,
                ".project.daw.0123456789abcdef.tmp"), Optional.of(LAST), true, true, List.of("segment-000.bin"),
                64L, 0L, List.of());

        assertThat(RecoveryDialog.buildInspectEventsText(summary, UTC)).isEqualTo(
                "No journaled changes are part of this recovery: the interrupted save written 2026-03-21 14:52 is a"
                        + " complete, newer copy of the project.");
        assertThat(RecoveryDialog.inspectEventsText(summary)).as("the public entry uses the system zone")
                .contains(RecoveryDialog.clock(ZoneId.systemDefault()).format(WRITTEN));
    }

    private static RecoverySummary.Replay replay(RecoverySummary.Changes changes) {
        return new RecoverySummary.Replay(RecoverySummary.BaseKind.CHECKPOINT_CAPTURE,
                "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), LAST,
                RecoverySummary.HeadRelation.HEAD_OLDER, changes, Optional.empty());
    }

    private static RecoverySummary summary(RecoverySummary.Recovery recovery, List<String> segments) {
        return new RecoverySummary(recovery, Optional.of(LAST), true, true, segments, 4096L, 0L, SESSION_RECORDS);
    }
}
