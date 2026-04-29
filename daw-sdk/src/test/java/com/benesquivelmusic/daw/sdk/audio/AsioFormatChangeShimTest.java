package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
