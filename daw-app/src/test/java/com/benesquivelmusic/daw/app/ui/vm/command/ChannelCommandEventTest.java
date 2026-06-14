package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.TrackVM;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.TrackEvent;

import javafx.application.Platform;
import javafx.scene.control.Button;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 291 — verifies a <em>bound control's gesture</em> issues the right
 * {@link TrackCommand} and, once executed through {@link CoreTrackIntentHandler},
 * the typed {@link TrackEvent}/{@link MixerEvent} reaches a bus subscriber via
 * its payload (the design-book analogue of "a parent {@code addEventFilter} on
 * the payload") — never via {@code Event.getSource()} identity, never via
 * rendered output (Control Synchronization Design Book §2.8, §5.1, §5.2).
 *
 * <p>This is the full intent→mutate→announce path a real button click drives:
 * {@link TrackControlBinder} turns the click into a command into the sink, the
 * sink runs it against the handler, and the bus carries the result.</p>
 */
class ChannelCommandEventTest {

    private static final long TIMEOUT_SECONDS = 5;

    private DefaultEventBus bus;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        startToolkit();
    }

    @BeforeEach
    void installBus() {
        bus = new DefaultEventBus();
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void clearBus() {
        EventBusPublisher.setDefault(null);
        bus.close();
    }

    @Test
    void boundMuteButtonClickIssuesCommandAndAnnouncesTrackAndMixerEvents() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        FxDispatcher dispatcher = new FxDispatcher();
        TrackVM trackVm = new TrackVM(track, dispatcher);
        ChannelVM channelVm = new ChannelVM(channel, dispatcher);

        CountDownLatch both = new CountDownLatch(2);
        AtomicReference<TrackEvent.Muted> trackEvent = new AtomicReference<>();
        AtomicReference<MixerEvent.MuteChanged> mixerEvent = new AtomicReference<>();
        try (EventBus.Subscription s1 = bus.on(TrackEvent.Muted.class,
                DispatchMode.ON_CALLER_THREAD, e -> { trackEvent.set(e); both.countDown(); });
             EventBus.Subscription s2 = bus.on(MixerEvent.MuteChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { mixerEvent.set(e); both.countDown(); })) {

            // The command sink runs the command against the handler — the wiring
            // a real composition root installs.
            runOnFx(() -> {
                Button laneMute = new Button("M");
                TrackControlBinder binder = new TrackControlBinder(
                        track, trackVm, channel, channelVm, command -> command.execute(handler));
                binder.bindMute(laneMute);
                laneMute.fire(); // the gesture
            });

            assertThat(both.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("a bound mute click announces TrackEvent.Muted AND MixerEvent.MuteChanged")
                    .isTrue();
            assertThat(trackEvent.get().trackId()).isEqualTo(UUID.fromString(track.getId()));
            assertThat(trackEvent.get().muted()).isTrue();
            assertThat(mixerEvent.get().channelId()).isEqualTo(channel.getId());
            assertThat(mixerEvent.get().muted()).isTrue();
        } finally {
            trackVm.dispose();
            channelVm.dispose();
        }
    }

    @Test
    void boundSoloButtonClickIssuesCommandAndAnnouncesTrackAndMixerEvents() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        FxDispatcher dispatcher = new FxDispatcher();
        TrackVM trackVm = new TrackVM(track, dispatcher);
        ChannelVM channelVm = new ChannelVM(channel, dispatcher);

        CountDownLatch both = new CountDownLatch(2);
        AtomicReference<TrackEvent.Soloed> trackEvent = new AtomicReference<>();
        AtomicReference<MixerEvent.SoloChanged> mixerEvent = new AtomicReference<>();
        try (EventBus.Subscription s1 = bus.on(TrackEvent.Soloed.class,
                DispatchMode.ON_CALLER_THREAD, e -> { trackEvent.set(e); both.countDown(); });
             EventBus.Subscription s2 = bus.on(MixerEvent.SoloChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { mixerEvent.set(e); both.countDown(); })) {

            runOnFx(() -> {
                Button laneSolo = new Button("S");
                TrackControlBinder binder = new TrackControlBinder(
                        track, trackVm, channel, channelVm, command -> command.execute(handler));
                binder.bindSolo(laneSolo);
                laneSolo.fire();
            });

            assertThat(both.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("a bound solo click announces TrackEvent.Soloed AND MixerEvent.SoloChanged")
                    .isTrue();
            assertThat(trackEvent.get().soloed()).isTrue();
            assertThat(mixerEvent.get().channelId()).isEqualTo(channel.getId());
            assertThat(mixerEvent.get().soloed()).isTrue();
        } finally {
            trackVm.dispose();
            channelVm.dispose();
        }
    }

    // ── toolkit + FX-thread helpers (this package is opened only to itself under
    //    surefire, so a same-package static starter is used rather than a
    //    cross-package @ExtendWith) ─────────────────────────────────────────────

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static void startToolkit() throws InterruptedException {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            CountDownLatch startup = new CountDownLatch(1);
            Platform.startup(startup::countDown);
            if (!startup.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit startup timed out");
            }
            Platform.setImplicitExit(false);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("Toolkit already initialized")) {
                throw e;
            }
        }
    }

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
}
