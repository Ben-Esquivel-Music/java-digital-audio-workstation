package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The harness backend's own contract: an {@code AudioBackend} whose
 * {@code sink} captures interleaved output, whose {@code awaitSinkCapacity}
 * never waits, and whose input side is driven by the test (story 316).
 */
class HeadlessAudioBackendTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16);

    @Test
    void lifecycleShouldProgressThroughOpenAndClose() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        assertThat(backend.isOpen()).isFalse();

        backend.open(DeviceId.defaultFor(HeadlessAudioBackend.NAME), FORMAT, 128);
        assertThat(backend.isOpen()).isTrue();
        assertThat(backend.openedFormat()).isEqualTo(FORMAT);
        assertThat(backend.openedBufferFrames()).isEqualTo(128);
        assertThat(backend.openedDevice().isDefault()).isTrue();

        backend.close();
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void doubleOpenShouldThrow() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.open(DeviceId.defaultFor(HeadlessAudioBackend.NAME), FORMAT, 128);

        assertThatThrownBy(() ->
                backend.open(DeviceId.defaultFor(HeadlessAudioBackend.NAME), FORMAT, 128))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeIsIdempotent() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.open(DeviceId.defaultFor(HeadlessAudioBackend.NAME), FORMAT, 128);
        backend.close();
        backend.close(); // must not throw
        assertThat(backend.isOpen()).isFalse();
    }

    @Test
    void sinkShouldCaptureInterleavedBlocksWhileOpen() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.open(DeviceId.defaultFor(HeadlessAudioBackend.NAME), FORMAT, 4);

        float[] samples = {0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f, 0.4f, -0.4f};
        backend.sink(new AudioBlock(44_100.0, 2, 4, samples.clone()));

        List<float[]> sunk = backend.sunkBlocks();
        assertThat(sunk).hasSize(1);
        assertThat(sunk.get(0)).containsExactly(samples);
        assertThat(backend.sunkBlockCount()).isEqualTo(1);

        backend.clearSunkBlocks();
        assertThat(backend.sunkBlockCount()).isZero();
    }

    @Test
    void sinkWhileClosedIsSilentlyDropped() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.sink(AudioBlock.silence(44_100.0, 2, 4));
        assertThat(backend.sunkBlockCount()).isZero();
    }

    @Test
    void awaitSinkCapacityReturnsImmediately() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        long start = System.nanoTime();
        backend.awaitSinkCapacity(TimeUnit.SECONDS.toNanos(30));
        long elapsed = System.nanoTime() - start;
        // Generous guard: a no-op must not take anywhere near the timeout.
        assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void publishInputReachesInputBlocksSubscribers() throws InterruptedException {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.open(DeviceId.defaultFor(HeadlessAudioBackend.NAME), FORMAT, 4);

        List<AudioBlock> received = new CopyOnWriteArrayList<>();
        CountDownLatch arrived = new CountDownLatch(1);
        backend.inputBlocks().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(AudioBlock item) {
                received.add(item);
                arrived.countDown();
            }
            @Override public void onError(Throwable throwable) { }
            @Override public void onComplete() { }
        });

        AudioBlock block = AudioBlock.silence(44_100.0, 2, 4);
        backend.publishInput(block);

        assertThat(arrived.await(5, TimeUnit.SECONDS))
                .as("the published input block reaches the subscriber")
                .isTrue();
        assertThat(received).containsExactly(block);
    }

    @Test
    void listDevicesShouldReturnHeadlessDevice() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        assertThat(backend.listDevices()).containsExactly(HeadlessAudioBackend.HEADLESS_DEVICE);
        assertThat(backend.isAvailable()).isTrue();
        assertThat(backend.supportsStreaming()).isTrue();
        assertThat(backend.name()).isEqualTo("Headless");
    }
}
