package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A take's dropout figures (story 338, rulings E24, E45, E48 and E49): blocks
 * lost are the take's ring overflows, its starved blocks and the input blocks
 * the engine lost before the rings since the take started; dropouts add the
 * truncation runs; the figures stay as the last take's until the next take
 * starts or a clear.
 */
class TakeDropoutTallyTest {

    private final AtomicLong lostBeforeTheRings = new AtomicLong(40);
    private final TakeDropoutTally tally = new TakeDropoutTally(lostBeforeTheRings::get, lostBeforeTheRings::get);
    private final FakeCounts take = new FakeCounts();
    private final AtomicBoolean throwNextRead = new AtomicBoolean();
    private final InternalError unreadable = new InternalError("injected: the count cannot be read");

    @Test
    void thereAreNoFiguresBeforeTheFirstAudioTake() {
        assertThat(tally.current()).isEmpty();
    }

    @Test
    void blocksLostAddTheRingsTheStarvedBlocksAndTheLossesBeforeTheRingsSinceTheTakeStarted() {
        tally.takeStarted(take);
        take.overflow = 3;
        take.starved = 5;
        take.truncatedFrames = 700;
        take.episodes = 2;
        lostBeforeTheRings.addAndGet(7);

        TakeDropouts figures = tally.current().orElseThrow();

        assertThat(figures).isEqualTo(new TakeDropouts(3 + 5 + 7, 2, 700, true));
        assertThat(figures.dropouts()).as("each lost block and each truncation run").isEqualTo(17);
    }

    @Test
    void theLossesBeforeTheRingsAreLatchedWhenTheTakeStopsAndItsOwnCountsStayReadable() {
        tally.takeStarted(take);
        lostBeforeTheRings.addAndGet(4);
        take.starved = 1;

        tally.takeStopped();
        lostBeforeTheRings.addAndGet(100);
        take.starved = 2;

        assertThat(tally.current()).as("a later stream's losses never reach the finished take")
                .contains(new TakeDropouts(4 + 2, 0, 0, false));
    }

    @Test
    void theNextTakeReplacesTheLastTakesFiguresAndAMidiOnlyTakeHasNone() {
        tally.takeStarted(take);
        take.overflow = 9;
        tally.takeStopped();
        FakeCounts next = new FakeCounts();

        tally.takeStarted(next);
        assertThat(tally.current()).contains(new TakeDropouts(0, 0, 0, true));
        tally.takeStopped();

        tally.takeStarted(null);
        assertThat(tally.current()).as("a MIDI-only take started: no audio figures").isEmpty();
        tally.takeStopped();
        assertThat(tally.current()).isEmpty();
    }

    /** Ruling E97: a finished take that lost nothing keeps its figures, all zero, for the dialog's line. */
    @Test
    void aFinishedTakeThatLostNothingKeepsItsZeroFigures() {
        tally.takeStarted(take);
        tally.takeStopped();

        assertThat(tally.current()).contains(new TakeDropouts(0, 0, 0, false));
    }

    @Test
    void clearForgetsAFinishedTakeAndLeavesATakeStillRecordingAlone() {
        tally.takeStarted(take);
        take.overflow = 1;
        tally.clear();
        assertThat(tally.current()).as("the take in flight keeps its figures")
                .contains(new TakeDropouts(1, 0, 0, true));

        tally.takeStopped();
        assertThat(tally.current()).as("negative control: the last take's figures stay until a clear")
                .isPresent();
        tally.clear();

        assertThat(tally.current()).isEqualTo(Optional.empty());
    }

    @Test
    void aSecondStopLatchesNothingNew() {
        tally.takeStarted(take);
        lostBeforeTheRings.addAndGet(2);
        tally.takeStopped();
        lostBeforeTheRings.addAndGet(50);
        tally.takeStopped();

        assertThat(tally.current().orElseThrow().blocksLost()).isEqualTo(2);
    }

