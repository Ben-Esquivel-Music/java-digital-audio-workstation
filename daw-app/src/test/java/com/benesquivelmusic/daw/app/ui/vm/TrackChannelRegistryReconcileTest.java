package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — the registry is <em>live</em>: it mirrors return buses and the
 * master as well as track channels, follows runtime track add/remove through
 * the project's {@code TRACKS} signal (inline on the FX thread, marshalled
 * otherwise), exposes {@link TrackChannelRegistry#reconcile()} for the
 * signal-less return-bus case, and applies the engine's own effective-mute
 * gates (return buses solo-gated, the master mute-only). Audio Engine Wiring
 * Design Book §5.6; Control Synchronization Design Book §3.2.
 */
class TrackChannelRegistryReconcileTest {

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
    void returnBusesAndTheMasterGetStandaloneChannelVms() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);
        MixerChannel master = project.getMixer().getMasterChannel();

        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            ChannelVM returnVm = registry.channelVm(returnBus.getId());
            assertThat(returnVm).as("the return bus has a ChannelVM").isNotNull();
            assertThat(registry.peerTrackVm(returnVm)).as("a return bus is standalone").isEmpty();

            ChannelVM masterVm = registry.masterVm();
            assertThat(masterVm.channelId()).isEqualTo(master.getId());
            assertThat(registry.channelVm(master.getId())).isSameAs(masterVm);
            assertThat(registry.peerTrackVm(masterVm)).as("the master is standalone").isEmpty();

            assertThat(registry.channelVms())
                    .as("track channels, then return buses, then the master")
                    .hasSize(1 + project.getMixer().getReturnBuses().size() + 1);
            assertThat(registry.channelVms().getLast()).isSameAs(masterVm);
        } finally {
            registry.dispose();
        }
    }

    @Test
    void addingATrackOnTheFxThreadRegistersItsVmsInline() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            onFx(() -> {
                Track bass = project.createAudioTrack("Bass");
                UUID id = UUID.fromString(bass.getId());
                // Inline: no flush, no pulse — the VMs exist the moment addTrack returns.
                assertThat(registry.trackVm(id)).isNotNull();
                assertThat(registry.channelVm(id)).isNotNull();
                assertThat(registry.peerChannelVm(registry.trackVm(id))).isPresent();
                assertThat(registry.trackVms()).hasSize(2);
            });
        } finally {
            registry.dispose();
        }
    }

    @Test
    void addingATrackOffTheFxThreadRegistersItsVmsAfterMarshalling() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            Track bass = project.createAudioTrack("Bass"); // the JUnit thread, not FX
            UUID id = UUID.fromString(bass.getId());
            flushFx();
            assertThat(registry.trackVm(id)).as("TrackVM after the onFx pass").isNotNull();
            assertThat(registry.channelVm(id)).as("ChannelVM after the onFx pass").isNotNull();
        } finally {
            registry.dispose();
        }
    }

    @Test
    void removingATrackDisposesAndDropsItsVms() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        MixerChannel bassChannel = project.getMixerChannelForTrack(bass);
        FxDispatcher dispatcher = new FxDispatcher();
        TrackChannelRegistry registry = new TrackChannelRegistry(project, dispatcher);
        try {
            UUID bassId = UUID.fromString(bass.getId());
            int channelsBefore = dispatcher.openChannelCount();
            ChannelVM bassVm = registry.channelVm(bassId);
            assertThat(bassVm).isNotNull();

            onFx(() -> project.removeTrack(bass));

            assertThat(registry.trackVm(bassId)).as("TrackVM gone").isNull();
            assertThat(registry.channelVm(bassId)).as("ChannelVM gone").isNull();
            assertThat(registry.trackVm(UUID.fromString(drums.getId()))).as("the other track survives").isNotNull();
            assertThat(dispatcher.openChannelCount())
                    .as("the removed ChannelVM closed its three continuous channels")
                    .isEqualTo(channelsBefore - 3);
            // The disposed VM observes nothing further.
            onFx(() -> bassChannel.setVolume(0.33));
            assertThat(bassVm.getVolume()).as("disposed VM keeps its last value").isEqualTo(1.0);
        } finally {
            registry.dispose();
        }
    }

    @Test
    void reconcilePicksUpAReturnBusAddedWithoutASignal() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            MixerChannel delay = project.getMixer().addReturnBus("Delay");
            assertThat(registry.channelVm(delay.getId()))
                    .as("return-bus add has no core signal — not yet mirrored").isNull();

            onFx(registry::reconcile);

            ChannelVM delayVm = registry.channelVm(delay.getId());
            assertThat(delayVm).isNotNull();
            assertThat(registry.peerTrackVm(delayVm)).isEmpty();
            // reconcile() is a no-op when nothing changed: the VM instance survives.
            onFx(registry::reconcile);
            assertThat(registry.channelVm(delay.getId())).isSameAs(delayVm);
        } finally {
            registry.dispose();
        }
    }

    @Test
    void effectiveMuteAppliesTheEngineGateToReturnBusesButNeverToTheMaster() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);
        MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);
        returnBus.setSoloSafe(false); // the default return is solo-safe; make the gate bite
        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            ChannelVM returnVm = registry.channelVm(returnBus.getId());
            ChannelVM masterVm = registry.masterVm();
            assertThat(returnVm.isEffectiveMute()).isFalse();
            assertThat(masterVm.isEffectiveMute()).isFalse();

            drumsChannel.setSolo(true);
            flushFx();

            assertThat(returnVm.isEffectiveMute())
                    .as("a non-solo-safe return bus is silenced by a track solo, exactly as the engine does")
                    .isTrue();
            assertThat(masterVm.isEffectiveMute())
                    .as("the master is never solo-gated").isFalse();

            project.getMixer().getMasterChannel().setMuted(true);
            flushFx();
            assertThat(masterVm.isEffectiveMute()).as("the master's own mute").isTrue();
            assertThat(masterVm.isMuted()).as("the discrete muted fact").isTrue();
        } finally {
            registry.dispose();
        }
    }

    @Test
    void disposeUnsubscribesTheProjectSignalAndReleasesEverything() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        FxDispatcher dispatcher = new FxDispatcher();
        TrackChannelRegistry registry = new TrackChannelRegistry(project, dispatcher);
        assertThat(dispatcher.openChannelCount()).isPositive();

        registry.dispose();

        assertThat(dispatcher.openChannelCount()).as("every ChannelVM closed its channels").isZero();
        onFx(() -> project.createAudioTrack("Late"));
        assertThat(registry.trackVms()).as("no VM is created after dispose").isEmpty();
        assertThat(registry.channelVms()).isEmpty();
        assertThat(dispatcher.openChannelCount()).isZero();
    }
}
