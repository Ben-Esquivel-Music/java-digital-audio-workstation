package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests for the audio output stream lifecycle on {@link AudioEngine} after
 * the story-316 consolidation: ONE provision slot typed by the SDK
 * {@link AudioBackend} interface, an explicit fallback ladder, and an
 * engine-owned render pump feeding {@link AudioBackend#sink(AudioBlock)}.
 *
 * <p>Async assertions use one generous guard budget
 * ({@link #GUARD_BUDGET_MILLIS}) and await conditions, never sleeps.</p>
 */
class AudioEngineOutputTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    /** Guard budget for pump-driven conditions — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private AudioEngine engine;
    private SynchronousTestBackend backend;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        backend = new SynchronousTestBackend("Stub");
        engine.setStreamingProvision(provisionOf("Stub", backend));
    }

    @AfterEach
    void tearDown() {
        engine.stopAudioOutput();
        engine.stop();
    }

    private static StreamingProvision provisionOf(String requestedName, AudioBackend... rungs) {
        List<BackendStreamRung> ladder = java.util.Arrays.stream(rungs)
                .map(b -> new BackendStreamRung(b, DeviceId.defaultFor(nameOf(b))))
                .toList();
        return new StreamingProvision(requestedName, ladder);
    }

    private static String nameOf(AudioBackend backend) {
        return backend.name();
    }

    // ── Open / pump-start order and truth queries ────────────────────────

    @Test
    void startAudioOutputShouldOpenBackendThenStartPump() {
        engine.startAudioOutput();

        assertThat(backend.events)
                .as("open precedes the pump start (inputBlocks subscription)")
                .startsWith("open", "pumpStart");
        assertThat(backend.isOpen()).isTrue();
        assertThat(engine.isRunning()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(engine.openStreamBackendName()).contains("Stub");
        assertThat(engine.openStreamDevice()).contains(DeviceId.defaultFor("Stub"));
    }

    @Test
    void startShouldPassNegotiatedFormatAndBufferSizeToOpen() {
        AudioEngine engine24 = new AudioEngine(new AudioFormat(44_100.0, 2, 24, 256));
        SynchronousTestBackend narrow = new SynchronousTestBackend("Narrow");
        narrow.negotiateToBitDepth = 16; // e.g. Java Sound's 16-bit clamp
        engine24.setStreamingProvision(provisionOf("Narrow", narrow));
        try {
            engine24.startAudioOutput();

            assertThat(narrow.lastOpenFormat)
                    .as("open receives the NEGOTIATED format, not the raw request")
                    .isEqualTo(new com.benesquivelmusic.daw.sdk.audio.AudioFormat(44_100.0, 2, 16));
            assertThat(narrow.lastOpenBufferFrames).isEqualTo(256);
            assertThat(narrow.lastOpenDevice).isEqualTo(DeviceId.defaultFor("Narrow"));
        } finally {
            engine24.stopAudioOutput();
            engine24.stop();
        }
    }

    @Test
    void stopAudioOutputShouldCloseTheOpenBackend() {
        engine.startAudioOutput();
        engine.stopAudioOutput();

        assertThat(backend.closeCount.get()).isEqualTo(1);
        assertThat(backend.isOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(engine.openStreamBackendName()).isEmpty();
        assertThat(engine.openStreamDevice()).isEmpty();
    }

    @Test
    void pauseShouldStopThePumpButKeepTheBackendOpen() {
        engine.startAudioOutput();
        engine.pauseAudioOutput();

        assertThat(backend.closeCount.get())
                .as("pause never closes the stream")
                .isZero();
        assertThat(backend.isOpen()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isTrue();
    }

    @Test
    void startAfterPauseShouldResumeWithoutReopening() {
        engine.startAudioOutput();
        engine.pauseAudioOutput();

        engine.startAudioOutput();

        assertThat(backend.openCount.get())
                .as("resume runs on the SAME open backend — no second open")
                .isEqualTo(1);
        assertThat(backend.pumpStarts.get())
                .as("resume starts a fresh pump")
                .isEqualTo(2);
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void stopWhenNoStreamIsOpenShouldBeNoOp() {
        engine.stopAudioOutput();
        assertThat(backend.closeCount.get()).isZero();
    }

    @Test
    void pauseWhenNoStreamIsOpenShouldBeNoOp() {
        engine.pauseAudioOutput();
        assertThat(engine.isStreamPaused()).isFalse();
    }

    @Test
    void startWithNoProvisionShouldStartEngineOnly() {
        AudioEngine engineNoBackend = new AudioEngine(FORMAT);
        try {
            engineNoBackend.startAudioOutput();

            assertThat(engineNoBackend.isRunning()).isTrue();
            assertThat(engineNoBackend.isStreamOpen()).isFalse();
            assertThat(engineNoBackend.openStreamBackendName()).isEmpty();
        } finally {
            engineNoBackend.stop();
        }
    }

    @Test
    void startWhenAlreadyRunningShouldBeNoOp() {
        engine.startAudioOutput();

        engine.startAudioOutput();

        assertThat(backend.openCount.get()).isEqualTo(1);
        assertThat(backend.pumpStarts.get()).isEqualTo(1);
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void startAudioInputOutputDelegatesToTheSameSeam() {
        engine.startAudioInputOutput();

        assertThat(backend.events).startsWith("open", "pumpStart");
        assertThat(backend.openCount.get())
                .as("from CLOSED, exactly one open — no close/reopen detour")
                .isEqualTo(1);
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.openStreamBackendName()).contains("Stub");
    }

    @Test
    void startAudioInputOutputClosesAnAlreadyOpenStreamBeforeReopening() {
        // Story 316 review (F3): the open stream may predate the input device
        // (or sit on a rung without capture) — record must close it FIRST and
        // reopen through the ladder, never silently proceed with zero input.
        engine.startAudioOutput();

        engine.startAudioInputOutput();

        assertThat(backend.events)
                .as("close of the old stream strictly precedes the fresh open")
                .containsExactly("open", "pumpStart", "close", "open", "pumpStart");
        assertThat(backend.openCount.get()).isEqualTo(2);
        assertThat(backend.closeCount.get()).isEqualTo(1);
        assertThat(engine.isStreamOpen()).isTrue();
    }

    // ── The pump renders into sink ───────────────────────────────────────

    @Test
    void renderedOutputReachesSinkAtTheOpenedShape() {
        engine.startAudioOutput();

        awaitCondition(() -> backend.sunkBlockCount.get() >= 2,
                "the pump sinks rendered blocks");
        assertThat(backend.lastSunkChannels.get()).isEqualTo(2);
        assertThat(backend.lastSunkFrames.get()).isEqualTo(512);
    }

    // ── The fallback ladder ──────────────────────────────────────────────

    @Test
    void openFailurePropagatesTheFirstRungsException() {
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.failOnOpen = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        second.failOnOpen = true;
        engine.setStreamingProvision(provisionOf("First", first, second));

        assertThatThrownBy(engine::startAudioOutput)
                .as("the requested backend's failure is the actionable one")
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("open refused by First");

        assertThat(first.openCount.get()).isEqualTo(1);
        assertThat(second.openCount.get())
                .as("the ladder still walked every rung")
                .isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void ladderFallsToTheNextRungOnOpenFailure() {
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.failOnOpen = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("First", first, second));

        engine.startAudioOutput();

        assertThat(second.isOpen()).isTrue();
        assertThat(engine.openStreamBackendName())
                .as("the reported active backend is the rung that opened")
                .contains("Second");
        assertThat(engine.getBackend())
                .as("getBackend answers with the OPEN backend while open")
                .isSameAs(second);

        engine.stopAudioOutput();
        assertThat(engine.getBackend())
                .as("with no stream open, getBackend answers with the requested rung")
                .isSameAs(first);
    }

    @Test
    void everyFailedHopPublishesABackendFallbackEvent() {
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend first = new SynchronousTestBackend("First");
            first.failOnOpen = true;
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            engine.setStreamingProvision(provisionOf("First", first, second));

            engine.startAudioOutput();

            awaitCondition(() -> received.size() >= 1,
                    "one fallback event per failed hop is published");
            assertThat(received).hasSize(1);
            BackendFallbackEvent event = received.get(0);
            assertThat(event.requestedBackend()).isEqualTo("First");
            assertThat(event.requestedDevice()).isEqualTo(DeviceId.defaultFor("First").name());
            assertThat(event.activeBackend()).isEqualTo("Second");
            assertThat(event.activeDevice()).isEqualTo(DeviceId.defaultFor("Second").name());
            assertThat(event.cause()).isEqualTo("open refused by First");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void allRungsFailingPublishesEventsNamingNoActiveBackend() {
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend first = new SynchronousTestBackend("First");
            first.failOnOpen = true;
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            second.failOnOpen = true;
            engine.setStreamingProvision(provisionOf("First", first, second));

            assertThatThrownBy(engine::startAudioOutput)
                    .isInstanceOf(AudioBackendException.class);

            awaitCondition(() -> received.size() >= 2,
                    "every failed hop publishes an event");
            assertThat(received).hasSize(2);
            assertThat(received)
                    .allSatisfy(event -> {
                        assertThat(event.requestedBackend()).isEqualTo("First");
                        assertThat(event.activeBackend()).isEqualTo("none");
                        assertThat(event.activeDevice()).isEqualTo("none");
                    });
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void aGateRejectedHopIsPublishedBeforeTheLaddersOwnFailedHops() {
        // Story 316 review: the app layer's availability/streaming gate
        // drops a backend from the ladder before the engine ever sees it,
        // so this walk records no failed hop for it and a fallback head that
        // opens first try published NOTHING — a silent substitution on the
        // seam the story says every fallback is visible on. The provision
        // carries the rejection forward and the walk SEEDS it, ordered
        // first because the gate rejection happened first.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend failing = new SynchronousTestBackend("Failing");
            failing.failOnOpen = true;
            SynchronousTestBackend winner = new SynchronousTestBackend("Winner");
            engine.setStreamingProvision(new StreamingProvision(
                    "ASIO",
                    new DeviceId("ASIO", "Studio Interface Out"),
                    List.of(
                            new BackendStreamRung(failing, DeviceId.defaultFor("Failing")),
                            new BackendStreamRung(winner, DeviceId.defaultFor("Winner"))),
                    List.of("ASIO is not available on this host")));

            engine.startAudioOutput();

            awaitCondition(() -> received.size() >= 2,
                    "the gate hop and the ladder's own failed hop both publish");
            assertThat(received).hasSize(2);
            assertThat(received.get(0).cause())
                    .as("the gate rejection is published FIRST — it happened first")
                    .isEqualTo("ASIO is not available on this host");
            assertThat(received.get(1).cause()).isEqualTo("open refused by Failing");
            assertThat(received)
                    .allSatisfy(event -> {
                        assertThat(event.requestedBackend()).isEqualTo("ASIO");
                        assertThat(event.requestedDevice()).isEqualTo("Studio Interface Out");
                        assertThat(event.activeBackend())
                                .as("both name the rung that ACTUALLY won")
                                .isEqualTo("Winner");
                        assertThat(event.activeDevice())
                                .isEqualTo(DeviceId.defaultFor("Winner").name());
                    });
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void aGateRejectedHopIsPublishedEvenWhenTheLadderHeadOpensFirstTry() {
        // The exact case the pre-fix code published nothing for.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend winner = new SynchronousTestBackend("Winner");
            engine.setStreamingProvision(new StreamingProvision(
                    "WASAPI",
                    new DeviceId("WASAPI", "Speakers"),
                    List.of(new BackendStreamRung(winner, DeviceId.defaultFor("Winner"))),
                    List.of("WASAPI is available on this host but its streaming path"
                            + " is not implemented")));

            engine.startAudioOutput();

            awaitCondition(() -> received.size() >= 1,
                    "the gate rejection is a visible fact even with no failed hop");
            assertThat(received).hasSize(1);
            assertThat(received.get(0).requestedBackend()).isEqualTo("WASAPI");
            assertThat(received.get(0).activeBackend()).isEqualTo("Winner");
            assertThat(received.get(0).cause())
                    .contains("streaming path is not implemented");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    // ── The stream-open seam names the WINNER (story 316 review) ─────────

    @Test
    void theStreamOpenListenerIsNotifiedWithTheRungThatActuallyOpened() {
        // Story 316 review: a plain Play calls startAudioOutput() straight
        // from the transport controller, so the app layer's device-event
        // subscription can only follow the OPEN stream if the engine tells
        // it which rung won. The requested rung is exactly the wrong answer
        // whenever the ladder fell back.
        SynchronousTestBackend failing = new SynchronousTestBackend("Failing");
        failing.failOnOpen = true;
        SynchronousTestBackend winner = new SynchronousTestBackend("Winner");
        engine.setStreamingProvision(provisionOf("Failing", failing, winner));
        List<String> opened = new CopyOnWriteArrayList<>();
        engine.setStreamOpenListener(
                (backend, device) -> opened.add(backend.name() + "@" + device.name()));

        engine.startAudioOutput();

        assertThat(opened)
                .as("one notification, naming the winning rung's backend AND device")
                .containsExactly("Winner@" + DeviceId.defaultFor("Winner").name());

        // A resume is not an open: the stream stays open on the same
        // backend and device, so nothing a listener tracks has changed.
        engine.pauseAudioOutput();
        engine.startAudioOutput();

        assertThat(opened)
                .as("resuming a paused stream must not re-fire the open seam")
                .hasSize(1);
    }

    @Test
    void aThrowingStreamOpenListenerNeverFailsAnOpenThatSucceeded() {
        // The stream is open and rendering by the time the listener runs;
        // letting an app-layer callback throw would fail an open that
        // actually succeeded, and unwind nothing.
        engine.setStreamOpenListener((backend, device) -> {
            throw new IllegalStateException("listener blew up");
        });

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.openStreamBackendName()).contains("Stub");
    }

    @Test
    void clearingTheStreamOpenListenerStopsTheNotifications() {
        List<String> opened = new CopyOnWriteArrayList<>();
        engine.setStreamOpenListener((backend, device) -> opened.add(backend.name()));
        engine.setStreamOpenListener(null);

        engine.startAudioOutput();

        assertThat(opened).isEmpty();
    }

    // ── Negotiation is bit-depth-only (story 316 review, F6) ─────────────

    @Test
    void aRungNegotiatingADifferentChannelCountIsTreatedAsAFailedHop() {
        // The pump builds its planes and its reusable block from the ENGINE
        // format while the backend opened the NEGOTIATED one, so a rung that
        // widens the channel count would make every sink reject the block.
        // Falling through the ladder — visibly, via the hop's event — is the
        // honest outcome; adapting the render shape is story 317.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend widening = new SynchronousTestBackend("Widening");
            widening.negotiateToChannels = 4; // the engine renders 2 planes
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            engine.setStreamingProvision(provisionOf("Widening", widening, second));

            engine.startAudioOutput();

            assertThat(widening.openCount.get())
                    .as("refused BEFORE the open — nothing is opened at a shape we cannot feed")
                    .isZero();
            assertThat(engine.openStreamBackendName())
                    .as("the ladder fell through to the next rung")
                    .contains("Second");
            awaitCondition(() -> received.size() >= 1,
                    "the refused hop publishes a fallback event");
            assertThat(received.get(0).cause())
                    .as("the event's cause names the rung and what differed")
                    .contains("Widening")
                    .contains("the channel count differs");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void aRungNegotiatingADifferentSampleRateIsTreatedAsAFailedHop() {
        // A changed rate is worse than a rejection: the block would merely be
        // RELABELLED, playing un-resampled audio at the wrong pitch with
        // nothing in the log.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend resampling = new SynchronousTestBackend("Resampling");
            resampling.negotiateToSampleRate = 48_000.0; // the engine renders at 44.1 kHz
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            engine.setStreamingProvision(provisionOf("Resampling", resampling, second));

            engine.startAudioOutput();

            assertThat(resampling.openCount.get()).isZero();
            assertThat(engine.openStreamBackendName()).contains("Second");
            awaitCondition(() -> received.size() >= 1,
                    "the refused hop publishes a fallback event");
            assertThat(received.get(0).cause())
                    .contains("Resampling")
                    .contains("the sample rate differs");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void aRungNegotiatingOnlyTheBitDepthStillOpens() {
        // The complement of the two tests above: bit depth IS the backend's
        // own encoding concern (the pump always hands over normalized
        // floats), so it must remain renegotiable.
        SynchronousTestBackend narrow = new SynchronousTestBackend("Narrow");
        narrow.negotiateToBitDepth = 8;
        engine.setStreamingProvision(provisionOf("Narrow", narrow));

        engine.startAudioOutput();

        assertThat(narrow.lastOpenFormat)
                .isEqualTo(new com.benesquivelmusic.daw.sdk.audio.AudioFormat(44_100.0, 2, 8));
        assertThat(engine.openStreamBackendName()).contains("Narrow");
    }

    // ── Timed-out pump join (story 315 conservative-preserve, F6) ────────

    @Test
    void timedOutPumpJoinPreservesClaimAndStateUntilARetriedStopReleases() {
        // A backend wait that swallows interrupts can outlive the stop's
        // bounded 1 s join. Only a CONFIRMED join proves nothing can call
        // processBlock any more — so the timed-out stop must keep the
        // RT-clock claim, keep the backend open, and leave the state
        // RUNNING; a later stop retries once the abandoned loop exits.
        // (Guard budget 5 s > the 1 s inner join wait — house async rule.)
        com.benesquivelmusic.daw.core.transport.Transport transport =
                new com.benesquivelmusic.daw.core.transport.Transport();
        engine.setGraph(transport, null, null);
        backend.blockAwait = true;

        engine.startAudioOutput();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        awaitCondition(() -> backend.blockedAwaitEntries.get() >= 1,
                "the pump is wedged inside awaitSinkCapacity");

        assertThat(engine.stopAudioOutput())
                .as("the stop reports UNCONFIRMED quiescence — a thread may still render")
                .isFalse();

        assertThat(engine.isStreamOpen())
                .as("the state stays RUNNING — the stop is deferred, not faked")
                .isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(backend.closeCount.get())
                .as("the backend handle is preserved for the retry")
                .isZero();
        assertThat(backend.isOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive())
                .as("the RT-clock claim is preserved while the pump may still render")
                .isTrue();

        backend.blockAwait = false; // the wedge clears; the loop exits on its flag

        assertThat(engine.stopAudioOutput())
                .as("the retried stop joins for real and reports confirmed quiescence")
                .isTrue();

        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(backend.closeCount.get()).isEqualTo(1);
        assertThat(backend.isOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    // ── engine.stop() must not wedge audio (story 316 review) ────────────

    @Test
    void stopQuiescesTheStreamSoAudioCanBeStartedAgain() {
        // Story 316 review, highest severity: stop() used to clear the
        // engine's running flag while leaving streamState == RUNNING with no
        // pump left driving it. startAudioOutput() then hit its
        // `state == RUNNING` early return and returned SILENTLY — no render
        // thread, no audio, while isStreamOpen() (and therefore the UI) still
        // reported RUNNING. Permanent silence, no error anywhere.
        com.benesquivelmusic.daw.core.transport.Transport transport =
                new com.benesquivelmusic.daw.core.transport.Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();

        engine.stop();

        assertThat(engine.isStreamOpen())
                .as("the engine no longer claims a RUNNING stream it is not driving")
                .isFalse();
        assertThat(engine.openStreamBackendName()).isEmpty();
        assertThat(engine.openStreamDevice()).isEmpty();
        assertThat(transport.isRealTimeClockActive())
                .as("the stop released the RT-clock claim it can no longer honour")
                .isFalse();

        int sunkBeforeRestart = backend.sunkBlockCount.get();

        engine.startAudioOutput();

        assertThat(backend.events)
                .as("the retained handle is released, then a FRESH stream is opened")
                .containsExactly("open", "pumpStart", "close", "open", "pumpStart");
        assertThat(backend.openCount.get()).isEqualTo(2);
        assertThat(backend.closeCount.get()).isEqualTo(1);
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.openStreamBackendName()).contains("Stub");
        assertThat(transport.isRealTimeClockActive()).isTrue();
        awaitCondition(() -> backend.sunkBlockCount.get() > sunkBeforeRestart,
                "audio really flows again — a pump is rendering into the sink");
    }

    @Test
    void aStartIsRefusedWhileAnEarlierPumpHasNotExited() {
        // Story 316 review: two live pumps would both call processBlock,
        // which renders through the SINGLE pre-allocated RenderPipeline —
        // one set of mix / per-track / return-bus buffers shared by two
        // threads, i.e. interleaved garbage rather than a doubled signal.
        // requireQuiescedPump() therefore re-joins the abandoned pump and
        // fails the open outright when it still has not exited.
        // (Two bounded 1 s joins < the 5 s guard budget — house async rule.)
        backend.blockAwait = true;
        engine.startAudioOutput();
        awaitCondition(() -> backend.blockedAwaitEntries.get() >= 1,
                "the pump is wedged inside awaitSinkCapacity");

        engine.stop(); // the quiesce's bounded join times out; the pump is KEPT

        assertThat(engine.isStreamOpen()).isFalse();

        assertThatThrownBy(engine::startAudioOutput)
                .as("a second pump into the shared render pipeline is a hard failure")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("previous render pump has not exited yet");
        assertThat(backend.openCount.get())
                .as("no second open — the refusal happens before the ladder is walked")
                .isEqualTo(1);
        assertThat(backend.pumpStarts.get()).isEqualTo(1);
        assertThat(backend.closeCount.get())
                .as("the retained handle is untouched while a pump may still render")
                .isZero();

        backend.blockAwait = false; // the wedge clears; the loop exits on its flag

        engine.startAudioOutput(); // joins for real, releases the handle, opens fresh

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(backend.openCount.get()).isEqualTo(2);
        assertThat(backend.closeCount.get()).isEqualTo(1);
    }

    // ── Deferred collaborator teardown (story 316 review, F4) ────────────

    @Test
    void stopWithAnUnjoinedPumpRetainsTheRenderCollaboratorsUntilAConfirmedStop() {
        // Story 316 review: stop() cleared `running` and then closed the MIDI
        // renderer, detached the scheduler and closed the worker pool — while
        // a pump already PAST that running check could still be inside
        // processBlock, using all three. Clearing `running` stays (it is how
        // the orphaned loop exits, and RELEASE_PENDING is what makes the next
        // start fail loudly); the COLLABORATOR teardown is what defers.
        // The pool size is pinned: defaults() derives it from the CPU count
        // and is 1 on a 2-core host, which would leave the scheduler null and
        // make the assertions below vacuous.
        AudioEngine pinned = new AudioEngine(
                FORMAT, AudioEngineSettings.defaults().withWorkerPoolSize(4));
        SynchronousTestBackend wedging = new SynchronousTestBackend("Wedging");
        pinned.setStreamingProvision(provisionOf("Wedging", wedging));
        try {
            wedging.blockAwait = true;
            pinned.startAudioOutput();

            assertThat(pinned.getGraphScheduler())
                    .as("non-vacuity guard: parallelism really is on for this engine")
                    .isNotNull();
            assertThat(pinned.getMidiTrackRenderer()).isNotNull();
            awaitCondition(() -> wedging.blockedAwaitEntries.get() >= 1,
                    "the pump is wedged inside awaitSinkCapacity");

            pinned.stop(); // the quiesce's bounded join times out

            assertThat(pinned.isRunning())
                    .as("the running flag still clears — the orphaned loop exits on it")
                    .isFalse();
            assertThat(pinned.getMidiTrackRenderer())
                    .as("the MIDI renderer a live processBlock may still use is RETAINED")
                    .isNotNull();
            assertThat(pinned.getGraphScheduler())
                    .as("the graph scheduler is RETAINED")
                    .isNotNull();
            assertThat(pinned.getWorkerPool())
                    .as("the worker pool is RETAINED — a closed pool would strand its tasks")
                    .isNotNull();

            wedging.blockAwait = false; // the wedge clears; the loop exits on its flag

            pinned.stopAudioOutput(); // the confirming lifecycle call drains the deferral

            assertThat(pinned.getMidiTrackRenderer())
                    .as("the deferred teardown DRAINED once the pump's exit was confirmed")
                    .isNull();
            assertThat(pinned.getGraphScheduler()).isNull();
            assertThat(pinned.getWorkerPool()).isNull();
        } finally {
            wedging.blockAwait = false;
            pinned.stopAudioOutput();
            pinned.stop();
        }
    }

    // ── The provision swap is refused, not forced (story 316 review, F9) ─

    @Test
    void replacingTheProvisionIsRefusedWhileThePumpMayStillBeInsideTheOutgoingBackend() {
        // A failed stopPump() means the retained thread may still be executing
        // sink / awaitSinkCapacity on the outgoing backend, whose handle this
        // swap closes next: releasing an ASIO upcall arena, a SourceDataLine
        // or a PortAudio stream handle under that thread races native state.
        // The swap is therefore aborted WHOLE and retried later, never forced.
        StreamingProvision outgoingProvision = engine.getStreamingProvision();
        SynchronousTestBackend replacement = new SynchronousTestBackend("Replacement");
        StreamingProvision incomingProvision = provisionOf("Replacement", replacement);

        backend.blockAwait = true;
        engine.startAudioOutput();
        awaitCondition(() -> backend.blockedAwaitEntries.get() >= 1,
                "the pump is wedged inside awaitSinkCapacity");
        // setStreamingProvision refuses to run while the engine is running;
        // stop() clears that flag and leaves the pump wedged.
        engine.stop();

        assertThatThrownBy(() -> engine.setStreamingProvision(incomingProvision))
                .as("closing a backend a live thread may be inside is a hard refusal")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("render pump");

        assertThat(backend.closeCount.get())
                .as("the outgoing handle is NOT closed under a possibly-live pump")
                .isZero();
        assertThat(engine.getStreamingProvision())
                .as("the swap aborted whole — the engine still points at the old provision")
                .isSameAs(outgoingProvision);

        backend.blockAwait = false; // the wedge clears; the loop exits on its flag

        engine.setStreamingProvision(incomingProvision); // the retry joins, then swaps

        assertThat(engine.getStreamingProvision()).isSameAs(incomingProvision);
        assertThat(backend.closeCount.get())
                .as("only now is the outgoing handle handed back")
                .isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();
    }

    // ── Pump-start failure unwind ────────────────────────────────────────

    @Test
    void pumpStartFailureUnwindClosesTheStreamAndRethrows() {
        backend.failOnPumpStart = true;

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("pump start refused by Stub");

        assertThat(backend.closeCount.get())
                .as("the opened handle was given back")
                .isEqualTo(1);
        assertThat(backend.isOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();

        // Not wedged: a later start opens a fresh stream normally.
        backend.failOnPumpStart = false;
        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(backend.openCount.get()).isEqualTo(2);
    }

    @Test
    void aPumpStartFailureNeverPublishesAnEventNamingTheUnstartedRungAsActive() {
        // Story 316 review: the fallback events used to go out the moment
        // the rung's open() returned — BEFORE startOpenedStream() put a pump
        // on it. When subscribing to inputBlocks() then failed, the handle
        // was closed but subscribers had already been told that rung was
        // the active stream.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend first = new SynchronousTestBackend("First");
            first.failOnOpen = true;
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            second.failOnPumpStart = true;
            engine.setStreamingProvision(provisionOf("First", first, second));

            assertThatThrownBy(engine::startAudioOutput)
                    .as("the pump-start failure still propagates unchanged")
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessage("pump start refused by Second");

            assertThat(second.closeCount.get())
                    .as("the rung whose pump never started gave its handle back")
                    .isEqualTo(1);
            assertThat(second.isOpen()).isFalse();
            assertThat(engine.isStreamOpen()).isFalse();

            awaitCondition(() -> received.size() >= 1,
                    "the failed first hop is still published — requested != active");
            assertThat(received).hasSize(1);
            BackendFallbackEvent event = received.get(0);
            assertThat(event.cause()).isEqualTo("open refused by First");
            assertThat(event.activeBackend())
                    .as("no stream became active, so the event must not name Second")
                    .isEqualTo("none");
            assertThat(event.activeDevice()).isEqualTo("none");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void aRungWhoseBackendCannotStreamIsSkippedAsAFailedHop() {
        // Story 316 review: supportsStreaming() is the backend's own word
        // that sink() reaches the device (for ASIO, a live probe of the
        // native shim). The engine asks it itself instead of trusting the
        // app layer's gate, so a non-streaming rung can never become an
        // honest-looking silent stream.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend silent = new SynchronousTestBackend("Silent");
            silent.streamingSupported = false;
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            engine.setStreamingProvision(provisionOf("Silent", silent, second));

            engine.startAudioOutput();

            assertThat(silent.openCount.get())
                    .as("the guard refuses the rung before it is ever opened")
                    .isZero();
            assertThat(second.isOpen()).isTrue();
            assertThat(engine.openStreamBackendName()).contains("Second");

            awaitCondition(() -> received.size() >= 1,
                    "the skipped rung is published as an ordinary failed hop");
            assertThat(received).hasSize(1);
            BackendFallbackEvent event = received.get(0);
            assertThat(event.requestedBackend()).isEqualTo("Silent");
            assertThat(event.activeBackend()).isEqualTo("Second");
            assertThat(event.cause())
                    .contains("Silent")
                    .contains("cannot stream");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    // ── Async await support ──────────────────────────────────────────────

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    // ── Scripted fake backend ────────────────────────────────────────────

    /**
     * Scripted {@link AudioBackend} fake (the design's
     * {@code SynchronousTestBackend}): {@code sink} counts and records block
     * shape, {@code awaitSinkCapacity} parks briefly so the pump cycles fast
     * without spinning, and open / pump-start / close failures are opt-in.
     */
    static final class SynchronousTestBackend implements AudioBackend {

        private final String name;
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger pumpStarts = new AtomicInteger();
        final AtomicInteger sunkBlockCount = new AtomicInteger();
        final AtomicInteger lastSunkChannels = new AtomicInteger();
        final AtomicInteger lastSunkFrames = new AtomicInteger();
        volatile boolean failOnOpen;
        volatile boolean failOnPumpStart;
        volatile boolean failOnClose;
        volatile boolean blockAwait;
        /** Story 316 review: what the engine-side streaming guard sees. */
        volatile boolean streamingSupported = true;
        final AtomicInteger blockedAwaitEntries = new AtomicInteger();
        volatile Integer negotiateToBitDepth;
        /** Story 316 review: a negotiation the engine must refuse (channels). */
        volatile Integer negotiateToChannels;
        /** Story 316 review: a negotiation the engine must refuse (sample rate). */
        volatile Double negotiateToSampleRate;
        volatile DeviceId lastOpenDevice;
        volatile com.benesquivelmusic.daw.sdk.audio.AudioFormat lastOpenFormat;
        volatile int lastOpenBufferFrames;
        private volatile boolean open;
        private final SubmissionPublisher<AudioBlock> inputPublisher =
                new SubmissionPublisher<>();

        SynchronousTestBackend(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return streamingSupported;
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return List.of();
        }

        @Override
        public com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiateFormat(
                com.benesquivelmusic.daw.sdk.audio.AudioFormat requested) {
            Integer clamp = negotiateToBitDepth;
            Integer channels = negotiateToChannels;
            Double sampleRate = negotiateToSampleRate;
            if (clamp == null && channels == null && sampleRate == null) {
                return requested;
            }
            return new com.benesquivelmusic.daw.sdk.audio.AudioFormat(
                    sampleRate == null ? requested.sampleRate() : sampleRate,
                    channels == null ? requested.channels() : channels,
                    clamp == null ? requested.bitDepth() : clamp);
        }

        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            events.add("open");
            openCount.incrementAndGet();
            if (failOnOpen) {
                throw new AudioBackendException("open refused by " + name);
            }
            if (open) {
                throw new IllegalStateException("A stream is already open on " + name);
            }
            lastOpenDevice = device;
            lastOpenFormat = format;
            lastOpenBufferFrames = bufferFrames;
            open = true;
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            events.add("pumpStart");
            pumpStarts.incrementAndGet();
            if (failOnPumpStart) {
                throw new AudioBackendException("pump start refused by " + name);
            }
            return inputPublisher;
        }

        @Override
        public void sink(AudioBlock block) {
            lastSunkChannels.set(block.channels());
            lastSunkFrames.set(block.frames());
            sunkBlockCount.incrementAndGet();
        }

        @Override
        public void awaitSinkCapacity(long timeoutNanos) {
            if (blockAwait) {
                // Simulates a native wait that swallows interrupts: the
                // pump stop's interrupt/unpark cannot free it — only
                // clearing blockAwait can. The interrupt status is cleared
                // each turn so parkNanos really parks instead of spinning.
                blockedAwaitEntries.incrementAndGet();
                while (blockAwait) {
                    Thread.interrupted();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                return;
            }
            // Fast pacing for tests: never spin, never wait a full period.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            events.add("close");
            closeCount.incrementAndGet();
            if (failOnClose) {
                throw new AudioBackendException("close refused by " + name);
            }
            open = false;
        }
    }
}
