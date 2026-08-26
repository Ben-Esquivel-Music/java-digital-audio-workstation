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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    /**
     * How long a racing thread parks INSIDE a backend call to widen a
     * lifecycle race window. Bounded on purpose: when the lifecycle is
     * correctly serialized the other thread is blocked on the engine's lock
     * and can never signal, so the parked thread must proceed on its own
     * rather than hang. Never used as an assertion budget.
     */
    private static final long RACE_WINDOW_MILLIS = 250L;

    /**
     * Quiet window used to assert an ABSENCE (no orphaned pump still
     * rendering). The pump paces at ~1 ms per block through the fake, so a
     * survivor pushes well over a hundred blocks through this window.
     */
    private static final long QUIET_WINDOW_MILLIS = 150L;

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
    void startWithNoProvisionShouldRefuseBeforeStartingEngine() {
        AudioEngine engineNoBackend = new AudioEngine(FORMAT);

        assertThatThrownBy(engineNoBackend::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no audio backend is configured");
        assertThat(engineNoBackend.isRunning())
                .as("a refused Play must not allocate/start an engine with no callback")
                .isFalse();
        assertThat(engineNoBackend.isStreamOpen()).isFalse();
        assertThat(engineNoBackend.openStreamBackendName()).isEmpty();
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
        assertThat(engine.isRunning())
                .as("an all-rungs failure rolls back the engine start it triggered")
                .isFalse();
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

    // ── Record requires CAPTURE, playback does not (story 316 review) ────

    /**
     * The silent take, closed. A rung can OPEN successfully and still have no
     * capture — {@code JavaxSoundBackend} swallowing a refused capture line,
     * {@code CallbackBackendAdapter} retrying a refused duplex open
     * output-only — and the record path used to walk the playback ladder and
     * accept exactly that. It now verifies
     * {@link AudioBackend#openedInputChannels()} after each open and treats a
     * zero as an ordinary failed hop, which is why the ladder can route
     * around it: an ASIO head exposing no inputs lets PortAudio take the
     * recording.
     */
    @Test
    void recordSkipsARungThatOpensWithoutCaptureAndTheNextRungWins() {
        SynchronousTestBackend playbackOnly = new SynchronousTestBackend("PlaybackOnly");
        playbackOnly.capturesInput = false;
        SynchronousTestBackend duplex = new SynchronousTestBackend("Duplex");
        engine.setStreamingProvision(provisionOf("PlaybackOnly", playbackOnly, duplex));

        engine.startAudioInputOutput();

        assertThat(playbackOnly.openCount.get())
                .as("the capture-less rung is only refusable AFTER its open returns")
                .isEqualTo(1);
        assertThat(playbackOnly.closeCount.get())
                .as("its handle is given back before the walk advances — the device"
                        + " must not stay held by a stream nobody wants")
                .isEqualTo(1);
        assertThat(playbackOnly.isOpen()).isFalse();
        assertThat(duplex.isOpen())
                .as("the walk fell through to a rung that really can capture")
                .isTrue();
        assertThat(engine.openStreamBackendName()).contains("Duplex");
        assertThat(duplex.openedInputChannels()).isGreaterThan(0);
    }

    @Test
    void recordFailsAndLeavesNoStreamOpenWhenEveryRungOpensWithoutCapture() {
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.capturesInput = false;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        second.capturesInput = false;
        engine.setStreamingProvision(provisionOf("First", first, second));

        assertThatThrownBy(engine::startAudioInputOutput)
                .as("the requested backend's refusal is the actionable one")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no capture channels");

        assertThat(first.closeCount.get()).isEqualTo(1);
        assertThat(second.closeCount.get()).isEqualTo(1);
        assertThat(first.isOpen()).isFalse();
        assertThat(second.isOpen()).isFalse();
        assertThat(engine.isStreamOpen())
                .as("a failed record open leaves nothing open on any rung")
                .isFalse();
    }

    /**
     * The DISCRIMINATING test. Without it the capture requirement could have
     * been applied to BOTH entry points and every suite above would still be
     * green, because none of them asserts that a playback-only device still
     * plays. A speakers-only interface, or an ASIO4ALL with only outputs
     * enabled, must open for playback — that is the whole point of
     * {@code CaptureRequirement.OPTIONAL}.
     */
    @Test
    void playbackStillOpensTheVerySameCaptureLessProvisionRecordRefuses() {
        SynchronousTestBackend playbackOnly = new SynchronousTestBackend("PlaybackOnly");
        playbackOnly.capturesInput = false;
        engine.setStreamingProvision(provisionOf("PlaybackOnly", playbackOnly));

        assertThatThrownBy(engine::startAudioInputOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no capture channels");
        assertThat(engine.isStreamOpen()).isFalse();

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen())
                .as("the playback contract is untouched: no capture is still a"
                        + " legitimate open")
                .isTrue();
        assertThat(engine.openStreamBackendName()).contains("PlaybackOnly");
        assertThat(playbackOnly.isOpen()).isTrue();
    }

    /**
     * The refusal must reach the outside world as an ordinary fallback, not
     * as a silent skip: {@code requested != active} is a published fact
     * whatever refused the rung.
     */
    @Test
    void aCaptureLessRungPublishesABackendFallbackEventNamingTheWinner() {
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);
            SynchronousTestBackend playbackOnly =
                    new SynchronousTestBackend("PlaybackOnly");
            playbackOnly.capturesInput = false;
            SynchronousTestBackend duplex = new SynchronousTestBackend("Duplex");
            engine.setStreamingProvision(
                    provisionOf("PlaybackOnly", playbackOnly, duplex));

            engine.startAudioInputOutput();

            awaitCondition(() -> received.size() >= 1,
                    "the capture refusal is published like any other failed hop");
            assertThat(received).hasSize(1);
            assertThat(received.get(0).requestedBackend()).isEqualTo("PlaybackOnly");
            assertThat(received.get(0).activeBackend()).isEqualTo("Duplex");
            assertThat(received.get(0).cause()).contains("no capture channels");
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    /**
     * A capture-less rung is refused AFTER its {@code open}, so
     * {@code openAttempted} is already true and the walk obeys the same
     * unreleasable-handle rule as any other post-open hop failure: it
     * ABANDONS rather than opening a fallback beside a backend that may still
     * hold the device.
     */
    @Test
    void aCaptureLessRungWhoseCloseFailsAbandonsTheWalkInsteadOfOpeningTheNextRung() {
        SynchronousTestBackend playbackOnly = new SynchronousTestBackend("PlaybackOnly");
        playbackOnly.capturesInput = false;
        playbackOnly.failOnClose = true;
        SynchronousTestBackend duplex = new SynchronousTestBackend("Duplex");
        engine.setStreamingProvision(provisionOf("PlaybackOnly", playbackOnly, duplex));

        assertThatThrownBy(engine::startAudioInputOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released");

        assertThat(duplex.openCount.get())
                .as("no fallback rung is opened beside a backend that may still hold"
                        + " the device")
                .isZero();
        assertThat(engine.isStreamOpen()).isFalse();
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
        // honest outcome. Story 317 broadens backend encoding negotiation;
        // adapting the engine's render shape remains unsupported.
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

    @Test
    void aRungThatFailsToOpenIsClosedBeforeTheLadderAdvances() {
        // Story 316 re-review: AudioBackend.open promises no rollback, so a
        // rung that took a partial native handle and then threw kept holding
        // the device while the fallback rung opened and rendered through it
        // — two backends on one device.
        List<String> timeline = new CopyOnWriteArrayList<>();
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.timeline = timeline;
        first.failOnOpen = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        second.timeline = timeline;
        engine.setStreamingProvision(provisionOf("First", first, second));

        engine.startAudioOutput();

        assertThat(first.closeCount.get())
                .as("the failed rung's handle was given back")
                .isEqualTo(1);
        assertThat(timeline)
                .as("the failed rung is closed BEFORE the next rung is opened")
                .containsSubsequence("First:open", "First:close", "Second:open");
        assertThat(second.isOpen()).isTrue();
    }

    @Test
    void aRungRefusedBeforeItWasEverOpenedIsStillClosedWithoutError() {
        // The streaming guard (and the negotiation guard) throw BEFORE
        // open() is ever called, so this rung never held a stream handle —
        // but it may well have initialized its driver, and closing a
        // never-opened backend is safe on every in-tree backend.
        List<String> timeline = new CopyOnWriteArrayList<>();
        SynchronousTestBackend silent = new SynchronousTestBackend("Silent");
        silent.timeline = timeline;
        silent.streamingSupported = false;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        second.timeline = timeline;
        engine.setStreamingProvision(provisionOf("Silent", silent, second));

        engine.startAudioOutput();

        assertThat(silent.openCount.get())
                .as("the guard refused the rung before it was ever opened")
                .isZero();
        assertThat(silent.closeCount.get())
                .as("the never-opened rung is closed anyway, and the close does not fail the hop")
                .isEqualTo(1);
        assertThat(timeline)
                .as("that release happens before the fallback rung opens")
                .containsSubsequence("Silent:close", "Second:open");
        assertThat(second.isOpen()).isTrue();
    }

    @Test
    void aFailedRungWhoseCloseAlsoFailsAbandonsTheWalkAndCarriesTheOpenFailure() {
        // `First` REACHED open(), so it may hold the device; its close then
        // failed, so the engine could not bound that partial acquisition to
        // the hop that made it. Opening `Second` beside it would put two
        // backends on one device — the exact invariant the ladder exists to
        // protect — so the walk is ABANDONED rather than continued.
        //
        // The surviving property from before this change: a close failure
        // still never MASKS the open failure. It is no longer what
        // propagates directly, but it is the CAUSE of what does, and the
        // close failure is still suppressed on it, so both survive in the
        // chain and the published cause still describes the OPEN failure.
        DefaultEventBus bus = new DefaultEventBus();
        List<BackendFallbackEvent> received = new CopyOnWriteArrayList<>();
        try (var subscription = bus.on(BackendFallbackEvent.class, received::add)) {
            EventBusPublisher.setDefault(bus);

            SynchronousTestBackend first = new SynchronousTestBackend("First");
            first.failOnOpen = true;
            first.failOnClose = true;
            SynchronousTestBackend second = new SynchronousTestBackend("Second");
            second.failOnOpen = true;
            engine.setStreamingProvision(provisionOf("First", first, second));

            assertThatThrownBy(engine::startAudioOutput)
                    .as("the walk is abandoned, and the refusal says why")
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("First")
                    .hasMessageContaining("could not be released")
                    .hasMessageContaining("two backends on one device")
                    .satisfies(failure -> {
                        assertThat(failure.getCause())
                                .as("the requested rung's OPEN failure is the cause — never"
                                        + " replaced by the close failure")
                                .isInstanceOf(AudioBackendException.class)
                                .hasMessage("open refused by First");
                        assertThat(failure.getCause().getSuppressed())
                                .as("the close failure still travels on that cause, never"
                                        + " in place of it")
                                .extracting(Throwable::getMessage)
                                .contains("close refused by First");
                    });

            assertThat(second.openCount.get())
                    .as("no fallback rung was opened beside the rung whose handle could"
                            + " not be released")
                    .isZero();
            assertThat(engine.isStreamOpen()).isFalse();

            awaitCondition(() -> received.size() >= 1,
                    "the hop that failed is still published");
            assertThat(received)
                    .as("exactly the one hop the walk got through, naming the open failure"
                            + " rather than the close")
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.requestedBackend()).isEqualTo("First");
                        assertThat(event.cause()).isEqualTo("open refused by First");
                        assertThat(event.activeBackend())
                                .as("nothing became active")
                                .isEqualTo("none");
                    });
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }

    @Test
    void aRungRefusedBeforeOpenWhoseCloseAlsoFailsStillFallsThroughToTheNextRung() {
        // The regression guard for the ASIO-symbols path. requireStreamingSupport
        // throws BEFORE open() is ever called, so this rung holds no device and
        // its close failure is spurious — the walk must fall through exactly as
        // it does when that close succeeds. In production this is ASIO being
        // refused because asioshim lacks the story-311 streaming symbols; that
        // refusal has to reach PortAudio / Java Sound.
        SynchronousTestBackend silent = new SynchronousTestBackend("Silent");
        silent.streamingSupported = false;
        silent.failOnClose = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("Silent", silent, second));

        engine.startAudioOutput();

        assertThat(silent.openCount.get())
                .as("the guard refused the rung before it was ever opened, so it holds"
                        + " no device however its close behaves")
                .isZero();
        assertThat(silent.closeCount.get())
                .as("the close was still attempted, and still failed")
                .isEqualTo(1);
        assertThat(second.isOpen())
                .as("a spurious close failure must not abandon the walk")
                .isTrue();
        assertThat(engine.openStreamBackendName()).contains("Second");
    }

    // ── Native control-panel sessions (story 316 re-review) ─────────────
    //
    // The controller already refuses to close the backend INSTANCES it owns
    // while a driver's modal control panel is up, but that guard cannot see
    // the ENGINE's own handle closes. With a stream open, Stop, shutdown or a
    // concurrent endpoint reconfigure could therefore tear the backend down
    // underneath a live native dialog. These pin the engine-side seam.

    @Test
    void aStopWhileTheControlPanelIsOpenRetainsTheHandleAndStillReportsQuiescence() {
        engine.startAudioOutput();
        engine.beginControlPanelSession(backend);

        assertThat(engine.stopAudioOutput())
                .as("the return value reports QUIESCENCE, and the pump really has joined;"
                        + " reporting false would make shutdown and applyConfiguration"
                        + " defer work that is perfectly safe")
                .isTrue();

        assertThat(backend.closeCount.get())
                .as("the handle is deliberately retained — closing it would free the"
                        + " native state the modal dialog is running on")
                .isZero();
        assertThat(backend.isOpen()).isTrue();
        assertThat(engine.isStreamOpen())
                .as("output is stopped and not resumable, whatever the backend still holds")
                .isFalse();

        engine.endControlPanelSession(backend);

        assertThat(backend.closeCount.get())
                .as("the deferral has an OWNER: ending the session drains the close it"
                        + " deferred, rather than leaving it to the next start or stop")
                .isEqualTo(1);
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void anOpenIsRefusedWhileTheControlPanelStillHoldsTheRetainedHandle() {
        engine.startAudioOutput();
        engine.beginControlPanelSession(backend);
        engine.stopAudioOutput();

        assertThatThrownBy(engine::startAudioOutput)
                .as("opening now would put a second stream on the device the panel's"
                        + " backend still holds")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("control panel");
        assertThat(backend.openCount.get())
                .as("no second open reached the device")
                .isEqualTo(1);

        engine.endControlPanelSession(backend);

        engine.startAudioOutput();
        assertThat(backend.openCount.get())
                .as("once the drained close released the handle, a fresh open succeeds")
                .isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void aProvisionSwapIsRefusedWhileTheOutgoingBackendsControlPanelIsOpen() {
        StreamingProvision outgoingProvision = engine.getStreamingProvision();
        SynchronousTestBackend replacement = new SynchronousTestBackend("Replacement");
        StreamingProvision incomingProvision = provisionOf("Replacement", replacement);
        engine.startAudioOutput();
        engine.beginControlPanelSession(backend);
        engine.stop(); // setStreamingProvision refuses while the engine runs

        assertThatThrownBy(() -> engine.setStreamingProvision(incomingProvision))
                .as("re-pointing the engine away would discard the only path that could"
                        + " release this handle once the dialog returns")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("control panel");

        assertThat(backend.closeCount.get())
                .as("nothing was closed under the modal dialog")
                .isZero();
        assertThat(engine.getStreamingProvision())
                .as("the swap is refused WHOLE — the engine still points at the outgoing"
                        + " provision, so the outgoing backend stays reachable")
                .isSameAs(outgoingProvision);
        assertThat(engine.isStreamOpen()).isFalse();

        engine.endControlPanelSession(backend);

        assertThat(backend.closeCount.get())
                .as("the retained handle is released when the panel returns")
                .isEqualTo(1);
        engine.setStreamingProvision(incomingProvision);
        assertThat(engine.getStreamingProvision()).isSameAs(incomingProvision);
    }

    @Test
    void endingASessionForAnotherBackendLeavesTheRegistrationInPlace() {
        SynchronousTestBackend other = new SynchronousTestBackend("Other");
        engine.startAudioOutput();
        engine.beginControlPanelSession(backend);

        engine.endControlPanelSession(other);
        engine.stopAudioOutput();

        assertThat(backend.closeCount.get())
                .as("the release compares by IDENTITY, so a late release for another"
                        + " instance cannot clear this registration")
                .isZero();

        engine.endControlPanelSession(backend);
        assertThat(backend.closeCount.get()).isEqualTo(1);
    }

    @Test
    void endingASessionWithAnUnconfirmedPumpExitLeavesTheHandleRetained() {
        // RELEASE_PENDING alone is NOT proof the pump exited: engine.stop()
        // enters that state even when its own bounded join timed out. The
        // drain must re-join rather than trust the state, or it would free
        // native state a thread still inside sink/awaitSinkCapacity is using.
        backend.blockAwait = true;
        engine.startAudioOutput();
        awaitCondition(() -> backend.blockedAwaitEntries.get() >= 1,
                "the pump is wedged inside awaitSinkCapacity");
        engine.beginControlPanelSession(backend);
        engine.stop(); // RELEASE_PENDING, join UNCONFIRMED, pump reference kept

        engine.endControlPanelSession(backend);

        assertThat(backend.closeCount.get())
                .as("the drain re-joins first and leaves the deferral in place rather"
                        + " than closing under a possibly-live pump")
                .isZero();

        backend.blockAwait = false; // the wedge clears; the loop exits on its flag

        assertThat(engine.stopAudioOutput())
                .as("the next stop retries the join and completes the release")
                .isTrue();
        assertThat(backend.closeCount.get()).isEqualTo(1);
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void aFailedHopWhoseCloseTheControlPanelBlocksAbandonsTheLadderWalk() {
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.failOnOpen = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("First", first, second));
        engine.beginControlPanelSession(first);

        assertThatThrownBy(engine::startAudioOutput)
                .as("the hop's handle was not released, so the walk stops here")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("First")
                .hasMessageContaining("could not be released");

        assertThat(first.closeCount.get())
                .as("the close is not even attempted while the panel is up")
                .isZero();
        assertThat(second.openCount.get())
                .as("no fallback rung opened beside the rung the panel pins open")
                .isZero();
        engine.endControlPanelSession(first);
    }

    // ── Backend-DEFERRED handle releases (story 316 re-review) ──────────
    //
    // A close that returns normally is not proof the device came back.
    // AsioBackend.open() can fail while DEFERRING its driver-shim release —
    // the load timed out, so the shim deliberately keeps its ownership claim
    // and queues the ASIOExit until the driver returns — and its close() then
    // returns normally over Java-side fields it had already cleared. Read as
    // a release, that is how the engine ends up opening PortAudio or Java
    // Sound over a device the ASIO driver may still be acquiring. Every
    // engine-owned close now reads the verdict through
    // AudioBackend#isReleasePending() and answers it exactly as it answers a
    // close that THREW.

    @Test
    void aFailedRungWhoseCloseDefersTheReleaseAbandonsTheLadderWalk() {
        // The REPORTED finding. `First` reached open() and failed, and its
        // close returned normally while the backend kept the handle — so it
        // may still hold the device, and opening `Second` beside it is the
        // two-backends-on-one-device outcome the abandonment rule exists to
        // prevent.
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.failOnOpen = true;
        first.deferReleaseOnClose = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("First", first, second));

        assertThatThrownBy(engine::startAudioOutput)
                .as("a close that RETURNED without the handle stops the walk exactly as a"
                        + " close that threw does")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("First")
                .hasMessageContaining("could not be released")
                .hasMessageContaining("two backends on one device")
                .satisfies(failure -> {
                    assertThat(failure.getCause())
                            .as("the OPEN failure is still the cause — a deferred release"
                                    + " carries no exception of its own, so nothing"
                                    + " replaces or masks it")
                            .isInstanceOf(AudioBackendException.class)
                            .hasMessage("open refused by First");
                    assertThat(failure.getCause().getSuppressed())
                            .as("and nothing is attached to it either: there was no close"
                                    + " failure to suppress")
                            .isEmpty();
                });

        assertThat(first.closeCount.get())
                .as("the close WAS attempted — it simply did not give the handle back")
                .isEqualTo(1);
        assertThat(second.openCount.get())
                .as("no fallback rung was opened beside a rung whose driver may still be"
                        + " taking the device")
                .isZero();
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void aRungRefusedBeforeOpenWhoseCloseDefersTheReleaseStillFallsThroughToTheNextRung() {
        // The openAttempted distinction, carried onto the new verdict. This
        // rung was refused by the streaming guard BEFORE open() was ever
        // called, so it was never asked for the device and whatever its
        // backend is still holding is not this walk's to wait for. In
        // production this is ASIO being refused because asioshim lacks the
        // story-311 streaming symbols, and that refusal has to reach
        // PortAudio / Java Sound.
        SynchronousTestBackend silent = new SynchronousTestBackend("Silent");
        silent.streamingSupported = false;
        silent.deferReleaseOnClose = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("Silent", silent, second));

        engine.startAudioOutput();

        assertThat(silent.openCount.get())
                .as("the guard refused the rung before it was ever opened, so it holds no"
                        + " device however its release behaves")
                .isZero();
        assertThat(silent.closeCount.get())
                .as("the close was still attempted, and still deferred")
                .isEqualTo(1);
        assertThat(second.isOpen())
                .as("a spurious deferred release must not abandon the walk")
                .isTrue();
        assertThat(engine.openStreamBackendName()).contains("Second");
    }

    @Test
    void aStopWhoseCloseDefersTheReleaseRetainsTheHandleAndTheNextStartRetriesIt() {
        engine.startAudioOutput();
        backend.deferReleaseOnClose = true;

        assertThat(engine.stopAudioOutput())
                .as("the return value reports QUIESCENCE, and the pump really did join;"
                        + " a handle the backend has not finished releasing is not a"
                        + " reason to tell callers a thread may still render")
                .isTrue();

        assertThat(backend.closeCount.get())
                .as("the close was attempted")
                .isEqualTo(1);
        assertThat(engine.isStreamOpen())
                .as("output is stopped and not resumable, whatever the backend still holds")
                .isFalse();

        assertThatThrownBy(engine::startAudioOutput)
                .as("the engine is still TRACKING the handle, so it retries the close"
                        + " before a fresh open and refuses the open while the release is"
                        + " still outstanding — opening now would put a second backend on"
                        + " the device this one has not let go of")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("still")
                .hasMessageContaining("DEFERRED");
        assertThat(backend.openCount.get())
                .as("no second open reached the device")
                .isEqualTo(1);
        assertThat(backend.closeCount.get())
                .as("that refusal came from a RETRIED close, which is the whole point of"
                        + " keeping the handle tracked")
                .isEqualTo(2);

        backend.completeDeferredRelease(); // the driver's queued teardown lands

        engine.startAudioOutput();

        assertThat(backend.closeCount.get())
                .as("the condition is self-clearing, so the next retry finds the handle"
                        + " really released")
                .isEqualTo(3);
        assertThat(backend.openCount.get())
                .as("and only then does a fresh stream open")
                .isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void aProvisionSwapIsRefusedWhenTheOutgoingBackendsCloseDefersTheRelease() {
        StreamingProvision outgoingProvision = engine.getStreamingProvision();
        SynchronousTestBackend replacement = new SynchronousTestBackend("Replacement");
        StreamingProvision incomingProvision = provisionOf("Replacement", replacement);
        engine.startAudioOutput();
        backend.deferReleaseOnClose = true;
        engine.stop(); // setStreamingProvision refuses while the engine runs

        assertThatThrownBy(() -> engine.setStreamingProvision(incomingProvision))
                .as("pointing the engine at a new provision while the outgoing backend may"
                        + " still hold the device is exactly what this refusal exists for")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Stub")
                .hasMessageContaining("DEFERRED");

        assertThat(engine.getStreamingProvision())
                .as("the swap is refused WHOLE — the engine still points at the outgoing"
                        + " provision, so the outgoing backend stays reachable for the"
                        + " retry that is its only release path")
                .isSameAs(outgoingProvision);
        assertThat(replacement.openCount.get())
                .as("and nothing from the incoming provision was opened")
                .isZero();

        backend.completeDeferredRelease();

        engine.setStreamingProvision(incomingProvision);
        assertThat(engine.getStreamingProvision()).isSameAs(incomingProvision);
    }

    @Test
    void aPumpStartFailureWhoseCloseDefersTheReleaseKeepsTrackingTheHandle() {
        backend.failOnPumpStart = true;
        backend.deferReleaseOnClose = true;

        assertThatThrownBy(engine::startAudioOutput)
                .as("the start failure still propagates unchanged through the unwind")
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("pump start refused by Stub")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .as("a deferred release carries no exception, so nothing is"
                                + " suppressed onto the start failure")
                        .isEmpty());

        assertThat(backend.closeCount.get())
                .as("the unwind attempted the close")
                .isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();

        backend.failOnPumpStart = false;
        assertThatThrownBy(engine::startAudioOutput)
                .as("the handle is still TRACKED rather than forgotten, so the next start"
                        + " retries its release instead of opening over it")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("DEFERRED");
        assertThat(backend.openCount.get())
                .as("no second open reached the device")
                .isEqualTo(1);

        backend.completeDeferredRelease();
        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(backend.openCount.get()).isEqualTo(2);
    }

    @Test
    void aControlPanelDrainWhoseCloseDefersTheReleaseKeepsTrackingTheHandle() {
        engine.startAudioOutput();
        engine.beginControlPanelSession(backend);
        engine.stopAudioOutput(); // close DEFERRED by the panel, handle retained
        backend.deferReleaseOnClose = true;

        engine.endControlPanelSession(backend);

        assertThat(backend.closeCount.get())
                .as("the drain attempted the close the panel deferred")
                .isEqualTo(1);
        assertThat(engine.isStreamOpen()).isFalse();

        assertThatThrownBy(engine::startAudioOutput)
                .as("the drain met a handle that was not ready yet, so it left the engine"
                        + " tracking it — the next start is simply the next retry")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("DEFERRED");

        backend.completeDeferredRelease();
        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(backend.openCount.get()).isEqualTo(2);
    }

    @Test
    void aBackendThatCannotSayWhetherItReleasedIsTreatedAsStillHoldingTheDevice() {
        // The guarding decision in releaseDeferredBy. AudioBackend is
        // deliberately not sealed, so the engine cannot assume the query is
        // as well-behaved as the contract asks. A backend that cannot say
        // whether it gave the handle back has not shown that it did, and the
        // conservative answer costs one deferred close where the other would
        // forget a handle a driver may still hold.
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        first.failOnOpen = true;
        first.failOnReleasePendingQuery = true;
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("First", first, second));

        assertThatThrownBy(engine::startAudioOutput)
                .as("an unanswerable query is a non-release, so the walk is abandoned")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("First")
                .hasMessageContaining("could not be released")
                .satisfies(failure -> assertThat(failure.getCause())
                        .as("and the query failure never masks the actionable OPEN failure")
                        .isInstanceOf(AudioBackendException.class)
                        .hasMessage("open refused by First"));

        assertThat(second.openCount.get())
                .as("no fallback rung opened beside it")
                .isZero();
    }

    // ── Lifecycle serialization (story 316 re-review) ────────────────────

    @Test
    void twoCallersRacingOneOpenLeaveExactlyOneStreamAndNoOrphanedBackend()
            throws Exception {
        // startAudioOutput() is called straight from the FX thread on Play
        // while the app layer drives the same method from its device-event
        // and format-change workers. The quintet it mutates — state,
        // backend, device, negotiated format, pump — is a multi-step
        // transition, and volatile visibility gives it no atomicity: both
        // callers could observe CLOSED, open two different rungs, overwrite
        // each other's fields, and strand one stream on a device nobody
        // would ever close again.
        SynchronousTestBackend first = new SynchronousTestBackend("First");
        SynchronousTestBackend second = new SynchronousTestBackend("Second");
        engine.setStreamingProvision(provisionOf("First", first, second));

        CountDownLatch firstIsInsideOpen = new CountDownLatch(1);
        CountDownLatch secondCallReturned = new CountDownLatch(1);
        first.openBarrier = () -> {
            firstIsInsideOpen.countDown();
            awaitRaceWindow(secondCallReturned);
        };
        AtomicReference<Throwable> raceFailure = new AtomicReference<>();

        Thread opener = daemon("race-opener", engine::startAudioOutput, raceFailure);
        opener.start();
        assertThat(firstIsInsideOpen.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                .as("the first caller reached the device open")
                .isTrue();
        Thread racer = daemon("race-second-opener", () -> {
            try {
                engine.startAudioOutput();
            } finally {
                secondCallReturned.countDown();
            }
        }, raceFailure);
        racer.start();

        joinOrFail(opener);
        joinOrFail(racer);

        assertThat(first.openCount.get())
                .as("exactly one open reached the device")
                .isEqualTo(1);
        assertThat(second.openCount.get())
                .as("no second rung was opened behind the first caller's back")
                .isZero();
        assertThat(first.closeCount.get())
                .as("and nothing was closed to make room for a second open")
                .isZero();
        assertThat(first.pumpStarts.get())
                .as("exactly one render pump drives the one shared render pipeline")
                .isEqualTo(1);
        assertThat(engine.openStreamBackendName()).contains("First");
        assertThat(raceFailure.get())
                .as("neither caller failed: the loser simply found the stream running")
                .isNull();
    }

    @Test
    void aStopRacingAnOpenNeverLeavesAPumpRenderingIntoAClosedDevice()
            throws Exception {
        // The nastiest interleaving of the same race: the stop lands while
        // the open is starting its pump — after the state says RUNNING and
        // the backend is stored, but before the pump reference is published.
        // Unserialized, stopPump() then finds a null pump, reports confirmed
        // quiescence, closes the device and forgets the stream, and the open
        // finishes by publishing a live pump onto a CLOSED backend: a render
        // thread nobody tracks, streaming into a released handle.
        SynchronousTestBackend raced = new SynchronousTestBackend("Raced");
        engine.setStreamingProvision(provisionOf("Raced", raced));

        CountDownLatch insidePumpStart = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        raced.pumpStartBarrier = () -> {
            insidePumpStart.countDown();
            awaitRaceWindow(stopReturned);
        };
        AtomicReference<Throwable> raceFailure = new AtomicReference<>();

        Thread opener = daemon("stop-race-opener", engine::startAudioOutput, raceFailure);
        opener.start();
        assertThat(insidePumpStart.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                .as("the opening caller reached the pump start")
                .isTrue();
        Thread stopper = daemon("stop-race-stopper", () -> {
            try {
                engine.stopAudioOutput();
            } finally {
                stopReturned.countDown();
            }
        }, raceFailure);
        stopper.start();

        joinOrFail(opener);
        joinOrFail(stopper);

        assertThat(engine.isStreamOpen())
                .as("the stop ran whole, after the open: no stream is reported open")
                .isFalse();
        assertThat(raced.isOpen())
                .as("and the device was really released — never a stream the engine forgot")
                .isFalse();
        // Absence needs a window, not a condition to await: an orphaned pump
        // would push blocks into the closed backend for as long as it lives.
        int sunkAtRest = raced.sunkBlockCount.get();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(QUIET_WINDOW_MILLIS));
        assertThat(raced.sunkBlockCount.get())
                .as("nothing still renders into a device the engine has closed")
                .isEqualTo(sunkAtRest);
        assertThat(raceFailure.get())
                .as("neither caller failed")
                .isNull();
    }

    @Test
    void aStreamOpenListenerTakingASecondLockCannotDeadlockTheLifecycle()
            throws Exception {
        // Regression guard for the lock-order inversion that serializing the
        // lifecycle could otherwise introduce. The app layer's stream-open
        // listener binds device events from a SYNCHRONIZED method, while
        // that same monitor's reconfigure path calls back into the engine's
        // lifecycle. Notifying the listener while the engine's lifecycle
        // lock is held therefore gives one thread [engine → app] and the
        // other [app → engine]: a guaranteed deadlock. The announcement must
        // be delivered only AFTER the lock is released.
        //
        // Deliberately NOT the shared engine field: a regression wedges both
        // threads for good, and @AfterEach must not join them.
        AudioEngine racedEngine = new AudioEngine(FORMAT);
        SynchronousTestBackend racedBackend = new SynchronousTestBackend("Listener");
        racedEngine.setStreamingProvision(provisionOf("Listener", racedBackend));
        Object appMonitor = new Object();
        AtomicInteger listenerEntries = new AtomicInteger();
        racedEngine.setStreamOpenListener((backend, device) -> {
            synchronized (appMonitor) {
                listenerEntries.incrementAndGet();
            }
        });

        CountDownLatch insideOpen = new CountDownLatch(1);
        CountDownLatch appMonitorHeld = new CountDownLatch(1);
        racedBackend.openBarrier = () -> {
            insideOpen.countDown();
            awaitRaceWindow(appMonitorHeld);
        };
        AtomicReference<Throwable> raceFailure = new AtomicReference<>();

        Thread play = daemon("listener-deadlock-play",
                racedEngine::startAudioOutput, raceFailure);
        Thread appThread = daemon("listener-deadlock-app", () -> {
            synchronized (appMonitor) {
                appMonitorHeld.countDown();
                racedEngine.stopAudioOutput();
            }
        }, raceFailure);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            play.start();
            assertThat(insideOpen.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS))
                    .as("the opening thread reached the device open")
                    .isTrue();
            appThread.start();
            play.join(GUARD_BUDGET_MILLIS);
            appThread.join(GUARD_BUDGET_MILLIS);
            assertThat(play.isAlive())
                    .as("the opening thread finished: its stream-open listener runs"
                            + " only after the engine's lifecycle lock is released")
                    .isFalse();
            assertThat(appThread.isAlive())
                    .as("and the thread holding the app-layer monitor was never"
                            + " blocked behind that listener")
                    .isFalse();
        });

        assertThat(listenerEntries.get())
                .as("the listener really did run — the open was not skipped")
                .isEqualTo(1);
        assertThat(raceFailure.get())
                .as("neither thread failed")
                .isNull();
        // Only reached when nothing deadlocked, so this can never hang.
        racedEngine.stopAudioOutput();
        racedEngine.stop();
    }

    // ── Async await support ──────────────────────────────────────────────

    /** Creates an unstarted DAEMON thread: a wedged run must never hold the JVM. */
    private static Thread daemon(String name, Runnable body,
                                 AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().name(name).daemon(true).unstarted(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
    }

    private static void joinOrFail(Thread thread) throws InterruptedException {
        thread.join(GUARD_BUDGET_MILLIS);
        assertThat(thread.isAlive())
                .as("thread '" + thread.getName() + "' finished within "
                        + GUARD_BUDGET_MILLIS + " ms")
                .isFalse();
    }

    /**
     * Parks a racing thread inside a backend call just long enough for the
     * other thread to reach the same transition. Bounded on purpose: when
     * the lifecycle IS serialized the other thread is blocked on the
     * engine's lock and can never signal, so this must proceed on its own.
     */
    private static void awaitRaceWindow(CountDownLatch signal) {
        try {
            signal.await(RACE_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


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
        /**
         * Optional CROSS-backend timeline (story 316 re-review): when two
         * rungs share one list, its {@code name:event} entries make the
         * order of one backend's close against another backend's open
         * assertable — something per-backend counters cannot express.
         */
        volatile List<String> timeline;
        /**
         * Optional ONE-SHOT gate run inside {@code open()}, before the
         * handle is taken (story 316 re-review). Parking a caller there is
         * the only way to make a two-thread lifecycle race wide enough to
         * assert an INVARIANT on rather than a timing. Cleared as it is
         * read so a racing second caller runs straight through.
         */
        volatile Runnable openBarrier;
        /** The same one-shot gate inside {@code inputBlocks()} — a pump start. */
        volatile Runnable pumpStartBarrier;
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger pumpStarts = new AtomicInteger();
        final AtomicInteger sunkBlockCount = new AtomicInteger();
        final AtomicInteger lastSunkChannels = new AtomicInteger();
        final AtomicInteger lastSunkFrames = new AtomicInteger();
        volatile boolean failOnOpen;
        volatile boolean failOnPumpStart;
        volatile boolean failOnClose;
        /**
         * Story 316 re-review: {@code close()} returns NORMALLY but the
         * handle does not come back. Models {@code AsioBackend} deferring its
         * driver-shim release while the asio-control thread is still inside a
         * downcall that outlived its budget — the Java-side fields are
         * cleared, so the close looks ordinary, while
         * {@link AudioBackend#isReleasePending()} reports the truth.
         *
         * <p>While this is set, EVERY close defers, which is what a driver
         * that is still wedged does. {@link #completeDeferredRelease()} is
         * the deferred release finally landing.</p>
         */
        volatile boolean deferReleaseOnClose;
        /**
         * Story 316 re-review: {@code isReleasePending()} itself throws. The
         * interface is deliberately not sealed, so the engine cannot assume
         * the query is as well-behaved as the contract asks; this pins what
         * it does when a backend cannot answer.
         */
        volatile boolean failOnReleasePendingQuery;
        /** Set by a DEFERRED close, cleared when that release completes. */
        private volatile boolean releaseDeferred;
        volatile boolean blockAwait;
        /** Story 316 review: what the engine-side streaming guard sees. */
        volatile boolean streamingSupported = true;
        /**
         * Story 316 review: whether the stream this fake opens has CAPTURE.
         *
         * <p>Default {@code true}, which is the realistic configuration for
         * a full-duplex interface and the one the record-path tests need:
         * {@link AudioBackend#openedInputChannels()} then answers with the
         * opened format's channel count, so the engine's post-open
         * verification sees a live capture stream. Clearing it models a
         * PLAYBACK-ONLY head — a speakers-only interface, an ASIO driver
         * exposing no inputs, or an adapter whose duplex open degraded — and
         * is what makes "a REQUIRED open refuses this rung and walks on"
         * assertable.</p>
         */
        volatile boolean capturesInput = true;
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

        /** Runs a race gate once, clearing it first so it cannot re-trap. */
        private void crossOnce(Runnable barrier) {
            if (barrier != null) {
                barrier.run();
            }
        }

        private void record(String event) {
            events.add(event);
            List<String> shared = this.timeline;
            if (shared != null) {
                shared.add(name + ":" + event);
            }
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
            record("open");
            openCount.incrementAndGet();
            Runnable gate = openBarrier;
            openBarrier = null;
            crossOnce(gate);
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
            record("pumpStart");
            pumpStarts.incrementAndGet();
            Runnable gate = pumpStartBarrier;
            pumpStartBarrier = null;
            crossOnce(gate);
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

        /**
         * Story 316 review: the capture channels the OPEN stream really has.
         * A duplex fake ({@link #capturesInput}, the default) answers with
         * the opened format's channel count; a playback-only one answers
         * {@code 0}, which is what the engine's REQUIRED-open verification
         * turns into a failed ladder hop.
         */
        @Override
        public int openedInputChannels() {
            com.benesquivelmusic.daw.sdk.audio.AudioFormat opened = lastOpenFormat;
            return open && capturesInput && opened != null ? opened.channels() : 0;
        }

        @Override
        public boolean isReleasePending() {
            if (failOnReleasePendingQuery) {
                throw new IllegalStateException(
                        name + " cannot say whether its close released the handle");
            }
            return releaseDeferred;
        }

        /**
         * The deferred driver teardown finally completing: the condition is
         * transient and SELF-CLEARING, so a later close finds the handle
         * really released and the engine's retry succeeds.
         */
        void completeDeferredRelease() {
            deferReleaseOnClose = false;
            releaseDeferred = false;
        }

        @Override
        public void close() {
            record("close");
            closeCount.incrementAndGet();
            if (failOnClose) {
                throw new AudioBackendException("close refused by " + name);
            }
            // The Java-side fields go back whatever the native release did —
            // which is exactly why a normal return is not proof of a release.
            open = false;
            if (deferReleaseOnClose) {
                releaseDeferred = true;
            }
        }
    }
}