    /** A punch take's figures are its files': the pipeline's file counts, not the rings' raw ones (E76, E86). */
    @Test
    void aPipelinesFiguresAreTheCountsOfTheTakesFiles() {
        RecordingPipeline pipeline = mock(RecordingPipeline.class);
        when(pipeline.getOverflowCount()).thenReturn(9L);
        when(pipeline.getFileOverflowBlocks()).thenReturn(3L);
        when(pipeline.getStarvedInputBlocks()).thenReturn(2L);
        when(pipeline.getTruncatedFrames()).thenReturn(12L);
        when(pipeline.getFileTruncatedFrames()).thenReturn(2L);
        when(pipeline.getTruncationEpisodes()).thenReturn(2L);
        when(pipeline.getFileTruncationEpisodes()).thenReturn(1L);

        tally.takeStarted(TakeDropoutTally.TakeCounts.of(pipeline));

        assertThat(tally.current()).contains(new TakeDropouts(3 + 2, 1, 2, true));
    }

    /**
     * Rulings E86 and E256: a loss before the rings joins the take's file
     * counts; the rings' raw count, which drops outside a punch region also
     * raise, is never the take's figure.
     */
    @Test
    void aLossBeforeTheRingsJoinsTheTakesFileCountsNotItsRingsRawCount() {
        RecordingPipeline pipeline = mock(RecordingPipeline.class);
        when(pipeline.getOverflowCount()).thenReturn(2L);
        tally.takeStarted(TakeDropoutTally.TakeCounts.of(pipeline));

        lostBeforeTheRings.incrementAndGet();

        TakeDropouts figures = tally.current().orElseThrow();
        assertThat(figures).as("the rings' raw drops are not the take's; the loss before the rings counts")
                .isEqualTo(new TakeDropouts(1, 0, 0, true));
        assertThat(figures.dropouts()).isEqualTo(1);
    }

    /**
     * Ruling E261: the take's baseline is the engine's read made as the take
     * starts, made once, and every later figure reads the ordinary count.
     */
    @Test
    void aTakeBaselinesTheReadMadeAsItStartsAndCountsOnFromTheOrdinaryRead() {
        AtomicInteger startReads = new AtomicInteger();
        TakeDropoutTally split = new TakeDropoutTally(() -> {
            startReads.incrementAndGet();
            return 40L;
        }, lostBeforeTheRings::get);
        lostBeforeTheRings.set(41);
        split.takeStarted(take);
        lostBeforeTheRings.set(43);

        assertThat(split.current()).contains(new TakeDropouts(3, 0, 0, true));
        split.takeStopped();
        assertThat(split.current()).contains(new TakeDropouts(3, 0, 0, false));
        assertThat(startReads).as("only the take's start makes that read").hasValue(1);
    }

