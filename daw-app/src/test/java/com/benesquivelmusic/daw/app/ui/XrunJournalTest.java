package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The xrun log's store (story 338, rulings E17, E19, E20, E22,
 * E38, E42, E174, E199, E225 and E226): which kinds raise the count, the
 * count's restart and Clear, the newest hundred entries, each drained once,
 * the one entry for the CPU budget markings that never reached the log, the
 * recent gaps, and the resync marks owed to the take apart from the log.
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
        journal.append(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("track-1", 93L), 1);
        journal.noteCpuBudgetEntriesLost(2);
        journal.append(XrunJournal.Detail.DEVICE_STOPPED_RESPONDING, List.of("Interface A"), 1);
        assertThat(journal.drain()).extracting(XrunJournal.Entry::detail)
                .as("premise: the entry for the lost CPU budget markings is written at the read")
                .contains(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);

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

    /**
     * Rulings E174 and E199: CPU budget markings lost before they reached
     * the journal's listener are one entry, written at the next drain as its
     * newest, dated then.
     */
    @Test
    void lostCpuBudgetEntriesAreOneEntryDrainedOnceAndLast() {
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 1);
        journal.noteCpuBudgetEntriesLost(2);
        journal.append(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("track-1", 93L), 1);
        journal.noteCpuBudgetEntriesLost(3);
        wall.set(wall.get().plusSeconds(3));
        nanos.addAndGet(7L);

        List<XrunJournal.Entry> drained = journal.drain();

        assertThat(drained).extracting(XrunJournal.Entry::detail).containsExactly(XrunJournal.Detail.OUTPUT_STARVED,
                XrunJournal.Detail.OVER_CPU_BUDGET, XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED);
        assertThat(drained.getLast().arguments()).as("one entry for every loss handed over since the last read")
                .containsExactly(5L);
        assertThat(drained.getLast().count()).isEqualTo(1L);
        assertThat(drained.getLast().arrivedAt()).as("dated at the drain").isEqualTo(wall.get());
        assertThat(drained.getLast().arrivedNanos()).isEqualTo(nanos.get());
        assertThat(journal.drain()).as("drained once").isEmpty();
        assertThat(journal.counts().gaps()).as("it raises nothing").isEqualTo(1L);
    }

    @Test
    void theEntriesShowTheLostCountAndClearForgetsIt() {
        journal.noteCpuBudgetEntriesLost(4);

        assertThat(journal.entries()).extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments)
                .containsExactly(tuple(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(4L)));
        assertThat(journal.drain()).as("written once, drained once").hasSize(1);

        journal.noteCpuBudgetEntriesLost(6);
        journal.clear();

        assertThat(journal.entries()).as("Clear forgets a count not written yet").isEmpty();
        assertThat(journal.drain()).isEmpty();
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

    /** Ruling E225: the take's resync marks are kept apart from the log, whatever its limit drops. */
    @Test
    void everyResyncIsKeptForTheTakeWhateverTheLogKeeps() {
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        for (int i = 0; i < 150; i++) {
            journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 1);
        }

        assertThat(journal.drain()).as("premise: the log's limit dropped the resync")
                .extracting(XrunJournal.Entry::detail).doesNotContain(XrunJournal.Detail.DRIVER_RESYNC);
        assertThat(journal.drainResyncMarks()).extracting(XrunJournal.ResyncMark::count).containsExactly(1L);
        assertThat(journal.drainResyncMarks()).as("drained once").isEmpty();
    }

    /** Ruling E226: neither the log's Clear nor a restarted count takes a resync from the take. */
    @Test
    void clearAndARestartedCountKeepTheResyncMarks() {
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 2);
        long arrived = nanos.get();

        journal.restartCount();
        journal.clear();

        assertThat(journal.entries()).as("premise: the log is empty").isEmpty();
        assertThat(journal.drainResyncMarks()).containsExactly(new XrunJournal.ResyncMark(arrived, 2L));
    }

    @Test
    void moreThanTheBoundCoalesceTheOldestKeepingEveryCount() {
        for (int i = 0; i < 650; i++) {
            nanos.addAndGet(1L);
            journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        }

        List<XrunJournal.ResyncMark> owed = journal.drainResyncMarks();

        assertThat(owed).hasSizeLessThanOrEqualTo(XrunJournal.RESYNC_MARKS_KEPT);
        assertThat(owed.stream().mapToLong(XrunJournal.ResyncMark::count).sum()).as("every resync once")
                .isEqualTo(650L);
    }

    @Test
    void aMergedMarkCarriesTheEarlierArrival() {
        long first = nanos.addAndGet(1L);
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        for (int i = 0; i < XrunJournal.RESYNC_MARKS_KEPT; i++) {
            nanos.addAndGet(1L);
            journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        }

        List<XrunJournal.ResyncMark> owed = journal.drainResyncMarks();

        assertThat(owed).hasSize(XrunJournal.RESYNC_MARKS_KEPT);
        assertThat(owed.getFirst()).as("the oldest two merged at the first's arrival")
                .isEqualTo(new XrunJournal.ResyncMark(first, 2L));
    }

    /** A merge keeps the earlier arrival of the two, whichever of them was written first. */
    @Test
    void aMergeOfTwoMarksWrittenOutOfArrivalOrderKeepsTheEarlierArrival() {
        long later = nanos.addAndGet(10L);
        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(later, 1L)));
        long earlier = later - 5L;
        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(earlier, 1L)));
        for (int i = 0; i < XrunJournal.RESYNC_MARKS_KEPT - 1; i++) {
            nanos.addAndGet(1L);
            journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        }

        List<XrunJournal.ResyncMark> owed = journal.drainResyncMarks();

        assertThat(owed).hasSize(XrunJournal.RESYNC_MARKS_KEPT);
        assertThat(owed.getFirst()).as("the second written arrived first")
                .isEqualTo(new XrunJournal.ResyncMark(earlier, 2L));
    }

    /** Rulings E270 and E279: an entry of several resync requests is dated at the first; each keeps its own mark. */
    @Test
    void aResyncEntryOfSeveralRequestsIsDatedAtTheFirstAndKeepsEachArrivalForTheTake() {
        long first = nanos.get() - 90_000_000L;
        long second = nanos.get() - 1_000_000L;

        XrunJournal.Entry entry = journal.appendResyncs(List.of(new XrunJournal.ResyncMark(first, 1L),
                new XrunJournal.ResyncMark(second, 1L)));

        assertThat(entry.detail()).isEqualTo(XrunJournal.Detail.DRIVER_RESYNC);
        assertThat(entry.count()).isEqualTo(2L);
        assertThat(entry.arrivedNanos()).as("the first request's arrival").isEqualTo(first);
        assertThat(entry.arrivedAt()).as("the same time on the wall clock").isEqualTo(wall.get().minusMillis(90));
        assertThat(journal.drainResyncMarks()).as("a mark per arrival")
                .containsExactly(new XrunJournal.ResyncMark(first, 1L), new XrunJournal.ResyncMark(second, 1L));
        assertThat(journal.counts().gaps()).isEqualTo(2L);
        assertThat(journal.gapsSince(nanos.get())).as("the 30-second figure counts them from now").isEqualTo(2L);
    }

    /** Ruling E279: a resync entry stands for at least one request. */
    @Test
    void aResyncEntryOfNoRequestIsRefused() {
        assertThatThrownBy(() -> journal.appendResyncs(List.of())).isInstanceOf(IllegalArgumentException.class);

        assertThat(journal.entries()).isEmpty();
    }

    /** Ruling E291: the dates owed to the take are its undrained marks and what the feed says it holds. */
    @Test
    void theDatesOwedToTheTakeAreItsUndrainedMarksAndWhatTheFeedHolds() {
        assertThat(journal.resyncDatesOwed()).as("nothing owed").isEmpty();
        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(100L, 1L), new XrunJournal.ResyncMark(200L, 2L)));
        long[] held = {300L};
        journal.noteResyncDatesHeld(held);
        held[0] = 999L;

        assertThat(journal.resyncDatesOwed()).containsExactlyInAnyOrder(100L, 200L, 300L);
        journal.drainResyncMarks();
        assertThat(journal.resyncDatesOwed()).as("the marks were handed to the take").containsExactly(300L);
        journal.noteResyncDatesHeld(XrunJournal.NO_DATES);
        assertThat(journal.resyncDatesOwed()).isEmpty();
    }

    @Test
    void theStarvationReportIsTheLastOneNoted() {
        journal.reportsOutputStarvation(true);
        assertThat(journal.counts().reportsOutputStarvation()).isTrue();
        journal.reportsOutputStarvation(false);
        assertThat(journal.counts().reportsOutputStarvation()).isFalse();
    }
}
