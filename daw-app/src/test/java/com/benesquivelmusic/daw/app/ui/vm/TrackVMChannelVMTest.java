package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.beans.property.Property;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 291 — verifies {@link TrackVM}, {@link ChannelVM} and their pairing via
 * {@link TrackChannelRegistry}: a track added with {@link DawProject#addTrack}
 * yields a paired track+channel VM sharing one id; a channel added directly to
 * the mixer (the aux/return carve-out) has no track peer; and each VM's
 * properties mirror the core after a change signal (Control Synchronization
 * Design Book §1.3, §3.2, §4.3).
 *
 * <p>Assertions are on property values only — never on rasterised output.</p>
 */
class TrackVMChannelVMTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /** Posts a barrier onto the FX thread and blocks until it (and every earlier {@code onFx}) has run. */
    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

    @Test
    void addTrackTrackAndChannelVmsArePairedBySharedId() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);

        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            TrackVM trackVm = registry.trackVm(UUID.fromString(drums.getId()));
            ChannelVM channelVm = registry.channelVm(drumsChannel.getId());

            assertThat(trackVm).as("track VM exists for the added track").isNotNull();
            assertThat(channelVm).as("channel VM exists for the paired channel").isNotNull();
            assertThat(trackVm.trackId())
                    .as("the addTrack invariant: track and channel share one id")
                    .isEqualTo(channelVm.channelId());
            assertThat(registry.peerChannelVm(trackVm)).hasValue(channelVm);
            assertThat(registry.peerTrackVm(channelVm)).hasValue(trackVm);
        } finally {
            registry.dispose();
        }
    }

    @Test
    void standaloneChannelHasNoTrackPeer_theAuxReturnCarveOut() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        // A return-bus-style channel added directly to the mixer gets a random
        // UUID — NOT the id of any track (the carve-out the brief calls out).
        MixerChannel reverbReturn = new MixerChannel("Reverb Return");
        project.getMixer().addChannel(reverbReturn);

        TrackChannelRegistry registry = new TrackChannelRegistry(project, new FxDispatcher());
        try {
            ChannelVM standalone = registry.channelVm(reverbReturn.getId());
            assertThat(standalone).as("the standalone channel has a VM").isNotNull();
            assertThat(registry.peerTrackVm(standalone))
                    .as("a standalone aux/return channel has NO track VM peer")
                    .isEmpty();
            assertThat(standalone.channelId())
                    .as("its id is distinct from every track id")
                    .isNotIn(registry.trackVms().stream().map(TrackVM::trackId).toList());
        } finally {
            registry.dispose();
        }
    }

    @Test
    void trackVmPropertiesFollowTrackSignals() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Bass");
        TrackVM vm = new TrackVM(track, new FxDispatcher());
        try {
            track.setName("Sub Bass");
            track.setMuted(true);
            track.setSolo(true);
            track.setArmed(true);
            flushFx();

            assertThat(vm.getName()).isEqualTo("Sub Bass");
            assertThat(vm.isMuted()).isTrue();
            assertThat(vm.isSoloed()).isTrue();
            assertThat(vm.isArmed()).isTrue();
        } finally {
            vm.dispose();
        }
    }

    @Test
    void channelVmVolumeAndPanFollowChannelSignals() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Keys");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        ChannelVM vm = new ChannelVM(channel, new FxDispatcher());
        try {
            channel.setVolume(0.4);
            channel.setPan(-0.75);
            flushFx();

            assertThat(vm.getVolume()).isEqualTo(0.4);
            assertThat(vm.getPan()).isEqualTo(-0.75);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void vmsAreTheSingleWriter_exposedPropertiesAreReadOnly() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Pad");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackVM trackVm = new TrackVM(track, new FxDispatcher());
        ChannelVM channelVm = new ChannelVM(channel, new FxDispatcher());
        try {
            // A read-only property is NOT a writable Property, so a control that
            // binds to it has no setter/bindBidirectional path back into the VM.
            assertThat(trackVm.nameProperty()).isNotInstanceOf(Property.class);
            assertThat(trackVm.mutedProperty()).isNotInstanceOf(Property.class);
            assertThat(trackVm.soloedProperty()).isNotInstanceOf(Property.class);
            assertThat(trackVm.armedProperty()).isNotInstanceOf(Property.class);
            assertThat(channelVm.volumeProperty()).isNotInstanceOf(Property.class);
            assertThat(channelVm.panProperty()).isNotInstanceOf(Property.class);
            assertThat(channelVm.meterLevelProperty()).isNotInstanceOf(Property.class);
            assertThat(channelVm.effectiveMuteProperty()).isNotInstanceOf(Property.class);
        } finally {
            trackVm.dispose();
            channelVm.dispose();
        }
    }

    @Test
    void disposeClosesTheMeterChannelAndUnregistersSoNothingLeaks() throws InterruptedException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Lead");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        FxDispatcher dispatcher = new FxDispatcher();

        assertThat(dispatcher.openChannelCount())
                .as("no continuous channel before the VM is created").isZero();
        ChannelVM channelVm = new ChannelVM(channel, dispatcher);
        assertThat(dispatcher.openChannelCount())
                .as("the channel VM opens exactly one meter channel").isEqualTo(1);

        TrackVM trackVm = new TrackVM(track, dispatcher);
        assertThat(dispatcher.openChannelCount())
                .as("the track VM opens no continuous channel").isEqualTo(1);

        channelVm.dispose();
        assertThat(dispatcher.openChannelCount())
                .as("dispose() closes the meter channel, not leaks it").isZero();

        // After dispose the VMs observe no further signals.
        trackVm.dispose();
        track.setMuted(true);
        channel.setVolume(0.1);
        flushFx();
        assertThat(trackVm.isMuted())
                .as("muted retains its last value — listener unregistered").isFalse();
        assertThat(channelVm.getVolume())
                .as("volume retains its last value — listener unregistered").isEqualTo(1.0);

        channelVm.dispose(); // idempotent
        trackVm.dispose();
        assertThat(dispatcher.openChannelCount()).isZero();
    }
}
