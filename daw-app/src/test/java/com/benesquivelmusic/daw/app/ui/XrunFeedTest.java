package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.DriverResyncArrivals;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The xrun feed on a real {@link DefaultAudioEngineController} over a real
 * {@link AudioEngine} (story 338, amendment A15): each observed
 * gap of rulings E17 and E38 raises the journal's count by exactly its own
 * count and reaches the SDK stream; a late block is a Late entry that raises
 * nothing; nothing is counted while paused, across a reopen or during the
 * start-up allowance; what grows while the watched heartbeat stands still is
 * counted when it advances again, and discarded by a pause, a reopen or the
 * stall verdict, whose growth is never counted (ruling E19); a
 * stall verdict writes one Stall entry; a settings change restarts the count
 * (ruling E22). Ticks run on the test thread through the controller's tick
 * seam; the health clock stands still, so the heartbeat watchdog decides
 * nothing unless a test moves it.
 */
@Tag("slow")
class XrunFeedTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);

    private final AudioEngine engine = new AudioEngine(FORMAT);
    private final RecordingNotifications notices = new RecordingNotifications();
    private final MockAudioBackend mock = new MockAudioBackend();
    private final DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null, notices,
            new AudioBackendSelector(Map.of(MockAudioBackend.NAME, () -> mock)));
    private final SpyStreamingBackend spy = new SpyStreamingBackend("SpyXruns");
    private final DeviceId device = new DeviceId("SpyXruns", "Interface A");
    private final AtomicLong clock = new AtomicLong();

    XrunFeedTest() {
        controller.setEngineHealthClock(clock::get);
        engine.setStreamingProvision(new StreamingProvision("SpyXruns", List.of(new BackendStreamRung(spy, device))));
    }

    @AfterEach
    void tearDown() {
        spy.blockAwait = false;
        engine.stopAudioOutput();
        engine.stop();
        controller.shutdown();
    }

    @Test
    void eachObservedGapRaisesTheCountByExactlyItsOwnCountAndReachesTheSdkStream() throws Exception {
        List<XrunEvent> offered = subscribe();
        AtomicInteger renderFaultsLeft = new AtomicInteger();
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (renderFaultsLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new IllegalStateException("an insert failed");
            }
        });
        startCounting();

        spy.dropouts = new BackendDropoutCounters(5, 7, 11, 13, 99, true);
        spy.threadCounters = callbackFaults(3);
        spy.sinkRefusals.set(2);
        renderFaultsLeft.set(4);
        await(() -> engine.audioHealth().total(AudioFailureCategory.SINK) == 2
                && engine.audioHealth().total(AudioFailureCategory.RENDER) == 4, "the refused and faulted blocks");
        tickAfterBlocks();
        tickAfterBlocks();

        XrunJournal journal = controller.xrunJournal();
        assertThat(countsByDetail(journal)).as("each source by exactly its own count (rulings E17, E38)")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        XrunJournal.Detail.OUTPUT_STARVED, 5L,
                        XrunJournal.Detail.BLOCK_NEVER_PLAYED, 7L,
                        XrunJournal.Detail.DRIVER_OVERLOAD, 11L,
                        XrunJournal.Detail.DRIVER_RESYNC, 13L,
                        XrunJournal.Detail.CALLBACK_FAILED, 3L,
                        XrunJournal.Detail.OUTPUT_REFUSED, 2L,
                        XrunJournal.Detail.PROCESSING_FAILED, 4L));
        assertThat(journal.counts().gaps()).as("input blocks lost before the ring are the take's, not xruns")
                .isEqualTo(5 + 7 + 11 + 13 + 3 + 2 + 4);
        assertThat(journal.counts().reportsOutputStarvation()).isTrue();
        assertThat(controller.getXrunDetector().getDroppedCount()).as("every gap was reported to the detector")
                .isGreaterThanOrEqualTo(45);
        await(() -> offered.stream().anyMatch(XrunEvent.BufferDropped.class::isInstance),
                "the gaps reach the SDK stream");
    }

    @Test
    void aLateBlockIsALateEntryAndNeverRaisesTheCount() throws Exception {
        AtomicInteger slowBlocksLeft = new AtomicInteger();
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (slowBlocksLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(7));
            }
        });
        startCounting();

        slowBlocksLeft.set(3);
        await(() -> controller.getXrunDetector().getLateCount() + controller.getXrunDetector().getDroppedCount() >= 3,
                "the slow blocks were rendered and timed");
        tickAfterBlocks();

        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.LATE_BLOCK)
                .isNotEmpty().allSatisfy(entry -> {
                    double took = (Double) entry.arguments().get(0);
                    double period = (Double) entry.arguments().get(1);
                    assertThat(period).isCloseTo(128 / 48.0, org.assertj.core.data.Offset.offset(0.01));
                    assertThat(took).as("the newest late block's time").isGreaterThan(period);
                });
        assertThat(journal.counts().lateBlocks()).as("counted apart").isGreaterThanOrEqualTo(3);
        assertThat(journal.counts().gaps()).as("a late block is not a gap (ruling E17)").isZero();
        assertThat(journal.entries()).extracting(XrunJournal.Entry::kind).containsOnly(XrunJournal.Kind.LATE);
    }

    @Test
    void nothingThatGrewWhilePausedIsCounted() throws Exception {
        startCounting();

        engine.pauseAudioOutput();
        tick();
        spy.dropouts = new BackendDropoutCounters(4, 0, 0, 0, 0, true);
        tick();
        tick();
        engine.startAudioOutput();
        tickAfterBlocks();
        tickAfterBlocks();
        tickAfterBlocks();

        assertThat(controller.xrunJournal().counts().gaps()).as("the dry periods of a paused stream (G-e)").isZero();
    }

    @Test
    void whatGrewWhileTheStreamWasClosedIsNotCountedAfterTheReopen() throws Exception {
        startCounting();

        engine.stopAudioOutput();
        spy.dropouts = new BackendDropoutCounters(6, 2, 0, 0, 0, true);
        engine.startAudioOutput();
        tickAfterBlocks();
        tickAfterBlocks();
        tickAfterBlocks();

        assertThat(controller.xrunJournal().counts().gaps()).as("the backend instance's counts span the reopen")
                .isZero();
    }

    @Test
    void nothingIsCountedBeforeTheDriveFirstAdvances() throws Exception {
        spy.blockAwait = true;
        engine.startAudioOutput();
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the first block is out and the pump waits");
        tick();
        tick();
        spy.dropouts = new BackendDropoutCounters(3, 0, 0, 0, 0, true);
        tick();
        tick();

        assertThat(controller.xrunJournal().counts().gaps()).as("inside the start-up allowance (ruling E2)").isZero();
    }

    @Test
    void whatAccruesWhileTheDevicePeriodsStandStillIsTheStallsAndNotCounted() throws Exception {
        spy.threadCounters = devicePeriods(10);
        startCounting();
        spy.threadCounters = devicePeriods(20);
        tickAfterBlocks();

        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 0, 0, true);
        tickAfterBlocks();
        tickAfterBlocks();
        assertThat(controller.xrunJournal().counts().gaps()).as("frozen device periods (ruling E19)").isZero();

        tickUntilTheStallIsDeclared();
        streamAndCount();
        // The reopened device's first period ends its start-up allowance.
        spy.threadCounters = devicePeriods(25);
        tickAfterBeats();
        spy.threadCounters = devicePeriods(30);
        spy.dropouts = new BackendDropoutCounters(42, 9, 0, 0, 0, true);
        tickAfterBeats();

        assertThat(countsByDetail(controller.xrunJournal()))
                .as("only what grew while the reopened device ran; the stall's growth is never counted")
                .containsExactlyEntriesOf(Map.of(XrunJournal.Detail.OUTPUT_STARVED, 2L));
    }

    @Test
    void aHiccupWhileTheDevicePeriodsStandStillIsCountedWithItsFullCountWhenTheyAdvanceAgain() throws Exception {
        spy.threadCounters = devicePeriods(10);
        streamAndCount();
        spy.threadCounters = devicePeriods(20);
        tickAfterBeats();

        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 0, 0, true);
        tickAfterBeats();
        tickAfterBeats();
        assertThat(controller.xrunJournal().counts().gaps()).as("not while the device stands still").isZero();

        spy.threadCounters = devicePeriods(30);
        spy.dropouts = new BackendDropoutCounters(42, 9, 0, 0, 0, true);
        tickAfterBeats();

        assertThat(controller.xrunJournal().entries())
                .extracting(XrunJournal.Entry::detail, XrunJournal.Entry::count)
                .as("one entry per detail, with what grew during the hiccup and after it")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(XrunJournal.Detail.OUTPUT_STARVED, 42L),
                        org.assertj.core.groups.Tuple.tuple(XrunJournal.Detail.BLOCK_NEVER_PLAYED, 9L));
        assertThat(controller.xrunJournal().counts().gaps()).isEqualTo(51L);
    }

    @Test
    void aRefusedBlockInAnIntervalWithNoBeatIsCountedAtTheNextBeat() throws Exception {
        streamAndCount();
        spy.blockAwait = true;
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump waits");
        tick();
        long refusedBefore = engine.audioHealth().total(AudioFailureCategory.SINK);
        int wedgesBefore = spy.blockedAwaitEntries.get();

        spy.wedgeAfterRefusal = true;
        spy.sinkRefusals.set(1);
        spy.blockAwait = false;
        await(() -> spy.blockedAwaitEntries.get() > wedgesBefore
                && engine.audioHealth().total(AudioFailureCategory.SINK) == refusedBefore + 1,
                "one block refused, and the pump waits again before it accepts another");
        tick();
        assertThat(controller.xrunJournal().counts().gaps()).as("no block was accepted in that interval").isZero();

        spy.wedgeAfterRefusal = false;
        spy.blockAwait = false;
        tickAfterBeats();

        assertThat(countsByDetail(controller.xrunJournal())).as("counted at the next beat")
                .containsExactlyEntriesOf(Map.of(XrunJournal.Detail.OUTPUT_REFUSED, 1L));
    }

    @Test
    void whatWasCarriedIsDiscardedWhenTheStreamPauses() throws Exception {
        carryGrowthWhileTheDevicePeriodsStandStill();

        engine.pauseAudioOutput();
        tick();
        engine.startAudioOutput();
        countAgainWithTheDevicePeriodsAdvancing();

        assertThat(controller.xrunJournal().counts().gaps()).as("a pause ends what the frozen interval carried")
                .isZero();
    }

    @Test
    void whatWasCarriedIsDiscardedWhenTheStreamReopensBetweenTwoTicks() throws Exception {
        carryGrowthWhileTheDevicePeriodsStandStill();

        engine.stopAudioOutput();
        engine.startAudioOutput();
        countAgainWithTheDevicePeriodsAdvancing();

        assertThat(controller.xrunJournal().counts().gaps()).as("a new epoch ends what the frozen interval carried")
                .isZero();
    }

    @Test
    void blocksDiscardedBeforeTheDevicesFirstPeriodAreNeverCountedAndOneAfterItIs() throws Exception {
        spy.threadCounters = devicePeriods(0);
        engine.startAudioOutput();
        tick();
        tickAfterBeats();
        tickAfterBeats();
        spy.dropouts = new BackendDropoutCounters(0, 5, 0, 0, 0, true);
        tickAfterBeats();
        spy.threadCounters = devicePeriods(1);
        tickAfterBeats();
        assertThat(controller.xrunJournal().counts().gaps())
                .as("the render beats advanced, but the device had not played its first period (ruling E17)")
                .isZero();

        spy.dropouts = new BackendDropoutCounters(0, 7, 0, 0, 0, true);
        spy.threadCounters = devicePeriods(2);
        tickAfterBeats();

        assertThat(countsByDetail(controller.xrunJournal())).as("a discard after the first period counts")
                .containsExactlyEntriesOf(Map.of(XrunJournal.Detail.BLOCK_NEVER_PLAYED, 2L));
    }

    @Test
    void devicePeriodsThatAppearInsideAnEpochStartTheAllowanceAfresh() throws Exception {
        streamAndCount();
        spy.threadCounters = devicePeriods(0);
        tickAfterBeats();
        spy.dropouts = new BackendDropoutCounters(0, 4, 0, 0, 0, true);
        tickAfterBeats();
        spy.threadCounters = devicePeriods(1);
        tickAfterBeats();

        assertThat(controller.xrunJournal().counts().gaps())
                .as("what grew before the newly watched device periods first advanced").isZero();
    }

    @Test
    void aResyncIsLoggedAtTheTimeTheDriversMessageArrived() throws Exception {
        startCounting();
        long arrived = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(80);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(arrived, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 1, 0, true);
        tickAfterBlocks();

        assertThat(controller.xrunJournal().entries()).filteredOn(entry -> entry.detail()
                        == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos).as("rulings E40 and E62: the arrival, not the tick")
                .containsExactly(arrived);
    }

    @Test
    void aResyncCarriedThroughAStandstillAndOneAfterItAreEachMarkedAtTheirOwnArrival() throws Exception {
        carryGrowthWhileTheDevicePeriodsStandStill();
        long during = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1_500);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(during, 1L))));
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 1, 0, true);
        tickAfterBeats();
        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .as("premise: carried while the device stands still").isEmpty();
        assertThat(journal.resyncDatesOwed()).as("ruling E291: owed to the take's frame history while carried")
                .contains(during);

        long after = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(20);
        spy.resyncArrivals.add(new DriverResyncArrivals(1L, List.of(new DriverResyncArrivals.Run(after, 1L))));
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 2, 0, true);
        spy.threadCounters = devicePeriods(30);
        tickAfterBeats();

        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos, XrunJournal.Entry::count)
                .as("one entry at the first arrival").containsExactly(org.assertj.core.groups.Tuple.tuple(during, 2L));
        assertThat(journal.drainResyncMarks()).as("each request at its own arrival")
                .containsExactly(new XrunJournal.ResyncMark(during, 1L), new XrunJournal.ResyncMark(after, 1L));
    }

    /** Rulings E270 and E279: two resyncs in one tick are one entry at the first arrival, and a mark at each. */
    @Test
    void twoResyncsInOneTickAreOneEntryAtTheFirstArrivalAndAMarkAtEachArrival() throws Exception {
        startCounting();
        long first = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(95);
        long second = first + TimeUnit.MILLISECONDS.toNanos(90);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(first, 1L),
                new DriverResyncArrivals.Run(second, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 2, 0, true);
        tickAfterBlocks();

        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos, XrunJournal.Entry::count)
                .as("one entry at the first arrival").containsExactly(org.assertj.core.groups.Tuple.tuple(first, 2L));
        assertThat(journal.drainResyncMarks()).as("a mark at each arrival")
                .containsExactly(new XrunJournal.ResyncMark(first, 1L), new XrunJournal.ResyncMark(second, 1L));
        assertThat(journal.counts().gaps()).isEqualTo(2L);
    }

    /** Ruling E279: the feed takes the resync arrivals up at every tick, whether or not it writes an entry. */
    @Test
    void theFeedTakesTheResyncArrivalsUpAtEveryTickWhetherOrNotItWrites() throws Exception {
        spy.threadCounters = devicePeriods(10);
        engine.startAudioOutput();
        int before = spy.resyncTakes.get();
        tick();
        tickAfterBeats();
        spy.threadCounters = devicePeriods(20);
        tickAfterBeats();
        tickAfterBeats();

        assertThat(controller.xrunJournal().counts().gaps()).as("premise: nothing written").isZero();
        assertThat(spy.resyncTakes.get() - before).as("one take a tick").isEqualTo(4);
    }

    /** Rulings E73 and E279: a resync carried into a pause is discarded with its arrival; the next keeps its own. */
    @Test
    void aResyncCarriedIntoAPauseIsDiscardedWithItsArrival() throws Exception {
        spy.threadCounters = devicePeriods(10);
        streamAndCount();
        spy.threadCounters = devicePeriods(20);
        tickAfterBeats();
        long carried = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(60);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(carried, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 1, 0, true);
        tickAfterBeats();
        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .as("premise: carried").isEmpty();

        engine.pauseAudioOutput();
        tick();
        engine.startAudioOutput();
        tick();
        tickAfterBeats();
        spy.threadCounters = devicePeriods(30);
        tickAfterBeats();
        long counted = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(5);
        spy.resyncArrivals.add(new DriverResyncArrivals(1L, List.of(new DriverResyncArrivals.Run(counted, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 2, 0, true);
        spy.threadCounters = devicePeriods(40);
        tickAfterBeats();

        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos, XrunJournal.Entry::count)
                .as("only the resync after the resume, at its own arrival")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(counted, 1L));
        assertThat(journal.drainResyncMarks()).containsExactly(new XrunJournal.ResyncMark(counted, 1L));
        assertThat(journal.counts().gaps()).isEqualTo(1L);
    }

    /**
     * Rulings E19, E73 and E291: a resync carried until the stream closes is never counted, so its arrival goes
     * with it, though the closed stream counts no resync to forget it below; another driver numbering its
     * requests afresh never brings it back.
     */
    @Test
    void aResyncCarriedUntilTheStreamClosesTakesItsArrivalWithIt() throws Exception {
        carryGrowthWhileTheDevicePeriodsStandStill();
        long carried = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(60);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(carried, 1L))));
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 1, 0, true);
        tickAfterBeats();
        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.resyncDatesOwed()).as("premise: carried, and owed to the take's frame history")
                .contains(carried);

        engine.stopAudioOutput();
        tick();

        assertThat(journal.resyncDatesOwed()).as("the stream closed: the carried resync is never written").isEmpty();
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 0, 0, true);
        engine.startAudioOutput();
        tick();
        tickAfterBeats();

        assertThat(journal.resyncDatesOwed()).isEmpty();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .isEmpty();
    }

    /**
     * Rulings E73, E291 and E292: a resync carried when the stream closes and reopens between two ticks, so that no
     * tick sees the drive dead, is never counted, so the new epoch takes its arrival with it, though the reopened
     * stream's backend counts fewer resyncs, as another backend numbering its own requests; a resync that backend
     * counts later with no arrival is dated at the read before the one that counted it, not at the forgotten one.
     */
    @Test
    void aResyncCarriedAcrossAReopenNoTickSawTakesItsArrivalWithIt() throws Exception {
        carryGrowthWhileTheDevicePeriodsStandStill();
        long carried = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(60);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(carried, 1L))));
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 1, 0, true);
        tickAfterBeats();
        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.resyncDatesOwed()).as("premise: carried, and owed to the take's frame history")
                .contains(carried);
        long epochBefore = engine.audioHealth().renderDriveEpoch();

        engine.stopAudioOutput();
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 0, 0, true);
        engine.startAudioOutput();
        tick();

        assertThat(engine.audioHealth().renderDriveEpoch()).as("premise: a new epoch").isNotEqualTo(epochBefore);
        assertThat(engine.isRenderDriveAlive()).as("premise: the tick saw the reopened drive alive").isTrue();
        assertThat(journal.resyncDatesOwed()).as("the carried resync is never written").isEmpty();

        spy.threadCounters = devicePeriods(30);
        long beforeThePreviousRead = System.nanoTime();
        tickAfterBeats();
        long afterThePreviousRead = System.nanoTime();
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 1, 0, true);
        spy.threadCounters = devicePeriods(40);
        tickAfterBeats();

        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .singleElement().satisfies(entry -> {
                    assertThat(entry.count()).isEqualTo(1L);
                    assertThat(entry.arrivedNanos()).as("ruling E292, not the forgotten arrival")
                            .isBetween(beforeThePreviousRead, afterThePreviousRead);
                });
        assertThat(journal.drainResyncMarks()).singleElement().satisfies(mark ->
                assertThat(mark.arrivedNanos()).isBetween(beforeThePreviousRead, afterThePreviousRead));
        assertThat(journal.resyncDatesOwed()).isEmpty();
    }

    /** Ruling E279: an arrival taken up before its request is counted waits for the tick that counts it. */
    @Test
    void anArrivalTakenUpBeforeItsRequestIsCountedWaitsForTheTickThatCountsIt() throws Exception {
        startCounting();
        long a0 = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50);
        long a1 = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(40);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(a0, 1L),
                new DriverResyncArrivals.Run(a1, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 1, 0, true);
        tickAfterBlocks();
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 2, 0, true);
        tickAfterBlocks();

        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos, XrunJournal.Entry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(a0, 1L),
                        org.assertj.core.groups.Tuple.tuple(a1, 1L));
        assertThat(journal.drainResyncMarks())
                .containsExactly(new XrunJournal.ResyncMark(a0, 1L), new XrunJournal.ResyncMark(a1, 1L));
    }

    /**
     * Ruling E279 at the feed: 300 resyncs in one tick are one entry at the first arrival, with every date the
     * backend gave, the last 45 at the 256th's arrival.
     */
    @Test
    void threeHundredResyncsInOneTickAreOneEntryAtTheFirstArrivalWithEachDateTheBackendGave() throws Exception {
        startCounting();
        long base = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(90);
        List<DriverResyncArrivals.Run> runs = new ArrayList<>();
        for (int i = 0; i < 255; i++) {
            runs.add(new DriverResyncArrivals.Run(base + 1_000L * i, 1L));
        }
        runs.add(new DriverResyncArrivals.Run(base + 255_000L, 45L));
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, runs));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 300, 0, true);
        tickAfterBlocks();

        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos, XrunJournal.Entry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(base, 300L));
        assertThat(journal.drainResyncMarks()).as("each date the backend gave")
                .containsExactlyElementsOf(runs.stream()
                        .map(run -> new XrunJournal.ResyncMark(run.arrivedNanos(), run.requests())).toList());
    }

    /** A resync counted inside the start-up allowance takes its arrival with it; the next one keeps its own. */
    @Test
    void aResyncCountedInTheStartUpAllowanceLeavesTheNextOneDatedAtItsOwnArrival() throws Exception {
        engine.startAudioOutput();
        tick();
        long early = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50);
        spy.resyncArrivals.add(new DriverResyncArrivals(0L, List.of(new DriverResyncArrivals.Run(early, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 1, 0, true);
        tickAfterBeats();
        long own = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(5);
        spy.resyncArrivals.add(new DriverResyncArrivals(1L, List.of(new DriverResyncArrivals.Run(own, 1L))));
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 2, 0, true);
        tickAfterBeats();

        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .extracting(XrunJournal.Entry::arrivedNanos, XrunJournal.Entry::count)
                .as("the allowance's resync is not counted; the next one is dated at its own arrival")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(own, 1L));
        assertThat(journal.drainResyncMarks()).containsExactly(new XrunJournal.ResyncMark(own, 1L));
    }

    /** Ruling E292: a resync the feed holds no arrival for is dated at the read before the one that counted it. */
    @Test
    void aResyncWithNoArrivalIsDatedAtTheReadBeforeTheOneThatCountedIt() throws Exception {
        startCounting();
        long beforeThePreviousRead = System.nanoTime();
        tickAfterBlocks();
        long afterThePreviousRead = System.nanoTime();
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 1, 0, true);
        tickAfterBlocks();

        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .singleElement().satisfies(entry -> {
                    assertThat(entry.count()).isEqualTo(1L);
                    assertThat(entry.arrivedNanos()).as("not the tick that wrote it")
                            .isBetween(beforeThePreviousRead, afterThePreviousRead);
                });
        assertThat(journal.drainResyncMarks()).singleElement().satisfies(mark ->
                assertThat(mark.arrivedNanos()).isBetween(beforeThePreviousRead, afterThePreviousRead));
    }

    /**
     * Ruling E292: resyncs carried with no arrival are dated at the read before the first interval that counted
     * them, a date owed to the take's frame history while they are carried (ruling E291).
     */
    @Test
    void resyncsCarriedWithNoArrivalAreDatedAtTheReadBeforeTheFirstIntervalThatCountedThem() throws Exception {
        carryGrowthWhileTheDevicePeriodsStandStill();
        long beforeTheRead = System.nanoTime();
        tickAfterBeats();
        long afterTheRead = System.nanoTime();
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 1, 0, true);
        tickAfterBeats();
        List<Long> owedWhileCarried = Arrays.stream(controller.xrunJournal().resyncDatesOwed()).boxed().toList();
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 2, 0, true);
        tickAfterBeats();
        spy.threadCounters = devicePeriods(30);
        tickAfterBeats();

        XrunJournal journal = controller.xrunJournal();
        assertThat(owedWhileCarried).anySatisfy(date -> assertThat(date).isBetween(beforeTheRead, afterTheRead));
        assertThat(journal.entries()).filteredOn(entry -> entry.detail() == XrunJournal.Detail.DRIVER_RESYNC)
                .singleElement().satisfies(entry -> {
                    assertThat(entry.count()).isEqualTo(2L);
                    assertThat(entry.arrivedNanos()).isBetween(beforeTheRead, afterTheRead);
                });
        assertThat(journal.drainResyncMarks()).singleElement().satisfies(mark -> {
            assertThat(mark.count()).isEqualTo(2L);
            assertThat(mark.arrivedNanos()).isBetween(beforeTheRead, afterTheRead);
        });
    }

    /**
     * Ruling E116: a sample taken before Settings forgot the driver's answer
     * never sets it again afterwards; a sample taken after the forget does.
     */
    @Test
    void anAnswerSampledBeforeTheForgetIsNotTakenAfterIt() throws Exception {
        XrunJournal journal = controller.xrunJournal();
        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 0, 0, true);
        startCounting();
        assertThat(journal.counts().underruns()).as("premise: the stream answered")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.REPORTED);

        spy.nextDropoutsHook.set(journal::forgetUnderrunReporting);
        tickAfterBlocks();
        assertThat(spy.nextDropoutsHook.get()).as("premise: the tick's sample read the counters").isNull();
        assertThat(journal.counts().underruns()).as("the answer sampled before the forget is not taken")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.NOT_ASKED);

        tickAfterBlocks();
        assertThat(journal.counts().underruns()).as("negative control: a sample after the forget answers")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.REPORTED);
    }

    @Test
    void underrunReportingSaysNothingUntilAStreamAnswersKeepsTheAnswerClosedAndASettingsChangeForgetsIt()
            throws Exception {
        XrunJournal journal = controller.xrunJournal();
        assertThat(journal.counts().underruns()).as("ruling E116: no stream has answered")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.NOT_ASKED);

        spy.dropouts = new BackendDropoutCounters(0, 0, 0, 0, 0, false);
        startCounting();
        assertThat(journal.counts().underruns()).as("the stream answered")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.NOT_REPORTED);
        engine.stopAudioOutput();
        tick();
        assertThat(journal.counts().underruns()).as("kept through the closed stream")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.NOT_REPORTED);

        controller.applyConfiguration(new AudioEngineController.Request(MockAudioBackend.NAME, "", "",
                SampleRate.HZ_44100, 256, 16));
        assertThat(journal.counts().underruns()).as("another driver chosen in Settings")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.NOT_ASKED);
        journal.reportsOutputStarvation(true);
        controller.applyConfiguration(new AudioEngineController.Request(MockAudioBackend.NAME, "", "",
                SampleRate.HZ_48000, 256, 16));
        assertThat(journal.counts().underruns()).as("negative control: the same driver at another rate")
                .isEqualTo(EngineHealthStatus.UnderrunReporting.REPORTED);
    }

    private void carryGrowthWhileTheDevicePeriodsStandStill() throws InterruptedException {
        spy.threadCounters = devicePeriods(10);
        streamAndCount();
        spy.threadCounters = devicePeriods(20);
        tickAfterBeats();
        spy.dropouts = new BackendDropoutCounters(40, 9, 0, 0, 0, true);
        tickAfterBeats();
    }

    private void countAgainWithTheDevicePeriodsAdvancing() throws InterruptedException {
        tick();
        tickAfterBeats();
        spy.threadCounters = devicePeriods(30);
        tickAfterBeats();
        spy.threadCounters = devicePeriods(40);
        tickAfterBeats();
    }

    @Test
    void refusalsWhileTheRenderDriveStandsStillAreTheStallsAndNotCounted() throws Exception {
        startCounting();
        spy.sinkRefusals.set(Integer.MAX_VALUE);
        await(() -> engine.audioHealth().total(AudioFailureCategory.SINK) >= 2, "the output refuses every block");
        tick();
        long countedWhenTheDriveStopped = controller.xrunJournal().counts().gaps();
        long refusedWhenTheDriveStopped = engine.audioHealth().total(AudioFailureCategory.SINK);

        await(() -> engine.audioHealth().total(AudioFailureCategory.SINK) >= refusedWhenTheDriveStopped + 5,
                "more refusals while no block is played");
        tick();
        tick();

        assertThat(controller.xrunJournal().counts().gaps())
                .as("a backend without device periods: the render beats stood still (rulings E8, E19)")
                .isEqualTo(countedWhenTheDriveStopped);

        tickUntilTheStallIsDeclared();
        spy.sinkRefusals.set(0);
        engine.startAudioOutput();
        tick();
        tickAfterBeats();
        tickAfterBeats();
        tickAfterBeats();

        assertThat(controller.xrunJournal().counts().gaps())
                .as("the reopened stream beats, and the stall's refusals are still not counted")
                .isEqualTo(countedWhenTheDriveStopped);
    }

    @Test
    void aStallVerdictWritesOneStallEntryAndRaisesNothing() throws Exception {
        startCounting();
        spy.blockAwait = true;
        await(() -> spy.blockedAwaitEntries.get() >= 1, "the render pump is wedged in awaitSinkCapacity");

        for (int i = 0; i < 40 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS));
            tick();
        }

        assertThat(controller.engineState()).as("premise: the stall was declared").isEqualTo(EngineState.DEVICE_LOST);
        assertThat(controller.xrunJournal().entries())
                .extracting(XrunJournal.Entry::detail, XrunJournal.Entry::arguments, XrunJournal.Entry::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(XrunJournal.Detail.DEVICE_STOPPED_RESPONDING,
                        List.of("Interface A"), 1L));
        assertThat(controller.xrunJournal().counts().gaps()).as("a stall is never xruns (ruling E19)").isZero();
    }

    @Test
    void aSettingsChangeRestartsTheCountAndKeepsTheEntries() {
        XrunJournal journal = controller.xrunJournal();
        tick();
        journal.append(XrunJournal.Detail.OUTPUT_STARVED, List.of(), 3);
        tick();
        assertThat(journal.counts().gaps()).as("negative control: a tick alone restarts nothing").isEqualTo(3);

        controller.applyConfiguration(new AudioEngineController.Request(MockAudioBackend.NAME, "", "",
                SampleRate.HZ_44100, 256, 16));
        tick();

        assertThat(journal.counts().gaps()).as("the detector was rebuilt (ruling E22)").isZero();
        assertThat(journal.entries()).as("the log keeps its entries until Clear").hasSize(1);
    }

    /** Streams and ticks until the feed counts: the epoch is seen, then the drive's first advance. */
    private void startCounting() throws InterruptedException {
        engine.startAudioOutput();
        tick();
        tickAfterBlocks();
        tickAfterBlocks();
    }

    /** Moves the health clock tick by tick until the watchdog's verdict closed the stream. */
    private void tickUntilTheStallIsDeclared() {
        for (int i = 0; i < 40 && controller.engineState() != EngineState.DEVICE_LOST; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(DefaultAudioEngineController.ENGINE_HEALTH_TICK_MILLIS));
            tick();
        }
        assertThat(controller.engineState()).as("premise: the stall was declared").isEqualTo(EngineState.DEVICE_LOST);
        assertThat(engine.isStreamOpen()).as("premise: the verdict closed the stream").isFalse();
    }

    /** Like {@link #startCounting()}, each tick after the render drive beat again, not after banked sunk blocks. */
    private void streamAndCount() throws InterruptedException {
        engine.startAudioOutput();
        tick();
        tickAfterBeats();
        tickAfterBeats();
    }

    private void tickAfterBeats() throws InterruptedException {
        long beats = engine.audioHealth().renderDriveBeats();
        await(() -> engine.audioHealth().renderDriveBeats() >= beats + 2, "the render drive beats");
        tick();
    }

    private void tickAfterBlocks() throws InterruptedException {
        assertThat(spy.awaitSunkBlocks(2, 10, TimeUnit.SECONDS)).as("the render drive keeps sinking").isTrue();
        tick();
    }

    private void tick() {
        controller.tickEngineHealthMonitor();
    }

    private List<XrunEvent> subscribe() {
        List<XrunEvent> offered = Collections.synchronizedList(new ArrayList<>());
        controller.xrunEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(XrunEvent event) { offered.add(event); }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });
        return offered;
    }

    private static Map<XrunJournal.Detail, Long> countsByDetail(XrunJournal journal) {
        Map<XrunJournal.Detail, Long> counts = new EnumMap<>(XrunJournal.Detail.class);
        for (XrunJournal.Entry entry : journal.entries()) {
            if (entry.kind().raisesTheCount()) {
                counts.merge(entry.detail(), entry.count(), Long::sum);
            }
        }
        return counts;
    }

    private static BackendThreadCounters callbackFaults(long faults) {
        return new BackendThreadCounters(faults, 0, 0, 0, OptionalLong.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static BackendThreadCounters devicePeriods(long periods) {
        return new BackendThreadCounters(0, 0, 0, 0, OptionalLong.of(periods), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
