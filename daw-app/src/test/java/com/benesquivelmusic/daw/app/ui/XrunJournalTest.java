package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The xrun log's store (story 338, rulings E17, E19, E20, E22,
 * E38, E42): which kinds raise the count, the count's restart and Clear, the
 * newest hundred entries, each drained once, and the recent gaps.
 */
class XrunJournalTest {

    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-11-16T12:00:00Z"));
    private final XrunJournal journal = new XrunJournal(wall::get, nanos::get);

    @Test
    void gapsDiscardsOverloadsAndResyncsRaiseTheCountAndNothingElseDoes() {
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 2);
        journal.append(XrunJournal.Detail.BLOCK_NEVER_PLAYED, List.of(), 3);
        journal.append(XrunJournal.Detail.DRIVER_OVERLOAD, List.of(), 1);
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(6.1, 2.7), 4);
        journal.append(XrunJournal.Detail.EXPENSIVE_PROCESSING_BYPASSED, List.of("track-1", 93L), 1);
        journal.append(XrunJournal.Detail.DEVICE_STOPPED_RESPONDING, List.of("Interface A"), 1);

        XrunJournal.Counts counts = journal.counts();
        assertThat(counts.gaps()).as("rulings E17 and E38").isEqualTo(7);
        assertThat(counts.lateBlocks()).as("late blocks are counted apart (ruling E17)").isEqualTo(4);
        assertThat(counts.since()).isEqualTo(wall.get());
    }

    @Test
    void anEntryCarriesItsDetailsArgumentsItsCountAndBothArrivalTimes() {
        XrunJournal.Entry entry = journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(6.1, 2.7), 2);

        assertThat(entry.kind()).isEqualTo(XrunJournal.Kind.LATE);
        assertThat(entry.arguments()).containsExactly(6.1, 2.7);
        assertThat(entry.count()).isEqualTo(2);
        assertThat(entry.arrivedAt()).isEqualTo(wall.get());
        assertThat(entry.arrivedNanos()).isEqualTo(nanos.get());
        assertThatThrownBy(() -> journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(6.1), 1))
                .as("a detail's arguments are its own, in number").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRestartedCountKeepsTheEntriesAndClearForgetsThem() {
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 2);
        journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(6.1, 2.7), 1);
        wall.set(wall.get().plusSeconds(60));

        journal.restartCount();

        assertThat(journal.counts()).isEqualTo(new XrunJournal.Counts(0, 0, wall.get(),
                com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus.UnderrunReporting.NOT_ASKED));
        assertThat(journal.entries()).as("a settings change keeps the log (ruling E22)").hasSize(2);
        assertThat(journal.gapsSince(0L)).isZero();

        journal.append(XrunJournal.Detail.BLOCK_NEVER_PLAYED, List.of(), 1);
        journal.clear();

        assertThat(journal.counts().gaps()).isZero();
        assertThat(journal.entries()).as("Clear empties the log").isEmpty();
        assertThat(journal.drain()).as("and what was not drained yet").isEmpty();
    }

    @Test
    void theJournalKeepsTheNewestHundredAndDrainsEachEntryOnce() {
        for (int i = 1; i <= 130; i++) {
            nanos.addAndGet(1L);
            journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), i);
        }

        List<XrunJournal.Entry> drained = journal.drain();

        assertThat(drained).hasSize(XrunJournal.MAX_ENTRIES);
        assertThat(drained.getFirst().count()).as("the oldest kept is the 31st").isEqualTo(31);
        assertThat(drained.getLast().count()).isEqualTo(130);
        assertThat(journal.entries()).isEqualTo(drained);
        assertThat(journal.counts().gaps()).as("the count stays exact").isEqualTo(130L * 131 / 2);
        assertThat(journal.drain()).as("each entry once").isEmpty();
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        assertThat(journal.drain()).extracting(XrunJournal.Entry::detail)
                .containsExactly(XrunJournal.Detail.DRIVER_RESYNC);
    }

    @Test
    void theRecentGapsAreThoseThatArrivedInTheWindow() {
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 2);
        long early = nanos.get();
        nanos.addAndGet(Duration.ofSeconds(20).toNanos());
        journal.append(XrunJournal.Detail.BLOCK_NEVER_PLAYED, List.of(), 3);
        journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(6.1, 2.7), 9);
        nanos.addAndGet(Duration.ofSeconds(15).toNanos());
        journal.append(XrunJournal.Detail.DRIVER_OVERLOAD, List.of(), 1);

        long now = nanos.get();
        assertThat(journal.gapsSince(now - Duration.ofSeconds(30).toNanos()))
                .as("the first arrived 35 s ago; late blocks are not gaps").isEqualTo(4);
        assertThat(journal.gapsSince(early)).as("an arrival older than the window is not kept").isEqualTo(4);
        assertThat(journal.gapsSince(now)).as("negative control: the newest arrival counts at its own time")
                .isEqualTo(1);
        assertThat(journal.counts().gaps()).isEqualTo(6);
    }

    @Test
    void theStarvationReportIsTheLastOneNoted() {
        journal.reportsOutputStarvation(true);
        assertThat(journal.counts().reportsOutputStarvation()).isTrue();
        journal.reportsOutputStarvation(false);
        assertThat(journal.counts().reportsOutputStarvation()).isFalse();
    }
}
