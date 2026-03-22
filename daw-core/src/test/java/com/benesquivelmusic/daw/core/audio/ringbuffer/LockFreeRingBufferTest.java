package com.benesquivelmusic.daw.core.audio.ringbuffer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockFreeRingBufferTest {

    @Test
    void shouldStartEmpty() {
        LockFreeRingBuffer<String> buffer = new LockFreeRingBuffer<String>(4);

        assertThat(buffer.isEmpty()).isTrue();
        assertThat(buffer.isFull()).isFalse();
        assertThat(buffer.size()).isZero();
    }

    @Test
    void shouldRoundCapacityUpToPowerOfTwo() {
        assertThat(new LockFreeRingBuffer<>(1).capacity()).isEqualTo(1);
        assertThat(new LockFreeRingBuffer<>(2).capacity()).isEqualTo(2);
        assertThat(new LockFreeRingBuffer<>(3).capacity()).isEqualTo(4);
        assertThat(new LockFreeRingBuffer<>(5).capacity()).isEqualTo(8);
        assertThat(new LockFreeRingBuffer<>(7).capacity()).isEqualTo(8);
        assertThat(new LockFreeRingBuffer<>(8).capacity()).isEqualTo(8);
        assertThat(new LockFreeRingBuffer<>(9).capacity()).isEqualTo(16);
    }

    @Test
    void shouldWriteAndRead() {
        LockFreeRingBuffer<Integer> buffer = new LockFreeRingBuffer<Integer>(4);

        assertThat(buffer.write(1)).isTrue();
        assertThat(buffer.write(2)).isTrue();
        assertThat(buffer.write(3)).isTrue();
        assertThat(buffer.size()).isEqualTo(3);

        assertThat(buffer.read()).isEqualTo(1);
        assertThat(buffer.read()).isEqualTo(2);
        assertThat(buffer.read()).isEqualTo(3);
        assertThat(buffer.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnNullWhenEmpty() {
        LockFreeRingBuffer<String> buffer = new LockFreeRingBuffer<String>(4);
        assertThat(buffer.read()).isNull();
    }

    @Test
    void shouldReturnFalseWhenFull() {
        LockFreeRingBuffer<Integer> buffer = new LockFreeRingBuffer<Integer>(2);

        assertThat(buffer.write(1)).isTrue();
        assertThat(buffer.write(2)).isTrue();
        assertThat(buffer.isFull()).isTrue();
        assertThat(buffer.write(3)).isFalse();
    }

    @Test
    void shouldWrapAroundCorrectly() {
        LockFreeRingBuffer<Integer> buffer = new LockFreeRingBuffer<Integer>(4);

        // Fill and drain twice to exercise wrap-around
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                assertThat(buffer.write(round * 4 + i)).isTrue();
            }
            for (int i = 0; i < 4; i++) {
                assertThat(buffer.read()).isEqualTo(round * 4 + i);
            }
        }
    }

    @Test
    void shouldRejectNonPositiveCapacity() {
        assertThatThrownBy(() -> new LockFreeRingBuffer<>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockFreeRingBuffer<>(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldWorkConcurrentlyWithSingleProducerSingleConsumer() throws InterruptedException {
        int itemCount = 100_000;
        LockFreeRingBuffer<Integer> buffer = new LockFreeRingBuffer<Integer>(1024);
        AtomicBoolean producerDone = new AtomicBoolean(false);
        ArrayList<Integer> results = new ArrayList<Integer>(itemCount);
        CountDownLatch latch = new CountDownLatch(2);

        // Producer thread
        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < itemCount; i++) {
                while (!buffer.write(i)) {
                    Thread.onSpinWait();
                }
            }
            producerDone.set(true);
            latch.countDown();
        });

        // Consumer thread
        Thread consumer = Thread.ofPlatform().start(() -> {
            while (results.size() < itemCount) {
                Integer val = buffer.read();
                if (val != null) {
                    results.add(val);
                } else if (producerDone.get() && buffer.isEmpty()) {
                    break;
                } else {
                    Thread.onSpinWait();
                }
            }
            latch.countDown();
        });

        latch.await();

        assertThat(results).hasSize(itemCount);
        for (int i = 0; i < itemCount; i++) {
            assertThat(results.get(i)).isEqualTo(i);
        }
    }

    @Test
    void shouldReportCorrectSizeAfterMixedOperations() {
        LockFreeRingBuffer<String> buffer = new LockFreeRingBuffer<String>(8);

        buffer.write("a");
        buffer.write("b");
        buffer.write("c");
        assertThat(buffer.size()).isEqualTo(3);

        buffer.read();
        assertThat(buffer.size()).isEqualTo(2);

        buffer.write("d");
        assertThat(buffer.size()).isEqualTo(3);
    }
}
