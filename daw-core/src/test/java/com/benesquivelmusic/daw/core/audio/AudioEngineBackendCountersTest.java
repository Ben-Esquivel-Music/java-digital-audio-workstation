package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.BackendDropoutCounters;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.DriverResyncArrivals;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The backend ledger through the real wiring (story 337, rulings E4 and
 * E78): the engine attaches the open backend's {@code threadCounters()} to
 * its health record when a stream opens and folds them when it closes, so a
 * backend's counts reach the record live during a stream, survive the close,
 * and are never counted twice when the same backend instance is reopened. A
 * backend whose report cannot be read — it is {@code null}, or throws
 * anything but a {@link VirtualMachineError} — still opens and closes as
 * chosen and never fails a later open; a {@link VirtualMachineError} from its
 * report unwinds an open like any other start failure, and at a close costs
 * only that close, which still forgets the stream (story 337, ruling E95).
 */
class AudioEngineBackendCountersTest {

    private static final Logger RECORD_LOG = Logger.getLogger(AudioHealthRecord.class.getName());

    private AudioEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void theOpenBackendsCountersReachTheRecordLiveAndSurviveTheClose() {
        MockAudioBackend backend = new MockAudioBackend();
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        IllegalStateException fault = new IllegalStateException("callback fault");

        backend.setThreadCounters(new BackendThreadCounters(3, 2, 0, 1, OptionalLong.of(120),
                Optional.of(fault), Optional.empty(), Optional.empty()));

        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isEqualTo(3);
        assertThat(engine.audioHealth().total(AudioFailureCategory.INPUT)).isEqualTo(2);
        assertThat(engine.audioHealth().total(AudioFailureCategory.DEVICE)).isEqualTo(1);
        assertThat(engine.audioHealth().deviceCallbackPeriods()).hasValue(120);
        assertThat(engine.audioHealth().lastFailure(AudioFailureCategory.CALLBACK)).containsSame(fault);

        engine.stopAudioOutput();

        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK))
                .as("the close folds the backend's counts into the record")
                .isEqualTo(3);
        backend.setThreadCounters(new BackendThreadCounters(40, 2, 0, 1, OptionalLong.of(900),
                Optional.of(fault), Optional.empty(), Optional.empty()));
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK))
                .as("a closed stream's backend is no longer read")
                .isEqualTo(3);
    }

    @Test
    void reopeningTheSameBackendNeverCountsItsHistoryTwice() {
        MockAudioBackend backend = new MockAudioBackend();
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.setThreadCounters(counters(4));
        engine.stopAudioOutput();

        engine.startAudioOutput();
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK))
                .as("the reopened backend's history is the new baseline")
                .isEqualTo(4);

        backend.setThreadCounters(counters(7));
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isEqualTo(7);
    }

    @Test
    void countsABackendReportedBeforeTheStreamOpenedAreItsHistoryNotNewFaults() {
        MockAudioBackend backend = new MockAudioBackend();
        backend.setThreadCounters(counters(9));
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        engine.startAudioOutput();

        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isZero();
    }

    /**
     * The engine reads the open stream's backend for its observed drops, and
     * answers none while no stream is open or when the backend's report
     * throws: a reader on any thread never sees the backend's failure.
     */
    @Test
    void dropoutCountersAreTheOpenStreamsBackendsAndNoneWithoutAStream() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Counting");
        BackendDropoutCounters reported = new BackendDropoutCounters(5, 4, 3, 2, 1, true);
        backend.dropoutReport = () -> reported;
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        assertThat(engine.dropoutCounters()).as("no stream is open yet").isEqualTo(BackendDropoutCounters.NONE);

        engine.startAudioOutput();
        assertThat(engine.dropoutCounters()).isEqualTo(reported);

        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke");
        };
        assertThat(engine.dropoutCounters())
                .as("a throwing report reads as none, never as a throw")
                .isEqualTo(BackendDropoutCounters.NONE);

        backend.dropoutReport = () -> reported;
        assertThat(engine.stopAudioOutput()).isTrue();
        assertThat(engine.dropoutCounters()).as("the stream closed").isEqualTo(BackendDropoutCounters.NONE);
    }

    /**
     * Story 338, rulings E270 and E279: the engine takes the resync arrivals up from the open stream's backend, and
     * answers none while no stream is open or when the backend's answer is null or throws.
     */
    @Test
    void resyncArrivalsAreTakenFromTheOpenStreamsBackendAndNoneWithoutAStream() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Resyncing");
        DriverResyncArrivals taken = new DriverResyncArrivals(3L, List.of(new DriverResyncArrivals.Run(42L, 2L)));
        backend.resyncArrivalsReport = () -> taken;
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        assertThat(engine.takeDriverResyncArrivals()).as("no stream is open yet").isEqualTo(DriverResyncArrivals.NONE);

        engine.startAudioOutput();
        assertThat(engine.takeDriverResyncArrivals()).isEqualTo(taken);

        backend.resyncArrivalsReport = () -> null;
        assertThat(engine.takeDriverResyncArrivals()).as("a null answer reads as none")
                .isEqualTo(DriverResyncArrivals.NONE);
        backend.resyncArrivalsReport = () -> {
            throw new IllegalStateException("the arrivals broke");
        };
        assertThat(engine.takeDriverResyncArrivals()).as("a throwing answer reads as none, never as a throw")
                .isEqualTo(DriverResyncArrivals.NONE);

        backend.resyncArrivalsReport = () -> taken;
        assertThat(engine.stopAudioOutput()).isTrue();
        assertThat(engine.takeDriverResyncArrivals()).as("the stream closed").isEqualTo(DriverResyncArrivals.NONE);
    }

    // ── A backend whose own report cannot be read (story 337, ruling E78) ──

    @Test
    void aBackendWhoseCounterReportFailsStillOpensItsStreamAndCountsNothingOfItsOwn() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Unreadable");
        backend.counterReport = () -> null;
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).as("a null report fails no open").isTrue();
        assertThat(engine.openStreamBackendName()).contains("Unreadable");
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isZero();
        assertThat(engine.audioHealth().deviceCallbackPeriods()).isEmpty();
        engine.audioHealth().read(sample -> { });
        assertThat(engine.stopAudioOutput()).isTrue();
        assertThat(backend.closeCount.get()).as("the stop closed the handle once").isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();

        backend.counterReport = () -> {
            throw new IllegalStateException("the counter report broke");
        };
        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).as("a throwing report fails no open").isTrue();
        assertThat(engine.audioHealth().total(AudioFailureCategory.CALLBACK)).isZero();
        assertThat(engine.audioHealth().deviceCallbackPeriods()).isEmpty();
        engine.audioHealth().read(sample -> { });
        assertThat(engine.stopAudioOutput()).isTrue();
        assertThat(backend.closeCount.get()).as("each stop closed the handle once").isEqualTo(2);
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void anErrorFromTheCounterReportAtOpenClosesTheOpenedHandleAndRollsTheEngineBack() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing Report");
        CounterReportError error = new CounterReportError();
        backend.counterReport = () -> {
            throw error;
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        Throwable thrown = catchThrowable(engine::startAudioOutput);

        assertThat(thrown).as("the Error fails the start").isSameAs(error);
        assertThat(backend.closeCount.get()).as("the opened handle was closed").isEqualTo(1);
        assertThat(backend.isOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.openStreamBackendName()).isEmpty();
        assertThat(engine.isRunning()).as("the engine this start began is stopped again").isFalse();

        backend.counterReport = null;
        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).as("a next start with a healthy report opens").isTrue();
        assertThat(backend.openCount.get()).isEqualTo(2);
    }

    @Test
    void aRetainedFailedHopStaysTrackedWhenItsCounterReportThrowsAnError() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Held Hop");
        backend.openFailure = new AudioBackendException("open refused after taking the handle");
        backend.failOnClose = true;
        CounterReportError error = new CounterReportError();
        backend.counterReport = () -> {
            throw error;
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));

        Throwable thrown = catchThrowable(engine::startAudioOutput);

        assertThat(thrown).isSameAs(error);
        assertThat(backend.closeCount.get()).as("the hop's close was tried and failed").isEqualTo(1);
        backend.openFailure = null;
        backend.failOnClose = false;
        backend.counterReport = null;

        engine.startAudioOutput();

        assertThat(backend.events.subList(0, 4))
                .as("the engine still tracked the hop's handle, so the next start released it"
                        + " before it opened again")
                .containsExactly("open", "close", "close", "open");
        assertThat(backend.closeCount.get()).isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void aCloseWhoseCounterFoldFailsIsStillAClose() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing After Attach");
        AtomicBoolean failing = new AtomicBoolean();
        backend.counterReport = () -> {
            if (failing.get()) {
                throw new IllegalStateException("the counter report broke after the open");
            }
            return BackendThreadCounters.NONE;
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        failing.set(true);

        assertThat(engine.stopAudioOutput()).isTrue();

        assertThat(backend.closeCount.get()).isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();
        // The record path's close-first step refuses unless the stop left the
        // stream CLOSED: a fold read as a failed close would have left it
        // RELEASE_PENDING with nothing tracked.
        engine.startAudioInputOutput();
        assertThat(engine.isStreamOpen()).as("the stream was forgotten, so the next start opens").isTrue();
        assertThat(backend.closeCount.get()).as("no retained handle was released again").isEqualTo(1);
        assertThat(backend.openCount.get()).isEqualTo(2);
    }

    @Test
    void aCounterReportThatThrowsAnErrorOtherThanAVirtualMachineErrorStillOpensTheStream() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Asserting Report");
        AssertionError assertion = new AssertionError("the counter report's own check failed");
        backend.counterReport = () -> {
            throw assertion;
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = capturing(records);
        RECORD_LOG.addHandler(capture);
        try {
            assertThatCode(engine::startAudioOutput).as("the start is not failed by the report's Error")
                    .doesNotThrowAnyException();
        } finally {
            RECORD_LOG.removeHandler(capture);
        }

        assertThat(engine.isStreamOpen()).as("a diagnostic report never refuses the chosen interface").isTrue();
        assertThat(engine.openStreamBackendName()).contains("Asserting Report");
        assertThat(records).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
            assertThat(entry.getThrown()).isSameAs(assertion);
        });
    }

    @Test
    void aCounterReportThatStartsThrowingAfterAGoodOpenNeverFailsAStopOrTheNextOpen() {
        AudioEngineOutputTest.SynchronousTestBackend first =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing After Open");
        NoClassDefFoundError missing = new NoClassDefFoundError("com/example/driver/CounterPage");
        AtomicBoolean failing = new AtomicBoolean();
        first.counterReport = () -> {
            if (failing.get()) {
                throw missing;
            }
            return BackendThreadCounters.NONE;
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(first));
        engine.startAudioOutput();
        failing.set(true);

        assertThatCode(() -> engine.audioHealth().read(sample -> { })).as("a reading answers")
                .doesNotThrowAnyException();
        AtomicBoolean stopped = new AtomicBoolean();
        assertThatCode(() -> stopped.set(engine.stopAudioOutput())).as("the stop returns")
                .doesNotThrowAnyException();
        assertThat(stopped).isTrue();
        assertThat(first.closeCount.get()).isEqualTo(1);

        AudioEngineOutputTest.SynchronousTestBackend second =
                new AudioEngineOutputTest.SynchronousTestBackend("Healthy");
        engine.stop();
        engine.setStreamingProvision(provision(second));
        assertThatCode(engine::startAudioOutput).as("an open on another backend is never refused for it")
                .doesNotThrowAnyException();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.openStreamBackendName()).contains("Healthy");
    }

    @Test
    void aVirtualMachineErrorFromTheCounterReportAtTheCloseStillForgetsTheStreamAndTheNextStartOpens() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Fatal At Close");
        CounterReportError error = new CounterReportError();
        AtomicBoolean failOnce = new AtomicBoolean();
        backend.counterReport = () -> {
            if (failOnce.getAndSet(false)) {
                throw error;
            }
            return BackendThreadCounters.NONE;
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        AudioFormat format = engine.getFormat();
        CountInSchedule countIn = new CountInSchedule(10_000, 4, 30.0, format.sampleRate(), format.bufferSize(), 0);
        engine.scheduleCountIn(countIn);
        failOnce.set(true);

        Throwable thrown = catchThrowable(engine::stopAudioOutput);

        assertThat(thrown).as("the close's fold met it, and its caller gets it").isSameAs(error);
        assertThat(backend.closeCount.get()).as("the stream was closed").isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.pendingCountIn())
                .as("the stream was forgotten before the fold: its pending count-in was cancelled")
                .isEmpty();
        assertThat(countIn.isPending()).isFalse();
        assertThatCode(engine::startAudioOutput).as("the next start opens: the frozen source costs it nothing")
                .doesNotThrowAnyException();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(backend.openCount.get()).isEqualTo(2);
    }

    // ── The take's count of input lost before the rings (story 338, ruling E214) ──

    @Test
    void aDropoutReportThatFailsMidStreamKeepsTheCountItHadRead() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing Drop Report");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();

        backend.dropoutReport = () -> inputDrops(3);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(3);
        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke");
        };
        assertThat(engine.capturedInputBlocksLost()).as("a throwing report never lowers the count").isEqualTo(3);
        backend.dropoutReport = () -> null;
        assertThat(engine.capturedInputBlocksLost()).as("an empty report never lowers it either").isEqualTo(3);
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(5);
    }

    @Test
    void aDropoutReportThatFailsAtTheCloseFoldsTheCountItHadRead() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing At Close");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.dropoutReport = () -> inputDrops(4);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(4);
        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke at the close");
        };

        assertThat(engine.stopAudioOutput()).isTrue();

        assertThat(engine.capturedInputBlocksLost()).as("the close folded the count it had read").isEqualTo(4);
    }

    @Test
    void aDropoutReportThatFailsAtTheOpenCountsFromItsFirstGoodRead() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing At Open");
        AtomicInteger calls = new AtomicInteger();
        AtomicLong dropped = new AtomicLong(7);
        backend.dropoutReport = () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("the drop report broke at the open");
            }
            return inputDrops(dropped.get());
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();

        assertThat(engine.capturedInputBlocksLost()).as("the blocks lost before the open are history").isZero();
        dropped.set(9);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(2);
    }

    /**
     * After a failed attach read, the baseline is the first good read to
     * land: a read made earlier but landing later with a lower count must not
     * pull the baseline down, or losses from before a figure already handed
     * out would join it. The third call stands in for that earlier read.
     */
    @Test
    void aLateEarlierReadAfterAFailedAttachNeverRaisesTheFigure() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Late Read");
        AtomicInteger calls = new AtomicInteger();
        AtomicLong later = new AtomicLong(9);
        backend.dropoutReport = () -> switch (calls.incrementAndGet()) {
            case 1 -> throw new IllegalStateException("the drop report broke at the open");
            case 2 -> inputDrops(9);
            case 3 -> inputDrops(7);
            default -> inputDrops(later.get());
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();

        assertThat(engine.capturedInputBlocksLost()).as("the first good read is the baseline").isZero();
        assertThat(engine.capturedInputBlocksLost()).as("a lower read landing later moves nothing").isZero();
        assertThat(engine.capturedInputBlocksLost()).isZero();
        later.set(11);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(2);
    }

    @Test
    void aVirtualMachineErrorFromTheDropoutReportAtTheCloseCostsThatStopAloneAndTheReopenCountsAgain() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Fatal Drop Report");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.dropoutReport = () -> inputDrops(2);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(2);
        CounterReportError error = new CounterReportError();
        backend.dropoutReport = () -> {
            throw error;
        };

        assertThat(catchThrowable(engine::stopAudioOutput)).as("the stop that met it gets it").isSameAs(error);
        assertThat(engine.capturedInputBlocksLost()).as("a later read answers the count it had").isEqualTo(2);

        backend.dropoutReport = () -> inputDrops(2);
        engine.startAudioOutput();
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost()).as("the reopen counts from its own open").isEqualTo(5);
    }

    /**
     * A backend opened again while its source is still attached (a close whose
     * health-record fold met a fatal error never reached the drop count's
     * detach) is folded first and counted on from the highest count already
     * read, even when the re-attach read itself is older and lower.
     */
    @Test
    void aBackendReopenedWhileStillAttachedNeverCountsTheSameBlocksTwice() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Reopened While Attached");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(5);
        CounterReportError error = new CounterReportError();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        backend.counterReport = () -> {
            if (failOnce.getAndSet(false)) {
                throw error;
            }
            return BackendThreadCounters.NONE;
        };

        assertThat(catchThrowable(engine::stopAudioOutput)).isSameAs(error);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(5);

        AtomicInteger calls = new AtomicInteger();
        backend.dropoutReport = () -> calls.incrementAndGet() == 1 ? inputDrops(3) : inputDrops(6);
        engine.startAudioOutput();

        assertThat(engine.capturedInputBlocksLost()).as("five folded, one since").isEqualTo(6);
    }

    /**
     * A report that fails at the read a take baselines on leaves the blocks
     * lost before it unknown: the next good read counts nothing for its gap,
     * and the take counts on from there (story 338, ruling E261).
     */
    @Test
    void aReportThatFailsAtTheTakesStartReadImportsNoEarlierLoss() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing At The Take Start");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke at the take's start");
        };

        long base = engine.capturedInputBlocksLostAsATakeStarts();

        backend.dropoutReport = () -> inputDrops(3);
        assertThat(engine.capturedInputBlocksLost() - base).as("the blocks lost before the take are not its own")
                .isZero();
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost() - base).as("the take counts on from its first good read")
                .isEqualTo(2);
    }

    /** A {@code null} report at the take's start read is a failed one (story 338, ruling E261). */
    @Test
    void aNullReportAtTheTakesStartReadImportsNoEarlierLoss() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Empty At The Take Start");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.dropoutReport = () -> null;

        long base = engine.capturedInputBlocksLostAsATakeStarts();

        backend.dropoutReport = () -> inputDrops(3);
        assertThat(engine.capturedInputBlocksLost() - base).as("the blocks lost before the take are not its own")
                .isZero();
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost() - base).as("the take counts on from its first good read")
                .isEqualTo(2);
    }

    /**
     * A report that fails later in the take changes nothing, and the next good
     * read counts every block lost since the take's last good read (story 338,
     * ruling E262).
     */
    @Test
    void aReportThatFailsMidTakeCountsEveryBlockLostSinceTheTakesLastGoodRead() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing Mid Take");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        long base = engine.capturedInputBlocksLostAsATakeStarts();

        backend.dropoutReport = () -> inputDrops(3);
        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(3);
        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke mid take");
        };
        assertThat(engine.capturedInputBlocksLost() - base).as("a failed read changes nothing").isEqualTo(3);
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost() - base).as("the gap since the last good read counts")
                .isEqualTo(5);
    }

    /**
     * On a stream that stays open across takes, a report that fails as the
     * second take starts brings nothing lost since the first take's last
     * good read into the second (story 338, ruling E261).
     */
    @Test
    void aReportThatFailsAsATakeStartsOnARunningStreamImportsNothingSinceTheLastTake() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing At The Second Take");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        long first = engine.capturedInputBlocksLostAsATakeStarts();
        backend.dropoutReport = () -> inputDrops(2);
        assertThat(engine.capturedInputBlocksLost() - first).as("the first take's losses").isEqualTo(2);

        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke as the second take started");
        };
        long second = engine.capturedInputBlocksLostAsATakeStarts();

        backend.dropoutReport = () -> inputDrops(6);
        assertThat(engine.capturedInputBlocksLost() - second).as("nothing lost before the second take is its own")
                .isZero();
        backend.dropoutReport = () -> inputDrops(7);
        assertThat(engine.capturedInputBlocksLost() - second).isEqualTo(1);
    }

    /**
     * With the open's read failed too, the take's start read that fails
     * leaves the first good read as the baseline, and the take counts on from
     * it (story 338, rulings E214 and E261). Call 1 is the open's read, call 2
     * the take's start read.
     */
    @Test
    void aTakeStartReadThatFailsAfterAFailedOpenCountsFromTheFirstGoodRead() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Failing At Open And Take Start");
        AtomicInteger calls = new AtomicInteger();
        AtomicLong dropped = new AtomicLong(7);
        backend.dropoutReport = () -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("the drop report broke");
            }
            return inputDrops(dropped.get());
        };
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();

        long base = engine.capturedInputBlocksLostAsATakeStarts();

        assertThat(engine.capturedInputBlocksLost() - base).as("the first good read is the baseline").isZero();
        dropped.set(9);
        assertThat(engine.capturedInputBlocksLost() - base).isEqualTo(2);
    }

    /**
     * A read landing after a failed take start read with a count lower than
     * the highest already read moves the figure neither up nor down (story
     * 338, ruling E261).
     */
    @Test
    void aStaleLowerReadAfterAFailedTakeStartReadNeverRaisesTheFigure() {
        AudioEngineOutputTest.SynchronousTestBackend backend =
                new AudioEngineOutputTest.SynchronousTestBackend("Stale After The Take Start");
        backend.dropoutReport = () -> inputDrops(0);
        engine = new AudioEngine(AudioFormat.CD_QUALITY);
        engine.setStreamingProvision(provision(backend));
        engine.startAudioOutput();
        backend.dropoutReport = () -> inputDrops(5);
        assertThat(engine.capturedInputBlocksLost()).isEqualTo(5);
        backend.dropoutReport = () -> {
            throw new IllegalStateException("the drop report broke at the take's start");
        };
        long base = engine.capturedInputBlocksLostAsATakeStarts();

        backend.dropoutReport = () -> inputDrops(3);

        assertThat(engine.capturedInputBlocksLost() - base).as("a lower read moves nothing").isZero();
    }

    private static BackendDropoutCounters inputDrops(long dropped) {
        return new BackendDropoutCounters(0, 0, 0, 0, dropped, false);
    }

    /** A {@link VirtualMachineError} a backend's counter report throws. */
    private static final class CounterReportError extends VirtualMachineError {
        CounterReportError() {
            super("the counter report could not be built");
        }
    }

    private static Handler capturing(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord entry) {
                records.add(entry);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static BackendThreadCounters counters(long callbackFaults) {
        return new BackendThreadCounters(callbackFaults, 0, 0, 0, OptionalLong.of(callbackFaults * 10),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static StreamingProvision provision(AudioBackend backend) {
        DeviceId device = DeviceId.defaultFor(backend.name());
        return new StreamingProvision(
                backend.name(), List.of(new BackendStreamRung(backend, device)));
    }
}
