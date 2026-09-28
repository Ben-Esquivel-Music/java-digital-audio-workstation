package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — {@link LinkedTrackCommandDispatcher} executes the command on
 * the handler and then mirrors a stereo-linked partner (channel AND its Track)
 * through {@code ChannelLinkManager}'s arithmetic, inline and without
 * re-dispatching (Audio Engine Wiring Design Book §5.6 "Stereo link"). An
 * unlinked channel is untouched; a per-attribute link flag turned off skips
 * that attribute. No JavaFX involved.
 */
class LinkedTrackCommandDispatcherTest {

    private record Rig(DawProject project, Track trackA, Track trackB, Track trackC,
                       MixerChannel a, MixerChannel b, MixerChannel c,
                       CountingHandler handler, LinkedTrackCommandDispatcher dispatcher) {

        static Rig linked(ChannelLink linkOrNull) {
            DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
            Track trackA = project.createAudioTrack("L");
            Track trackB = project.createAudioTrack("R");
            Track trackC = project.createAudioTrack("Unlinked");
            MixerChannel a = project.getMixerChannelForTrack(trackA);
            MixerChannel b = project.getMixerChannelForTrack(trackB);
            MixerChannel c = project.getMixerChannelForTrack(trackC);
            if (linkOrNull != null) {
                project.getChannelLinkManager().link(linkOrNull);
            }
            CountingHandler handler = new CountingHandler(new CoreTrackIntentHandler(project));
            return new Rig(project, trackA, trackB, trackC, a, b, c, handler,
                    new LinkedTrackCommandDispatcher(project, handler));
        }

        static Rig absolutePair() {
            DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
            Track trackA = project.createAudioTrack("L");
            Track trackB = project.createAudioTrack("R");
            Track trackC = project.createAudioTrack("Unlinked");
            MixerChannel a = project.getMixerChannelForTrack(trackA);
            MixerChannel b = project.getMixerChannelForTrack(trackB);
            MixerChannel c = project.getMixerChannelForTrack(trackC);
            project.getChannelLinkManager().link(
                    ChannelLink.ofPair(a.getId(), b.getId()).withMode(LinkMode.ABSOLUTE));
            CountingHandler handler = new CountingHandler(new CoreTrackIntentHandler(project));
            return new Rig(project, trackA, trackB, trackC, a, b, c, handler,
                    new LinkedTrackCommandDispatcher(project, handler));
        }
    }

    @Test
    void volumeOnALinkedChannelMovesThePartnerChannelAndTrackByTheSameDeltaInRelativeMode() {
        Rig rig = Rig.absolutePair();
        // Re-link RELATIVE with an offset between the members.
        rig.project().getChannelLinkManager().replace(
                ChannelLink.ofPair(rig.a().getId(), rig.b().getId())); // RELATIVE, all flags
        rig.b().setVolume(0.8);
        rig.trackB().setVolume(0.8);

        rig.dispatcher().accept(new SetChannelVolumeCommand(rig.a(), 0.6)); // delta −0.4

        assertThat(rig.a().getVolume()).isEqualTo(0.6);
        assertThat(rig.trackA().getVolume()).as("source Track dual-written").isEqualTo(0.6);
        assertThat(rig.b().getVolume()).as("partner shifted by the delta").isCloseTo(0.4, within(1e-12));
        assertThat(rig.trackB().getVolume()).as("partner Track healed by the handler")
                .isCloseTo(0.4, within(1e-12));
        assertThat(rig.c().getVolume()).as("unlinked channel untouched").isEqualTo(1.0);
        assertThat(rig.trackC().getVolume()).isEqualTo(1.0);
        assertThat(rig.handler().setVolumeCalls.get())
                .as("source + one partner heal — no re-dispatch, no recursion").isEqualTo(2);
    }

    @Test
    void volumeInAbsoluteModeCopiesTheValueToThePartner() {
        Rig rig = Rig.absolutePair();

        rig.dispatcher().accept(new SetChannelVolumeCommand(rig.a(), 0.25));

        assertThat(rig.b().getVolume()).isEqualTo(0.25);
        assertThat(rig.trackB().getVolume()).isEqualTo(0.25);
    }

    @Test
    void panOnALinkedChannelMirrorsThePartnerAroundCentre() {
        Rig rig = Rig.absolutePair();

        rig.dispatcher().accept(new SetChannelPanCommand(rig.a(), -0.3));

        assertThat(rig.a().getPan()).isEqualTo(-0.3);
        assertThat(rig.trackA().getPan()).isEqualTo(-0.3);
        assertThat(rig.b().getPan()).isEqualTo(0.3);
        assertThat(rig.trackB().getPan()).isEqualTo(0.3);
        assertThat(rig.c().getPan()).isEqualTo(0.0);
        assertThat(rig.handler().setPanCalls.get()).isEqualTo(2);
    }

    @Test
    void trackMuteOnALinkedChannelMutesThePartnerChannelAndTrack() {
        Rig rig = Rig.absolutePair();

        rig.dispatcher().accept(new ToggleMuteCommand(rig.trackA(), true));

        assertThat(rig.trackA().isMuted()).isTrue();
        assertThat(rig.a().isMuted()).isTrue();
        assertThat(rig.b().isMuted()).as("partner channel follows").isTrue();
        assertThat(rig.trackB().isMuted()).as("partner Track follows").isTrue();
        assertThat(rig.c().isMuted()).isFalse();
        assertThat(rig.trackC().isMuted()).isFalse();
    }

