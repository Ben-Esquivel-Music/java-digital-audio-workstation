package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a throwing insert on a mixer channel cannot take the session
 * down: the chain keeps running, the faulting slot is silenced, and
 * downstream non-faulting slots still receive input.
 */
class MixerChannelFaultIntegrationTest {

    @TempDir
    Path tempDir;

    private PluginInvocationSupervisor supervisor;

    @BeforeEach
    void setUp() {
        supervisor = new PluginInvocationSupervisor(tempDir.resolve("faults.log"));
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void channelWithFaultingInsertKeepsProcessingAndBypassesSlot() throws Exception {
        MixerChannel channel = new MixerChannel("Ch1");
        channel.setPluginSupervisor(supervisor);

        FaultLatchSubscriber subscriber = new FaultLatchSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot faultSlot = new InsertSlot("Crasher",
                new ThrowingProcessor(new RuntimeException("boom")));
        CountingProcessor downstream = new CountingProcessor();
        InsertSlot goodSlot = new InsertSlot("Passthrough", downstream);

        channel.addInsert(faultSlot);
        channel.addInsert(goodSlot);
        channel.prepareEffectsChain(2, 4);

        float[][] in = new float[][]{{0.1f, 0.2f, 0.3f, 0.4f}, {0.1f, 0.2f, 0.3f, 0.4f}};
        float[][] out = new float[][]{{0f, 0f, 0f, 0f}, {0f, 0f, 0f, 0f}};

        // First block: faulting insert throws → slot bypassed mid-chain,
        // zeroed intermediate propagates to downstream slot.
        channel.getEffectsChain().process(in, out, 4);
        assertThat(downstream.invocationCount.get()).isEqualTo(1);

        // Deterministic sync: drain thread persists the fault and then
        // publishes; once the subscriber's latch fires, the counter is set.
        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.getFaultCount("Crasher")).isEqualTo(1);
        assertThat(faultSlot.isBypassed()).isTrue();

        // Second block: after the app observes the bypass flag and rebuilds,
        // the chain drops the faulting slot entirely — downstream still runs.
        channel.setInsertBypassed(0, true);
        channel.getEffectsChain().process(in, out, 4);
        assertThat(downstream.invocationCount.get()).isEqualTo(2);

        // Signal must now pass cleanly through the surviving slot.
        assertThat(out[0]).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(out[1]).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
    }

    private static final class FaultLatchSubscriber implements Flow.Subscriber<PluginFault> {
        final CountDownLatch latch;

        FaultLatchSubscriber(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PluginFault item) {
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }

    // --- helpers ---

    private static final class ThrowingProcessor implements AudioProcessor {
        private final RuntimeException error;

        ThrowingProcessor(RuntimeException error) {
            this.error = error;
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            throw error;
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }

    private static final class CountingProcessor implements AudioProcessor {
        final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            invocationCount.incrementAndGet();
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
