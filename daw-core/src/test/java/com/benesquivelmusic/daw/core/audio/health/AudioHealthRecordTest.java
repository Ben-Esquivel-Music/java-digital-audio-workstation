package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit contract of the per-engine {@link AudioHealthRecord} (story 337,
 * rulings E4, E5, E18, E20 and E21): lanes retire into monotonic totals, a
 * render thread records only into its own record, the backend ledger counts
 * only what grew since attach and folds at detach, and the two heartbeats
 * read as documented.
 */
class AudioHealthRecordTest {

    /** Guard budget for a render thread's join — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private final AudioHealthRecord record = new AudioHealthRecord();

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
        List<AudioFailureSample> samples = new ArrayList<>();
        record.drainSamples(samples::add);
        assertThat(samples).as("a retired lane is no longer drained").isEmpty();
    }

    @Test
    void retiringIsIdempotentAndClearsTheRingsReferences() throws Exception {
        AudioHealthLane lane = record.claimLane(LaneRole.WORKER);
        lane.record(AudioFailureCodes.WORKER, new IllegalStateException("task"), "subject", 0);

        record.retireLane(lane);
        record.retireLane(lane);

        assertThat(record.total(AudioFailureCategory.WORKER)).as("folded exactly once").isEqualTo(1);
        java.lang.reflect.Field field = AudioHealthLane.class.getDeclaredField("slots");
        field.setAccessible(true);
        for (RecordSlot slot : (RecordSlot[]) field.get(lane)) {
            assertThat(slot.failure).as("no retired slot keeps a throwable alive").isNull();
            assertThat(slot.subject).as("no retired slot keeps a subject alive").isNull();
        }
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

    @Test
    void theBackendLedgerCountsOnlyWhatGrewSinceAttachAndFoldsAtDetach() {
        IllegalStateException callbackFault = new IllegalStateException("callback");
        AtomicReference<BackendThreadCounters> source = new AtomicReference<>(
                counters(3, 1, 0, 2, OptionalLong.of(500)));

        record.attachBackendCounters(source::get);
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
        record.attachBackendCounters(source::get);
        source.set(counters(4, 0, 0, 0, OptionalLong.of(10)));
        record.detachBackendCounters();

        record.attachBackendCounters(source::get);
        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(4);

        source.set(counters(6, 0, 0, 0, OptionalLong.of(25)));
        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(6);
        assertThat(record.deviceCallbackPeriods()).hasValue(25);
    }

    @Test
    void attachingAnotherSourceFoldsThePreviousOne() {
        AtomicReference<BackendThreadCounters> first = new AtomicReference<>(BackendThreadCounters.NONE);
        record.attachBackendCounters(first::get);
        first.set(counters(2, 0, 0, 0, OptionalLong.empty()));

        record.attachBackendCounters(() -> counters(0, 0, 0, 0, OptionalLong.empty()));
        first.set(counters(99, 0, 0, 0, OptionalLong.empty()));

        assertThat(record.total(AudioFailureCategory.CALLBACK)).isEqualTo(2);
    }

    @Test
    void aSourceThatGoesBackwardsNeverProducesANegativeCount() {
        AtomicReference<BackendThreadCounters> source = new AtomicReference<>(
                counters(10, 10, 10, 10, OptionalLong.of(10)));
        record.attachBackendCounters(source::get);
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
        record.attachBackendCounters(asio::get);
        asio.set(counters(0, 0, 0, 0, OptionalLong.of(40)));
        record.detachBackendCounters();

        record.attachBackendCounters(() -> BackendThreadCounters.NONE);

        assertThat(record.deviceCallbackPeriods())
                .as("the open stream's backend has no device thread: use the render-drive heartbeat")
                .isEmpty();
        record.detachBackendCounters();
        assertThat(record.deviceCallbackPeriods()).hasValue(40);
    }

    @Test
    void anEmptyRecordReportsNothing() {
        assertThat(record.totals()).hasSize(AudioFailureCodes.COUNT).containsOnly(0L);
        assertThat(record.deviceCallbackPeriods()).isEmpty();
        assertThat(record.renderDriveBeats()).isZero();
        assertThat(record.renderDriveEpoch()).isZero();
        assertThat(record.lostSamples()).isZero();
        assertThat(record.backendCounters()).isEqualTo(BackendThreadCounters.NONE);
        assertThat(record.drainSamples(sample -> { })).isZero();
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
}
