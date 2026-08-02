package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link AsioFormatChangeShim} — story 218 FFM upcall
 * plumbing for ASIO's {@code asioMessage} host-callback.
 */
class AsioFormatChangeShimTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Mock ASIO Device");

    @Test
    void shimBuildsValidUpcallStubOnAnyPlatform() {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            // Construction must succeed even on Linux / hosts without
            // an ASIO driver — the FFM upcall stub itself does not
            // require any platform library.
            assertThat(shim.upcallStub()).isNotNull();
            assertThat(shim.upcallStub().equals(MemorySegment.NULL)).isFalse();
            // Registration must transparently no-op when asioshim is missing.
            assertThat(shim.isRegistered()).isFalse();
        }
    }

    @Test
    void closeIsIdempotent() {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE);
        shim.close();
        shim.close(); // must not throw
    }

    /**
     * Story 224 — positive-case registration test. When the bundled
     * {@code asioshim.dll} is present on the FFM library path, the
     * shim must successfully install its upcall callback. This test
     * uses {@code assumeTrue} (never hard-fails) because
     * {@code daw-sdk} executes in the Maven reactor before
     * {@code daw-core}'s {@code generate-resources} phase, which is
     * where the CMake native build produces the DLL. The hard-failure
     * env-gated assertions live in {@code NativeLibraryDetectorTest}
     * inside {@code daw-core} (which runs after the native build and
     * has {@code -Djava.library.path=${native.libs.dir}} set).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shimRegistersWhenAsioshimIsAvailable() {
        assumeTrue(asioshimAvailable(),
                "asioshim.dll not on java.library.path — skip "
                        + "(build native libs with -DASIO_SDK_DIR=...)");
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            assertThat(shim.isRegistered())
                    .as("AsioFormatChangeShim must successfully register its "
                            + "upcall against the bundled asioshim.dll")
                    .isTrue();
        }
    }

    /**
     * Lightweight FFM probe for the {@code asioshim} library. Uses a
     * bare {@link SymbolLookup#libraryLookup} call — this does not
     * scan {@code java.library.path} directories or try platform
     * filename variants the way {@code NativeLibraryDetector} does,
     * but it is sufficient here because {@code daw-core}'s Surefire
     * config places the native output directory on
     * {@code java.library.path} for tests that run after the native
     * build. When the DLL is on the path, the bare lookup succeeds;
     * when it is not, the test skips via the {@code assumeTrue}
     * guard.
     */
    private static boolean asioshimAvailable() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup.libraryLookup("asioshim", arena);
            return true;
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    @Test
    void bufferSizeChangeSelectorEmitsBufferSizeChange() throws Exception {
        // Set up a real opened-support so the proposed format can be built
        // from the previously opened sample rate / channels / bit depth,
        // per the BufferSizeChange contract.
        AudioFormat opened = new AudioFormat(48_000.0, 2, 24);
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        support.markOpen(opened, 256);
        AudioDeviceEvent received = subscribeAndDispatch(backend, support,
                shim -> shim.dispatch(AsioFormatChangeShim.kAsioBufferSizeChange, 512));
        assertThat(received).isInstanceOf(AudioDeviceEvent.FormatChangeRequested.class);
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) received;
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.BufferSizeChange.class);
        FormatChangeReason.BufferSizeChange bsc = (FormatChangeReason.BufferSizeChange) fc.reason();
        assertThat(bsc.newBufferFrames()).isEqualTo(512);
        assertThat(fc.proposedFormat()).contains(opened);
        assertThat(fc.device()).isEqualTo(DEVICE);
    }

    @Test
    void resyncRequestSelectorEmitsClockSourceChange() throws Exception {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        AudioDeviceEvent received = subscribeAndDispatch(backend, support,
                shim -> shim.dispatch(AsioFormatChangeShim.kAsioResyncRequest, 0L));
        assertThat(received).isInstanceOf(AudioDeviceEvent.FormatChangeRequested.class);
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) received;
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.ClockSourceChange.class);
        assertThat(fc.proposedFormat()).isEmpty();
    }

    @Test
    void resetRequestSelectorEmitsDriverReset() throws Exception {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        AudioDeviceEvent received = subscribeAndDispatch(backend, support,
                shim -> shim.dispatch(AsioFormatChangeShim.kAsioResetRequest, 0L));
        assertThat(received).isInstanceOf(AudioDeviceEvent.FormatChangeRequested.class);
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) received;
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.DriverReset.class);
        assertThat(fc.proposedFormat()).isEmpty();
    }

    /**
     * Story 311 makes the {@code ASIOCallbacks} struct real for the first
     * time, so the driver now sends the ASIO handshake selectors the shim
     * previously answered with {@code ASE_NotPresent}.
     */
    @Test
    void engineVersionSelectorReportsAsioTwo() {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            assertThat(shim.dispatch(AsioFormatChangeShim.kAsioEngineVersion, 0L))
                    .as("answering 0 would make drivers fall back to ASIO 1.0")
                    .isEqualTo(2L);
        }
    }

    @Test
    void supportsTimeInfoAndLatenciesChangedSelectorsAreAccepted() {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            assertThat(shim.dispatch(AsioFormatChangeShim.kAsioSupportsTimeInfo, 0L))
                    .isEqualTo(1L);
            assertThat(shim.dispatch(AsioFormatChangeShim.kAsioLatenciesChanged, 0L))
                    .isEqualTo(1L);
        }
    }

    @Test
    void selectorSupportedAnswersOnlyForSelectorsTheShimHandles() {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            int[] handled = {
                    AsioFormatChangeShim.kAsioSelectorSupported,
                    AsioFormatChangeShim.kAsioEngineVersion,
                    AsioFormatChangeShim.kAsioResetRequest,
                    AsioFormatChangeShim.kAsioResyncRequest,
                    AsioFormatChangeShim.kAsioSupportsTimeInfo,
                    AsioFormatChangeShim.kAsioLatenciesChanged,
                    AsioFormatChangeShim.kAsioBufferSizeChange};
            for (int selector : handled) {
                assertThat(shim.dispatch(
                        AsioFormatChangeShim.kAsioSelectorSupported, selector))
                        .as("selector %d must be advertised as supported", selector)
                        .isEqualTo(1L);
            }
            assertThat(shim.dispatch(AsioFormatChangeShim.kAsioSelectorSupported, 9999))
                    .isEqualTo(0L);
            assertThat(shim.dispatch(AsioFormatChangeShim.kAsioSelectorSupported, 8))
                    .isEqualTo(0L);
        }
    }

    @Test
    void unknownSelectorReturnsZeroAndEmitsNothing() throws Exception {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, new CountDownLatch(1)));
        Thread.sleep(50);
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            long status = shim.dispatch(/* unrecognized */ 9999, 0L);
            assertThat(status).isEqualTo(0L);
        }
        Thread.sleep(50);
        assertThat(ref.get()).isNull();
    }

    /**
     * Story 218 &times; 311: a driver-initiated reset invalidates the ASIO
     * buffers, so the streaming bridge must be quiesced before the event is
     * announced.
     *
     * <p>The quiesce is asserted <strong>synchronously, on the dispatching
     * thread, the instant {@code dispatch} returns</strong> — not sampled
     * inside {@code onNext}. The previous shape sampled the flag from the
     * subscriber, which runs on the publisher's executor: it would have passed
     * just as often had the quiesce happened <em>after</em> the publish, and it
     * could equally have failed for pure scheduling reasons. The synchronous
     * assertion is deterministic in both directions: if
     * {@code stopStreamingForDriverReset()} were not called at all — the actual
     * regression this guards — the flag is still {@code true} and the test
     * fails every time.</p>
     *
     * <p>Ordering <em>relative to the publish</em> is guaranteed by program
     * order inside {@code dispatch}; it is not separately observable, because
     * the inline quiesce has no injectable seam
     * ({@link AsioBufferSwitchShim} is created inside
     * {@code AsioBackend#open}) and a {@code SubmissionPublisher} exposes no
     * synchronous publish hook. Comparing a stamp taken in the stub's
     * {@code stop()} would be wrong: that runs on the
     * {@code asio-reset-teardown} executor, deliberately <em>after</em> the
     * announcement.</p>
     */
    @Test
    void resetRequestQuiescesTheStreamingBridgeAndAnnouncesTheEvent()
            throws Exception {
        DispatchOutcome outcome = dispatchAgainstOpenBackend(
                AsioFormatChangeShim.kAsioResetRequest, 0L);

        assertThat(outcome.streamingAfterDispatch())
                .as("kAsioResetRequest must stop the bridge touching driver buffers "
                        + "before dispatch returns")
                .isFalse();
        assertThat(outcome.eventAnnounced()).isTrue();
        assertThat(outcome.driverTornDown())
                .as("ASIOStop / ASIODisposeBuffers must follow on the teardown thread")
                .isTrue();
    }

    @Test
    void bufferSizeChangeQuiescesTheStreamingBridgeAndAnnouncesTheEvent()
            throws Exception {
        DispatchOutcome outcome = dispatchAgainstOpenBackend(
                AsioFormatChangeShim.kAsioBufferSizeChange, 512L);

        assertThat(outcome.streamingAfterDispatch())
                .as("kAsioBufferSizeChange invalidates the buffers, so it must quiesce")
                .isFalse();
        assertThat(outcome.eventAnnounced()).isTrue();
        assertThat(outcome.driverTornDown()).isTrue();
    }

    @Test
    void resyncRequestLeavesTheStreamingBridgeRunning() throws Exception {
        DispatchOutcome outcome = dispatchAgainstOpenBackend(
                AsioFormatChangeShim.kAsioResyncRequest, 0L);

        assertThat(outcome.streamingAfterDispatch())
                .as("a resync does not invalidate the driver's buffers")
                .isTrue();
        assertThat(outcome.eventAnnounced()).isTrue();
        assertThat(outcome.driverTornDown())
                .as("a resync must not schedule ASIOStop / ASIODisposeBuffers — the "
                        + "inline quiesce above proves the reset path was not taken")
                .isFalse();
    }

    /**
     * Outcome of one {@code dispatch} against a fully opened backend.
     *
     * @param streamingAfterDispatch the bridge's flag sampled synchronously
     *                               once {@code dispatch} returned
     * @param eventAnnounced         whether the device event was delivered
     * @param driverTornDown         whether the asynchronous teardown reached
     *                               {@code stop()} + {@code disposeBuffers()}
     */
    private record DispatchOutcome(boolean streamingAfterDispatch,
                                   boolean eventAnnounced, boolean driverTornDown) {
    }

    /**
     * Opens a backend against stub driver / streaming shims, dispatches the
     * selector, and reports what the dispatch did.
     */
    private static DispatchOutcome dispatchAgainstOpenBackend(int selector, long value)
            throws Exception {
        AudioFormat format = new AudioFormat(48_000.0, 2, 32);
        List<String> driverCalls = Collections.synchronizedList(new ArrayList<>());
        try (Arena buffers = Arena.ofShared()) {
            StubStreamingShim streaming =
                    new StubStreamingShim(buffers, format, 128, driverCalls);
            AsioBackend.setDriverShimFactory(StubDriverShim::new);
            AsioBackend.setStreamingShimFactory(() -> streaming);
            AsioBackend backend = new AsioBackend();
            AudioBackendSupport support = new AudioBackendSupport();
            support.markOpen(format, 128);
            try {
                backend.open(DEVICE, format, 128);
                assertThat(backend.activeBufferSwitchShim()).isNotNull();
                driverCalls.clear();

                CountDownLatch latch = new CountDownLatch(1);
                backend.deviceEvents().subscribe(new Flow.Subscriber<AudioDeviceEvent>() {
                    @Override public void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }
                    @Override public void onNext(AudioDeviceEvent event) {
                        latch.countDown();
                    }
                    @Override public void onError(Throwable throwable) { }
                    @Override public void onComplete() { }
                });
                Thread.sleep(50);

                boolean streamingAfterDispatch;
                try (AsioFormatChangeShim shim =
                             new AsioFormatChangeShim(backend, support, DEVICE)) {
                    shim.dispatch(selector, value);
                    AsioBufferSwitchShim bridge = backend.activeBufferSwitchShim();
                    streamingAfterDispatch = bridge != null && bridge.isStreaming();
                }
                boolean announced = latch.await(10, TimeUnit.SECONDS);
                boolean tornDown = awaitTeardown(driverCalls, streamingAfterDispatch);
                return new DispatchOutcome(streamingAfterDispatch, announced, tornDown);
            } finally {
                backend.close();
                AsioBackend.resetDriverShimFactory();
                AsioBackend.resetStreamingShimFactory();
            }
        }
    }

    /**
     * Waits for the asynchronous {@code asio-reset-teardown} work. When the
     * bridge is still streaming no teardown was scheduled at all, so a short
     * bounded settle is enough to assert the negative.
     */
    private static boolean awaitTeardown(List<String> driverCalls,
                                         boolean stillStreaming) throws Exception {
        long budgetMillis = stillStreaming ? 500 : 10_000;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        while (System.nanoTime() < deadline) {
            if (driverCalls.contains("stop") && driverCalls.contains("disposeBuffers")) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /** Minimal driver shim that reports one loadable driver. */
    private static final class StubDriverShim extends AsioDriverShim {
        @Override boolean isEnumerationAvailable() { return true; }
        @Override boolean isLifecycleAvailable() { return true; }
        @Override java.util.List<DriverDescriptor> listDrivers() {
            return java.util.List.of(new DriverDescriptor(
                    DEVICE.name(), "{00000000-0000-0000-0000-000000000000}"));
        }
        @Override boolean loadDriver(String driverName) { return true; }
        @Override public void close() {
            // Release the real superclass's Arena.ofShared().
            super.close();
        }
    }

    /** Streaming shim backed by test-owned memory instead of a real driver. */
    private static final class StubStreamingShim extends AsioStreamingShim {

        private final java.util.List<BufferInfo> bufferInfos;
        private final List<String> driverCalls;

        StubStreamingShim(Arena arena, AudioFormat format, int frames,
                          List<String> driverCalls) {
            super();
            this.driverCalls = driverCalls;
            java.util.List<BufferInfo> infos = new java.util.ArrayList<>();
            for (int channel = 0; channel < format.channels(); channel++) {
                infos.add(new BufferInfo(channel, true, 19,
                        arena.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT, frames)
                                .address(),
                        arena.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT, frames)
                                .address()));
            }
            for (int channel = 0; channel < format.channels(); channel++) {
                infos.add(new BufferInfo(channel, false, 19,
                        arena.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT, frames)
                                .address(),
                        arena.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT, frames)
                                .address()));
            }
            bufferInfos = java.util.List.copyOf(infos);
        }

        @Override boolean isStreamingAvailable() { return true; }
        @Override boolean createBuffers(int[] in, int[] out, int frames) { return true; }
        @Override java.util.List<BufferInfo> getBufferInfos() { return bufferInfos; }
        @Override boolean start() { return true; }
        @Override boolean stop() {
            driverCalls.add("stop");
            return true;
        }
        @Override boolean disposeBuffers() {
            driverCalls.add("disposeBuffers");
            return true;
        }
        @Override boolean installBufferSwitchCallback(MemorySegment stub) { return true; }
        @Override void uninstallBufferSwitchCallback() { }
        @Override public void close() {
            // Release the real superclass's Arena.ofShared().
            super.close();
        }
    }

    /** Subscribes, dispatches via the shim, and returns the first event. */
    private static AudioDeviceEvent subscribeAndDispatch(
            AsioBackend backend, AudioBackendSupport support,
            java.util.function.Consumer<AsioFormatChangeShim> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, latch));
        Thread.sleep(50);
        try (AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE)) {
            action.accept(shim);
        }
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    private record CapturingSubscriber(AtomicReference<AudioDeviceEvent> ref, CountDownLatch latch)
            implements Flow.Subscriber<AudioDeviceEvent> {
        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(AudioDeviceEvent e) {
            if (ref.compareAndSet(null, e)) {
                latch.countDown();
            }
        }
        @Override public void onError(Throwable t) {}
        @Override public void onComplete() {}
    }
}
