package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.db;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.isActive;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.onFx;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.peak;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 contract (probe-derived, kept as a permanent guard for the §5.6 "one intent path, both surfaces" row) — end-to-end cross-surface truth through the production
 * wiring shape (registry + {@code CoreTrackIntentHandler} +
 * {@code LinkedTrackCommandDispatcher}) over THREE tracks whose middle one is
 * stereo-linked to the third. Every start value is non-default (0.81 faders,
 * a −0.62 kick pan) and every gesture value differs from every default.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322CrossSurfaceContractTest {

    private static final double VOL_START = 0.81;
    private static final double PAN_KICK = -0.62;
    private static final float SIGNAL = 0.9f;
    private static final double TOL = 1e-4;

    private static double channelLeft(double pan, double gain) {
        return Math.cos((pan + 1.0) * Math.PI / 4.0) * gain;
    }

    private static double channelRight(double pan, double gain) {
        return Math.sin((pan + 1.0) * Math.PI / 4.0) * gain;
    }

    /** FX thread only. */
    private static final class World implements AutoCloseable {
        final DawProject project = new DawProject("Probe322", Story322ContractRig.FORMAT);
        final Track kick;
        final Track snareL;
        final Track snareR;
        final MixerChannel chKick;
        final MixerChannel chSnareL;
        final MixerChannel chSnareR;
        final Story322ContractRig rig;

        World() {
            kick = project.createAudioTrack("Kick");
            snareL = project.createAudioTrack("Snare L");
            snareR = project.createAudioTrack("Snare R");
            chKick = project.getMixerChannelForTrack(kick);
            chSnareL = project.getMixerChannelForTrack(snareL);
            chSnareR = project.getMixerChannelForTrack(snareR);
            for (Track t : List.of(kick, snareL, snareR)) {
                t.setVolume(VOL_START);
                project.getMixerChannelForTrack(t).setVolume(VOL_START);
            }
            kick.setPan(PAN_KICK);
            chKick.setPan(PAN_KICK);
            // The middle track is linked to the third (faders, pans, mute/solo, sends; RELATIVE).
            project.getChannelLinkManager().link(ChannelLink.ofPair(chSnareL.getId(), chSnareR.getId()));
            rig = new Story322ContractRig(project);
            rig.lane(kick);
            rig.lane(snareL);
            rig.lane(snareR);
        }

        @Override
        public void close() {
            rig.close();
        }
    }

    @Test
    void anArrangementMuteSilencesTheRenderedBlockAndFlipsTheMixerStripAndBack() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                var lane = w.rig.laneControls(w.kick);
                MixerChannelStrip strip = w.rig.strip(0);
                assertThat(strip.isMuted()).isFalse();
                assertThat(isActive(lane.muteBtn())).isFalse();
                float[][] before = w.rig.render(0, SIGNAL);
                assertThat(peak(before[0])).isCloseTo(SIGNAL * channelLeft(PAN_KICK, VOL_START), within(TOL));
                assertThat(peak(before[1])).isCloseTo(SIGNAL * channelRight(PAN_KICK, VOL_START), within(TOL));

                lane.muteBtn().fire();

                assertThat(w.kick.isMuted()).as("Track").isTrue();
                assertThat(w.chKick.isMuted()).as("MixerChannel — what the engine reads").isTrue();
                assertThat(strip.isMuted()).as("mixer strip bound control").isTrue();
                assertThat(isActive(lane.muteBtn())).as("arrangement :active").isTrue();
                float[][] muted = w.rig.render(0, SIGNAL);
                assertThat(peak(muted[0])).isZero();
                assertThat(peak(muted[1])).isZero();
                assertThat(w.snareL.isMuted()).as("an unlinked neighbour is untouched").isFalse();
                assertThat(w.chSnareL.isMuted()).isFalse();

                strip.setMuted(false); // the reverse direction, from the mixer strip

                assertThat(w.kick.isMuted()).isFalse();
                assertThat(w.chKick.isMuted()).isFalse();
                assertThat(isActive(lane.muteBtn())).isFalse();
                assertThat(peak(w.rig.render(0, SIGNAL)[0])).isCloseTo(peak(before[0]), within(TOL));
            }
        });
    }

    @Test
    void aMixerStripMToggleClickFlipsTheArrangementButtonAndSilencesTheBlock() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                StackPane host = new StackPane(w.rig.mixerView);
                new Scene(host, 1400, 900);
                host.applyCss();
                host.layout();
                MixerChannelStrip strip = w.rig.strip(0);
                Node toggle = strip.lookup(".track-toggle.mute");
                assertThat(toggle).as("the skin's M toggle exists once the strip has a scene").isInstanceOf(ToggleButton.class);
                ToggleButton m = (ToggleButton) toggle;
                var lane = w.rig.laneControls(w.kick);

                m.fire();

                assertThat(w.kick.isMuted()).isTrue();
                assertThat(w.chKick.isMuted()).isTrue();
                assertThat(strip.isMuted()).isTrue();
                assertThat(isActive(lane.muteBtn())).isTrue();
                assertThat(peak(w.rig.render(0, SIGNAL)[0])).isZero();

                m.fire();

                assertThat(w.kick.isMuted()).isFalse();
                assertThat(w.chKick.isMuted()).isFalse();
                assertThat(isActive(lane.muteBtn())).isFalse();
                assertThat(peak(w.rig.render(0, SIGNAL)[0])).isGreaterThan(0.1);
            }
        });
    }

    @Test
    void aVolumeOf037FromTheArrangementSliderReachesTrackChannelStripAndTheRenderedBlock() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                var lane = w.rig.laneControls(w.kick);
                MixerChannelStrip strip = w.rig.strip(0);
                assertThat(strip.getFaderDb()).isCloseTo(db(VOL_START), within(1e-9));

                lane.volumeSlider().setValue(0.37);

                assertThat(w.kick.getVolume()).as("Track").isEqualTo(0.37);
                assertThat(w.chKick.getVolume()).as("MixerChannel").isEqualTo(0.37);
                assertThat(strip.getFaderDb()).as("mixer strip fader in dB").isCloseTo(db(0.37), within(1e-9));
                float[][] out = w.rig.render(0, SIGNAL);
                assertThat(peak(out[0])).isCloseTo(SIGNAL * channelLeft(PAN_KICK, 0.37), within(TOL));
                assertThat(peak(out[1])).isCloseTo(SIGNAL * channelRight(PAN_KICK, 0.37), within(TOL));

                lane.panSlider().setValue(0.81);

                assertThat(w.kick.getPan()).isEqualTo(0.81);
                assertThat(w.chKick.getPan()).isEqualTo(0.81);
                assertThat(strip.getPan()).isEqualTo(0.81);
                out = w.rig.render(0, SIGNAL);
                assertThat(peak(out[0])).isCloseTo(SIGNAL * channelLeft(0.81, 0.37), within(TOL));
                assertThat(peak(out[1])).isCloseTo(SIGNAL * channelRight(0.81, 0.37), within(TOL));

                strip.setFaderDb(-12.7); // the reverse direction, from the mixer strip
                double linear = Math.pow(10.0, -12.7 / 20.0);
                assertThat(w.chKick.getVolume()).isCloseTo(linear, within(1e-12));
                assertThat(w.kick.getVolume()).isCloseTo(linear, within(1e-12));
                assertThat(lane.volumeSlider().getValue()).isCloseTo(linear, within(1e-12));
                assertThat(peak(w.rig.render(0, SIGNAL)[1]))
                        .isCloseTo(SIGNAL * channelRight(0.81, linear), within(TOL));
            }
        });
    }

    @Test
    void aLinkedPairFollowsInBothModelsAndBothSurfacesWhileTheUnlinkedTrackStaysPut() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                var laneL = w.rig.laneControls(w.snareL);
                var laneR = w.rig.laneControls(w.snareR);
                var laneKick = w.rig.laneControls(w.kick);
                MixerChannelStrip stripL = w.rig.strip(1);
                MixerChannelStrip stripR = w.rig.strip(2);

                laneL.volumeSlider().setValue(0.37); // RELATIVE: partner 0.81 + (0.37 − 0.81)

                assertThat(w.chSnareL.getVolume()).isEqualTo(0.37);
                assertThat(w.snareL.getVolume()).isEqualTo(0.37);
                assertThat(w.chSnareR.getVolume()).as("partner channel").isCloseTo(0.37, within(1e-12));
                assertThat(w.snareR.getVolume()).as("partner Track (Track-only heal)").isCloseTo(0.37, within(1e-12));
                assertThat(stripR.getFaderDb()).isCloseTo(db(0.37), within(1e-6));
                assertThat(laneR.volumeSlider().getValue()).isCloseTo(0.37, within(1e-12));
                assertThat(w.kick.getVolume()).isEqualTo(VOL_START);
                assertThat(laneKick.volumeSlider().getValue()).isEqualTo(VOL_START);

                laneL.panSlider().setValue(-0.62); // pan mirrors around centre

                assertThat(w.chSnareR.getPan()).isEqualTo(0.62);
                assertThat(w.snareR.getPan()).isEqualTo(0.62);
                assertThat(stripR.getPan()).isEqualTo(0.62);
                assertThat(laneR.panSlider().getValue()).isEqualTo(0.62);
                assertThat(stripL.getPan()).isEqualTo(-0.62);
                assertThat(w.kick.getPan()).isEqualTo(PAN_KICK);

                stripL.setMuted(true); // from the mixer strip of the middle track

                assertThat(w.snareR.isMuted()).isTrue();
                assertThat(w.chSnareR.isMuted()).isTrue();
                assertThat(stripR.isMuted()).isTrue();
                assertThat(isActive(laneR.muteBtn())).isTrue();
                assertThat(isActive(laneL.muteBtn())).isTrue();
                assertThat(w.kick.isMuted()).isFalse();
                assertThat(isActive(laneKick.muteBtn())).isFalse();

                laneR.soloBtn().fire(); // from the arrangement lane of the third track

                assertThat(w.snareL.isSolo()).isTrue();
                assertThat(w.chSnareL.isSolo()).isTrue();
                assertThat(stripL.isSoloed()).isTrue();
                assertThat(isActive(laneL.soloBtn())).isTrue();
                assertThat(w.kick.isSolo()).isFalse();
                assertThat(peak(w.rig.render(0, SIGNAL)[0])).as("the un-soloed kick is gated").isZero();
            }
        });
    }

    @Test
    void aVcaStripMuteReachesTrackChannelAndTheArrangementButtonOfEveryMember() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                w.project.getVcaGroupManager().createVcaGroup("Drums", List.of(w.chKick.getId(), w.chSnareL.getId()));
                w.rig.mixerView.refresh();
                VcaStrip vca = w.rig.mixerView.getVcaStrips().getChildren().stream()
                        .filter(VcaStrip.class::isInstance).map(VcaStrip.class::cast)
                        .findFirst().orElseThrow();

                vca.getMuteButton().fire();

                assertThat(w.kick.isMuted()).isTrue();
                assertThat(w.chKick.isMuted()).isTrue();
                assertThat(w.snareL.isMuted()).isTrue();
                assertThat(w.chSnareL.isMuted()).isTrue();
                assertThat(w.snareR.isMuted()).as("the linked partner follows the member").isTrue();
                assertThat(w.chSnareR.isMuted()).isTrue();
                assertThat(isActive(w.rig.laneControls(w.kick).muteBtn())).isTrue();
                assertThat(isActive(w.rig.laneControls(w.snareL).muteBtn())).isTrue();
                assertThat(isActive(w.rig.laneControls(w.snareR).muteBtn())).isTrue();
                assertThat(w.rig.strip(0).isMuted()).isTrue();
                assertThat(peak(w.rig.render(0, SIGNAL)[0])).isZero();

                vca.getMuteButton().fire();

                assertThat(w.kick.isMuted()).isFalse();
                assertThat(w.chKick.isMuted()).isFalse();
                assertThat(w.snareL.isMuted()).isFalse();
                assertThat(w.snareR.isMuted()).isFalse();
                assertThat(isActive(w.rig.laneControls(w.kick).muteBtn())).isFalse();
                assertThat(w.rig.strip(0).isMuted()).isFalse();
            }
        });
    }
}
