package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit contract of the per-engine {@link AudioHealthRecord} (story 337,
 * rulings E4, E5, E18, E20, E21, E78, E79, E81, E82, E86 and E94 to E100):
 * lanes retire into monotonic totals and stay readable until drained, a
 * render thread records only into its own record, one reading takes every
 * count before every sample and uses one report of each counter source —
 * reading the still-attached sources once more when one is folded while it
 * reads them — the backend ledger counts only what grew since attach and
 * folds at detach from one report, a reading names the device whose count
 * grew — the one attached last among those that kept a fault — a failing
 * counter source is frozen and never throws into a reader — a
 * {@link VirtualMachineError} into the one reader that met it — an input
 * device counts as input, and the two heartbeats read as documented.
 */
class AudioHealthRecordTest {

    /** Guard budget for a render thread's join — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    /** How long the writer and the reader race in the probabilistic test. */
    private static final long RACE_MILLIS = 1_000L;
    private static final Duration JOIN_BUDGET = Duration.ofSeconds(10);
    private static final long MIN_READINGS = 1_000L;

    private final AudioHealthRecord record = new AudioHealthRecord();

    // ── Lanes ───────────────────────────────────────────────────────────

    @Test
    void retiringALaneFoldsItsCountsAndKeepsTheTotalsMonotonic() {
        AudioHealthLane lane = record.claimLane(LaneRole.RENDER_DRIVE);
        IllegalStateException failure = new IllegalStateException("render");
        lane.record(AudioFailureCodes.RENDER, failure, null, 1);
        lane.record(AudioFailureCodes.RENDER, failure, null, 2);
        lane.beat();
        lane.beat();
        long[] before = record.totals();

        record.retireLane(lane);

        assertThat(record.totals()).containsExactly(before);
        assertThat(record.total(AudioFailureCategory.RENDER)).isEqualTo(2);
        assertThat(record.lastFailure(AudioFailureCategory.RENDER)).containsSame(failure);
        assertThat(record.renderDriveBeats()).as("retired render-drive beats are kept").isEqualTo(2);
    }

    @Test
    void retiringALaneTwiceFoldsItOnce() {
        AudioHealthLane lane = record.claimLane(LaneRole.WORKER);
        lane.record(AudioFailureCodes.WORKER, new IllegalStateException("task"), "subject", 0);

        record.retireLane(lane);
        record.retireLane(lane);

        assertThat(record.total(AudioFailureCategory.WORKER)).as("folded exactly once").isEqualTo(1);
    }

    /**
     * Story 337, ruling E81: a failure in the last blocks before a lane's
     * thread stopped is still handed to the reading with its throwable,
     * subject and detail, once.
     */
    @Test
    void aRetiredLanesUndrainedSamplesAreStillHandedToTheReading() {
        AudioHealthLane lane = record.claimLane(LaneRole.RENDER_DRIVE);
        IllegalStateException setterFailure = new IllegalStateException("setter");
        IllegalArgumentException renderFailure = new IllegalArgumentException("render");
        lane.record(AudioFailureCodes.AUTOMATION, setterFailure, "cutoff", 3);
        lane.record(AudioFailureCodes.RENDER, renderFailure, "insert", 4);

        record.retireLane(lane);
        List<AudioFailureSample> samples = read();

        assertThat(samples).extracting(AudioFailureSample::category)
                .containsExactly(AudioFailureCategory.AUTOMATION, AudioFailureCategory.RENDER);
        assertThat(samples.get(0).failure()).containsSame(setterFailure);
        assertThat(samples.get(0).subject()).contains("cutoff");
        assertThat(samples.get(0).detail()).isEqualTo(3);
        assertThat(samples.get(1).failure()).containsSame(renderFailure);
        assertThat(samples.get(1).subject()).contains("insert");
        assertThat(samples.get(1).detail()).isEqualTo(4);
        assertThat(read()).as("each descriptor is handed over once").isEmpty();
    }

    /**
     * Story 337, ruling E81: one reading hands over the samples of an
     * undrained retired lane before those of a live lane — oldest first —
     * even when the live lane was claimed first.
     */
    @Test
    void oneReadingHandsOverARetiredLanesSamplesBeforeALiveLanes() {
        AudioHealthLane live = record.claimLane(LaneRole.RENDER_DRIVE);
        AudioHealthLane retired = record.claimLane(LaneRole.WORKER);
        live.record(AudioFailureCodes.RENDER, new IllegalStateException("live"), "live lane", 1);
        retired.record(AudioFailureCodes.WORKER, new IllegalStateException("retired"), "retired lane", 2);
        record.retireLane(retired);

        assertThat(read()).extracting(AudioFailureSample::detail)
                .as("the retired lane's sample first, then the live lane's")
                .containsExactly(2, 1);
    }

    /**
     * Story 337, ruling E81: once the drain has taken a retired lane's
     * descriptors the lane holds no reference, and the next lifecycle event
     * drops it from the record.
     */
    @Test
    void aRetiredLaneKeepsNoReferenceOnceItsSamplesAreDrained() throws Exception {
        AudioHealthLane lane = record.claimLane(LaneRole.WORKER);
        lane.record(AudioFailureCodes.WORKER, new IllegalStateException("task"), "subject", 0);
        record.retireLane(lane);
        assertThat(lanes().draining()).as("an undrained retired lane is kept for the drain")
                .containsExactly(lane);

        assertThat(read()).hasSize(1);

        for (RecordSlot slot : slotsOf(lane)) {
            assertThat(slot.failure).as("no drained slot keeps a throwable alive").isNull();
            assertThat(slot.subject).as("no drained slot keeps a subject alive").isNull();
        }
        record.claimLane(LaneRole.WORKER);
        assertThat(lanes().draining()).as("a drained retired lane is dropped at the next claim").isEmpty();
        assertThat(lanes().live()).doesNotContain(lane);
    }

    /**
     * Story 337, ruling E81: at most {@link LaneSet#MAX_DRAINING} undrained
     * retired lanes are kept; beyond that the oldest is cleared and its
     * descriptors are counted as lost samples, while the counts stay exact.
     */
    @Test
    void retiredLanesNobodyDrainsAreBoundedAndTheirSamplesCountedLost() throws Exception {
        List<AudioHealthLane> retired = new ArrayList<>();
        for (int i = 0; i <= LaneSet.MAX_DRAINING; i++) {
            AudioHealthLane lane = record.claimLane(LaneRole.WORKER);
            lane.record(AudioFailureCodes.WORKER, new IllegalStateException("task " + i), "lane " + i, i);
            record.retireLane(lane);
            retired.add(lane);
        }

        assertThat(record.lostSamples()).as("the oldest undrained lane was evicted").isEqualTo(1);
        for (RecordSlot slot : slotsOf(retired.get(0))) {
            assertThat(slot.failure).as("the evicted lane's slots are cleared").isNull();
            assertThat(slot.subject).as("the evicted lane's slots are cleared").isNull();
        }
        List<AudioFailureSample> samples = read();
        assertThat(samples).extracting(AudioFailureSample::detail)
                .as("oldest retired first, the evicted lane's left out")
                .containsExactlyElementsOf(IntStream.rangeClosed(1, LaneSet.MAX_DRAINING).boxed().toList());
        assertThat(record.total(AudioFailureCategory.WORKER)).as("the counts stay exact")
                .isEqualTo(LaneSet.MAX_DRAINING + 1);
    }

