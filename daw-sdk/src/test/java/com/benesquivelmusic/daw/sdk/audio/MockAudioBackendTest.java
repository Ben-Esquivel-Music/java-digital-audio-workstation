package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockAudioBackendTest {

    @Test
    void openThenCloseTogglesIsOpen() {
        MockAudioBackend b = new MockAudioBackend();
        assertFalse(b.isOpen());
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 128);
        assertTrue(b.isOpen());
        b.close();
        assertFalse(b.isOpen());
    }

    @Test
    void sinkRecordsWrittenPcm() {
        MockAudioBackend b = new MockAudioBackend();
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 4);
        // 4 stereo frames = 8 samples
        float[] samples = {0.0f, 1.0f, -1.0f, 0.5f, 0.25f, -0.25f, 0.0f, 0.0f};
        b.sink(new AudioBlock(44_100.0, 2, 4, samples));
        byte[] out = b.recordedOutput();
        // 8 samples * 2 bytes = 16 bytes
        assertEquals(16, out.length);
        // First sample = 0 -> 0x0000
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        // Second sample = 1.0 -> 32767 -> 0x7FFF LE
        assertEquals((byte) 0xFF, out[2]);
        assertEquals(0x7F, out[3]);
        b.close();
    }

    @Test
    void pumpInputEmitsBlocksToSubscribers() throws Exception {
        // Build 4 stereo frames (8 shorts = 16 bytes) of known PCM.
        byte[] pcm = new byte[16];
        for (int i = 0; i < 8; i++) {
            int v = i * 100;
            pcm[2 * i]     = (byte) (v & 0xFF);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        MockAudioBackend b = new MockAudioBackend(pcm);
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 4);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AudioBlock> received = new AtomicReference<>();
        b.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AudioBlock item) { received.set(item); latch.countDown(); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });

        b.pumpInput(4);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "expected input block within 2s");
        AudioBlock blk = received.get();
        assertNotNull(blk);
        assertEquals(2, blk.channels());
        assertEquals(4, blk.frames());
        assertEquals(8, blk.samples().length);
        b.close();
    }

    @Test
    void sinkRejectsMismatchedChannelCount() {
        MockAudioBackend b = new MockAudioBackend();
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 4); // stereo
        assertThrows(IllegalArgumentException.class,
                () -> b.sink(new AudioBlock(44_100.0, 1, 4, new float[4])));
        b.close();
    }

    @Test
    void openTwiceThrows() {
        MockAudioBackend b = new MockAudioBackend();
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 4);
        assertThrows(IllegalStateException.class,
                () -> b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 4));
        b.close();
    }

    @Test
    void listDevicesIncludesMockDevice() {
        MockAudioBackend b = new MockAudioBackend();
        assertTrue(b.isAvailable());
        assertEquals(1, b.listDevices().size());
        assertEquals("Mock", b.listDevices().get(0).hostApi());
    }

    @Test
    void closeIsIdempotent() {
        MockAudioBackend b = new MockAudioBackend();
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 4);
        b.close();
        b.close(); // should not throw
        assertFalse(b.isOpen());
    }

    @Test
    void openControlPanelReturnsRunnableThatRecordsInvocations() {
        MockAudioBackend b = new MockAudioBackend();
        assertEquals(0, b.controlPanelInvocationCount());

        var action = b.openControlPanel();
        assertTrue(action.isPresent(),
                "MockAudioBackend should expose a control-panel runnable for tests");

        action.get().run();
        action.get().run();
        assertEquals(2, b.controlPanelInvocationCount());
    }

    @Test
    void sinkBeforeOpenIsNoOp() {
        MockAudioBackend b = new MockAudioBackend();
        b.sink(new AudioBlock(44_100.0, 2, 1, new float[2]));
        assertArrayEquals(new byte[0], b.recordedOutput());
    }

    @Test
    void pumpInputZeroPadsWhenBufferExhausted() throws Exception {
        MockAudioBackend b = new MockAudioBackend(new byte[4]); // 1 stereo frame only
        b.open(DeviceId.defaultFor("Mock"), AudioFormat.CD_QUALITY, 8);
        AtomicInteger count = new AtomicInteger();
        b.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AudioBlock item) { count.incrementAndGet(); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        b.pumpInput(8); // request more than buffered — should still emit a block
        Thread.sleep(150);
        assertEquals(1, count.get());
        b.close();
    }

    @Test
    void simulateDeviceEventsArePublishedInOrder() throws InterruptedException {
        MockAudioBackend b = new MockAudioBackend();
        java.util.List<AudioDeviceEvent> received = new java.util.ArrayList<>();
        CountDownLatch ready = new CountDownLatch(3);
        b.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AudioDeviceEvent e) {
                synchronized (received) { received.add(e); }
                ready.countDown();
            }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });

        DeviceId id = new DeviceId("Mock", "USB Interface");
        b.simulateDeviceArrived(id);
        b.simulateDeviceFormatChanged(id, AudioFormat.STUDIO_QUALITY_48K);
        b.simulateDeviceRemoved(id);

        assertTrue(ready.await(2, TimeUnit.SECONDS), "all three events should be delivered");
        synchronized (received) {
            assertEquals(3, received.size());
            assertTrue(received.get(0) instanceof AudioDeviceEvent.DeviceArrived);
            assertTrue(received.get(1) instanceof AudioDeviceEvent.DeviceFormatChanged);
            assertTrue(received.get(2) instanceof AudioDeviceEvent.DeviceRemoved);
        }
        b.close();
    }

    @Test
    void simulateDeviceEventsRejectNullArguments() {
        MockAudioBackend b = new MockAudioBackend();
        assertThrows(NullPointerException.class, () -> b.simulateDeviceArrived(null));
        assertThrows(NullPointerException.class, () -> b.simulateDeviceRemoved(null));
        DeviceId id = new DeviceId("Mock", "Dev");
        assertThrows(NullPointerException.class, () -> b.simulateDeviceFormatChanged(id, null));
        assertThrows(NullPointerException.class, () -> b.simulateDeviceFormatChanged(null, AudioFormat.CD_QUALITY));
    }

    @Test
    void deviceEventsReturnsEmptyPublisherByDefaultForJavaxBackend() {
        // JavaxSoundBackend keeps the AudioBackend.deviceEvents() default (empty publisher).
        JavaxSoundBackend backend = new JavaxSoundBackend();
        AtomicInteger count = new AtomicInteger();
        backend.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AudioDeviceEvent e) { count.incrementAndGet(); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
        });
        // Default empty publisher never emits.
        assertEquals(0, count.get());
    }
}