    /**
     * Ruling E261, through a real engine: a backend's dropout report that
     * fails at the read the take baselines on brings none of the blocks it
     * lost before the take into the take's figure.
     */
    @Test
    void aReportThatFailsAsTheTakeStartsImportsNothingLostBeforeIt() {
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyTakeStart");
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 128));
        engine.setStreamingProvision(new StreamingProvision("SpyTakeStart",
                List.of(new BackendStreamRung(spy, new DeviceId("SpyTakeStart", "Interface A")))));
        try {
            engine.startAudioOutput();
            spy.dropouts = inputDrops(3);
            spy.nextDropoutsHook.set(() -> {
                throw new IllegalStateException("the drop report broke as the take started");
            });
            TakeDropoutTally engineTally = new TakeDropoutTally(engine::capturedInputBlocksLostAsATakeStarts,
                    engine::capturedInputBlocksLost);

            engineTally.takeStarted(take);

            assertThat(spy.nextDropoutsHook.get()).as("premise: the take's start read met the broken report")
                    .isNull();
            assertThat(engineTally.current().orElseThrow().blocksLost())
                    .as("the blocks lost before the take are not its own").isZero();
            spy.dropouts = inputDrops(4);
            assertThat(engineTally.current().orElseThrow().blocksLost()).isEqualTo(1);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    /**
     * Ruling E261: no test fails a backend's report through the record
     * coordinator as a take starts recording (the one that fails it through
     * the coordinator does so at the Stop), so its source pins the wiring:
     * the tally baselines on the engine's read made as a take starts.
     */
    @Test
    void theRecordCoordinatorGivesTheTallyTheEnginesReadMadeAsATakeStarts() throws IOException {
        Path source = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/RecordCoordinator.java");
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                Files.readString(source, StandardCharsets.UTF_8))).replaceAll("\\s+", "");
        assertThat(code)
                .contains("newTakeDropoutTally(this::capturedInputBlocksLostAsATakeStarts,this::capturedInputBlocksLost)")
                .contains("audioEngine.capturedInputBlocksLostAsATakeStarts()");
    }

    /** Rulings E214 and E245: a start whose count cannot be read changes nothing, and imports no old losses. */
    @Test
    void aCountThatCannotBeReadWhenATakeStartsLeavesTheFiguresAsTheyWere() {
        TakeDropoutTally unreliable = tallyWhoseNextReadThrows();
        unreliable.takeStarted(take);
        take.overflow = 2;
        lostBeforeTheRings.addAndGet(3);
        unreliable.takeStopped();
        Optional<TakeDropouts> lastTake = unreliable.current();
        assertThat(lastTake).as("fixture: the finished take's figures").contains(new TakeDropouts(5, 0, 0, false));

        throwNextRead.set(true);
        assertThatThrownBy(() -> unreliable.takeStarted(new FakeCounts())).isSameAs(unreadable);

        assertThat(unreliable.current()).isEqualTo(lastTake);
    }

    /** Ruling E245: a Stop whose count cannot be read keeps what the cell showed and counts no later loss. */
    @Test
    void aCountThatCannotBeReadWhenATakeStopsKeepsTheFigureTheCellShowedAndStopsCounting() {
        TakeDropoutTally unreliable = tallyWhoseNextReadThrows();
        unreliable.takeStarted(take);
        lostBeforeTheRings.set(43);
        take.starved = 1;
        assertThat(unreliable.current()).contains(new TakeDropouts(4, 0, 0, true));

        lostBeforeTheRings.set(45);
        throwNextRead.set(true);
        assertThatThrownBy(unreliable::takeStopped).isSameAs(unreadable);

        assertThat(unreliable.current()).as("the last take, at what the cell showed")
                .contains(new TakeDropouts(4, 0, 0, false));
        lostBeforeTheRings.set(145);
        assertThat(unreliable.current()).as("a later loss never reaches it").contains(new TakeDropouts(4, 0, 0, false));
    }

    /** Ruling E245: what a take lost before the rings never falls below what the figures showed for it. */
    @Test
    void aTakeNeverShowsLessThanItShowedBefore() {
        tally.takeStarted(take);
        lostBeforeTheRings.set(45);
        assertThat(tally.current()).contains(new TakeDropouts(5, 0, 0, true));

        lostBeforeTheRings.set(43);
        assertThat(tally.current()).as("a count that dipped while the take records")
                .contains(new TakeDropouts(5, 0, 0, true));
        tally.takeStopped();

        assertThat(tally.current()).as("and at its Stop").contains(new TakeDropouts(5, 0, 0, false));
    }

    /** A tally over {@link #lostBeforeTheRings} whose next read throws {@link #unreadable} once asked to. */
    private TakeDropoutTally tallyWhoseNextReadThrows() {
        LongSupplier reads = () -> {
            if (throwNextRead.getAndSet(false)) {
                throw unreadable;
            }
            return lostBeforeTheRings.get();
        };
        return new TakeDropoutTally(reads, reads);
    }

    @Test
    void negativeFiguresAreRefused() {
        assertThatThrownBy(() -> new TakeDropouts(-1, 0, 0, false)).isInstanceOf(IllegalArgumentException.class);
    }

    private static BackendDropoutCounters inputDrops(long dropped) {
        return new BackendDropoutCounters(0, 0, 0, 0, dropped, false);
    }

    private static final class FakeCounts implements TakeDropoutTally.TakeCounts {
        long overflow;
        long starved;
        long truncatedFrames;
        long episodes;

        @Override
        public long overflowBlocks() {
            return overflow;
        }

        @Override
        public long starvedBlocks() {
            return starved;
        }

        @Override
        public long truncatedFrames() {
            return truncatedFrames;
        }

        @Override
        public long truncationEpisodes() {
            return episodes;
        }
    }
}
