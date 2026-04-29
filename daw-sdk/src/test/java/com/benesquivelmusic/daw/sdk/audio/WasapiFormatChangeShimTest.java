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
 * Unit tests for {@link WasapiFormatChangeShim} — story 218 FFM upcall
 * plumbing for WASAPI's {@code IMMNotificationClient::OnPropertyValueChanged}.
 */
class WasapiFormatChangeShimTest {

    private static final DeviceId DEVICE = new DeviceId("WASAPI", "Mock WASAPI Device");

    @Test
    void shimBuildsValidVtableOnAnyPlatform() {
        try (WasapiFormatChangeShim shim =
                     new WasapiFormatChangeShim(new WasapiBackend(), DEVICE)) {
            // Vtable + COM "fat pointer" must be allocated even on Linux.
            assertThat(shim.vtable()).isNotNull();
            assertThat(shim.vtable().equals(MemorySegment.NULL)).isFalse();
            assertThat(shim.instance()).isNotNull();
            assertThat(shim.instance().equals(MemorySegment.NULL)).isFalse();
            // First slot of the instance points to the vtable (COM
            // convention) — verify the layout is correct.
            MemorySegment first = shim.instance().reinterpret(8)
                    .get(ValueLayout.ADDRESS, 0);
            assertThat(first.address()).isEqualTo(shim.vtable().address());
        }
    }

    @Test
    void closeIsIdempotent() {
        WasapiFormatChangeShim shim =
                new WasapiFormatChangeShim(new WasapiBackend(), DEVICE);
        shim.close();
        shim.close();
    }

    @Test
    void matchingPropertyKeyEmitsSampleRateChange() throws Exception {
        WasapiBackend backend = new WasapiBackend();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, latch));
        Thread.sleep(50);
        try (Arena scratch = Arena.ofConfined();
             WasapiFormatChangeShim shim = new WasapiFormatChangeShim(backend, DEVICE)) {
            MemorySegment key = scratch.allocate(WasapiFormatChangeShim.PROPERTYKEY_SIZE);
            // Copy GUID bytes.
            for (int i = 0; i < WasapiFormatChangeShim.PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID.length; i++) {
                key.set(ValueLayout.JAVA_BYTE, i,
                        WasapiFormatChangeShim.PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID[i]);
            }
            key.set(ValueLayout.JAVA_INT, 16,
                    WasapiFormatChangeShim.PKEY_AUDIO_ENGINE_DEVICE_FORMAT_PID);
            int status = shim.dispatchPropertyChanged(key);
            assertThat(status).isEqualTo(WasapiFormatChangeShim.S_OK);
        }
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        AudioDeviceEvent.FormatChangeRequested fc =
                (AudioDeviceEvent.FormatChangeRequested) ref.get();
        assertThat(fc.reason()).isInstanceOf(FormatChangeReason.SampleRateChange.class);
        assertThat(fc.proposedFormat()).isEmpty();
        assertThat(fc.device()).isEqualTo(DEVICE);
    }

    @Test
    void nonMatchingGuidProducesNoEvent() throws Exception {
        WasapiBackend backend = new WasapiBackend();
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, new CountDownLatch(1)));
        Thread.sleep(50);
        try (Arena scratch = Arena.ofConfined();
             WasapiFormatChangeShim shim = new WasapiFormatChangeShim(backend, DEVICE)) {
            MemorySegment key = scratch.allocate(WasapiFormatChangeShim.PROPERTYKEY_SIZE);
            // All zeros — definitely not the audio-engine device-format GUID.
            shim.dispatchPropertyChanged(key);
        }
        Thread.sleep(50);
        assertThat(ref.get()).isNull();
    }

    @Test
    void matchingGuidWithWrongPidProducesNoEvent() throws Exception {
        WasapiBackend backend = new WasapiBackend();
        AtomicReference<AudioDeviceEvent> ref = new AtomicReference<>();
        backend.deviceEvents().subscribe(new CapturingSubscriber(ref, new CountDownLatch(1)));
        Thread.sleep(50);
        try (Arena scratch = Arena.ofConfined();
             WasapiFormatChangeShim shim = new WasapiFormatChangeShim(backend, DEVICE)) {
            MemorySegment key = scratch.allocate(WasapiFormatChangeShim.PROPERTYKEY_SIZE);
            for (int i = 0; i < WasapiFormatChangeShim.PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID.length; i++) {
                key.set(ValueLayout.JAVA_BYTE, i,
                        WasapiFormatChangeShim.PKEY_AUDIO_ENGINE_DEVICE_FORMAT_GUID[i]);
            }
            // Wrong pid.
            key.set(ValueLayout.JAVA_INT, 16, 99);
            shim.dispatchPropertyChanged(key);
        }
        Thread.sleep(50);
        assertThat(ref.get()).isNull();
    }

    @Test
    void nullKeyIsTolerated() {
        try (WasapiFormatChangeShim shim =
                     new WasapiFormatChangeShim(new WasapiBackend(), DEVICE)) {
            int status = shim.dispatchPropertyChanged(MemorySegment.NULL);
            assertThat(status).isEqualTo(WasapiFormatChangeShim.S_OK);
        }
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
