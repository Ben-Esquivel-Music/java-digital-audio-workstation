package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackIntentHandler;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 291 — verifies the mute/solo cascade end-to-end through
 * {@link CoreTrackIntentHandler} + {@link TrackChannelRegistry}: REPUBLISH (the
 * {@link TrackVM} flag follows the mutation), the cross-channel effective-mute
 * recompute (a solo on one channel silences its non-soloed peers), and ANNOUNCE
 * (the typed {@link TrackEvent}/{@link MixerEvent} pair reaches the bus) —
 * Control Synchronization Design Book §1.3, §5.1, §5.2.
 *
 * <p>Events are asserted via their <em>payload</em> captured by a bus subscriber,
 * never via source identity or rendered output.</p>
 */
class MuteSoloCascadeTest {

    private static final long TIMEOUT_SECONDS = 5;

    private DefaultEventBus bus;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
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

    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

    @Test
    void muteCommandMutatesBothTrackAndChannelAndRepublishesTheTrackVmFlag() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            TrackVM trackVm = registry.trackVm(UUID.fromString(track.getId()));

            new ToggleMuteCommand(track, true).execute(handler);

            // MUTATE: both flags flipped in lock-step (the §1.3 fix).
            assertThat(track.isMuted()).as("Track muted (lane/persistence authority)").isTrue();
            assertThat(channel.isMuted()).as("paired MixerChannel muted (engine)").isTrue();

            // REPUBLISH: the single TrackVM flag both surfaces bind to follows it.
            flushFx();
            assertThat(trackVm.isMuted()).as("TrackVM republished the mute").isTrue();
        } finally {
            registry.dispose();
        }
    }

    @Test
    void soloOnOneTrackEffectiveMutesItsNonSoloedPeers() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track a = project.createAudioTrack("A");
        Track b = project.createAudioTrack("B");
        MixerChannel channelA = project.getMixerChannelForTrack(a);
        MixerChannel channelB = project.getMixerChannelForTrack(b);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            ChannelVM vmA = registry.channelVm(channelA.getId());
            ChannelVM vmB = registry.channelVm(channelB.getId());

            // No solo yet → nobody effectively muted.
            flushFx();
            assertThat(vmA.isEffectiveMute()).isFalse();
            assertThat(vmB.isEffectiveMute()).isFalse();

            new ToggleSoloCommand(a, true).execute(handler);
            flushFx();

            assertThat(channelA.isSolo()).as("A's channel soloed in lock-step").isTrue();
            assertThat(vmA.isEffectiveMute())
                    .as("the soloed channel is audible (not effectively muted)").isFalse();
            assertThat(vmB.isEffectiveMute())
                    .as("a non-soloed peer is silenced while another channel solos").isTrue();

            // Clearing solo restores everyone.
            new ToggleSoloCommand(a, false).execute(handler);
            flushFx();
            assertThat(vmB.isEffectiveMute())
                    .as("clearing solo un-mutes the peer").isFalse();
        } finally {
            registry.dispose();
        }
    }

    @Test
    void soloOnAReturnBusEffectiveMutesNonSoloSafeTrackChannels() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        // The default reverb return bus. Soloing a return bus silences non-solo-safe
        // tracks in the engine (Mixer.isAnySolo() counts return-bus solo), so a
        // track channel's effective-mute must follow it just as it follows a soloed
        // track — the divergence the effective-mute recompute must not have.
        MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);

        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            ChannelVM vm = registry.channelVm(channel.getId());

            flushFx();
            assertThat(vm.isEffectiveMute()).as("no solo yet → audible").isFalse();

            returnBus.setSolo(true);
            flushFx();
            assertThat(vm.isEffectiveMute())
                    .as("soloing a return bus silences the non-solo-safe track, exactly as the engine does")
                    .isTrue();

            returnBus.setSolo(false);
            flushFx();
            assertThat(vm.isEffectiveMute())
                    .as("clearing the return-bus solo restores the track").isFalse();
        } finally {
            registry.dispose();
        }
    }

    @Test
    void muteAnnouncesBothTrackEventAndMixerEvent() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        CountDownLatch both = new CountDownLatch(2);
        AtomicReference<TrackEvent.Muted> trackEvent = new AtomicReference<>();
        AtomicReference<MixerEvent.MuteChanged> mixerEvent = new AtomicReference<>();
        try (EventBus.Subscription s1 = bus.on(TrackEvent.Muted.class,
                DispatchMode.ON_CALLER_THREAD, e -> { trackEvent.set(e); both.countDown(); });
             EventBus.Subscription s2 = bus.on(MixerEvent.MuteChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { mixerEvent.set(e); both.countDown(); })) {

            new ToggleMuteCommand(track, true).execute(handler);

            assertThat(both.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("ANNOUNCE: both TrackEvent.Muted and MixerEvent.MuteChanged reach the bus")
                    .isTrue();
            assertThat(trackEvent.get().trackId()).isEqualTo(UUID.fromString(track.getId()));
            assertThat(trackEvent.get().muted()).isTrue();
            assertThat(mixerEvent.get().channelId()).isEqualTo(channel.getId());
            assertThat(mixerEvent.get().muted()).isTrue();
            // The §1.3 invariant: the two ids are the same value (addTrack pairing).
            assertThat(mixerEvent.get().channelId()).isEqualTo(trackEvent.get().trackId());
        }
    }

    @Test
    void soloAnnouncesBothTrackEventAndMixerEvent() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        CountDownLatch both = new CountDownLatch(2);
        AtomicReference<TrackEvent.Soloed> trackEvent = new AtomicReference<>();
        AtomicReference<MixerEvent.SoloChanged> mixerEvent = new AtomicReference<>();
        try (EventBus.Subscription s1 = bus.on(TrackEvent.Soloed.class,
                DispatchMode.ON_CALLER_THREAD, e -> { trackEvent.set(e); both.countDown(); });
             EventBus.Subscription s2 = bus.on(MixerEvent.SoloChanged.class,
                DispatchMode.ON_CALLER_THREAD, e -> { mixerEvent.set(e); both.countDown(); })) {

            new ToggleSoloCommand(track, true).execute(handler);

            assertThat(both.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("ANNOUNCE: both TrackEvent.Soloed and MixerEvent.SoloChanged reach the bus")
                    .isTrue();
            assertThat(trackEvent.get().soloed()).isTrue();
            assertThat(mixerEvent.get().channelId()).isEqualTo(channel.getId());
            assertThat(mixerEvent.get().soloed()).isTrue();
        }
    }

    @Test
    void idempotentMuteCommandNeitherMutatesNorAnnounces() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        // Establish a CONSISTENT muted state THROUGH the handler (Track + channel
        // in §1.3 lock-step), so the second identical toggle is a genuine no-op on
        // both surfaces — not an artificial Track-only divergence the per-surface
        // gate would (correctly) repair.
        new ToggleMuteCommand(track, true).execute(handler);
        assertThat(track.isMuted()).isTrue();
        assertThat(channel.isMuted()).isTrue();

        AtomicReference<Object> announced = new AtomicReference<>();
        try (EventBus.Subscription s1 = bus.on(TrackEvent.Muted.class,
                DispatchMode.ON_CALLER_THREAD, announced::set);
             EventBus.Subscription s2 = bus.on(MixerEvent.MuteChanged.class,
                DispatchMode.ON_CALLER_THREAD, announced::set)) {

            // Already muted on BOTH surfaces → the VALIDATE no-op gate drops it.
            new ToggleMuteCommand(track, true).execute(handler);

            assertThat(announced.get())
                    .as("no event announced when the toggle is a no-op on both surfaces").isNull();
        }
    }
}
