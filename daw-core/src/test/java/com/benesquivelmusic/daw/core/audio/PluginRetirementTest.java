package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PluginRetirementTest {
    @Test
    void retirementWaitsForAnOldRenderAndDisposesRemovedUndoSlotsOnlyOnce() throws Exception {
        var channel = new MixerChannel("Track");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disposed = new AtomicBoolean();
        var slot = new InsertSlot("Held", new HeldProcessor(entered, release, disposed));
        slot.setDisposal(() -> disposed.set(true));
        channel.addInsert(slot);
        var rendering = Thread.ofPlatform().start(() ->
                channel.getEffectsChain().process(new float[1][1], new float[1][1], 1));
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            channel.removeInsert(slot);
            var completion = channel.disposeInsertsWhenQuiescent();
            assertThat(disposed).isFalse();
            assertThat(completion).isNotDone();
            release.countDown();
            completion.get(2, TimeUnit.SECONDS);
            assertThat(disposed).isTrue();
            assertThat(channel.getEffectsChain().isEmpty()).isTrue();
        } finally {
            release.countDown();
            rendering.join(2000);
        }
    }

    @Test
    void nestedRenderGuardDoesNotRetireUntilTheOuterConsumerLeaves() throws Exception {
        var chain = new EffectsChain();
        chain.enterRender();
        var disposed = new AtomicBoolean();
        var completion = chain.retireAll(() -> disposed.set(true));
        chain.process(new float[1][1], new float[1][1], 1);
        assertThat(disposed).isFalse();
        assertThat(completion).isNotDone();
        chain.leaveRender();
        completion.get(2, TimeUnit.SECONDS);
        assertThat(disposed).isTrue();
    }

    private record HeldProcessor(CountDownLatch entered, CountDownLatch release,
                                 AtomicBoolean disposed) implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            assertThat(disposed).isFalse();
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }
}