    @Test
    void aLaneOfAnotherRecordIsRefused() {
        AudioHealthLane foreign = new AudioHealthRecord().claimLane(LaneRole.WORKER);

        assertThatThrownBy(() -> record.retireLane(foreign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordingOffARenderThreadRecordsNothing() {
        assertThat(record.recordOnCurrentThread(AudioFailureCodes.MIDI,
                new IllegalStateException("off a render thread"), null, 0)).isFalse();
        assertThat(RenderThread.recordOnCurrentThread(AudioFailureCodes.MIDI,
                new IllegalStateException("off a render thread"), null, 0)).isFalse();

        assertThat(record.totals()).containsOnly(0L);
    }

    @Test
    void aRenderThreadRecordsIntoItsOwnRecordOnly() throws Exception {
        AudioHealthRecord other = new AudioHealthRecord();
        AtomicBoolean recordedHere = new AtomicBoolean();
        AtomicBoolean recordedOnForeign = new AtomicBoolean();
        AtomicBoolean recordedThroughTheThread = new AtomicBoolean();

        runOnRenderThread(record.claimLane(LaneRole.RENDER_DRIVE), () -> {
            recordedHere.set(record.recordOnCurrentThread(AudioFailureCodes.MIDI,
                    new IllegalStateException("midi"), null, 60));
            recordedOnForeign.set(other.recordOnCurrentThread(AudioFailureCodes.MIDI,
                    new IllegalStateException("foreign"), null, 61));
            recordedThroughTheThread.set(RenderThread.recordOnCurrentThread(AudioFailureCodes.DSP,
                    new IllegalStateException("dsp"), "processor", 3));
        });

        assertThat(recordedHere).isTrue();
        assertThat(recordedOnForeign).as("another engine's record never sees this lane").isFalse();
        assertThat(recordedThroughTheThread).isTrue();
        assertThat(record.total(AudioFailureCategory.MIDI)).isEqualTo(1);
        assertThat(record.total(AudioFailureCategory.DSP)).isEqualTo(1);
        assertThat(other.totals()).containsOnly(0L);
    }

    @Test
    void aRenderThreadIsADaemonPlatformThreadAtMaximumPriorityThatCarriesItsLane() {
        AudioHealthLane lane = record.claimLane(LaneRole.WORKER);
        RenderThread thread = new RenderThread(lane, () -> { }, "render-thread-under-test");

        assertThat(thread.lane()).isSameAs(lane);
        assertThat(thread.isDaemon()).isTrue();
        assertThat(thread.getPriority()).isEqualTo(Thread.MAX_PRIORITY);
        assertThat(thread.isVirtual()).isFalse();
        assertThat(thread.getName()).isEqualTo("render-thread-under-test");
        assertThat(thread.getUncaughtExceptionHandler())
                .as("no handler of its own: the thread group's default applies")
                .isSameAs(thread.getThreadGroup());
    }

    @Test
    void theRenderDriveEpochCountsRenderDriveClaimsOnly() {
        record.claimLane(LaneRole.RENDER_DRIVE);
        record.claimLane(LaneRole.WORKER);
        record.claimLane(LaneRole.RENDER_DRIVE);

        assertThat(record.renderDriveEpoch()).isEqualTo(2);
    }

    @Test
    void theRenderDriveHeartbeatExcludesWorkerLanes() {
        AudioHealthLane drive = record.claimLane(LaneRole.RENDER_DRIVE);
        AudioHealthLane worker = record.claimLane(LaneRole.WORKER);
        drive.beat();
        worker.beat();
        worker.beat();

        assertThat(record.renderDriveBeats()).isEqualTo(1);
    }

    @Test
    void theNewestLiveLanesFailureIsTheLastFailure() {
        AudioHealthLane older = record.claimLane(LaneRole.RENDER_DRIVE);
        IllegalStateException olderFailure = new IllegalStateException("older");
        older.record(AudioFailureCodes.RENDER, olderFailure, null, 1);
        record.retireLane(older);
        AudioHealthLane newer = record.claimLane(LaneRole.RENDER_DRIVE);

        assertThat(record.lastFailure(AudioFailureCategory.RENDER)).containsSame(olderFailure);

        IllegalStateException newerFailure = new IllegalStateException("newer");
        newer.record(AudioFailureCodes.RENDER, newerFailure, null, 1);

        assertThat(record.lastFailure(AudioFailureCategory.RENDER)).containsSame(newerFailure);
        assertThat(record.lastFailure(AudioFailureCategory.CONFIG)).isEmpty();
    }

    // ── One reading per frame ───────────────────────────────────────────

    /**
     * Story 337, ruling E79: the reading takes every count before it polls a
     * ring, so a failure recorded while the reading is handing samples out —
     * into a lane it has already polled — is neither counted nor sampled by
     * that reading, and the next reading delivers its count and its sample
     * together.
     */
    @Test
    void aReadingNeverCountsAFailureItHasNotBeenHandedTheSampleOf() {
        AudioHealthLane polledFirst = record.claimLane(LaneRole.RENDER_DRIVE);
        AudioHealthLane polledSecond = record.claimLane(LaneRole.WORKER);
        polledSecond.record(AudioFailureCodes.WORKER, new IllegalStateException("task"), null, 0);
        IllegalStateException late = new IllegalStateException("setter");
        AtomicBoolean recorded = new AtomicBoolean();
        List<AudioFailureSample> first = new ArrayList<>();

        AudioHealthReading reading = record.read(sample -> {
            first.add(sample);
            if (recorded.compareAndSet(false, true)) {
                polledFirst.record(AudioFailureCodes.AUTOMATION, late, "cutoff", 7);
            }
        });

        assertThat(first).extracting(AudioFailureSample::category).containsExactly(AudioFailureCategory.WORKER);
        assertThat(reading.total(AudioFailureCategory.WORKER)).isEqualTo(1);
        assertThat(reading.total(AudioFailureCategory.AUTOMATION))
                .as("a failure recorded after the reading took its counts is not counted by it")
                .isZero();

        List<AudioFailureSample> second = new ArrayList<>();
        AudioHealthReading next = record.read(second::add);

        assertThat(next.total(AudioFailureCategory.AUTOMATION)).isEqualTo(1);
        assertThat(second).singleElement().satisfies(sample -> {
            assertThat(sample.category()).isEqualTo(AudioFailureCategory.AUTOMATION);
            assertThat(sample.failure()).containsSame(late);
            assertThat(sample.subject()).contains("cutoff");
        });
    }

    /**
     * Story 337, ruling E79, under a live writer: after every reading, the
     * failures it counts are no more than the samples handed out so far plus
     * the samples counted lost. A memory-ordering fault cannot be forced, so
     * the writer and the reader race for a bounded time: a probabilistic
     * guard, not a proof — the bytecode pin of the lane's commit order in
     * {@code RealTimeSafeContractTest} is the deterministic one.
     */
    @Test
    void aWriterRacingTheReaderNeverLeavesACountWithoutItsSample() throws Exception {
        AudioHealthLane lane = record.claimLane(LaneRole.RENDER_DRIVE);
        IllegalStateException failure = new IllegalStateException("render");
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong delivered = new AtomicLong();
        AtomicLong readings = new AtomicLong();
        AtomicReference<String> firstShortfall = new AtomicReference<>();

        Thread writer = Thread.ofPlatform().name("lane-writer").daemon(true).start(() -> {
            int detail = 0;
            while (!stop.get()) {
                int category = (detail & 1) == 0 ? AudioFailureCodes.RENDER : AudioFailureCodes.AUTOMATION;
                lane.record(category, failure, null, detail);
                detail = (detail + 1) & Integer.MAX_VALUE;
            }
        });
        Thread reader = Thread.ofPlatform().name("health-reader").daemon(true).start(() -> {
            while (!stop.get()) {
                AudioHealthReading reading = record.read(sample -> delivered.incrementAndGet());
                readings.incrementAndGet();
                long counted = 0L;
                for (AudioFailureCategory category : AudioFailureCategory.values()) {
                    counted += reading.total(category);
                }
                long accounted = delivered.get() + reading.lostSamples();
                if (counted > accounted) {
                    firstShortfall.compareAndSet(null,
                            "counted " + counted + ", sampled " + delivered.get() + ", lost "
                                    + reading.lostSamples());
                }
            }
        });

        try {
            Thread.sleep(RACE_MILLIS);
        } finally {
            stop.set(true);
        }

        assertThat(writer.join(JOIN_BUDGET)).as("the writer stopped").isTrue();
        assertThat(reader.join(JOIN_BUDGET)).as("the reader stopped").isTrue();
        assertThat(readings.get()).as("the reader raced the writer").isGreaterThanOrEqualTo(MIN_READINGS);
        assertThat(firstShortfall.get()).as("a reading that counted a failure it had no sample of").isNull();
    }

    /**
     * Story 337, rulings E82, E94 and E98: a reading during which no source is
     * folded reads each attached counter source once — the stream's backend
     * and an input device beside it — and its totals, its input halts, its
     * backend report and its last failures all come from those reports —
     * against sources that answer differently on every call.
     */
    @Test
    void aReadingIsOneCutOfTheLanesAndTheBackend() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger inputCalls = new AtomicInteger();
        record.attachBackendCounters(() -> "Changing backend", () -> changingReport("", calls.incrementAndGet()));
        record.attachInputCounters(new Object(), () -> "Changing input",
                () -> changingReport("input-device ", inputCalls.incrementAndGet()));
        int callsBeforeTheReading = calls.get();
        int inputCallsBeforeTheReading = inputCalls.get();

        AudioHealthReading reading = record.read(sample -> { });

        assertThat(calls.get() - callsBeforeTheReading).as("the backend is read once per reading").isEqualTo(1);
        assertThat(inputCalls.get() - inputCallsBeforeTheReading).as("so is the input device").isEqualTo(1);
        BackendThreadCounters backend = reading.backendCounters();
        assertThat(backend.inputHalts()).as("the report grew since the attach").isPositive();
        assertThat(reading.total(AudioFailureCategory.INPUT))
                .isEqualTo(backend.inputBlocksLost() + backend.inputHalts());
        assertThat(reading.total(AudioFailureCategory.CALLBACK)).isEqualTo(backend.callbackFaults());
        assertThat(reading.total(AudioFailureCategory.DEVICE)).isEqualTo(backend.deviceNotificationFaults());
        assertThat(reading.inputHalts()).isEqualTo(backend.inputHalts());
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT))
                .containsSame(backend.lastInputFault().orElseThrow());
        assertThat(reading.lastFailure(AudioFailureCategory.CALLBACK))
                .containsSame(backend.lastCallbackFault().orElseThrow());
        assertThat(reading.lastFailure(AudioFailureCategory.DEVICE))
                .containsSame(backend.lastDeviceNotificationFault().orElseThrow());
        assertThat(reading.inputHaltFault()).as("both halted: the newest-attached one is named")
                .containsSame(backend.lastInputFault().orElseThrow());
    }

    // ── Which device a reading names (story 337, ruling E94) ────────────