    @Test
    void channelSoloOnALinkedChannelSolosThePartnerChannelAndTrack() {
        Rig rig = Rig.absolutePair();

        rig.dispatcher().accept(new ToggleChannelSoloCommand(rig.a(), true));

        assertThat(rig.a().isSolo()).isTrue();
        assertThat(rig.trackA().isSolo()).isTrue();
        assertThat(rig.b().isSolo()).isTrue();
        assertThat(rig.trackB().isSolo()).isTrue();
        assertThat(rig.c().isSolo()).isFalse();
    }

    @Test
    void aGestureOnTheRightMemberMirrorsToTheLeft() {
        Rig rig = Rig.absolutePair();

        rig.dispatcher().accept(new ToggleChannelMuteCommand(rig.b(), true));

        assertThat(rig.a().isMuted()).isTrue();
        assertThat(rig.trackA().isMuted()).isTrue();
    }

    @Test
    void anUnlinkedChannelMirrorsNothing() {
        Rig rig = Rig.linked(null);

        rig.dispatcher().accept(new SetChannelVolumeCommand(rig.a(), 0.4));
        rig.dispatcher().accept(new ToggleSoloCommand(rig.trackA(), true));

        assertThat(rig.a().getVolume()).isEqualTo(0.4);
        assertThat(rig.trackA().getVolume()).isEqualTo(0.4);
        assertThat(rig.b().getVolume()).isEqualTo(1.0);
        assertThat(rig.trackB().getVolume()).isEqualTo(1.0);
        assertThat(rig.b().isSolo()).isFalse();
        assertThat(rig.handler().setVolumeCalls.get()).isEqualTo(1);
    }

    @Test
    void aLinkWithFadersUnlinkedStillMirrorsMuteButNotVolume() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track trackA = project.createAudioTrack("L");
        Track trackB = project.createAudioTrack("R");
        MixerChannel a = project.getMixerChannelForTrack(trackA);
        MixerChannel b = project.getMixerChannelForTrack(trackB);
        project.getChannelLinkManager().link(
                ChannelLink.ofPair(a.getId(), b.getId()).withLinkFaders(false));
        LinkedTrackCommandDispatcher dispatcher =
                new LinkedTrackCommandDispatcher(project, new CoreTrackIntentHandler(project));

        dispatcher.accept(new SetChannelVolumeCommand(a, 0.5));
        dispatcher.accept(new ToggleMuteCommand(trackA, true));

        assertThat(b.getVolume()).as("faders unlinked").isEqualTo(1.0);
        assertThat(trackB.getVolume()).isEqualTo(1.0);
        assertThat(b.isMuted()).as("mute/solo still linked").isTrue();
        assertThat(trackB.isMuted()).isTrue();
    }

    @Test
    void armIsExecutedWithoutMirroring() {
        Rig rig = Rig.absolutePair();

        rig.dispatcher().accept(new ToggleArmCommand(rig.trackA(), true));

        assertThat(rig.trackA().isArmed()).isTrue();
        assertThat(rig.trackB().isArmed()).isFalse();
    }

    @Test
    void aNoOpCommandMirrorsNothing() {
        Rig rig = Rig.absolutePair();
        rig.b().setVolume(0.7); // diverged partner

        rig.dispatcher().accept(new SetChannelVolumeCommand(rig.a(), 1.0)); // a is already 1.0

        assertThat(rig.b().getVolume()).as("no source change → no mirror").isEqualTo(0.7);
        assertThat(rig.handler().setVolumeCalls.get()).isEqualTo(1);
    }

    /** Counts handler invocations to prove the dispatcher never re-enters itself. */
    private static final class CountingHandler implements TrackIntentHandler {
        final AtomicInteger setVolumeCalls = new AtomicInteger();
        final AtomicInteger setPanCalls = new AtomicInteger();
        private final TrackIntentHandler delegate;

        CountingHandler(TrackIntentHandler delegate) {
            this.delegate = delegate;
        }

        @Override public void toggleMute(Track track, boolean muted) { delegate.toggleMute(track, muted); }
        @Override public void toggleSolo(Track track, boolean soloed) { delegate.toggleSolo(track, soloed); }
        @Override public void toggleArm(Track track, boolean armed) { delegate.toggleArm(track, armed); }
        @Override public void setVolume(MixerChannel channel, double volume) {
            setVolumeCalls.incrementAndGet();
            delegate.setVolume(channel, volume);
        }
        @Override public void setPan(MixerChannel channel, double pan) {
            setPanCalls.incrementAndGet();
            delegate.setPan(channel, pan);
        }
        @Override public void toggleChannelMute(MixerChannel channel, boolean muted) {
            delegate.toggleChannelMute(channel, muted);
        }
        @Override public void toggleChannelSolo(MixerChannel channel, boolean soloed) {
            delegate.toggleChannelSolo(channel, soloed);
        }
        @Override public void renameTrack(Track track, String name) {
            delegate.renameTrack(track, name);
        }
    }
}
