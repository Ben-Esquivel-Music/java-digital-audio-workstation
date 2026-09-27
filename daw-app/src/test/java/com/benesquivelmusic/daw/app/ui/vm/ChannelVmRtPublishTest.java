package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicRotator;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 §2.2 — RT-safety of a live {@link ChannelVM}. A {@code VOLUME} /
 * {@code PAN} signal from a non-FX thread (the audio thread under automation)
 * must reach the property through the dispatcher's wait-free continuous
 * channel on the next {@link FxDispatcher#pulse()} and <strong>never</strong>
 * through a {@code Platform.runLater}; on the FX thread it is applied inline.
 * The discrete {@code MUTE}/{@code SOLO}/{@code INSERTS} facts marshal through
 * {@code onFx} off-thread and inline on the FX thread.
 *
 * <p>The "no {@code runLater}" proof: after the off-thread setter, the FX queue
 * is flushed to a barrier — had the VM posted a runnable, it would have run by
 * then — and the property is asserted <em>unchanged</em>; only an explicit
 * pulse of the (un-started, so never self-pulsing) dispatcher delivers it.</p>
 */
class ChannelVmRtPublishTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    private static void onFx(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof RuntimeException re) {
            throw re;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
    }

    @Test
    void volumeFromANonFxThreadArrivesOnPulseNotViaRunLater() throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            assertThat(dispatcher.openChannelCount())
                    .as("meter + volume + pan continuous channels").isEqualTo(3);
            assertThat(Platform.isFxApplicationThread()).isFalse();

            channel.setVolume(0.37); // the "audio thread"
            flushFx();
            assertThat(vm.getVolume())
                    .as("no runLater was posted: the FX barrier ran and the property is untouched")
                    .isEqualTo(1.0);

            onFx(dispatcher::pulse);
            assertThat(vm.getVolume()).as("the pulse drained the continuous channel").isEqualTo(0.37);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void aBurstOfOffThreadVolumeSignalsCoalescesToTheAuthorityOnOnePulse() throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            channel.setVolume(0.1);
            channel.setVolume(0.2);
            channel.setVolume(0.3);
            flushFx();
            assertThat(vm.getVolume()).isEqualTo(1.0);
            onFx(dispatcher::pulse);
            assertThat(vm.getVolume()).as("the drain re-reads the channel: latest wins").isEqualTo(0.3);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void volumeAndPanOnTheFxThreadAreAppliedInline() throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            onFx(() -> {
                channel.setVolume(0.25);
                assertThat(vm.getVolume()).as("synchronous — no pulse needed").isEqualTo(0.25);
                channel.setPan(-0.4);
                assertThat(vm.getPan()).isEqualTo(-0.4);
            });
        } finally {
            vm.dispose();
        }
    }

    @Test
    void panFromANonFxThreadArrivesOnPulse() throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            channel.setPan(0.6);
            flushFx();
            assertThat(vm.getPan()).isEqualTo(0.0);
            onFx(dispatcher::pulse);
            assertThat(vm.getPan()).isEqualTo(0.6);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void anInlineFxWriteInterleavedWithAQueuedOffThreadTickConvergesOnTheAuthority()
            throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            channel.setVolume(0.3);      // off-thread tick queued
            onFx(() -> channel.setVolume(0.5)); // inline FX write lands first
            assertThat(vm.getVolume()).isEqualTo(0.5);
            onFx(dispatcher::pulse);     // the stale tick drains
            assertThat(vm.getVolume())
                    .as("the drain re-reads the channel, so a stale tick cannot regress the value")
                    .isEqualTo(0.5);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void muteAndSoloAreDiscreteFactsMarshalledOffThreadAndInlineOnFx() throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            channel.setMuted(true); // off-thread → onFx (setters notify on change only)
            flushFx();
            assertThat(vm.isMuted()).isTrue();

            onFx(() -> {
                channel.setSolo(true);
                assertThat(vm.isSoloed()).as("inline on FX").isTrue();
                channel.setMuted(false);
                assertThat(vm.isMuted()).isFalse();
            });
        } finally {
            vm.dispose();
        }
    }

    @Test
    void insertsAndSpatialNodePresentFollowTheChainOnInsertsSignals() throws InterruptedException {
        MixerChannel channel = new MixerChannel("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        ChannelVM vm = new ChannelVM(channel, dispatcher);
        try {
            assertThat(vm.insertsProperty()).isEmpty();
            assertThat(vm.isSpatialNodePresent()).isFalse();

            onFx(() -> {
                channel.addInsert(new InsertSlot("Gain Trim", new Passthrough()));
                assertThat(vm.insertsProperty())
                        .containsExactly(new InsertSlotModel("Gain Trim", true, false));
                assertThat(vm.isSpatialNodePresent()).isFalse();

                channel.setInsertBypassed(0, true);
                assertThat(vm.insertsProperty())
                        .as("bypass flips active/bypassed on the same slot")
                        .containsExactly(new InsertSlotModel("Gain Trim", false, true));

                channel.addInsert(new InsertSlot("Rotator", new AmbisonicRotator(AmbisonicOrder.FIRST)));
                assertThat(vm.insertsProperty()).hasSize(2);
                assertThat(vm.isSpatialNodePresent())
                        .as("a spatial node in the chain — the 3D panner gate").isTrue();

                channel.removeInsert(1);
                assertThat(vm.isSpatialNodePresent()).isFalse();
                assertThat(vm.insertsProperty()).hasSize(1);
            });

            // Off-thread: marshalled through onFx.
            channel.addInsert(new InsertSlot("Late", new Passthrough()));
            flushFx();
            assertThat(vm.insertsProperty()).hasSize(2);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void theExposedListIsUnmodifiable() {
        MixerChannel channel = new MixerChannel("Drums");
        ChannelVM vm = new ChannelVM(channel, new FxDispatcher());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> vm.insertsProperty().add(new InsertSlotModel("x", true, false)))
                    .isInstanceOf(UnsupportedOperationException.class);
        } finally {
            vm.dispose();
        }
    }

    /** A processor with no capability tags, so it is not a spatial node. */
    private static final class Passthrough implements AudioProcessor {
        @Override
        public void process(float[][] in, float[][] out, int numFrames) {
            for (int c = 0; c < Math.min(in.length, out.length); c++) {
                System.arraycopy(in[c], 0, out[c], 0, numFrames);
            }
        }

        @Override
        public void reset() { }

        @Override
        public int getInputChannelCount() { return 2; }

        @Override
        public int getOutputChannelCount() { return 2; }
    }
}
