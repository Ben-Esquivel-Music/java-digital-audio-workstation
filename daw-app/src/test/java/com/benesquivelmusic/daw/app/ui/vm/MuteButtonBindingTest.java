package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 291 — the §1.3 "one flag, both surfaces" join. A lane mute {@link Button}
 * and a {@link MixerChannelStrip} are bound to the <em>same</em> {@link TrackVM};
 * a single mute toggle drives both — the lane button gains the {@code :active}
 * pseudo-class and the strip's bound {@code mutedProperty} (which flips its
 * {@code :muted} pseudo) goes true (Control Synchronization Design Book §1.3,
 * §4.4).
 */
class MuteButtonBindingTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static void flushFx() throws InterruptedException {
        runOnFx(() -> { });
    }

    /** Runs {@code work} on the FX thread and blocks until it completes, rethrowing failures. */
    private static void runOnFx(Runnable work) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
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
    void oneFlagDrivesBothLaneButtonAndMixerStrip() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        FxDispatcher dispatcher = new FxDispatcher();
        TrackVM trackVm = new TrackVM(track, dispatcher);
        ChannelVM channelVm = new ChannelVM(channel, dispatcher);

        AtomicReference<Button> laneButtonRef = new AtomicReference<>();
        AtomicReference<MixerChannelStrip> stripRef = new AtomicReference<>();
        AtomicReference<TrackControlBinder> binderRef = new AtomicReference<>();
        try {
            // Build the controls and bind them to ONE TrackVM on the FX thread.
            runOnFx(() -> {
                Button laneMute = new Button("M");
                MixerChannelStrip strip = new MixerChannelStrip();
                TrackControlBinder binder = new TrackControlBinder(
                        track, trackVm, channel, channelVm, command -> { /* sink unused here */ });
                binder.bindMute(laneMute);
                binder.bindChannelStrip(strip);
                laneButtonRef.set(laneMute);
                stripRef.set(strip);
                binderRef.set(binder);
            });

            // Before muting: neither surface is active.
            runOnFx(() -> {
                assertThat(laneButtonRef.get().getPseudoClassStates()).doesNotContain(ACTIVE);
                assertThat(stripRef.get().mutedProperty().get()).isFalse();
            });

            // Toggle mute at the core (the binder's command sink is exercised in
            // the command-event test; here we assert the bound surfaces follow the
            // ONE VM flag once the core mutates and the VM republishes).
            track.setMuted(true);
            flushFx();

            runOnFx(() -> {
                assertThat(laneButtonRef.get().getPseudoClassStates())
                        .as("lane mute button shows :active from the shared TrackVM flag")
                        .contains(ACTIVE);
                assertThat(stripRef.get().mutedProperty().get())
                        .as("mixer strip mute follows the SAME TrackVM flag")
                        .isTrue();
            });

            // And back off — both surfaces clear together.
            track.setMuted(false);
            flushFx();
            runOnFx(() -> {
                assertThat(laneButtonRef.get().getPseudoClassStates()).doesNotContain(ACTIVE);
                assertThat(stripRef.get().mutedProperty().get()).isFalse();
            });
        } finally {
            if (binderRef.get() != null) {
                runOnFx(() -> binderRef.get().dispose());
            }
            trackVm.dispose();
            channelVm.dispose();
        }
    }
}
