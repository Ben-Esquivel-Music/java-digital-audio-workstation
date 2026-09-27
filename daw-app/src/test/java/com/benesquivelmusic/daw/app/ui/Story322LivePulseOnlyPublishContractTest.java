package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract (probe-derived, permanent; fix-round 2 probe "d") — the
 * continuous VOLUME/PAN path publishes ONLY through the pulse (R2-5 / R2-7):
 * 20,000 steps from a virtual-thread "audio thread" against a LIVE started
 * dispatcher (timer running) after the VMs were constructed. Every property
 * write on that path must happen on the FX thread from INSIDE
 * {@code FxDispatcher.pulse} — a stack-frame audit, which unlike the FX
 * barrier of {@code Story322RecordedFxThreadPublishContractTest} still
 * discriminates when the live timer is converging the channels itself;
 * {@code isFxThread()} is false on the worker throughout and one explicit
 * pulse converges every channel. A MUTE edge from the same worker is the
 * contrast: an edge-triggered runLater, on FX, NOT inside pulse.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322LivePulseOnlyPublishContractTest {

    private static final int STEPS = 20_000;

    @Test
    void twentyThousandRampStepsAgainstALiveStartedDispatcherWriteThePropertiesOnlyFromInsidePulse()
            throws Exception {
        FxDispatcher dispatcher = new FxDispatcher();
        Story322ContractRig.onFx(dispatcher::start);
        MixerChannel kick = new MixerChannel("Kick");
        MixerChannel bass = new MixerChannel("Bass");
        ChannelVM kickVm = Story322ContractRig.onFx(() -> new ChannelVM(kick, dispatcher));
        ChannelVM bassVm = Story322ContractRig.onFx(() -> new ChannelVM(bass, dispatcher));
        List<String> offPulseWrites = new CopyOnWriteArrayList<>();
        AtomicInteger pulseWrites = new AtomicInteger();
        ChangeListener<Number> audit = (_, _, now) -> {
            boolean onFx = Platform.isFxApplicationThread();
            boolean insidePulse = insidePulse();
            if (onFx && insidePulse) {
                pulseWrites.incrementAndGet();
            } else {
                offPulseWrites.add(Thread.currentThread().getName() + " onFx=" + onFx
                        + " insidePulse=" + insidePulse + " value=" + now);
            }
        };
        Story322ContractRig.onFx(() -> {
            kickVm.volumeProperty().addListener(audit);
            kickVm.panProperty().addListener(audit);
            bassVm.volumeProperty().addListener(audit);
            bassVm.panProperty().addListener(audit);
        });
        try {
            AtomicBoolean workerLookedLikeFx = new AtomicBoolean(false);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().name("probe2-audio-thread").unstarted(() -> {
                try {
                    for (int i = 0; i <= STEPS; i++) {
                        double t = i / (double) STEPS;
                        kick.setVolume(0.37 * t);
                        kick.setPan(-0.62 * t);
                        bass.setVolume(0.81 * t);
                        bass.setPan(0.81 * t);
                        if (dispatcher.isFxThread()) {
                            workerLookedLikeFx.set(true);
                        }
                    }
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(worker.isAlive()).as("the 20,000-step ramp finished").isFalse();
            assertThat(workerFailure.get()).as("no failure on the audio thread").isNull();
            assertThat(workerLookedLikeFx).as("isFxThread() false throughout").isFalse();

            Story322ContractRig.flushFx();
            assertThat(offPulseWrites)
                    .as("every VOLUME/PAN property write came from inside FxDispatcher.pulse on the FX thread")
                    .isEmpty();

            Story322ContractRig.onFx(dispatcher::pulse);
            assertThat(kickVm.getVolume()).isEqualTo(0.37);
            assertThat(kickVm.getPan()).isEqualTo(-0.62);
            assertThat(bassVm.getVolume()).isEqualTo(0.81);
            assertThat(bassVm.getPan()).isEqualTo(0.81);
            assertThat(offPulseWrites).isEmpty();
            assertThat(pulseWrites.get())
                    .as("non-vacuity: each of the four properties changed at least once, all inside pulse")
                    .isGreaterThanOrEqualTo(4);

            List<String> muteWrites = new CopyOnWriteArrayList<>();
            Story322ContractRig.onFx(() -> kickVm.mutedProperty().addListener((_, _, now) ->
                    muteWrites.add("onFx=" + Platform.isFxApplicationThread()
                            + " insidePulse=" + insidePulse() + " value=" + now)));
            Thread edge = Thread.ofVirtual().name("probe2-audio-thread-edge").unstarted(() -> kick.setMuted(true));
            edge.start();
            edge.join(TimeUnit.SECONDS.toMillis(5));
            Story322ContractRig.flushFx();
            assertThat(kickVm.isMuted()).isTrue();
            assertThat(muteWrites).as("the discrete arm is a runLater, not a pulse write")
                    .containsExactly("onFx=true insidePulse=false value=true");
        } finally {
            kickVm.dispose();
            bassVm.dispose();
            Story322ContractRig.onFx(dispatcher::dispose);
        }
    }

    private static boolean insidePulse() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(
                frame -> frame.getClassName().equals(FxDispatcher.class.getName())
                        && frame.getMethodName().equals("pulse")));
    }
}
