package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window's engine-health feed of the xrun log (story 338):
 * each journal entry becomes one log line worded as rulings E20, E38, E42,
 * E72 and E78 word it, stamped with the transport's position only while it
 * rolls; the log keeps the newest 100 lines; the figures follow the journal
 * and the take; a resync is marked in the take (ruling E40), whatever the
 * log's limit or its Clear dropped (rulings E225 and E226), but not once the
 * take's capture thread has sealed it early (ruling E280); Clear empties
 * the log and the count, and forgets only a finished take's figures (ruling
 * E24). The feed runs on the FX thread; the tests assert off it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
@Tag("slow")
class EngineHealthFeedTest {

    private static final Instant ARRIVED = Instant.parse("2026-11-16T10:00:01.250Z");

    @TempDir
    Path projectDirectory;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final XrunJournal journal = new XrunJournal(InstantSource.fixed(ARRIVED), nanos::get);
    private final AtomicReference<OptionalDouble> rolling = new AtomicReference<>(OptionalDouble.empty());
    private final Map<String, String> tracks = Map.of("t-1", "Lead Vox");
    private final AtomicReference<ShedLogSubscriber> cpuBudgetLog = new AtomicReference<>();
    private DeviceLossRecordingFixture fixture;

    @AfterEach
    void stopEverything() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void eachEntryIsWordedAsRuled() throws Exception {
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, () -> null);
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 42);
        journal.append(XrunJournal.Detail.CALLBACK_FAILED, List.of(), 1);
        journal.append(XrunJournal.Detail.PROCESSING_FAILED, List.of(), 1);
        journal.append(XrunJournal.Detail.OUTPUT_REFUSED, List.of(), 2);
        journal.append(XrunJournal.Detail.BLOCK_NEVER_PLAYED, List.of(), 9);
        journal.append(XrunJournal.Detail.DRIVER_OVERLOAD, List.of(), 1);
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(7.44, 2.6666), 1);
        journal.append(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-1", 87L), 1);
        journal.append(XrunJournal.Detail.OVER_CPU_BUDGET, List.of("t-gone", 104L), 1);
        journal.append(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(1L), 1);
        journal.append(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(44L), 1);
        journal.append(XrunJournal.Detail.CPU_BUDGET_ENTRIES_NOT_LOGGED, List.of(1_234L), 1);
        journal.append(XrunJournal.Detail.DEVICE_STOPPED_RESPONDING, List.of("Interface A"), 1);
        journal.append(XrunJournal.Detail.DEVICE_STOPPED_RESPONDING, List.of(""), 1);

        List<String> lines = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });

        String at = "10:00:01.250  ";
        assertThat(lines).containsExactly(
                at + "Gap  Device played silence (output buffer empty) ×42",
                at + "Gap  Device callback failed; one period silenced",
                at + "Gap  Audio processing failed; one block silenced",
                at + "Gap  Output refused a block; it was not played ×2",
                at + "Discarded  One rendered block was skipped and never played ×9",
                at + "Overload  Audio driver reported an overload",
                at + "Resync  Audio driver lost sync and resynchronized",
                at + "Late  Block took 7.4 ms of a 2.7 ms period",
                at + "Budget  Track 'Lead Vox': over the CPU budget (averaging 87 % of the block time);"
                        + " its processing was not reduced",
                at + "Budget  Track (removed): over the CPU budget (averaging 104 % of the block time);"
                        + " its processing was not reduced",
                at + "Budget  1 CPU budget marking never reached the log: it fell behind",
                at + "Budget  44 CPU budget markings never reached the log: it fell behind",
                at + "Budget  1234 CPU budget markings never reached the log: it fell behind",
                at + "Stall  Device stopped responding: Interface A",
                at + "Stall  Device stopped responding");
    }

    /**
     * Rulings E197 and E203: the feed's read asks the current CPU budget log
     * listener, and the listener of each replaced enforcer that waits for its
     * block in flight to end, to hand over a count that is due before it
     * drains the journal, so the count is written in that same read's entry,
     * the stream running or not. The listeners and the retirer are real, the
     * engine's counts held at a block in flight; the window's wiring is pinned
     * in {@code FailureSpineWiringSourceTest}.
     */
    @Test
    void theReadHandsTheCurrentAndTheRetiringListenersDueCountsOverAndWritesThemInThatReadsEntry() throws Exception {
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        CpuBudgetEnforcerRetirer retirer = new CpuBudgetEnforcerRetirer(() -> { }, () -> 1L, () -> 0L, true);
        EngineHealthFeed feed = feed(model, () -> null, retirer::handOverAtTheRead);
        TrackCpuBudgetEnforcer replacedEnforcer = new TrackCpuBudgetEnforcer(48_000.0, 128);
        ShedLogSubscriber replaced = new ShedLogSubscriber(journal);
        cpuBudgetLog.set(replaced);
        runOnFx(feed::pulse);
        ShedLogSubscriber current = new ShedLogSubscriber(journal);
        runOnFx(() -> retirer.retire(replacedEnforcer, replaced));
        cpuBudgetLog.set(current);
        replaced.eventDropped(marking("t-1"), 0L);

        List<String> afterTheReplacement = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });
        current.eventDropped(marking("t-1"), 0L);
        List<String> afterIt = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });
        runOnFx(retirer::closeAll);

        assertThat(afterTheReplacement).as("the retiring listener's count, written by the read after it was due")
                .containsExactly(
                        "10:00:01.250  Budget  1 CPU budget marking never reached the log: it fell behind");
        assertThat(afterIt).as("then the current listener's").containsExactly(
                "10:00:01.250  Budget  1 CPU budget marking never reached the log: it fell behind",
                "10:00:01.250  Budget  1 CPU budget marking never reached the log: it fell behind");
    }

    /** Ruling E181: no policy is applied yet, so no entry may claim one. */
    @Test
    void noXrunLogDetailClaimsAReductionTheEngineDoesNotMake() {
        ResourceBundle messages = ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        List<String> details = messages.keySet().stream().filter(key -> key.startsWith("xrunLog.detail.")).toList();

        assertThat(details).as("premise: the details are read").contains("xrunLog.detail.OVER_CPU_BUDGET");
        assertThat(details).allSatisfy(key -> assertThat(messages.getString(key)).as(key)
                .doesNotContainIgnoringCase("bypassed").doesNotContainIgnoringCase("reduced to")
                .doesNotContainIgnoringCase("substituted"));
        assertThat(messages.getString("xrunLog.kind.SHED")).isEqualTo("Budget");
    }

    @Test
    void anEntryIsStampedWithTheTransportPositionOnlyWhileItRolls() throws Exception {
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, () -> null);
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 1);
        runOnFx(feed::pulse);
        rolling.set(OptionalDouble.of(65.37));
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 3);
        runOnFx(feed::pulse);
        rolling.set(OptionalDouble.of(4.0));
        journal.append(XrunJournal.Detail.DRIVER_OVERLOAD, List.of(), 1);
        runOnFx(feed::pulse);

        assertThat(onFx(() -> List.copyOf(model.xrunLog()))).containsExactly(
                "10:00:01.250  Gap  Device played silence (output buffer empty)",
                "10:00:01.250  Gap  Device played silence (output buffer empty) ×3  near 1:05.3",
                "10:00:01.250  Overload  Audio driver reported an overload  near 0:04.0");
    }

    @Test
    void theLogKeepsTheNewestHundredLinesAndTheFiguresFollowTheJournal() throws Exception {
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, () -> null);
        for (int i = 1; i <= 60; i++) {
            journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), i);
        }
        runOnFx(feed::pulse);
        for (int i = 61; i <= 120; i++) {
            journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), i);
        }
        journal.append(XrunJournal.Detail.LATE_BLOCK, List.of(3.0, 2.0), 1);
        List<String> lines = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });

        assertThat(lines).as("the newest 100 (ruling E22)").hasSize(EngineHealthStatus.MAX_LOG_LINES);
        assertThat(lines.getFirst()).endsWith("×22");
        assertThat(lines.getLast()).contains("Late  Block took 3.0 ms");
        EngineHealthStatus.XrunFigures figures = onFx(() -> model.xrunFiguresProperty().get());
        assertThat(figures.count()).isEqualTo(120L * 121L / 2L);
        assertThat(figures.recent()).isEqualTo(figures.count());
        assertThat(figures.lateBlocks()).isEqualTo(1);

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(XrunJournal.RECENT_WINDOW_SECONDS + 1L));
        runOnFx(feed::pulse);
        EngineHealthStatus.XrunFigures later = onFx(() -> model.xrunFiguresProperty().get());
        assertThat(later.recent()).as("nothing grew in the last 30 seconds").isZero();
        assertThat(later.count()).as("the count stays until Clear or a settings change (ruling E22)")
                .isEqualTo(figures.count());
    }

    @Test
    void clearEmptiesTheLogAndRestartsTheCount() throws Exception {
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, () -> null);
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 5);
        runOnFx(feed::pulse);
        journal.append(XrunJournal.Detail.DRIVER_OVERLOAD, List.of(), 1);

        runOnFx(feed::clearXrunLog);
        runOnFx(feed::pulse);

        assertThat(onFx(() -> List.copyOf(model.xrunLog()))).as("what was not drained yet goes too").isEmpty();
        assertThat(onFx(() -> model.xrunFiguresProperty().get().count())).isZero();
        assertThat(journal.entries()).isEmpty();
    }

    @Test
    void aTakesFiguresAndResyncsReachTheLogAndTheTakeAndClearForgetsOnlyAFinishedTake() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        XrunLogDialog dialog = onFx(() -> new XrunLogDialog(null, model, feed::clearXrunLog));
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();

        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        runOnFx(feed::pulse);
        String duringTheTake = onFx(() -> dialog.takeLabel().getText());
        runOnFx(() -> dialog.clearButton().fire());
        String afterClearDuringTheTake = onFx(() -> dialog.takeLabel().getText());
        int linesAfterClear = onFx(() -> dialog.entryList().getItems().size());
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();
        runOnFx(feed::pulse);
        String afterTheTake = onFx(() -> dialog.takeLabel().getText());
        runOnFx(() -> dialog.clearButton().fire());
        String afterClear = onFx(() -> dialog.takeLabel().getText());

        assertThat(duringTheTake).as("ruling E24").matches("This take: \\d+ dropouts? \\(\\d+ blocks? lost,"
                + " \\d+ frames? truncated\\)");
        assertThat(afterClearDuringTheTake).as("a take still recording keeps its figures").startsWith("This take: ");
        assertThat(linesAfterClear).as("the log itself is emptied").isZero();
        assertThat(afterTheTake).as("kept as the last take after the Stop").startsWith("Last take: ");
        assertThat(afterClear).as("Clear forgets the finished take")
                .isEqualTo("No take to report.");
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event)
                .as("the resync is marked in the take (ruling E40)")
                .containsExactly(TakeManifest.ClockEvent.RESYNC);
    }

    /**
     * Ruling E97: a finished take with no loss still shows its line, because
     * it is the result the person reads after Stop; with no take figures and
     * with an empty log the dialog says so without claiming a Clear.
     */
    @Test
    void aFinishedTakeWithNoLossKeepsItsLineAndAnEmptyLogClaimsNoClear() throws Exception {
        String[] shown = onFx(() -> {
            EngineHealthStatus model = new EngineHealthStatus();
            XrunLogDialog dialog = new XrunLogDialog(null, model, () -> { });
            String none = dialog.takeLabel().getText();
            model.setTakeDropouts(Optional.of(new TakeDropouts(0, 0, 0, false)));
            String clean = dialog.takeLabel().getText();
            javafx.scene.Node placeholder = dialog.entryList().getPlaceholder();
            return new String[] {none, clean, ((javafx.scene.control.Label) placeholder).getText()};
        });

        assertThat(shown[0]).isEqualTo("No take to report.");
        assertThat(shown[1]).as("the clean take's result, after its Stop")
                .isEqualTo("Last take: 0 dropouts (0 blocks lost, 0 frames truncated)");
        assertThat(shown[2]).isEqualTo("Nothing logged.");
    }

    @Test
    void severalResyncsInOneEntryAreEachMarkedInTheTake() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();

        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(nanos.get() - 80_000_000L, 2L)));
        runOnFx(feed::pulse);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event)
                .as("ruling E106: each resync gets its own mark")
                .containsExactly(TakeManifest.ClockEvent.RESYNC, TakeManifest.ClockEvent.RESYNC);
    }

    /** Ruling E225: a resync is marked in the take once, though more entries than the log holds came after it. */
    @Test
    void aResyncFollowedByMoreThanTheLogHoldsIsStillMarkedOnce() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();

        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        for (int i = 0; i < 150; i++) {
            journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 1);
        }
        List<String> lines = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });
        runOnFx(feed::pulse);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(lines).as("premise: the log holds the newest 100, not the resync")
                .hasSize(EngineHealthStatus.MAX_LOG_LINES).noneMatch(line -> line.contains("Resync"));
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.RESYNC);
    }

    /** Ruling E226: the log's Clear takes no resync from the take. */
    @Test
    void aResyncLoggedBeforeAClearIsStillMarked() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();

        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        runOnFx(feed::clearXrunLog);
        List<String> lines = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(lines).as("premise: the Clear emptied the log").isEmpty();
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.RESYNC);
    }

    /**
     * Ruling E280: a resync after the capture thread sealed the take early, before the take is stopped for it, is
     * logged and counted in the Xruns number but not marked in the take.
     */
    @Test
    void aResyncAfterTheEarlySealIsLoggedAndCountedButNotMarked() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        runOnFx(fixture.coordinator()::readTheCaptureAsSealedForTest);
        journal.append(XrunJournal.Detail.DRIVER_RESYNC, List.of(), 1);
        List<String> lines = onFx(() -> {
            feed.pulse();
            return List.copyOf(model.xrunLog());
        });
        long counted = onFx(() -> model.xrunFiguresProperty().get().count());
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        assertThat(lines).as("logged").hasSize(1)
                .allMatch(line -> line.endsWith("Resync  Audio driver lost sync and resynchronized"));
        assertThat(counted).as("counted in the Xruns number").isEqualTo(1L);
        assertThat(TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks())
                .as("not marked in the take (ruling E280)").isEmpty();
    }

    /**
     * Rulings E40 and E123: a resync whose driver message arrived at t0 and
     * that the feed hands over at a later pulse is marked at the frame the
     * take was at when it arrived, not at the pulse's.
     */
    @Test
    void aResyncIsMarkedAtTheFrameOfItsArrivalNotOfThePulseThatHandsItOver() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> fixture.coordinator().setTakeFrameClockForTest(nanos::get));
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();

        long arrival = nanos.addAndGet(1_000_000_000L);
        long[] atArrival = onFx(() -> {
            long before = pipeline.transportFrameOf(transport.getPositionInBeats());
            dispatcher.pulse();
            return new long[] {before, pipeline.transportFrameOf(transport.getPositionInBeats())};
        });
        nanos.addAndGet(1_500_000_000L);
        double beatsPerFrame = 1_000.0 / pipeline.transportFrameOf(1_000.0);
        DeviceLossRecordingFixture.await(() -> transport.getPositionInBeats() >= atArrival[1] * beatsPerFrame + 1.0,
                "the take recorded on past the arrival");
        runOnFx(dispatcher::pulse);
        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(arrival, 1L)));
        long pulsedAt = onFx(() -> {
            feed.pulse();
            return pipeline.transportFrameOf(transport.getPositionInBeats());
        });
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(marks).extracting(TakeManifest.ClockMark::event).containsExactly(TakeManifest.ClockEvent.RESYNC);
        assertThat(pulsedAt).as("premise: the pulse came well after the arrival").isGreaterThan(atArrival[1]);
        assertThat(marks.getFirst().frame()).as("the frame when the driver's message arrived")
                .isBetween(atArrival[0], atArrival[1]);
    }

    /**
     * Rulings E270 and E279: an entry of two resyncs 90 ms apart is marked twice in the take, each at the frame
     * sampled at or before its own request's arrival.
     */
    @Test
    void eachResyncOfAnEntryIsMarkedAtTheFrameSampledAtOrBeforeItsOwnArrival() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> fixture.coordinator().setTakeFrameClockForTest(nanos::get));
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        double beatsPerFrame = 1_000.0 / pipeline.transportFrameOf(1_000.0);

        long first = nanos.addAndGet(1_000_000_000L);
        long[] atFirst = framesAroundAPulse(dispatcher, pipeline, transport);
        DeviceLossRecordingFixture.await(() -> transport.getPositionInBeats() >= atFirst[1] * beatsPerFrame + 1.0,
                "the take recorded on past the first arrival");
        long second = nanos.addAndGet(90_000_000L);
        long[] atSecond = framesAroundAPulse(dispatcher, pipeline, transport);
        DeviceLossRecordingFixture.await(() -> transport.getPositionInBeats() >= atSecond[1] * beatsPerFrame + 1.0,
                "the take recorded on past the second arrival");
        nanos.addAndGet(10_000_000L);
        runOnFx(dispatcher::pulse);
        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(first, 1L), new XrunJournal.ResyncMark(second, 1L)));
        runOnFx(feed::pulse);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(atSecond[0]).as("premise: the two arrivals' frames are apart").isGreaterThan(atFirst[1]);
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.RESYNC, TakeManifest.ClockEvent.RESYNC);
        assertThat(marks.get(0).frame()).as("the frame when the first request arrived")
                .isBetween(atFirst[0], atFirst[1]);
        assertThat(marks.get(1).frame()).as("the frame when the second arrived")
                .isBetween(atSecond[0], atSecond[1]);
    }

    /** Ruling E291: carried 4.6 s past the first arrival, each resync at the frame sampled at or before its own. */
    @Test
    void resyncsCarriedThroughAStandstillOfFourPointSixSecondsAreEachMarkedAtTheirOwnFrame() throws Exception {
        assertEachResyncCarriedThroughAStandstillIsMarkedAtItsOwnFrame(4_600_000_000L);
    }

    /** Ruling E291: as above, carried 20 s at 60 frame samples a second. */
    @Test
    void resyncsCarriedThroughAStandstillOfTwentySecondsAreEachMarkedAtTheirOwnFrame() throws Exception {
        assertEachResyncCarriedThroughAStandstillIsMarkedAtItsOwnFrame(20_000_000_000L);
    }

    private void assertEachResyncCarriedThroughAStandstillIsMarkedAtItsOwnFrame(long carriedNanos) throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        fixture.bindTheProjectToTheEngine();
        runOnFx(() -> {
            fixture.coordinator().setTakeFrameClockForTest(nanos::get);
            fixture.coordinator().setResyncDatesOwed(journal::resyncDatesOwed);
        });
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        EngineHealthFeed feed = feed(model, fixture::controller);
        Path takeDirectory = fixture.recordUntilABlockIsOnDisk();
        RecordingPipeline pipeline = fixture.pipeline();
        Transport transport = fixture.project().getTransport();
        double beatsPerFrame = 1_000.0 / pipeline.transportFrameOf(1_000.0);

        long first = nanos.addAndGet(1_000_000_000L);
        long second = first + 400_000_000L;
        journal.noteResyncDatesHeld(new long[] {first, second});
        long[] atFirst = framesAroundAPulse(dispatcher, pipeline, transport);
        nanos.set(second);
        DeviceLossRecordingFixture.await(() -> transport.getPositionInBeats() >= atFirst[1] * beatsPerFrame + 1.0,
                "the take recorded on past the first arrival");
        long[] atSecond = framesAroundAPulse(dispatcher, pipeline, transport);
        runOnFx(() -> {
            while (nanos.get() - first < carriedNanos) {
                nanos.addAndGet(16_666_667L);
                dispatcher.pulse();
            }
        });
        journal.appendResyncs(List.of(new XrunJournal.ResyncMark(first, 1L),
                new XrunJournal.ResyncMark(second, 1L)));
        journal.noteResyncDatesHeld(XrunJournal.NO_DATES);
        runOnFx(feed::pulse);
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();

        List<TakeManifest.ClockMark> marks = TakeManifest.read(TakeManifest.manifestPath(takeDirectory)).clockMarks();
        assertThat(atFirst[0]).as("premise: past the take's start-frame")
                .isGreaterThan(pipeline.getRecordingStartFrame());
        assertThat(atSecond[0]).as("premise: the two arrivals' frames are apart").isGreaterThan(atFirst[1]);
        assertThat(marks).extracting(TakeManifest.ClockMark::event)
                .containsExactly(TakeManifest.ClockEvent.RESYNC, TakeManifest.ClockEvent.RESYNC);
        assertThat(marks.get(0).frame()).as("the frame when the first request arrived")
                .isBetween(atFirst[0], atFirst[1]);
        assertThat(marks.get(1).frame()).as("the frame when the second arrived")
                .isBetween(atSecond[0], atSecond[1]);
    }

    private static long[] framesAroundAPulse(FxDispatcher dispatcher, RecordingPipeline pipeline,
                                             Transport transport) throws Exception {
        return onFx(() -> {
            long before = pipeline.transportFrameOf(transport.getPositionInBeats());
            dispatcher.pulse();
            return new long[] {before, pipeline.transportFrameOf(transport.getPositionInBeats())};
        });
    }

    @Test
    void anotherProjectsTransportShowsNoneOfTheLastProjectsTakeFigures() throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        fixture = DeviceLossRecordingFixture.withoutEngineBridge(projectDirectory, CountInMode.OFF, dispatcher);
        EngineHealthStatus model = onFx(EngineHealthStatus::new);
        AtomicReference<TransportController> current = new AtomicReference<>(fixture.controller());
        EngineHealthFeed feed = feed(model, current::get);
        fixture.recordUntilABlockIsOnDisk();
        runOnFx(fixture.controller()::stop);
        fixture.awaitPublished();
        runOnFx(feed::pulse);
        Optional<TakeDropouts> before = onFx(() -> model.takeDropoutsProperty().get());

        try (DeviceLossRecordingFixture other = DeviceLossRecordingFixture.withoutEngineBridge(
                projectDirectory.resolve("other"), CountInMode.OFF, dispatcher)) {
            current.set(other.controller());
            runOnFx(feed::pulse);
            Optional<TakeDropouts> after = onFx(() -> model.takeDropoutsProperty().get());

            assertThat(before).as("premise: the last take's figures are on show").isPresent();
            assertThat(after).as("ruling E109: never one project's take figures over another").isEmpty();
        }
    }

    private static TrackPerformanceEvent.TrackDegraded marking(String trackId) {
        return new TrackPerformanceEvent.TrackDegraded(trackId, 0.9, TrackCpuBudget.UNLIMITED,
                new DegradationPolicy.BypassExpensive());
    }

    private EngineHealthFeed feed(EngineHealthStatus model, Supplier<TransportController> transport) {
        return feed(model, transport, () -> { });
    }

    private EngineHealthFeed feed(EngineHealthStatus model, Supplier<TransportController> transport,
                                  Runnable retiringCpuBudgetLogs) {
        return new EngineHealthFeed(model, journal, new EngineHealthFeed.Sources(transport, rolling::get,
                id -> Optional.ofNullable(tracks.get(id)), cpuBudgetLog::get, retiringCpuBudgetLogs), nanos::get,
                ZoneOffset.UTC);
    }
}
