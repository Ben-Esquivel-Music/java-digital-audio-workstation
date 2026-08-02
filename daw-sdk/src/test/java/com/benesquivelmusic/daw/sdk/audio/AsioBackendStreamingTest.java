package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Story 311 lifecycle tests for the streaming path wired into
 * {@link AsioBackend}: {@code open} &rarr; install the story-218
 * {@code asioMessage} upcall / {@code createBuffers} /
 * {@code installBufferSwitchCallback} / {@code start}, and {@code close}
 * &rarr; {@code stop} / {@code disposeBuffers} /
 * {@code uninstallBufferSwitchCallback}, in that order.
 *
 * <p>Uses the established stubbing idiom — a
 * {@code private final class Stub… extends Asio…Shim} injected through
 * the backend's static factory seams and reset in {@code @AfterEach} (see
 * {@code AsioBackendDriverLifecycleTest} / {@code AsioBackendCapabilityTest}).
 * Every stub calls {@code super.close()} so the real superclass's
 * {@code Arena.ofShared()} is released and the asserted call sequence is the
 * shape production actually has.</p>
 */
class AsioBackendStreamingTest {

    private static final DeviceId DRIVER_A = new DeviceId("ASIO", "Driver A");
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 32);
    private static final int FRAMES = 128;
    private static final long AWAIT_SECONDS = 10;

    private Arena arena;
    private List<String> calls;
    private StubDriverShim driver;
    private AsioBackend backend;
    private MemorySegment[][] inputBuffers;
    private MemorySegment[][] outputBuffers;
    private List<AsioStreamingShim.BufferInfo> bufferInfos;

    // Behaviour switches read by every StubStreamingShim the factory creates.
    private boolean streamingAvailable = true;
    private boolean createSucceeds = true;
    private boolean startSucceeds = true;
    private boolean stopSucceeds = true;
    private boolean disposeSucceeds = true;
    private boolean resetFromInsideCreateBuffers;

    // Capture of AsioBackend's own logger, for the teardown-refusal warnings.
    private Logger backendLog;
    private Handler logCapture;
    private List<LogRecord> logRecords;
    private boolean logUsedParentHandlers;
    private Level logLevel;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        calls = Collections.synchronizedList(new ArrayList<>());
        bufferInfos = driverBufferInfos();
        driver = new StubDriverShim();
        AsioBackend.setDriverShimFactory(() -> driver);
        AsioBackend.setStreamingShimFactory(StubStreamingShim::new);
        backend = new AsioBackend();
        installLogCapture();
    }

    @AfterEach
    void tearDown() {
        backend.close();
        AsioBackend.resetDriverShimFactory();
        AsioBackend.resetStreamingShimFactory();
        AsioBackend.resetCapabilityShimFactory();
        removeLogCapture();
        arena.close();
    }

    // ------------------------------------------------------------------
    // Open / close ordering
    // ------------------------------------------------------------------

    /**
     * The story's headline ordering assertion. {@code asioMessageShimInstalled}
     * is sampled from live backend state <em>inside</em> the
     * {@code createBuffers} stand-in, so it genuinely proves the story-218
     * upcall is registered before {@code ASIOCreateBuffers} runs — real drivers
     * issue {@code kAsioEngineVersion} / {@code kAsioSelectorSupported} /
     * {@code kAsioSupportsTimeInfo} synchronously from inside that call, and an
     * unanswered engine-version query makes them fall back to ASIO 1.0.
     */
    @Test
    void openInstallsTheMessageCallbackBeforeCreatingBuffersThenStarts() {
        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(backend.isOpen()).isTrue();
        assertThat(calls).containsExactly(
                "asioMessageShimInstalled=true",
                "createBuffers:in=[0, 1]:out=[0, 1]:frames=128",
                "getBufferInfos",
                "installBufferSwitchCallback",
                "start");
        assertThat(backend.activeBufferSwitchShim()).isNotNull();
        assertThat(backend.activeBufferSwitchShim().bufferFrames()).isEqualTo(FRAMES);
        assertThat(backend.activeFormatChangeShim()).isNotNull();
    }

    @Test
    void closeStopsDisposesThenUninstallsBeforeTheDriverUnload() {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        calls.clear();

        backend.close();

        assertThat(calls).containsExactly(
                "stop", "disposeBuffers", "uninstallBufferSwitchCallback", "close",
                "unload");
        assertThat(backend.isOpen()).isFalse();
        assertThat(backend.activeBufferSwitchShim()).isNull();
        assertThat(backend.activeFormatChangeShim()).isNull();
    }

    /**
     * Story 311 (G1): {@code asioshim_stop} no longer always answers "OK" — it
     * reports success only for the genuine "nothing to do" cases (not started,
     * no buffers, no driver loaded) and failure when {@code ASIOStop} was
     * actually issued and the driver refused it. A refusal must be logged, and
     * the teardown must still run to completion.
     *
     * <p>Continuing is the safe response, not a compromise: the native shim
     * drains its in-flight callback barrier before {@code ASIODisposeBuffers}
     * and again inside {@code uninstallAsioBufferSwitchCallback}, after which
     * the trampoline loads a null callback and returns without touching the
     * upcall stub. Aborting would skip the very uninstall that protects the
     * arena, and would leak that arena plus the {@code asio-input-drain} thread
     * for the life of the process.</p>
     */
    @Test
    void aRefusedAsioStopIsLoggedAndTheTeardownStillCompletesInOrder() {
        stopSucceeds = false;
        backend.open(DRIVER_A, FORMAT, FRAMES);
        calls.clear();

        backend.close();

        assertThat(calls)
                .as("a driver that refuses ASIOStop must still reach dispose, "
                        + "uninstall and the driver unload, in order")
                .containsExactly(
                        "stop", "disposeBuffers", "uninstallBufferSwitchCallback",
                        "close", "unload");
        assertThat(backend.isOpen()).isFalse();
        assertThat(backend.activeBufferSwitchShim()).isNull();
        assertThat(driver.unloaded).isTrue();
        assertWarningLogged("refused ASIOStop during teardown", "Driver A");
    }

    @Test
    void closeIsIdempotent() {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        backend.close();
        calls.clear();

        assertThatCode(backend::close).doesNotThrowAnyException();
        assertThat(calls).isEmpty();
    }

    /**
     * Story 311 (S5): the shim's own {@code close()} releases its FFM arena and
     * nothing else. {@link AsioBackend} owns the
     * stop &rarr; dispose &rarr; uninstall ordering, so repeating it here would
     * make the headline ordering test above assert a shape production does not
     * have.
     */
    @Test
    void streamingShimCloseReleasesOnlyItsArenaAndNeverRepeatsTheTeardown() {
        AsioStreamingShim shim = new StubStreamingShim();
        calls.clear();

        shim.close();

        assertThat(calls)
                .as("close() must not re-run stop / disposeBuffers / uninstall")
                .containsExactly("close");
    }

    // ------------------------------------------------------------------
    // Buffer-size negotiation (stories 213 / 220)
    // ------------------------------------------------------------------

    @Test
    void bufferSizeOutsideTheDriverReportedRangeIsRejectedWithoutResizing() {
        // Driver reports {min 96, max 384, granularity 96}; 128 is off-ladder.
        AsioBackend.setCapabilityShimFactory(
                () -> StubCapabilityShim.withRange(new BufferSizeRange(96, 384, 192, 96)));

        assertThatThrownBy(() -> backend.open(DRIVER_A, FORMAT, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("rejected buffer size 128")
                .hasMessageContaining("min=96")
                .hasMessageContaining("max=384")
                .hasMessageContaining("preferred=192")
                .hasMessageContaining("granularity=96");
        assertThat(backend.isOpen()).isFalse();
        assertThat(driver.unloaded).isTrue();
        assertThat(calls)
                .as("no buffers may be created when the size was rejected")
                .containsExactly("unload");
    }

    @Test
    void bufferSizeOnTheDriverReportedLadderIsAccepted() {
        AsioBackend.setCapabilityShimFactory(
                () -> StubCapabilityShim.withRange(new BufferSizeRange(96, 384, 192, 96)));

        backend.open(DRIVER_A, FORMAT, 192);

        assertThat(backend.isOpen()).isTrue();
        assertThat(calls).contains("start");
    }

    // ------------------------------------------------------------------
    // Channel negotiation (S1 / F9)
    // ------------------------------------------------------------------

    @Test
    void aPlaybackOnlyDriverIsAskedForNoInputChannels() {
        AsioBackend.setCapabilityShimFactory(
                () -> StubCapabilityShim.withChannels(0, 8));

        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(backend.isOpen()).isTrue();
        assertThat(calls)
                .as("requesting phantom inputs makes ASIOCreateBuffers fail on "
                        + "playback-only devices")
                .contains("createBuffers:in=[]:out=[0, 1]:frames=128");
    }

    @Test
    void aCaptureOnlyDriverIsAskedForNoOutputChannels() {
        AsioBackend.setCapabilityShimFactory(
                () -> StubCapabilityShim.withChannels(8, 0));

        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(backend.isOpen()).isTrue();
        assertThat(calls).contains("createBuffers:in=[0, 1]:out=[]:frames=128");
    }

    @Test
    void eachDirectionIsClampedToTheDriverReportedChannelCount() {
        AsioBackend.setCapabilityShimFactory(
                () -> StubCapabilityShim.withChannels(1, 1));

        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(calls).contains("createBuffers:in=[0]:out=[0]:frames=128");
    }

    @Test
    void aCombinedChannelRequestAboveTheNativeCapIsRejectedUpFront() {
        AudioFormat wide = new AudioFormat(48_000.0, 40, 32);
        AsioBackend.setCapabilityShimFactory(
                () -> StubCapabilityShim.withChannels(40, 40));

        assertThatThrownBy(() -> backend.open(DRIVER_A, wide, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("40 input(s) + 40 output(s)")
                .hasMessageContaining("64")
                .hasMessageContaining("32");
        assertThat(backend.isOpen()).isFalse();
        assertThat(driver.unloaded).isTrue();
        assertThat(calls)
                .as("the cap must be enforced before ASIOCreateBuffers is called")
                .noneMatch(call -> call.startsWith("createBuffers"));
    }

    // ------------------------------------------------------------------
    // Degradation and rollback
    // ------------------------------------------------------------------

    @Test
    void unavailableStreamingShimLeavesTheStory310OpenPathUntouched() {
        streamingAvailable = false;

        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(backend.isOpen()).isTrue();
        assertThat(backend.activeBufferSwitchShim()).isNull();
        assertThat(calls).containsExactly("close");

        backend.close();
        assertThat(backend.isOpen()).isFalse();
        assertThat(calls).containsExactly("close", "unload");
    }

    @Test
    void failedCreateBuffersRollsBackAndLeavesTheBackendClosed() {
        createSucceeds = false;

        assertThatThrownBy(() -> backend.open(DRIVER_A, FORMAT, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASIOCreateBuffers");
        assertThat(backend.isOpen()).isFalse();
        assertThat(driver.unloaded).isTrue();
        assertThat(calls).containsSubsequence(
                "createBuffers:in=[0, 1]:out=[0, 1]:frames=128",
                "stop", "disposeBuffers", "uninstallBufferSwitchCallback",
                "close", "unload");
        assertThat(backend.activeFormatChangeShim())
                .as("a failed open must not leave the asioMessage upcall registered")
                .isNull();
    }

    @Test
    void failedStartRollsBackAndTearsTheDriverDownInOrder() {
        startSucceeds = false;

        assertThatThrownBy(() -> backend.open(DRIVER_A, FORMAT, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASIOStart failed");
        assertThat(backend.isOpen()).isFalse();
        assertThat(calls).containsSubsequence(
                "start", "stop", "disposeBuffers",
                "uninstallBufferSwitchCallback", "close");
        assertThat(driver.unloaded).isTrue();
        assertThat(backend.activeBufferSwitchShim()).isNull();
    }

    /**
     * Story 311 (G1), rollback counterpart: {@code rollbackOpen(...)} discards
     * the same two status codes {@code close()} does, so it gets the same
     * treatment — log the refusal, then finish the teardown.
     */
    @Test
    void aRefusedTeardownDuringRollbackStillUninstallsAndUnloads() {
        startSucceeds = false;
        stopSucceeds = false;
        disposeSucceeds = false;

        assertThatThrownBy(() -> backend.open(DRIVER_A, FORMAT, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("ASIOStart failed");

        assertThat(calls).containsSubsequence(
                "start", "stop", "disposeBuffers",
                "uninstallBufferSwitchCallback", "close", "unload");
        assertThat(backend.isOpen()).isFalse();
        assertThat(driver.unloaded).isTrue();
        assertWarningLogged(
                "refused ASIOStop and ASIODisposeBuffers during teardown", "Driver A");
    }

    @Test
    void emptyBufferInfoListRollsBack() {
        bufferInfos = List.of();

        assertThatThrownBy(() -> backend.open(DRIVER_A, FORMAT, FRAMES))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no buffer descriptors");
        assertThat(backend.isOpen()).isFalse();
        assertThat(driver.unloaded).isTrue();
    }

    // ------------------------------------------------------------------
    // sink(...)
    // ------------------------------------------------------------------

    @Test
    void sinkBeforeOpenValidatesAndDoesNotThrow() {
        AudioBlock block = AudioBlock.silence(48_000.0, 2, FRAMES);

        assertThatCode(() -> backend.sink(block)).doesNotThrowAnyException();
        assertThat(calls).isEmpty();
    }

    @Test
    void sinkAfterOpenReachesTheDriverOutputBuffersOnTheNextCallback() {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        float[] samples = new float[2 * FRAMES];
        for (int frame = 0; frame < FRAMES; frame++) {
            samples[frame * 2] = 0.5f;
            samples[frame * 2 + 1] = -0.5f;
        }
        backend.sink(new AudioBlock(48_000.0, 2, FRAMES, samples));

        backend.activeBufferSwitchShim().bufferSwitch(0, 1);

        assertThat(readDriverBuffer(outputBuffers[0][0])).containsOnly(0.5f);
        assertThat(readDriverBuffer(outputBuffers[0][1])).containsOnly(-0.5f);
    }

    /**
     * Story 311 (S7): an engine rendering 64-frame blocks into a 128-frame ASIO
     * stream would otherwise produce half-audio / half-silence buffers with no
     * diagnostic. The story requires a buffer-size mismatch to surface as an
     * {@link AudioBackendException} "rather than silently resizing".
     */
    @Test
    void sinkRejectsABlockWhoseFrameCountDoesNotMatchTheOpenedBufferSize() {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        AudioBlock halfSized = AudioBlock.silence(48_000.0, 2, FRAMES / 2);

        assertThatThrownBy(() -> backend.sink(halfSized))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("rejected block size 64")
                .hasMessageContaining("128 frames per buffer");
    }

    // ------------------------------------------------------------------
    // Driver reset (stories 218 x 311)
    // ------------------------------------------------------------------

    @Test
    void stopStreamingForDriverResetQuiescesTheBridgeAndTearsDownAsynchronously()
            throws Exception {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        calls.clear();

        backend.stopStreamingForDriverReset();

        assertThat(backend.activeBufferSwitchShim().isStreaming()).isFalse();
        awaitCall("disposeBuffers");
        assertThat(calls).containsExactly("stop", "disposeBuffers");
    }

    @Test
    void queuedResetTeardownSkipsAStreamThatWasClosedAndReopened() {
        List<Runnable> queuedTeardowns = new ArrayList<>();
        backend.close();
        backend = new AsioBackend(queuedTeardowns::add);
        calls.clear();

        backend.open(DRIVER_A, FORMAT, FRAMES);
        backend.stopStreamingForDriverReset();
        assertThat(queuedTeardowns).hasSize(1);

        backend.close();
        backend.open(DRIVER_A, FORMAT, FRAMES);
        assertThat(backend.activeBufferSwitchShim().isStreaming()).isTrue();
        calls.clear();
        logRecords.clear();
        stopSucceeds = false;
        disposeSucceeds = false;

        queuedTeardowns.getFirst().run();

        assertThat(calls)
                .as("a reset task captured from the previous stream must make no native calls")
                .isEmpty();
        assertThat(logRecords)
                .as("a stale reset task must not report refusals from an obsolete shim")
                .noneMatch(record -> Level.WARNING.equals(record.getLevel()));
        assertThat(backend.activeBufferSwitchShim().isStreaming())
                .as("the reopened stream remains current and running")
                .isTrue();
    }

    /**
     * Story 311 (S3): a reset does not close the backend — the driver is still
     * loaded, and short-circuiting {@code close()} would leak the driver shim.
     * The documented contract is therefore reset &rarr; {@code close()} &rarr;
     * {@code open()}, and the reopen must re-create the driver's buffers.
     */
    @Test
    void afterAResetTheBackendMustBeClosedBeforeItCanBeReopened() throws Exception {
        backend.open(DRIVER_A, FORMAT, FRAMES);

        backend.stopStreamingForDriverReset();
        awaitCall("disposeBuffers");

        assertThat(backend.isOpen())
                .as("a reset leaves the driver loaded, so the backend stays open")
                .isTrue();
        assertThatThrownBy(() -> backend.open(DRIVER_A, FORMAT, FRAMES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an open stream");

        backend.close();
        assertThat(backend.isOpen()).isFalse();
        calls.clear();

        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(backend.isOpen()).isTrue();
        assertThat(calls).containsExactly(
                "asioMessageShimInstalled=true",
                "createBuffers:in=[0, 1]:out=[0, 1]:frames=128",
                "getBufferInfos",
                "installBufferSwitchCallback",
                "start");
        assertThat(backend.activeBufferSwitchShim().isStreaming()).isTrue();
    }

    /**
     * Story 311 (B3, was S4): the {@code asioMessage} upcall is now live before
     * {@code ASIOCreateBuffers}, so a driver can dispatch
     * {@code kAsioResetRequest} from inside it — before either streaming field
     * is published. Without the latch the reset would silently no-op and
     * {@code open()} would go on to start a driver that just asked to be reset.
     */
    @Test
    void aResetDispatchedFromInsideCreateBuffersIsAppliedOnceTheStreamIsLive()
            throws Exception {
        resetFromInsideCreateBuffers = true;

        backend.open(DRIVER_A, FORMAT, FRAMES);

        assertThat(backend.activeBufferSwitchShim())
                .as("open() still completes; the stream is simply quiesced")
                .isNotNull();
        assertThat(backend.activeBufferSwitchShim().isStreaming())
                .as("open() must re-run the teardown the early reset could only latch")
                .isFalse();
        awaitCall("disposeBuffers");
        assertThat(calls).containsSubsequence("start", "stop", "disposeBuffers");
    }

    /**
     * Story 311 (G1), reset counterpart: the asynchronous reset teardown runs
     * the same two calls and would otherwise discard the same information. It
     * frees nothing — the upcall stays installed and the bridge alive — so a
     * refusal here is purely a diagnostic.
     */
    @Test
    void aRefusedDisposeDuringADriverResetIsLogged() throws Exception {
        disposeSucceeds = false;
        backend.open(DRIVER_A, FORMAT, FRAMES);
        calls.clear();

        backend.stopStreamingForDriverReset();

        awaitCall("disposeBuffers");
        assertThat(calls).containsExactly("stop", "disposeBuffers");
        awaitWarning("refused ASIODisposeBuffers during teardown");
    }

    @Test
    void stopStreamingForDriverResetIsSafeWhenNothingIsStreaming() {
        assertThatCode(backend::stopStreamingForDriverReset)
                .doesNotThrowAnyException();
        assertThat(calls).isEmpty();
    }

    /** N10: the opened-then-already-torn-down case, not just the null branch. */
    @Test
    void stopStreamingForDriverResetIsSafeAfterTheBackendWasClosed() {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        backend.close();
        calls.clear();

        assertThatCode(backend::stopStreamingForDriverReset)
                .doesNotThrowAnyException();

        assertThat(calls)
                .as("both shims are already released, so there is nothing to tear down")
                .isEmpty();
    }

    @Test
    void repeatedResetsAreIdempotent() throws Exception {
        backend.open(DRIVER_A, FORMAT, FRAMES);
        calls.clear();

        backend.stopStreamingForDriverReset();
        backend.stopStreamingForDriverReset();
        awaitCall("disposeBuffers");

        assertThat(backend.activeBufferSwitchShim().isStreaming()).isFalse();
        assertThatCode(backend::close).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Production shim degradation
    // ------------------------------------------------------------------

    @Test
    void productionStreamingShimConstructsAndClosesIdempotentlyOnEveryHost() {
        AsioStreamingShim shim = new AsioStreamingShim();
        try {
            assertThatCode(shim::uninstallBufferSwitchCallback)
                    .doesNotThrowAnyException();
            assertThat(shim.installBufferSwitchCallback(MemorySegment.NULL)).isFalse();
        } finally {
            shim.close();
            shim.close();
        }
    }

    /**
     * Story 311 (S6): gated, because on the {@code windows-asioshim.yml} lane —
     * the one lane this story exists to serve — {@code asioshim.dll} <em>is</em>
     * discoverable and the shim is expected to resolve. Only the no-library
     * degradation path is asserted here.
     */
    @Test
    void productionStreamingShimIsUnavailableWhenAsioshimLibraryIsMissing() {
        assumeFalse(asioshimAvailable(),
                "asioshim is on the FFM library path — the missing-library "
                        + "degradation path is not exercisable on this host");
        AsioStreamingShim shim = new AsioStreamingShim();
        try {
            assertThat(shim.isStreamingAvailable()).isFalse();
            assertThat(shim.createBuffers(new int[] {0}, new int[] {0}, 128)).isFalse();
            assertThat(shim.getBufferInfos()).isEmpty();
            assertThat(shim.start()).isFalse();
            assertThat(shim.stop()).isFalse();
            assertThat(shim.disposeBuffers()).isFalse();
        } finally {
            shim.close();
        }
    }

    /**
     * Lightweight FFM probe for the {@code asioshim} library, copied from
     * {@code AsioFormatChangeShimTest}.
     */
    private static boolean asioshimAvailable() {
        try (Arena probe = Arena.ofConfined()) {
            SymbolLookup.libraryLookup("asioshim", probe);
            return true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /**
     * Captures {@link AsioBackend}'s own {@code java.util.logging} output for
     * the duration of one test, following the idiom in
     * {@code AsioBackendCapabilityTest#shimAbsenceIsLoggedExactlyOncePerProcess}.
     * Parent handlers are muted so the expected teardown warnings do not spam
     * the build log.
     */
    private void installLogCapture() {
        logRecords = new CopyOnWriteArrayList<>();
        logCapture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override public void flush() { }

            @Override public void close() { }
        };
        backendLog = Logger.getLogger(AsioBackend.class.getName());
        logUsedParentHandlers = backendLog.getUseParentHandlers();
        logLevel = backendLog.getLevel();
        backendLog.addHandler(logCapture);
        backendLog.setUseParentHandlers(false);
        backendLog.setLevel(Level.ALL);
    }

    private void removeLogCapture() {
        backendLog.removeHandler(logCapture);
        backendLog.setUseParentHandlers(logUsedParentHandlers);
        backendLog.setLevel(logLevel);
    }

    private void assertWarningLogged(String... fragments) {
        assertThat(logRecords)
                .as("a teardown the driver refused must be logged at WARNING "
                        + "containing %s", java.util.Arrays.toString(fragments))
                .anySatisfy(record -> {
                    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                    assertThat(record.getMessage()).contains(fragments);
                });
    }

    /** {@link #assertWarningLogged} for the asynchronous reset teardown. */
    private void awaitWarning(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline && !warningLogged(fragment)) {
            Thread.sleep(5);
        }
        assertWarningLogged(fragment);
    }

    private boolean warningLogged(String fragment) {
        return logRecords.stream().anyMatch(
                record -> Level.WARNING.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(fragment));
    }

    private void awaitCall(String call) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline && !calls.contains(call)) {
            Thread.sleep(5);
        }
        assertThat(calls)
                .as("the asynchronous reset teardown must reach %s", call)
                .contains(call);
    }

    private List<AsioStreamingShim.BufferInfo> driverBufferInfos() {
        inputBuffers = new MemorySegment[2][2];
        outputBuffers = new MemorySegment[2][2];
        List<AsioStreamingShim.BufferInfo> infos = new ArrayList<>();
        for (int channel = 0; channel < 2; channel++) {
            inputBuffers[0][channel] = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
            inputBuffers[1][channel] = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
            infos.add(new AsioStreamingShim.BufferInfo(channel, true, 19,
                    inputBuffers[0][channel].address(),
                    inputBuffers[1][channel].address()));
        }
        for (int channel = 0; channel < 2; channel++) {
            outputBuffers[0][channel] = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
            outputBuffers[1][channel] = arena.allocate(ValueLayout.JAVA_FLOAT, FRAMES);
            infos.add(new AsioStreamingShim.BufferInfo(channel, false, 19,
                    outputBuffers[0][channel].address(),
                    outputBuffers[1][channel].address()));
        }
        return List.copyOf(infos);
    }

    private static float[] readDriverBuffer(MemorySegment buffer) {
        float[] values = new float[FRAMES];
        for (int frame = 0; frame < FRAMES; frame++) {
            values[frame] = buffer.get(ValueLayout.JAVA_FLOAT_UNALIGNED,
                    (long) frame * Float.BYTES);
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------

    /**
     * Records every streaming call. An inner (non-static) class so the
     * {@code createBuffers} stand-in can sample live {@link AsioBackend} state
     * exactly as a real driver's synchronous callbacks would see it.
     */
    private final class StubStreamingShim extends AsioStreamingShim {

        private boolean closed;

        @Override
        boolean isStreamingAvailable() {
            return streamingAvailable && !closed;
        }

        @Override
        boolean createBuffers(int[] inputChannels, int[] outputChannels, int bufferFrames) {
            calls.add("asioMessageShimInstalled="
                    + (backend.activeFormatChangeShim() != null));
            calls.add("createBuffers:in=" + java.util.Arrays.toString(inputChannels)
                    + ":out=" + java.util.Arrays.toString(outputChannels)
                    + ":frames=" + bufferFrames);
            if (resetFromInsideCreateBuffers) {
                // Real drivers dispatch host callbacks from inside
                // ASIOCreateBuffers; kAsioResetRequest routes here.
                backend.stopStreamingForDriverReset();
            }
            return createSucceeds;
        }

        @Override
        List<BufferInfo> getBufferInfos() {
            calls.add("getBufferInfos");
            return bufferInfos;
        }

        @Override
        boolean start() {
            calls.add("start");
            return startSucceeds;
        }

        @Override
        boolean stop() {
            calls.add("stop");
            return stopSucceeds;
        }

        @Override
        boolean disposeBuffers() {
            calls.add("disposeBuffers");
            // Independent of stopSucceeds on purpose: asioshim_stop clears the
            // native "started" flag before it calls ASIOStop, so the stop that
            // asioshim_disposeBuffers performs first is then a successful
            // "nothing to do" even when the driver refused the real one.
            return disposeSucceeds;
        }

        @Override
        boolean installBufferSwitchCallback(MemorySegment upcallStub) {
            calls.add("installBufferSwitchCallback");
            return true;
        }

        @Override
        void uninstallBufferSwitchCallback() {
            calls.add("uninstallBufferSwitchCallback");
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                calls.add("close");
            }
            // Release the real superclass's Arena.ofShared() rather than
            // leaking one per stub instance.
            super.close();
        }
    }

    private final class StubDriverShim extends AsioDriverShim {

        private boolean loaded;
        private boolean unloaded;

        @Override
        boolean isEnumerationAvailable() {
            return true;
        }

        @Override
        boolean isLifecycleAvailable() {
            return true;
        }

        @Override
        List<DriverDescriptor> listDrivers() {
            return List.of(new DriverDescriptor("Driver A",
                    "{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}"));
        }

        @Override
        boolean loadDriver(String driverName) {
            loaded = true;
            return true;
        }

        @Override
        Optional<DriverInfo> getDriverInfo() {
            return loaded
                    ? Optional.of(new DriverInfo(2, 1, "Driver A", "ready"))
                    : Optional.empty();
        }

        @Override
        public void close() {
            if (loaded) {
                loaded = false;
                unloaded = true;
                calls.add("unload");
            }
            super.close();
        }
    }

    /** Reports a fixed, driver-style capability answer. */
    private static final class StubCapabilityShim extends AsioCapabilityShim {

        private final Optional<BufferSizeRange> range;
        private final Optional<int[]> channelCounts;

        private StubCapabilityShim(Optional<BufferSizeRange> range,
                                   Optional<int[]> channelCounts) {
            this.range = range;
            this.channelCounts = channelCounts;
        }

        static StubCapabilityShim withRange(BufferSizeRange range) {
            return new StubCapabilityShim(Optional.of(range), Optional.empty());
        }

        static StubCapabilityShim withChannels(int inputs, int outputs) {
            return new StubCapabilityShim(Optional.empty(),
                    Optional.of(new int[] {inputs, outputs}));
        }

        @Override
        boolean isAvailable() {
            return true;
        }

        @Override
        Optional<BufferSizeRange> getBufferSize() {
            return range;
        }

        @Override
        Optional<int[]> getChannelCount() {
            return channelCounts;
        }

        @Override
        public void close() {
            // Release the real superclass's Arena.ofShared().
            super.close();
        }
    }
}