    @Test
    void anInputHaltIsNamedByTheHaltedDevicesFaultNotAnotherDevicesOlderFault() {
        IllegalStateException halt = haltOfTheStreamAfterASecondInputLostABlock();

        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(1);
        assertThat(reading.inputHaltFault()).as("the halt is named by the input that stopped").containsSame(halt);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT))
                .as("not by the newer-attached input's older lost block")
                .containsSame(halt);
    }

    @Test
    void aReadingWithNoNewInputHaltNamesNoHaltFault() {
        IllegalStateException halt = haltOfTheStreamAfterASecondInputLostABlock();
        assertThat(record.read(sample -> { }).inputHaltFault()).as("fixture: the halt is named").containsSame(halt);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.inputHalts()).as("the halt is still counted").isEqualTo(1);
        assertThat(next.inputHaltFault()).as("but no input halted since the previous reading").isEmpty();
    }

    @Test
    void aLostBlockAndADeviceNotificationAreNamedByTheDeviceThatCountedThem() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface B", second::get);
        second.set(new BackendThreadCounters(0, 1, 0, 1, OptionalLong.empty(), Optional.empty(),
                Optional.of(new IllegalStateException("an older lost block of B")),
                Optional.of(new IllegalStateException("an older notification of B"))));
        record.read(sample -> { });
        IllegalStateException lostBlock = new IllegalStateException("a block of A was dropped");
        IllegalStateException notification = new IllegalStateException("A's driver sent a broken notification");

        stream.set(new BackendThreadCounters(0, 1, 0, 1, OptionalLong.empty(), Optional.empty(),
                Optional.of(lostBlock), Optional.of(notification)));
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.lastFailure(AudioFailureCategory.INPUT)).as("the lost block of the device that counted it")
                .containsSame(lostBlock);
        assertThat(reading.lastFailure(AudioFailureCategory.DEVICE))
                .as("the notification of the device that counted it")
                .containsSame(notification);
    }

    @Test
    void twoInputsHaltingWithinOneReadingAreNamedByTheNewestAttached() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface B", second::get);
        IllegalStateException firstHalt = new IllegalStateException("input from “Interface A” stopped");
        IllegalStateException secondHalt = new IllegalStateException("input from “Interface B” stopped");

        stream.set(inputReport(0, 1, firstHalt));
        second.set(inputReport(0, 1, secondHalt));
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("one name, the input opened last").containsSame(secondHalt);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT)).containsSame(secondHalt);
    }

    /**
     * Story 337, ruling E94: the input that was not named still had its halt
     * counted by that reading, so the next reading has no halt to name.
     */
    @Test
    void anInputThatHaltedBesideTheNamedOneIsNotNamedAgainByTheNextReading() {
        ScriptedSource older = new ScriptedSource();
        ScriptedSource newer = new ScriptedSource();
        record.attachInputCounters(new Object(), () -> "Interface A", older);
        record.attachInputCounters(new Object(), () -> "Interface B", newer);
        IllegalStateException newerHalt = new IllegalStateException("input from “Interface B” stopped");
        older.answer(inputReport(0, 1, new IllegalStateException("input from “Interface A” stopped")));
        newer.answer(inputReport(0, 1, newerHalt));
        AudioHealthReading first = record.read(sample -> { });
        assertThat(first.inputHalts()).as("fixture: both halts are counted").isEqualTo(2);
        assertThat(first.inputHaltFault()).as("fixture: one name, the input opened last").containsSame(newerHalt);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.inputHalts()).as("nothing new counted").isEqualTo(first.inputHalts());
        assertThat(next.inputHaltFault()).as("A's halt was counted by the previous reading, which named B")
                .isEmpty();
    }

    @Test
    void aLostBlockBesideTheNamedOneDoesNotReplaceTheQuoteInTheNextReading() {
        ScriptedSource older = new ScriptedSource();
        ScriptedSource newer = new ScriptedSource();
        record.attachInputCounters(new Object(), () -> "Interface A", older);
        record.attachInputCounters(new Object(), () -> "Interface B", newer);
        IllegalStateException newerLoss = new IllegalStateException("a block of B was dropped");
        older.answer(inputReport(1, 0, new IllegalStateException("a block of A was dropped")));
        newer.answer(inputReport(1, 0, newerLoss));
        assertThat(record.read(sample -> { }).lastFailure(AudioFailureCategory.INPUT))
                .as("fixture: one name, the input opened last").containsSame(newerLoss);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.lastFailure(AudioFailureCategory.INPUT))
                .as("nothing new counted: still B's, never A's that the previous reading counted")
                .containsSame(newerLoss);
    }

    /**
     * Story 337, rulings E94 and E99: B kept no fault, so the reading quoted A's; left uncounted, B's lost
     * block would grow again in the next reading and replace the quote with B's missing fault.
     */
    @Test
    void aNewerInputsLostBlockWithoutAFaultDoesNotClearTheQuoteInTheNextReading() {
        ScriptedSource older = new ScriptedSource();
        ScriptedSource newer = new ScriptedSource();
        record.attachInputCounters(new Object(), () -> "Interface A", older);
        record.attachInputCounters(new Object(), () -> "Interface B", newer);
        IllegalStateException olderLoss = new IllegalStateException("a block of A was dropped");
        older.answer(inputReport(1, 0, olderLoss));
        newer.answer(inputReport(1, 0, null));
        AudioHealthReading first = record.read(sample -> { });
        assertThat(first.total(AudioFailureCategory.INPUT)).as("fixture: both lost blocks are counted").isEqualTo(2);
        assertThat(first.lastFailure(AudioFailureCategory.INPUT)).as("fixture: B kept no fault, so A's is quoted")
                .containsSame(olderLoss);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.total(AudioFailureCategory.INPUT)).as("nothing new counted")
                .isEqualTo(first.total(AudioFailureCategory.INPUT));
        assertThat(next.lastFailure(AudioFailureCategory.INPUT))
                .as("B's lost block was counted by the previous reading, which quoted A")
                .containsSame(olderLoss);
    }

    @Test
    void anOlderInputsLostBlockWithoutAFaultDoesNotClearTheQuoteInTheNextReading() {
        ScriptedSource older = new ScriptedSource();
        ScriptedSource newer = new ScriptedSource();
        record.attachInputCounters(new Object(), () -> "Interface A", older);
        record.attachInputCounters(new Object(), () -> "Interface B", newer);
        IllegalStateException newerLoss = new IllegalStateException("a block of B was dropped");
        older.answer(inputReport(1, 0, null));
        newer.answer(inputReport(1, 0, newerLoss));
        AudioHealthReading first = record.read(sample -> { });
        assertThat(first.total(AudioFailureCategory.INPUT)).as("fixture: both lost blocks are counted").isEqualTo(2);
        assertThat(first.lastFailure(AudioFailureCategory.INPUT)).as("fixture: A kept no fault, so B's is quoted")
                .containsSame(newerLoss);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.total(AudioFailureCategory.INPUT)).as("nothing new counted")
                .isEqualTo(first.total(AudioFailureCategory.INPUT));
        assertThat(next.lastFailure(AudioFailureCategory.INPUT))
                .as("A's lost block was counted by the previous reading, which quoted B")
                .containsSame(newerLoss);
    }

    /**
     * Story 337, ruling E94: a halt's fault is named per reading, so B's missing one shows nothing by itself.
     * B's later lost block gives B a last input fault, which the halt kind reads too: it names a halt only if
     * the reading that named A left B's halt uncounted.
     */
    @Test
    void anInputThatHaltedWithoutAFaultBesideTheNamedOneIsNotNamedByItsLaterLostBlock() {
        ScriptedSource older = new ScriptedSource();
        ScriptedSource newer = new ScriptedSource();
        record.attachInputCounters(new Object(), () -> "Interface A", older);
        record.attachInputCounters(new Object(), () -> "Interface B", newer);
        IllegalStateException olderHalt = new IllegalStateException("input from “Interface A” stopped");
        older.answer(inputReport(0, 1, olderHalt));
        newer.answer(inputReport(0, 1, null));
        AudioHealthReading first = record.read(sample -> { });
        assertThat(first.inputHalts()).as("fixture: both halts are counted").isEqualTo(2);
        assertThat(first.inputHaltFault()).as("fixture: B kept no fault, so A's halt is named").containsSame(olderHalt);
        IllegalStateException newerLoss = new IllegalStateException("a block of B was dropped");

        newer.answer(inputReport(1, 1, newerLoss));
        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.inputHalts()).as("no new halt").isEqualTo(first.inputHalts());
        assertThat(next.inputHaltFault()).as("B's halt was counted by the previous reading, which named A")
                .isEmpty();
        assertThat(next.lastFailure(AudioFailureCategory.INPUT)).as("B's new lost block, by its own fault")
                .containsSame(newerLoss);
    }

    @Test
    void aDeviceThatCountsWithoutAFaultIsNotGivenAnotherDevicesFault() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface B",
                () -> inputReport(1, 0, new IllegalStateException("an older lost block of B")));

        stream.set(inputReport(1, 0, null));
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.total(AudioFailureCategory.INPUT)).as("fixture: A counted a lost block").isEqualTo(1);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT))
                .as("A kept no fault, and B's older one is not A's")
                .isEmpty();
    }

    @Test
    void aReadingWithNoNewFailureOfAKindKeepsQuotingTheDeviceThatLastCounted() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface B",
                () -> inputReport(1, 0, new IllegalStateException("an older lost block of B")));
        IllegalStateException lostBlock = new IllegalStateException("a block of A was dropped");
        stream.set(inputReport(1, 0, lostBlock));
        assertThat(record.read(sample -> { }).lastFailure(AudioFailureCategory.INPUT))
                .as("fixture: A's lost block is named").containsSame(lostBlock);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.lastFailure(AudioFailureCategory.INPUT))
                .as("nothing new counted: still the device that counted last")
                .containsSame(lostBlock);
    }

    @Test
    void aDeviceThatHaltedAndWasClosedBeforeTheReadingIsStillNamedByItsOwnFault() {
        record.attachBackendCounters(() -> "Interface A",
                () -> inputReport(1, 0, new IllegalStateException("an older lost block of A")));
        Object input = new Object();
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(input, () -> "Interface B", second::get);
        IllegalStateException halt = new IllegalStateException("input from “Interface B” stopped");

        second.set(inputReport(0, 1, halt));
        record.detachInputCounters(input);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(1);
        assertThat(reading.inputHaltFault()).as("the closed input's own halt").containsSame(halt);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT)).containsSame(halt);
    }

    @Test
    void onlyAReadingMarksABackendFailureAsSeen() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface B",
                () -> inputReport(1, 0, new IllegalStateException("an older lost block of B")));
        IllegalStateException halt = new IllegalStateException("input from “Interface A” stopped");

        stream.set(inputReport(0, 1, halt));
        record.totals();
        record.total(AudioFailureCategory.INPUT);
        record.lastFailure(AudioFailureCategory.INPUT);
        record.inputHalts();
        record.backendCounters();
        record.deviceCallbackPeriods();
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHaltFault()).as("the other readers left the halt to the reading").containsSame(halt);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT)).containsSame(halt);
    }

    @Test
    void aClosedSourceWhoseHaltAReadingAlreadyNamedIsNeverNamedAgain() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        Object input = new Object();
        record.attachInputCounters(input, () -> "Interface B", second::get);
        IllegalStateException secondHalt = new IllegalStateException("input from “Interface B” stopped");
        second.set(inputReport(0, 1, secondHalt));
        assertThat(record.read(sample -> { }).inputHaltFault()).as("fixture: B's halt is named").containsSame(secondHalt);
        record.detachInputCounters(input);
        IllegalStateException streamHalt = new IllegalStateException("input from “Interface A” stopped");

        stream.set(inputReport(0, 1, streamHalt));
        record.detachBackendCounters();
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("A's halt, never B's halt that a reading already named")
                .containsSame(streamHalt);
    }

    @Test
    void aReadingAfterAGrowthFreeCloseNamesNoHalt() {
        Object input = new Object();
        AtomicReference<BackendThreadCounters> device = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(input, () -> "Interface B", device::get);
        IllegalStateException halt = new IllegalStateException("input from “Interface B” stopped");
        device.set(inputReport(0, 1, halt));
        assertThat(record.read(sample -> { }).inputHaltFault()).as("fixture: the halt is named").containsSame(halt);

        record.detachInputCounters(input);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("the halt is still counted").isEqualTo(1);
        assertThat(reading.inputHaltFault()).as("the close counted nothing new").isEmpty();
    }

    /**
     * Story 337, rulings E94 and E99: after B's close the ledger's folded
     * counters still carry B's halt fault, so a later close that kept no fault
     * would name B if it took its fault from there rather than from its own
     * report.
     */
    @Test
    void aCloseThatKeptNoHaltFaultNeverTakesTheFaultOfAnInputClosedBeforeIt() {
        ScriptedSource stream = new ScriptedSource();
        ScriptedSource input = new ScriptedSource();
        Object device = new Object();
        record.attachBackendCounters(() -> "Interface S", stream);
        record.attachInputCounters(device, () -> "Interface B", input);
        IllegalStateException inputHalt = new IllegalStateException("input from “Interface B” stopped");
        input.answer(inputReport(0, 1, inputHalt));
        assertThat(record.read(sample -> { }).inputHaltFault()).as("fixture: B's halt is named")
                .containsSame(inputHalt);
        record.detachInputCounters(device);

        stream.answer(inputReport(0, 1, null));
        record.detachBackendCounters();
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("S's halt is counted").isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("S kept no fault, and B's halts did not grow in this reading")
                .isEmpty();
    }

    /**
     * Story 337, rulings E94 and E99: of two inputs that halted within one
     * reading and kept a fault, the one attached last is named, whether it
     * is still open or closed and whether the other is.
     */
    @ParameterizedTest
    @EnumSource(Closing.class)
    void ofTwoInputsThatHaltedWithinOneReadingTheOneAttachedLastIsNamedOpenOrClosed(Closing closing) {
        Object older = new Object();
        Object newer = new Object();
        AtomicReference<BackendThreadCounters> olderReport = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> newerReport = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(older, () -> "Interface A", olderReport::get);
        record.attachInputCounters(newer, () -> "Interface B", newerReport::get);
        IllegalStateException newerHalt = new IllegalStateException("input from “Interface B” stopped");
        olderReport.set(inputReport(0, 1, new IllegalStateException("input from “Interface A” stopped")));
        newerReport.set(inputReport(0, 1, newerHalt));

        closing.close(record, older, newer);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("one name, the input opened last").containsSame(newerHalt);
    }

    /**
     * Story 337, ruling E99: an input that halted without keeping a fault
     * never displaces an older one that halted and kept one, open or closed.
     */
    @ParameterizedTest
    @EnumSource(Closing.class)
    void anInputHaltIsNamedByAnOlderInputThatKeptAFaultOverANewerOneThatKeptNone(Closing closing) {
        Object older = new Object();
        Object newer = new Object();
        AtomicReference<BackendThreadCounters> olderReport = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> newerReport = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(older, () -> "Interface A", olderReport::get);
        record.attachInputCounters(newer, () -> "Interface B", newerReport::get);
        IllegalStateException olderHalt = new IllegalStateException("input from “Interface A” stopped");
        olderReport.set(inputReport(0, 1, olderHalt));
        newerReport.set(inputReport(0, 1, null));

        closing.close(record, older, newer);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("B kept no fault: A's halt names an input that stopped")
                .containsSame(olderHalt);
    }

    @Test
    void aLostBlockIsNamedByAClosedOlderInputThatKeptAFaultOverAnOpenNewerOneThatKeptNone() {
        Object older = new Object();
        AtomicReference<BackendThreadCounters> olderReport = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> newerReport = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(older, () -> "Interface A", olderReport::get);
        record.attachInputCounters(new Object(), () -> "Interface B", newerReport::get);
        IllegalStateException olderLoss = new IllegalStateException("a block of A was dropped");
        olderReport.set(inputReport(1, 0, olderLoss));
        newerReport.set(inputReport(1, 0, null));

        record.detachInputCounters(older);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.total(AudioFailureCategory.INPUT)).isEqualTo(2);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT)).as("B kept no fault: A's lost block")
                .containsSame(olderLoss);
    }

    /**
     * Story 337, rulings E94 and E99: C, attached last, kept a halt fault an
     * earlier reading named, so it would outrank both inputs that stopped
     * without one if a source whose halts did not grow could lend its fault.
     */
    @Test
    void noHaltFaultIsQuotedWhenNoneOfTheInputsWhoseHaltsGrewKeptOne() {
        Object newer = new Object();
        AtomicReference<BackendThreadCounters> olderReport = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> newerReport = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> namedReport = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(new Object(), () -> "Interface A", olderReport::get);
        record.attachInputCounters(newer, () -> "Interface B", newerReport::get);
        record.attachInputCounters(new Object(), () -> "Interface C", namedReport::get);
        IllegalStateException namedHalt = new IllegalStateException("input from “Interface C” stopped");
        namedReport.set(inputReport(0, 1, namedHalt));
        assertThat(record.read(sample -> { }).inputHaltFault()).as("fixture: C's halt is named")
                .containsSame(namedHalt);
        olderReport.set(inputReport(0, 1, null));
        newerReport.set(inputReport(0, 1, null));

        record.detachInputCounters(newer);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("C's halt, already named, and both new ones").isEqualTo(3);
        assertThat(reading.inputHaltFault()).as("no input that stopped kept a fault: the site reason applies")
                .isEmpty();
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT))
                .as("the inputs that counted kept no fault, and C's count did not grow")
                .isEmpty();
    }

    @Test
    void aClosedInputAttachedLastIsNamedOverAnOpenStreamAttachedFirst() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        Object input = new Object();
        record.attachInputCounters(input, () -> "Interface B", second::get);
        IllegalStateException secondHalt = new IllegalStateException("input from “Interface B” stopped");

        stream.set(inputReport(0, 1, new IllegalStateException("input from “Interface A” stopped")));
        second.set(inputReport(0, 1, secondHalt));
        record.detachInputCounters(input);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("the input opened last, although it is closed").containsSame(secondHalt);
    }

    @Test
    void anOpenInputAttachedLastIsNamedOverAClosedStreamAttachedFirst() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface C", second::get);
        IllegalStateException secondHalt = new IllegalStateException("input from “Interface C” stopped");

        stream.set(inputReport(0, 1, new IllegalStateException("input from “Interface A” stopped")));
        record.detachBackendCounters();
        second.set(inputReport(0, 1, secondHalt));
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("the input opened last, still open").containsSame(secondHalt);
    }

    /**
     * Story 337, ruling E94: the reading that named C also counted the closed
     * stream's halt it passed over, so that halt is not kept for a later
     * reading.
     */
    @Test
    void aClosedSourcesHaltThatAnOpenInputOutrankedIsNotNamedByTheNextReading() {
        ScriptedSource stream = new ScriptedSource();
        ScriptedSource input = new ScriptedSource();
        record.attachBackendCounters(() -> "Interface A", stream);
        record.attachInputCounters(new Object(), () -> "Interface C", input);
        IllegalStateException inputHalt = new IllegalStateException("input from “Interface C” stopped");
        stream.answer(inputReport(0, 1, new IllegalStateException("input from “Interface A” stopped")));
        record.detachBackendCounters();
        input.answer(inputReport(0, 1, inputHalt));
        assertThat(record.read(sample -> { }).inputHaltFault()).as("fixture: the input opened last is named")
                .containsSame(inputHalt);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.inputHalts()).as("both halts are still counted").isEqualTo(2);
        assertThat(next.inputHaltFault()).as("A's halt was counted by the previous reading, which named C")
                .isEmpty();
    }

    @Test
    void aClosedSourcesLostBlockThatAnOpenInputOutrankedDoesNotReplaceTheQuoteInTheNextReading() {
        ScriptedSource stream = new ScriptedSource();
        ScriptedSource input = new ScriptedSource();
        record.attachBackendCounters(() -> "Interface A", stream);
        record.attachInputCounters(new Object(), () -> "Interface C", input);
        IllegalStateException inputLoss = new IllegalStateException("a block of C was dropped");
        stream.answer(inputReport(1, 0, new IllegalStateException("a block of A was dropped")));
        record.detachBackendCounters();
        input.answer(inputReport(1, 0, inputLoss));
        assertThat(record.read(sample -> { }).lastFailure(AudioFailureCategory.INPUT))
                .as("fixture: the input opened last is named").containsSame(inputLoss);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.lastFailure(AudioFailureCategory.INPUT))
                .as("nothing new counted: still C's, never A's that the previous reading counted")
                .containsSame(inputLoss);
    }

    /**
     * Story 337, rulings E94 and E99: the reading that names the closed A's halt counts B's too, although it
     * quotes no open input; B's later lost block gives B a last input fault that would otherwise name a halt.
     */
    @Test
    void anOpenInputThatHaltedWithoutAFaultBesideAClosedOneIsNotNamedByItsLaterLostBlock() {
        Object closed = new Object();
        ScriptedSource older = new ScriptedSource();
        ScriptedSource newer = new ScriptedSource();
        record.attachInputCounters(closed, () -> "Interface A", older);
        record.attachInputCounters(new Object(), () -> "Interface B", newer);
        IllegalStateException olderHalt = new IllegalStateException("input from “Interface A” stopped");
        older.answer(inputReport(0, 1, olderHalt));
        record.detachInputCounters(closed);
        newer.answer(inputReport(0, 1, null));
        AudioHealthReading first = record.read(sample -> { });
        assertThat(first.inputHalts()).as("fixture: both halts are counted").isEqualTo(2);
        assertThat(first.inputHaltFault()).as("fixture: B kept no fault, so the closed A's halt is named")
                .containsSame(olderHalt);
        IllegalStateException newerLoss = new IllegalStateException("a block of B was dropped");

        newer.answer(inputReport(1, 1, newerLoss));
        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.inputHalts()).as("no new halt").isEqualTo(first.inputHalts());
        assertThat(next.inputHaltFault()).as("B's halt was counted by the previous reading, which named A")
                .isEmpty();
        assertThat(next.lastFailure(AudioFailureCategory.INPUT)).as("B's new lost block, by its own fault")
                .containsSame(newerLoss);
    }

    /**
     * Story 337, rulings E94 and E99: the closed A, which kept faults, outranks the open B, which counted
     * without any; B's growth must still be marked seen, or the next reading would count it as grown again
     * and replace each quote with its missing fault.
     */
    @Test
    void anOpenStreamThatCountedWithoutFaultsBesideAClosedOneDoesNotClearTheQuotesInTheNextReading() {
        ScriptedSource closed = new ScriptedSource();
        ScriptedSource open = new ScriptedSource();
        record.attachBackendCounters(() -> "Interface A", closed);
        IllegalStateException callbackFault = new IllegalStateException("A's callback failed");
        IllegalStateException lostBlock = new IllegalStateException("a block of A was dropped");
        IllegalStateException notification = new IllegalStateException("A's driver sent a broken notification");
        closed.answer(new BackendThreadCounters(1, 1, 0, 1, OptionalLong.empty(), Optional.of(callbackFault),
                Optional.of(lostBlock), Optional.of(notification)));
        record.detachBackendCounters();
        record.attachBackendCounters(() -> "Interface B", open);
        open.answer(counters(1, 1, 0, 1, OptionalLong.empty()));
        AudioHealthReading first = record.read(sample -> { });
        assertThat(first.lastFailure(AudioFailureCategory.CALLBACK)).as("fixture: B kept no fault, so A's is quoted")
                .containsSame(callbackFault);
        assertThat(first.lastFailure(AudioFailureCategory.INPUT)).as("fixture: likewise").containsSame(lostBlock);
        assertThat(first.lastFailure(AudioFailureCategory.DEVICE)).as("fixture: likewise").containsSame(notification);

        AudioHealthReading next = record.read(sample -> { });

        assertThat(next.backendCounters()).as("nothing new counted").isEqualTo(first.backendCounters());
        assertThat(next.lastFailure(AudioFailureCategory.CALLBACK))
                .as("B's callback fault was counted by the previous reading, which quoted A")
                .containsSame(callbackFault);
        assertThat(next.lastFailure(AudioFailureCategory.INPUT))
                .as("B's lost block was counted by the previous reading, which quoted A")
                .containsSame(lostBlock);
        assertThat(next.lastFailure(AudioFailureCategory.DEVICE))
                .as("B's notification was counted by the previous reading, which quoted A")
                .containsSame(notification);
    }

    /**
     * Story 337, ruling E100: an input closed and reopened within one reading
     * ranks by the attachment that halted, not by its reopen, which counted
     * nothing.
     */
    @Test
    void anInputReopenedBeforeTheReadingRanksByTheAttachmentThatHalted() {
        Object reopened = new Object();
        AtomicReference<BackendThreadCounters> reopenedReport = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> other = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachInputCounters(reopened, () -> "Interface D", reopenedReport::get);
        record.attachInputCounters(new Object(), () -> "Interface Y", other::get);
        IllegalStateException otherHalt = new IllegalStateException("input from “Interface Y” stopped");
        reopenedReport.set(inputReport(0, 1, new IllegalStateException("input from “Interface D” stopped")));
        other.set(inputReport(0, 1, otherHalt));

        record.detachInputCounters(reopened);
        record.attachInputCounters(reopened, () -> "Interface D", reopenedReport::get);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("the reopen counted nothing").isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("Y's attachment that halted is newer than D's").containsSame(otherHalt);
    }

    /** Story 337, ruling E100: the stream's owner is one key across reopens, ranked by the attachment that halted. */
    @Test
    void aStreamBackendReattachedBeforeTheReadingRanksByTheAttachmentThatHalted() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> other = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface D", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface Y", other::get);
        IllegalStateException otherHalt = new IllegalStateException("input from “Interface Y” stopped");
        stream.set(inputReport(0, 1, new IllegalStateException("input from “Interface D” stopped")));
        other.set(inputReport(0, 1, otherHalt));

        record.attachBackendCounters(() -> "Interface D", stream::get);
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("the reattach counted nothing").isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("Y's attachment that halted is newer than D's").containsSame(otherHalt);
    }

    // ── A close while a reading runs (story 337, ruling E98) ────────────

    /**
     * Story 337, rulings E94 and E98: an input closed while a reading reads
     * the counter sources is counted and named by that reading — which drops
     * the reports it had and reads the still-attached input once more — and
     * by no later one.
     */
    @Test
    void aSourceClosedWhileAReadingRunsIsCountedAndNamedByThatReading() {
        Object closed = new Object();
        ScriptedSource closedSource = new ScriptedSource();
        ScriptedSource open = new ScriptedSource();
        record.attachInputCounters(closed, () -> "Interface X", closedSource);
        record.attachInputCounters(new Object(), () -> "Interface Y", open);
        IllegalStateException secondHalt = new IllegalStateException("input from “Interface X” stopped again");
        closedSource.answer(inputReport(0, 1, new IllegalStateException("input from “Interface X” stopped")));
        int closedCalls = closedSource.calls();
        int openCalls = open.calls();
        AtomicInteger closedCallsBeforeTheClose = new AtomicInteger(-1);
        open.beforeNextAnswer(() -> {
            closedCallsBeforeTheClose.set(closedSource.calls() - closedCalls);
            closedSource.answer(inputReport(0, 2, secondHalt));
            record.detachInputCounters(closed);
        });

        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("both halts of the input closed during the reading").isEqualTo(2);
        assertThat(reading.inputHaltFault()).as("the closed input's own halt").containsSame(secondHalt);
        assertThat(closedCallsBeforeTheClose).as("the reading read X once before X closed").hasValue(1);
        assertThat(closedSource.calls() - closedCalls).as("X: once by the reading, once by its close").isEqualTo(2);
        assertThat(open.calls() - openCalls).as("Y is read once more after the close").isEqualTo(2);
        AudioHealthReading next = record.read(sample -> { });
        assertThat(next.inputHalts()).as("nothing new counted").isEqualTo(2);
        assertThat(next.inputHaltFault()).as("no input halted since the previous reading").isEmpty();
    }

    @Test
    void lostBlocksOfASourceClosedWhileAReadingRunsAreCountedByThatReadingAndByNoLaterOne() {
        Object closed = new Object();
        ScriptedSource closedSource = new ScriptedSource();
        ScriptedSource open = new ScriptedSource();
        record.attachInputCounters(closed, () -> "Interface X", closedSource);
        record.attachInputCounters(new Object(), () -> "Interface Y", open);
        IllegalStateException openLoss = new IllegalStateException("a block of Y was dropped");
        closedSource.answer(inputReport(1, 0, new IllegalStateException("a block of X was dropped")));
        int closedCalls = closedSource.calls();
        int openCalls = open.calls();
        open.beforeNextAnswer(() -> {
            closedSource.answer(inputReport(2, 0, new IllegalStateException("another block of X was dropped")));
            record.detachInputCounters(closed);
            open.answer(inputReport(1, 0, openLoss));
        });

        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.total(AudioFailureCategory.INPUT)).as("X's two and Y's one").isEqualTo(3);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT)).as("both kept a fault: Y was attached last")
                .containsSame(openLoss);
        assertThat(closedSource.calls() - closedCalls).as("X: once by the reading, once by its close").isEqualTo(2);
        assertThat(open.calls() - openCalls).as("Y is read once more after the close").isEqualTo(2);
        assertThat(record.read(sample -> { }).total(AudioFailureCategory.INPUT)).as("nothing new counted")
                .isEqualTo(3);
    }

    /**
     * Story 337, ruling E98: the fault a close keeps comes from the report
     * whose counts it folded — never from the report the guard kept for
     * another reader, which here had more lost blocks and no halt yet.
     */
    @Test
    void aClosedSourcesHaltIsNamedByTheReportItsCloseFoldedNotByAReportAnotherReaderKept() {
        ScriptedSource source = new ScriptedSource();
        record.attachBackendCounters(() -> "Interface A", source);
        IllegalStateException halt = new IllegalStateException("input from “Interface A” stopped delivering audio");
        source.beforeNextAnswer(() -> {
            source.answer(inputReport(5, 0, new IllegalStateException("a block of A was dropped")));
            assertThat(record.total(AudioFailureCategory.INPUT)).as("fixture: another reader, during the close")
                    .isEqualTo(5);
            source.answer(inputReport(3, 1, halt));
        });

        record.detachBackendCounters();
        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.inputHalts()).as("the close folded the report with the halt").isEqualTo(1);
        assertThat(reading.inputHaltFault()).as("the halt's own fault, from the report the close folded")
                .containsSame(halt);
    }

    // ── The backend ledger ──────────────────────────────────────────────

    /** Story 337, ruling E98: a close folds its source from one call of its report. */
    @Test
    void aCloseCallsTheClosedSourceOnce() {
        ScriptedSource stream = new ScriptedSource();
        ScriptedSource input = new ScriptedSource();
        Object device = new Object();
        record.attachBackendCounters(() -> "Interface A", stream);
        record.attachInputCounters(device, () -> "Interface B", input);
        int streamCalls = stream.calls();
        int inputCalls = input.calls();

        record.detachBackendCounters();
        record.detachInputCounters(device);

        assertThat(stream.calls() - streamCalls).as("the stream's close").isEqualTo(1);
        assertThat(input.calls() - inputCalls).as("the input device's close").isEqualTo(1);
    }

    /** Story 337, ruling E98: an attach that replaces a source folds it from one call of its report. */
    @Test
    void aReplacingAttachCallsTheReplacedSourceOnce() {
        ScriptedSource stream = new ScriptedSource();
        ScriptedSource input = new ScriptedSource();
        Object device = new Object();
        record.attachBackendCounters(() -> "Interface A", stream);
        record.attachInputCounters(device, () -> "Interface B", input);
        int streamCalls = stream.calls();
        int inputCalls = input.calls();
        ScriptedSource nextStream = new ScriptedSource();
        ScriptedSource nextInput = new ScriptedSource();

        record.attachBackendCounters(() -> "Interface C", nextStream);
        record.attachInputCounters(device, () -> "Interface B", nextInput);

        assertThat(stream.calls() - streamCalls).as("the replaced stream source").isEqualTo(1);
        assertThat(input.calls() - inputCalls).as("the replaced input source").isEqualTo(1);
        assertThat(nextStream.calls()).as("the new stream source's own baseline read").isEqualTo(1);
        assertThat(nextInput.calls()).as("the new input source's own baseline read").isEqualTo(1);
    }

    @Test
    void theBackendLedgerCountsOnlyWhatGrewSinceAttachAndFoldsAtDetach() {
        IllegalStateException callbackFault = new IllegalStateException("callback");
        AtomicReference<BackendThreadCounters> source = new AtomicReference<>(
                counters(3, 1, 0, 2, OptionalLong.of(500)));

        record.attachBackendCounters(() -> "Test backend", source::get);
        assertThat(record.total(AudioFailureCategory.CALLBACK))
                .as("the backend's history before attach is its baseline, not new faults")
                .isZero();

        source.set(new BackendThreadCounters(5, 4, 1, 3, OptionalLong.of(800),
                Optional.of(callbackFault), Optional.empty(), Optional.empty()));
        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(2);
        assertThat(record.total(AudioFailureCategory.INPUT)).as("lost blocks + halts").isEqualTo(4);
        assertThat(record.total(AudioFailureCategory.DEVICE)).isEqualTo(1);
        assertThat(record.inputHalts()).isEqualTo(1);
        assertThat(record.deviceCallbackPeriods()).hasValue(300);
        assertThat(record.lastFailure(AudioFailureCategory.CALLBACK)).containsSame(callbackFault);

        record.detachBackendCounters();
        source.set(counters(50, 50, 50, 50, OptionalLong.of(9_000)));

        assertThat(record.total(AudioFailureCategory.CALLBACK))
                .as("a detached source is folded and no longer read")
                .isEqualTo(2);
        assertThat(record.deviceCallbackPeriods()).as("the folded heartbeat with no stream").hasValue(300);
        assertThat(record.backendCounters().lastCallbackFault()).containsSame(callbackFault);
    }

    @Test
    void reattachingTheSameBackendNeverCountsItsHistoryTwice() {
        AtomicReference<BackendThreadCounters> source = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Test backend", source::get);
        source.set(counters(4, 0, 0, 0, OptionalLong.of(10)));
        record.detachBackendCounters();

        record.attachBackendCounters(() -> "Test backend", source::get);
        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(4);

        source.set(counters(6, 0, 0, 0, OptionalLong.of(25)));
        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(6);
        assertThat(record.deviceCallbackPeriods()).hasValue(25);
        assertThat(record.read(sample -> { }).streamOpens())
                .as("each attach of a stream's backend is one stream open (story 337, ruling E87)")
                .isEqualTo(2);
    }

    @Test
    void attachingAnotherSourceFoldsThePreviousOne() {
        AtomicReference<BackendThreadCounters> first = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "First backend", first::get);
        first.set(counters(2, 0, 0, 0, OptionalLong.empty()));

        record.attachBackendCounters(() -> "Second backend", () -> counters(0, 0, 0, 0, OptionalLong.empty()));
        first.set(counters(99, 0, 0, 0, OptionalLong.empty()));

        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(2);
    }

    @Test
    void aSourceThatGoesBackwardsNeverProducesANegativeCount() {
        AtomicReference<BackendThreadCounters> source = new AtomicReference<>(
                counters(10, 10, 10, 10, OptionalLong.of(10)));
        record.attachBackendCounters(() -> "Test backend", source::get);
        source.set(BackendThreadCounters.NONE);

        assertThat(record.totals()).containsOnly(0L);
        assertThat(record.deviceCallbackPeriods())
                .as("a live source with no device heartbeat reports none")
                .isEmpty();
    }

    @Test
    void aLiveSourceWithoutADeviceThreadReportsNoDeviceHeartbeat() {
        AtomicReference<BackendThreadCounters> asio = new AtomicReference<>(
                counters(0, 0, 0, 0, OptionalLong.of(0)));
        record.attachBackendCounters(() -> "ASIO", asio::get);
        asio.set(counters(0, 0, 0, 0, OptionalLong.of(40)));
        record.detachBackendCounters();

        record.attachBackendCounters(() -> "Java Sound", () -> BackendThreadCounters.NONE);

        assertThat(record.deviceCallbackPeriods())
                .as("the open stream's backend has no device thread: use the render-drive heartbeat")
                .isEmpty();
        record.detachBackendCounters();
        assertThat(record.deviceCallbackPeriods()).hasValue(40);
    }

    /**
     * Story 337, ruling E86: an input device opened beside the stream counts
     * as input — its callback faults and lost blocks are lost input blocks,
     * never {@code audio.callback}; its halt is an input halt; its last input
     * fault comes first; its device notifications stay {@code audio.device};
     * its heartbeat is not the stream's. It is folded once at its close and
     * is not a stream open.
     */
    @Test
    void anInputDevicesCallbackFaultsCountAsLostInputAndItsHeartbeatIsNotTheStreams() {
        Object input = new Object();
        IllegalStateException callbackFault = new IllegalStateException("input device callback");
        IllegalStateException inputFault = new IllegalStateException("lost input block");
        IllegalStateException deviceFault = new IllegalStateException("device notification");
        record.attachBackendCounters(() -> "Output backend", () -> counters(0, 0, 0, 0, OptionalLong.of(40)));
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(
                counters(1, 1, 0, 0, OptionalLong.of(1_000)));
        record.attachInputCounters(input, () -> "Second interface", second::get);
        second.set(new BackendThreadCounters(4, 3, 1, 2, OptionalLong.of(9_000),
                Optional.of(callbackFault), Optional.of(inputFault), Optional.of(deviceFault)));

        AudioHealthReading reading = record.read(sample -> { });

        assertThat(reading.total(AudioFailureCategory.CALLBACK))
                .as("an input device's callback faults are never audio.callback")
                .isZero();
        assertThat(reading.total(AudioFailureCategory.INPUT))
                .as("3 more callback faults and 2 more lost blocks are lost input, plus its one halt")
                .isEqualTo(6);
        assertThat(reading.inputHalts()).isEqualTo(1);
        assertThat(reading.total(AudioFailureCategory.DEVICE)).isEqualTo(2);
        assertThat(reading.lastFailure(AudioFailureCategory.INPUT))
                .as("its last input fault comes before its last callback fault")
                .containsSame(inputFault);
        assertThat(reading.lastFailure(AudioFailureCategory.CALLBACK)).isEmpty();
        assertThat(reading.streamOpens()).as("an input device is not a stream open").isEqualTo(1);
        assertThat(record.deviceCallbackPeriods())
                .as("the stream's heartbeat, never the input device's")
                .hasValue(0L);

        record.detachInputCounters(input);
        second.set(counters(99, 99, 99, 99, OptionalLong.of(99_999)));

        assertThat(record.total(AudioFailureCategory.INPUT)).as("folded at its close, no longer read")
                .isEqualTo(6);
        assertThat(record.deviceCallbackPeriods())
                .as("the stream's heartbeat, also once the input device is folded in")
                .hasValue(0L);
        assertThat(record.read(sample -> { }).backendCounters().deviceCallbackPeriods())
                .as("a reading's heartbeat is the stream source's alone")
                .isEqualTo(OptionalLong.of(0L));
        record.detachInputCounters(input);
        assertThat(record.total(AudioFailureCategory.INPUT)).as("a second detach folds nothing")
                .isEqualTo(6);
    }

    // ── A failing counter source (story 337, ruling E78) ────────────────

    /**
     * Story 337, ruling E78: a source whose report is {@code null}, or which
     * throws, from its attach on counts nothing; the attach returns, every
     * reader answers, the lanes keep reporting, and the device heartbeat is
     * empty.
     */
    @Test
    void aCounterSourceThatFailsAtAttachCountsNothingAndNeverThrowsIntoAReader() {
        assertFailingAttachCountsNothing("a null report", () -> null);
        assertFailingAttachCountsNothing("a throwing report", () -> {
            throw new IllegalArgumentException("no counters for this device");
        });
    }

    /**
     * Story 337, ruling E78: a source that starts failing after good reports
     * keeps every total it had given (no total ever drops) and loses its
     * heartbeat; folded at its detach, it is followed by a healthy source
     * that counts on from there.
     */
    @Test
    void aCounterSourceThatStartsFailingKeepsItsLastReportAndNeverThrowsIntoAReader() {
        IllegalStateException callbackFault = new IllegalStateException("callback");
        AtomicReference<BackendThreadCounters> report = new AtomicReference<>(
                counters(0, 0, 0, 0, OptionalLong.of(100)));
        AtomicBoolean failing = new AtomicBoolean();
        record.attachBackendCounters(() -> "Flaky backend", () -> {
            if (failing.get()) {
                throw new IllegalStateException("counters unavailable");
            }
            return report.get();
        });
        report.set(new BackendThreadCounters(3, 0, 0, 0, OptionalLong.of(160),
                Optional.of(callbackFault), Optional.empty(), Optional.empty()));
        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(3);
        assertThat(record.deviceCallbackPeriods()).hasValue(60L);

        failing.set(true);

        assertThat(record.total(AudioFailureCategory.CALLBACK)).as("no total ever drops").isEqualTo(3);
        assertThat(record.read(sample -> { }).total(AudioFailureCategory.CALLBACK)).isEqualTo(3);
        assertThat(record.lastFailure(AudioFailureCategory.CALLBACK)).containsSame(callbackFault);
        assertThat(record.inputHalts()).isZero();
        assertThat(record.backendCounters().callbackFaults()).isEqualTo(3);
        assertThat(record.deviceCallbackPeriods())
                .as("a source whose report failed has no heartbeat a watchdog could trust")
                .isEmpty();

        record.detachBackendCounters();
        AtomicReference<BackendThreadCounters> healthy = new AtomicReference<>(
                counters(10, 0, 0, 0, OptionalLong.of(5)));
        record.attachBackendCounters(() -> "Healthy backend", healthy::get);
        healthy.set(counters(12, 0, 0, 0, OptionalLong.of(9)));

        assertThat(record.total(AudioFailureCategory.CALLBACK))
                .as("the frozen report was folded at the detach, and the next source counts on")
                .isEqualTo(5);
    }

    /**
     * Story 337, rulings E78 and E96: one WARNING per attach, naming the
     * backend, saying what goes uncounted until it is closed and carrying
     * what the report threw — however often the source is read, because a
     * frozen source is never called again.
     */
    @Test
    void aFailingCounterSourceIsLoggedOnceAtWarningNamingTheBackend() {
        IllegalStateException failure = new IllegalStateException("counters unavailable");
        AtomicInteger calls = new AtomicInteger();
        try (CapturedRecordLog log = CapturedRecordLog.attach()) {
            record.attachBackendCounters(() -> "Flaky Interface", () -> {
                calls.incrementAndGet();
                throw failure;
            });
            for (int i = 0; i < 5; i++) {
                record.totals();
            }

            assertThat(calls).as("called once, by the attach's baseline read, and never again").hasValue(1);
            assertThat(log.records()).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
                assertThat(entry.getMessage()).isEqualTo("The audio backend “Flaky Interface” failed to report its"
                        + " failure counters; until it is closed, its device-callback faults, lost input blocks,"
                        + " input stops, device notifications and device heartbeat are not counted");
                assertThat(entry.getThrown()).isSameAs(failure);
            });
        }
    }

    /**
     * Story 337, ruling E96: an input device's WARNING lists only what an
     * input device contributes — no device-callback faults and no heartbeat
     * — and a {@code null} report says it reported none.
     */
    @Test
    void anInputDeviceWithoutAReportIsLoggedNamingOnlyWhatItCounts() {
        try (CapturedRecordLog log = CapturedRecordLog.attach()) {
            record.attachInputCounters(new Object(), () -> "Second Interface", () -> null);

            assertThat(log.records()).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
                assertThat(entry.getMessage()).isEqualTo("The input device “Second Interface” reported no failure"
                        + " counters; until it is closed, its lost input blocks, input stops and device"
                        + " notifications are not counted");
                assertThat(entry.getThrown()).isNull();
            });
        }
    }

    /**
     * Story 337, ruling E95: a report that throws an {@link Error} other than
     * a {@link VirtualMachineError} — here a class its counters need is
     * missing — is frozen like any other failure: every reader answers with
     * the last good report, a later attach folds it and returns, it is
     * logged once, and the source is never called again.
     */
    @Test
    void aCounterSourceThatThrowsAnErrorIsFrozenLikeAnyOtherFailure() {
        NoClassDefFoundError missing = new NoClassDefFoundError("com/example/driver/CounterPage");
        IllegalStateException callbackFault = new IllegalStateException("callback");
        AtomicReference<BackendThreadCounters> report = new AtomicReference<>(
                counters(0, 0, 0, 0, OptionalLong.of(100)));
        AtomicBoolean failing = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        try (CapturedRecordLog log = CapturedRecordLog.attach()) {
            record.attachBackendCounters(() -> "Asserting Backend", () -> {
                calls.incrementAndGet();
                if (failing.get()) {
                    throw missing;
                }
                return report.get();
            });
            report.set(new BackendThreadCounters(3, 0, 0, 0, OptionalLong.of(160),
                    Optional.of(callbackFault), Optional.empty(), Optional.empty()));
            assertThat(record.total(AudioFailureCategory.CALLBACK)).as("fixture: a good report").isEqualTo(3);
            failing.set(true);
            int callsBeforeItFailed = calls.get();

            assertThatCode(() -> {
                record.totals();
                record.lastFailure(AudioFailureCategory.CALLBACK);
                record.inputHalts();
                record.backendCounters();
                record.deviceCallbackPeriods();
                record.read(sample -> { });
            }).as("every reader answers").doesNotThrowAnyException();
            assertThat(record.total(AudioFailureCategory.CALLBACK)).as("no total ever drops").isEqualTo(3);
            assertThat(record.deviceCallbackPeriods()).as("a frozen source has no heartbeat").isEmpty();
            assertThatCode(() -> record.attachBackendCounters(() -> "Next Backend", () -> BackendThreadCounters.NONE))
                    .as("a later attach folds the frozen source and returns")
                    .doesNotThrowAnyException();

            assertThat(calls.get() - callsBeforeItFailed).as("called once when it failed, never again").isEqualTo(1);
            assertThat(record.total(AudioFailureCategory.CALLBACK)).as("its frozen report was folded").isEqualTo(3);
            assertThat(log.records()).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
                assertThat(entry.getMessage()).startsWith("The audio backend “Asserting Backend” failed to report");
                assertThat(entry.getThrown()).isSameAs(missing);
            });
        }
    }

    /**
     * Story 337, ruling E95: a {@link VirtualMachineError} from a report
     * freezes the source before it propagates, so it costs the one reading
     * that met it: the next reading answers the frozen report without calling
     * the source, and a detach and a new attach return. It writes no log
     * record.
     */
    @Test
    void aVirtualMachineErrorFreezesTheSourceBeforeItIsRethrown() {
        InternalError fatal = new InternalError("the driver's counter page is gone");
        AtomicReference<BackendThreadCounters> report = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicBoolean failing = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        try (CapturedRecordLog log = CapturedRecordLog.attach()) {
            record.attachBackendCounters(() -> "Fatal Backend", () -> {
                calls.incrementAndGet();
                if (failing.get()) {
                    throw fatal;
                }
                return report.get();
            });
            report.set(counters(2, 0, 0, 0, OptionalLong.empty()));
            assertThat(record.total(AudioFailureCategory.CALLBACK)).as("fixture: a good report").isEqualTo(2);
            failing.set(true);
            int callsBeforeItFailed = calls.get();

            assertThatThrownBy(() -> record.read(sample -> { })).as("the reading that met it rethrows it")
                    .isSameAs(fatal);
            AtomicReference<AudioHealthReading> next = new AtomicReference<>();
            assertThatCode(() -> next.set(record.read(sample -> { })))
                    .as("the next reading answers the frozen report")
                    .doesNotThrowAnyException();
            assertThat(next.get().total(AudioFailureCategory.CALLBACK)).isEqualTo(2);
            assertThatCode(record::detachBackendCounters).as("its detach returns").doesNotThrowAnyException();
            assertThatCode(() -> record.attachBackendCounters(() -> "Next Backend", () -> BackendThreadCounters.NONE))
                    .as("a new attach returns").doesNotThrowAnyException();

            assertThat(calls.get() - callsBeforeItFailed).as("called once, frozen before the rethrow").isEqualTo(1);
            assertThat(record.total(AudioFailureCategory.CALLBACK)).as("its frozen report was folded").isEqualTo(2);
            assertThat(log.records()).as("a VirtualMachineError writes no log record").isEmpty();
        }
    }

    /**
     * Story 337, rulings E95 and E96: a backend whose report fails and whose
     * name cannot be read either — a class the name needs is missing — is
     * frozen and logged once with the fallback subject, and the attach that
     * met it returns.
     */
    @Test
    void aFailingBackendWhoseNameCannotBeReadIsLoggedOnceWithTheFallbackSubject() {
        NoClassDefFoundError unreadable = new NoClassDefFoundError("com/example/driver/DeviceName");
        IllegalStateException failure = new IllegalStateException("counters unavailable");
        AtomicInteger calls = new AtomicInteger();
        try (CapturedRecordLog log = CapturedRecordLog.attach()) {
            assertThatCode(() -> record.attachBackendCounters(() -> {
                throw unreadable;
            }, () -> {
                calls.incrementAndGet();
                throw failure;
            })).as("the attach that met it returns").doesNotThrowAnyException();
            record.totals();

            assertThat(calls).as("frozen at the attach, never called again").hasValue(1);
            assertThat(log.records()).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
                assertThat(entry.getMessage()).isEqualTo("An audio backend failed to report its failure counters;"
                        + " until it is closed, its device-callback faults, lost input blocks, input stops, device"
                        + " notifications and device heartbeat are not counted");
                assertThat(entry.getThrown()).isSameAs(failure);
            });
        }
    }

    /**
     * Story 337, rulings E95 and E96: an input device whose report starts
     * failing and whose name cannot be read is frozen and logged once with
     * the fallback subject, and the reading that met it returns.
     */
    @Test
    void aFailingInputDeviceWhoseNameCannotBeReadIsLoggedOnceWithTheFallbackSubject() {
        NoClassDefFoundError unreadable = new NoClassDefFoundError("com/example/driver/DeviceName");
        IllegalStateException failure = new IllegalStateException("counters unavailable");
        AtomicBoolean failing = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        try (CapturedRecordLog log = CapturedRecordLog.attach()) {
            record.attachInputCounters(new Object(), () -> {
                throw unreadable;
            }, () -> {
                calls.incrementAndGet();
                if (failing.get()) {
                    throw failure;
                }
                return BackendThreadCounters.NONE;
            });
            failing.set(true);
            int callsBeforeItFailed = calls.get();

            assertThatCode(() -> record.read(sample -> { })).as("the reading that met it returns")
                    .doesNotThrowAnyException();
            record.read(sample -> { });

            assertThat(calls.get() - callsBeforeItFailed).as("called once when it failed, never again").isEqualTo(1);
            assertThat(log.records()).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
                assertThat(entry.getMessage()).isEqualTo("An input device failed to report its failure counters;"
                        + " until it is closed, its lost input blocks, input stops and device notifications are"
                        + " not counted");
                assertThat(entry.getThrown()).isSameAs(failure);
            });
        }
    }

    @Test
    void anEmptyRecordReportsNothing() {
        assertThat(record.totals()).hasSize(AudioFailureCodes.COUNT).containsOnly(0L);
        assertThat(record.deviceCallbackPeriods()).isEmpty();
        assertThat(record.renderDriveBeats()).isZero();
        assertThat(record.renderDriveEpoch()).isZero();
        assertThat(record.lostSamples()).isZero();
        assertThat(record.backendCounters()).isEqualTo(BackendThreadCounters.NONE);
        List<AudioFailureSample> samples = new ArrayList<>();
        AudioHealthReading reading = record.read(samples::add);
        assertThat(samples).isEmpty();
        for (AudioFailureCategory category : AudioFailureCategory.values()) {
            assertThat(reading.total(category)).isZero();
            assertThat(reading.lastFailure(category)).isEmpty();
        }
        assertThat(reading.backendCounters()).isEqualTo(BackendThreadCounters.NONE);
        assertThat(reading.renderDriveBeats()).isZero();
        assertThat(reading.renderDriveEpoch()).isZero();
        assertThat(reading.streamOpens()).isZero();
        assertThat(reading.lostSamples()).isZero();
    }

    // ── Support ─────────────────────────────────────────────────────────

    private void assertFailingAttachCountsNothing(String how, Supplier<BackendThreadCounters> failing) {
        AudioHealthRecord fresh = new AudioHealthRecord();
        assertThatCode(() -> fresh.attachBackendCounters(() -> "Broken backend", failing))
                .as("%s: the attach returns", how)
                .doesNotThrowAnyException();
        AudioHealthLane lane = fresh.claimLane(LaneRole.RENDER_DRIVE);
        lane.record(AudioFailureCodes.RENDER, new IllegalStateException("render"), null, 0);

        assertThat(fresh.total(AudioFailureCategory.CALLBACK)).as(how).isZero();
        assertThat(fresh.total(AudioFailureCategory.INPUT)).as(how).isZero();
        assertThat(fresh.total(AudioFailureCategory.DEVICE)).as(how).isZero();
        assertThat(fresh.lastFailure(AudioFailureCategory.CALLBACK)).as(how).isEmpty();
        assertThat(fresh.lastFailure(AudioFailureCategory.INPUT)).as(how).isEmpty();
        assertThat(fresh.inputHalts()).as(how).isZero();
        assertThat(fresh.backendCounters()).as(how).isEqualTo(BackendThreadCounters.NONE);
        assertThat(fresh.deviceCallbackPeriods()).as(how).isEmpty();
        List<AudioFailureSample> samples = new ArrayList<>();
        AudioHealthReading reading = fresh.read(samples::add);
        assertThat(reading.total(AudioFailureCategory.RENDER)).as("%s: the lanes keep reporting", how)
                .isEqualTo(1);
        assertThat(samples).as(how).hasSize(1);
        fresh.detachBackendCounters();
        assertThat(fresh.backendCounters()).as("%s: the detach folds nothing", how)
                .isEqualTo(BackendThreadCounters.NONE);
    }

    private List<AudioFailureSample> read() {
        List<AudioFailureSample> samples = new ArrayList<>();
        record.read(samples::add);
        return samples;
    }

    /**
     * Attaches the stream's backend, then an input device beside it that loses a block one reading names;
     * then the stream's backend halts. The halt is not read yet.
     *
     * @return the halt's fault
     */
    private IllegalStateException haltOfTheStreamAfterASecondInputLostABlock() {
        AtomicReference<BackendThreadCounters> stream = new AtomicReference<>(BackendThreadCounters.NONE);
        AtomicReference<BackendThreadCounters> second = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(() -> "Interface A", stream::get);
        record.attachInputCounters(new Object(), () -> "Interface B", second::get);
        IllegalStateException lostBlock = new IllegalStateException("a block of B was dropped");
        second.set(inputReport(1, 0, lostBlock));
        assertThat(record.read(sample -> { }).lastFailure(AudioFailureCategory.INPUT))
                .as("fixture: B's lost block is named by B").containsSame(lostBlock);
        IllegalStateException halt = new IllegalStateException("input from “Interface A” stopped delivering audio");
        stream.set(inputReport(0, 1, halt));
        return halt;
    }

    /** A report of lost input blocks and input halts behind {@code inputFault}, which may be {@code null}. */
    private static BackendThreadCounters inputReport(long lost, long halts, Throwable inputFault) {
        return new BackendThreadCounters(0, lost, halts, 0, OptionalLong.empty(), Optional.empty(),
                Optional.ofNullable(inputFault), Optional.empty());
    }

    /** A report that grows by one of everything on each call, its faults naming the call. */
    private static BackendThreadCounters changingReport(String prefix, int call) {
        return new BackendThreadCounters(call, call, call, call, OptionalLong.of(call),
                Optional.of(new IllegalStateException(prefix + "callback " + call)),
                Optional.of(new IllegalStateException(prefix + "input " + call)),
                Optional.of(new IllegalStateException(prefix + "device " + call)));
    }

    /** Which of two attached inputs are closed before a reading, and in which order. */
    private enum Closing {
        NEITHER, NEWER, OLDER, OLDER_THEN_NEWER, NEWER_THEN_OLDER;

        void close(AudioHealthRecord record, Object older, Object newer) {
            switch (this) {
                case NEITHER -> { }
                case NEWER -> record.detachInputCounters(newer);
                case OLDER -> record.detachInputCounters(older);
                case OLDER_THEN_NEWER -> {
                    record.detachInputCounters(older);
                    record.detachInputCounters(newer);
                }
                case NEWER_THEN_OLDER -> {
                    record.detachInputCounters(newer);
                    record.detachInputCounters(older);
                }
            }
        }
    }

    /** A counter source that counts its calls and can run a hook, once, on the caller's thread before it answers. */
    private static final class ScriptedSource implements Supplier<BackendThreadCounters> {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Runnable> beforeNextAnswer = new AtomicReference<>();
        private volatile BackendThreadCounters report = BackendThreadCounters.NONE;

        void answer(BackendThreadCounters next) {
            report = next;
        }

        void beforeNextAnswer(Runnable hook) {
            beforeNextAnswer.set(hook);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public BackendThreadCounters get() {
            calls.incrementAndGet();
            Runnable hook = beforeNextAnswer.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return report;
        }
    }

    private LaneSet lanes() throws ReflectiveOperationException {
        Field field = AudioHealthRecord.class.getDeclaredField("lanes");
        field.setAccessible(true);
        return (LaneSet) field.get(record);
    }

    private static RecordSlot[] slotsOf(AudioHealthLane lane) throws ReflectiveOperationException {
        Field field = AudioHealthLane.class.getDeclaredField("slots");
        field.setAccessible(true);
        return (RecordSlot[]) field.get(lane);
    }

    private static BackendThreadCounters counters(long callback, long lost, long halts, long device,
                                                  OptionalLong periods) {
        return new BackendThreadCounters(callback, lost, halts, device, periods,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void runOnRenderThread(AudioHealthLane lane, Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RenderThread thread = new RenderThread(lane, () -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "render-thread-under-test");
        thread.start();
        thread.join(GUARD_BUDGET_MILLIS);
        assertThat(thread.isAlive()).as("the render thread finished within the guard budget").isFalse();
        assertThat(failure.get()).isNull();
    }

    /** Captures the record's logger for one test. */
    private static final class CapturedRecordLog extends Handler implements AutoCloseable {

        private static final Logger RECORD_LOG = Logger.getLogger(AudioHealthRecord.class.getName());

        private final List<LogRecord> entries = new CopyOnWriteArrayList<>();

        static CapturedRecordLog attach() {
            CapturedRecordLog log = new CapturedRecordLog();
            RECORD_LOG.addHandler(log);
            return log;
        }

        List<LogRecord> records() {
            return List.copyOf(entries);
        }

        @Override
        public void publish(LogRecord entry) {
            entries.add(entry);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            RECORD_LOG.removeHandler(this);
        }
    }
}
