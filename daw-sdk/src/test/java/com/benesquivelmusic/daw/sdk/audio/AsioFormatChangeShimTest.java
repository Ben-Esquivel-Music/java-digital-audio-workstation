package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
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
            // Registration transparently no-ops when asioshim is missing
            // (the typical case off-Windows and on a fresh Windows
            // checkout). On the dedicated Windows-with-shim CI lane the
            // companion test {@code shimRegistersAgainstBundledAsioshimDll}
            // asserts the inverse.
            if (!asioshimAvailable()) {
                assertThat(shim.isRegistered()).isFalse();
            }
        }
    }

    /**
     * Story 224 — on the dedicated Windows-with-shim CI lane (the
     * {@code windows-asioshim.yml} workflow exports
     * {@code DAW_REQUIRE_ASIOSHIM=1}) the bundled {@code asioshim.dll}
     * must be loadable and the FFM upcall registration must succeed.
     * On developer workstations and other CI lanes the env var is
     * unset and this test cleanly skips when the shim is missing.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shimRegistersAgainstBundledAsioshimDll() {
        boolean available = asioshimAvailable();
        if ("1".equals(System.getenv("DAW_REQUIRE_ASIOSHIM"))) {
            assertThat(available)
                    .as("DAW_REQUIRE_ASIOSHIM=1 — asioshim.dll must be on the "
                            + "FFM library path. Build the native libs with "
                            + "-DASIO_SDK_DIR=... before mvn verify.")
                    .isTrue();
        } else {
            assumeTrue(available,
                    "asioshim.dll not on java.library.path — skip "
                            + "(set DAW_REQUIRE_ASIOSHIM=1 to fail instead of skip)");
        }
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
     * Lightweight existence probe for the {@code asioshim} FFM library —
     * mirrors the logic used by {@code NativeLibraryDetector.isAvailable},
     * but inlined here because {@code daw-sdk} does not depend on
     * {@code daw-core}.
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
    void closeIsIdempotent() {
        AsioBackend backend = new AsioBackend();
        AudioBackendSupport support = new AudioBackendSupport();
        AsioFormatChangeShim shim = new AsioFormatChangeShim(backend, support, DEVICE);
        shim.close();
        shim.close(); // must not throw
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
