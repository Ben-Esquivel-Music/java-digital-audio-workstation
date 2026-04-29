package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CoreAudioFormatChangeShim} — story 218 FFM
 * upcall plumbing for CoreAudio's
 * {@code AudioObjectPropertyListenerProc}.
 */
class CoreAudioFormatChangeShimTest {

    private static final DeviceId DEVICE = new DeviceId("CoreAudio", "Mock CoreAudio Device");

    @Test
    void shimBuildsValidUpcallStubOnAnyPlatform() {
        try (CoreAudioFormatChangeShim shim =
                     new CoreAudioFormatChangeShim(new CoreAudioBackend(), DEVICE)) {
            assertThat(shim.upcallStub()).isNotNull();
            assertThat(shim.upcallStub().equals(MemorySegment.NULL)).isFalse();
            // CoreAudio.framework is absent on non-macOS hosts.
            assertThat(shim.isRegistered()).isFalse();
        }
    }

    @Test
    void closeIsIdempotent() {
        CoreAudioFormatChangeShim shim =
                new CoreAudioFormatChangeShim(new CoreAudioBackend(), DEVICE);
        shim.close();
        shim.close();
    }

    @Test
    void nominalSampleRateSelectorEmitsSampleRateChange() throws Exception {
        AudioDeviceEvent received = dispatchSelector(CoreAudioFormatChangeShim.kSelNominalSampleRate);
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) received;
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.SampleRateChange.class);
        assertThat(fc.proposedFormat()).isEmpty();
        assertThat(fc.device()).isEqualTo(DEVICE);
    }

    @Test
    void bufferFrameSizeSelectorEmitsBufferSizeChange() throws Exception {
        AudioDeviceEvent received = dispatchSelector(CoreAudioFormatChangeShim.kSelBufferFrameSize);
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) received;
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.BufferSizeChange.class);
    }

    @Test
    void clockSourceSelectorEmitsClockSourceChange() throws Exception {
        AudioDeviceEvent received = dispatchSelector(CoreAudioFormatChangeShim.kSelClockSource);
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) received;
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.ClockSourceChange.class);
    }

    @Test
    void unknownSelectorIsIgnored() throws Exception {
        CoreAudioBackend backend = new CoreAudioBackend();
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, new CountDownLatch(1)));
        Thread.sleep(50);
        try (Arena scratch = Arena.ofConfined();
             CoreAudioFormatChangeShim shim = new CoreAudioFormatChangeShim(backend, DEVICE)) {
            MemorySegment addr = scratch.allocate(12);
            addr.set(ValueLayout.JAVA_INT, 0, /* unrecognized */ 0xDEADBEEF);
            shim.dispatch(CoreAudioFormatChangeShim.kAudioObjectSystemObject, 1, addr);
        }
        Thread.sleep(50);
        assertThat(ref.get()).isNull();
    }

    @Test
    void nullAddressArrayIsTolerated() {
        CoreAudioBackend backend = new CoreAudioBackend();
        try (CoreAudioFormatChangeShim shim = new CoreAudioFormatChangeShim(backend, DEVICE)) {
            int status = shim.dispatch(CoreAudioFormatChangeShim.kAudioObjectSystemObject,
                    1, MemorySegment.NULL);
            assertThat(status).isEqualTo(0);
        }
    }

    @Test
    void multipleAddressesInOneCallAllDispatch() throws Exception {
        CoreAudioBackend backend = new CoreAudioBackend();
        CountDownLatch latch = new CountDownLatch(2);
        java.util.List<AudioDeviceEvent> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        backend.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AudioDeviceEvent e) {
                received.add(e);
                latch.countDown();
            }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });
        Thread.sleep(50);
        try (Arena scratch = Arena.ofConfined();
             CoreAudioFormatChangeShim shim = new CoreAudioFormatChangeShim(backend, DEVICE)) {
            MemorySegment addrs = scratch.allocate(24);
            addrs.set(ValueLayout.JAVA_INT, 0, CoreAudioFormatChangeShim.kSelNominalSampleRate);
            addrs.set(ValueLayout.JAVA_INT, 12, CoreAudioFormatChangeShim.kSelClockSource);
            shim.dispatch(CoreAudioFormatChangeShim.kAudioObjectSystemObject, 2, addrs);
        }
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
    }

    private static AudioDeviceEvent dispatchSelector(int selector) throws Exception {
        CoreAudioBackend backend = new CoreAudioBackend();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, latch));
        Thread.sleep(50);
        try (Arena scratch = Arena.ofConfined();
             CoreAudioFormatChangeShim shim = new CoreAudioFormatChangeShim(backend, DEVICE)) {
            MemorySegment addr = scratch.allocate(12);
            addr.set(ValueLayout.JAVA_INT, 0, selector);
            shim.dispatch(CoreAudioFormatChangeShim.kAudioObjectSystemObject, 1, addr);
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
