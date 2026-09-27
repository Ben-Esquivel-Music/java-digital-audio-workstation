package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.css.PseudoClass;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — the cross-surface proof of Audio Engine Wiring Design Book
 * §5.6 rows one and two: a mute / solo / volume / pan gesture on the
 * arrangement strip travels the <em>shared</em> wiring (one registry, one
 * command sink), moves the mixer strip's control, dual-writes Track +
 * MixerChannel, and audibly changes a block rendered by {@link Mixer#mixDown}
 * — and the reverse direction from the mixer strip does the same to the
 * arrangement strip.
 *
 * <p>The mixer strip is a {@link MixerChannelStrip} (story 271 skin swap):
 * its fader is in dB, so a mixer volume gesture is {@code setFaderDb(db(v))}
 * and the linear value the command carries is the strip's {@code 10^(dB/20)}
 * round-trip of {@code v} — exact to well below {@value #EPS}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CrossSurfaceControlTruthTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final int FRAMES = 64;
    /** The channel pan law at centre: cos(π/4) per lane. */
    private static final double CENTRE_GAIN = Math.cos(Math.PI / 4.0);
    private static final double TOLERANCE = 1e-4;
    private static final double EPS = 1e-9;

    /** The strip fader's dB for a linear volume (the binder's law). */
    private static double db(double linear) {
        return 20.0 * Math.log10(linear);
    }

    private record Surfaces(ArrangementStripFixture rig, Track kick, Track snare,
                            MixerChannel kickCh, MixerChannel snareCh,
                            TrackStripController.StripControls kickLane,
                            TrackStripController.StripControls snareLane) {

        MixerChannelStrip kickStrip() {
            return rig.mixerView.getTrackStrips().get(0).strip();
        }

        MixerChannelStrip snareStrip() {
            return rig.mixerView.getTrackStrips().get(1).strip();
        }

        Mixer mixer() {
            return rig.project.getMixer();
        }
    }

    /** FX thread only: both surfaces over the SAME wiring, commands executed. */
    private static Surfaces build(DawProject project, Track kick, Track snare) {
        ArrangementStripFixture rig = new ArrangementStripFixture(project, true);
        rig.mixerView.setTrackControlWiring(() -> rig.wiring);
        HBox kickLane = rig.addStrip(kick);
        HBox snareLane = rig.addStrip(snare);
        return new Surfaces(rig, kick, snare,
                project.getMixerChannelForTrack(kick), project.getMixerChannelForTrack(snare),
                ArrangementStripFixture.controlsOf(kickLane), ArrangementStripFixture.controlsOf(snareLane));
    }

    @Test
    void arrangementGesturesMoveTheMixerStripAndTheRenderedBlock() throws Exception {
        DawProject project = new DawProject("Truth", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");

        ArrangementStripFixture.onFx(() -> {
            Surfaces s = build(project, kick, snare);
            try {
                assertPeaks(s.mixer(), 0, CENTRE_GAIN, CENTRE_GAIN, "kick baseline");

                // Mute from the arrangement.
                s.kickLane().muteBtn().fire();
                assertThat(s.kick().isMuted()).as("Track").isTrue();
                assertThat(s.kickCh().isMuted()).as("MixerChannel").isTrue();
                assertThat(s.kickStrip().isMuted()).as("mixer strip").isTrue();
                assertThat(s.rig().raised).contains(new ToggleMuteCommand(kick, true));
                assertPeaks(s.mixer(), 0, 0.0, 0.0, "kick muted");
                assertPeaks(s.mixer(), 1, CENTRE_GAIN, CENTRE_GAIN, "snare unaffected");
                s.kickLane().muteBtn().fire();
                assertThat(s.kickStrip().isMuted()).isFalse();
                assertPeaks(s.mixer(), 0, CENTRE_GAIN, CENTRE_GAIN, "kick unmuted");

                // Volume from the arrangement.
                s.kickLane().volumeSlider().setValue(0.5);
                assertThat(s.kick().getVolume()).isEqualTo(0.5);
                assertThat(s.kickCh().getVolume()).isEqualTo(0.5);
                assertThat(s.kickStrip().getFaderDb()).as("mixer fader follows (dB)")
                        .isCloseTo(db(0.5), within(EPS));
                assertThat(s.rig().raised).contains(new SetChannelVolumeCommand(s.kickCh(), 0.5));
                assertPeaks(s.mixer(), 0, 0.5 * CENTRE_GAIN, 0.5 * CENTRE_GAIN, "kick at half");

                // Pan from the arrangement: hard left.
                s.kickLane().panSlider().setValue(-1.0);
                assertThat(s.kick().getPan()).isEqualTo(-1.0);
                assertThat(s.kickCh().getPan()).isEqualTo(-1.0);
                assertThat(s.kickStrip().getPan()).as("mixer pan follows").isEqualTo(-1.0);
                assertThat(s.rig().raised).contains(new SetChannelPanCommand(s.kickCh(), -1.0));
                assertPeaks(s.mixer(), 0, 0.5, 0.0, "kick hard left at half");

                // Solo from the arrangement: the other channel is silenced.
                s.snareLane().soloBtn().fire();
                assertThat(s.snare().isSolo()).isTrue();
                assertThat(s.snareCh().isSolo()).isTrue();
                assertThat(s.snareStrip().isSoloed()).isTrue();
                assertThat(s.rig().raised).contains(new ToggleSoloCommand(snare, true));
                assertPeaks(s.mixer(), 0, 0.0, 0.0, "kick silenced by the snare solo");
                assertPeaks(s.mixer(), 1, CENTRE_GAIN, CENTRE_GAIN, "snare soloed");
            } finally {
                s.rig().close();
            }
        });
    }

    @Test
    void mixerGesturesMoveTheArrangementStripAndTheRenderedBlock() throws Exception {
        DawProject project = new DawProject("Truth", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");

        ArrangementStripFixture.onFx(() -> {
            Surfaces s = build(project, kick, snare);
            try {
                // Mute from the mixer — what the strip's M toggle does.
                s.snareStrip().setMuted(true);
                assertThat(s.snare().isMuted()).as("Track").isTrue();
                assertThat(s.snareCh().isMuted()).as("MixerChannel").isTrue();
                assertThat(s.snareLane().muteBtn().getPseudoClassStates()).as("arrangement strip").contains(ACTIVE);
                assertThat(s.rig().raised).contains(new ToggleMuteCommand(snare, true));
                assertPeaks(s.mixer(), 1, 0.0, 0.0, "snare muted");
                s.snareStrip().setMuted(false);
                assertThat(s.snareLane().muteBtn().getPseudoClassStates()).doesNotContain(ACTIVE);

                // Volume from the mixer — what the strip's Fader does on a drag tick.
                s.snareStrip().setFaderDb(db(0.25));
                assertThat(s.snare().getVolume()).isCloseTo(0.25, within(EPS));
                assertThat(s.snareCh().getVolume()).isCloseTo(0.25, within(EPS));
                assertThat(s.snareLane().volumeSlider().getValue()).as("arrangement slider follows")
                        .isCloseTo(0.25, within(EPS));
                assertPeaks(s.mixer(), 1, 0.25 * CENTRE_GAIN, 0.25 * CENTRE_GAIN, "snare at a quarter");

                // Pan from the mixer: hard right.
                s.snareStrip().setPan(1.0);
                assertThat(s.snare().getPan()).isEqualTo(1.0);
                assertThat(s.snareCh().getPan()).isEqualTo(1.0);
                assertThat(s.snareLane().panSlider().getValue()).as("arrangement pan follows").isEqualTo(1.0);
                assertPeaks(s.mixer(), 1, 0.0, 0.25, "snare hard right at a quarter");

                // Solo from the mixer.
                s.kickStrip().setSoloed(true);
                assertThat(s.kick().isSolo()).isTrue();
                assertThat(s.kickCh().isSolo()).isTrue();
                assertThat(s.kickLane().soloBtn().getPseudoClassStates()).contains(ACTIVE);
                assertThat(s.rig().raised).contains(new ToggleSoloCommand(kick, true));
                assertPeaks(s.mixer(), 1, 0.0, 0.0, "snare silenced by the kick solo");
                assertPeaks(s.mixer(), 0, CENTRE_GAIN, CENTRE_GAIN, "kick soloed");

                // Every gesture above travelled the ONE sink both surfaces
                // share — exactly the five, no echo re-raised anything. The
                // volume command carries the strip's dB→linear round-trip.
                List<TrackCommand> raised = s.rig().raised;
                assertThat(raised).hasSize(5);
                assertThat(raised.get(0)).isEqualTo(new ToggleMuteCommand(snare, true));
                assertThat(raised.get(1)).isEqualTo(new ToggleMuteCommand(snare, false));
                assertThat(raised.get(2)).isInstanceOfSatisfying(SetChannelVolumeCommand.class, cmd -> {
                    assertThat(cmd.channel()).isSameAs(s.snareCh());
                    assertThat(cmd.volume()).isCloseTo(0.25, within(EPS));
                });
                assertThat(raised.get(3)).isEqualTo(new SetChannelPanCommand(s.snareCh(), 1.0));
                assertThat(raised.get(4)).isEqualTo(new ToggleSoloCommand(kick, true));
            } finally {
                s.rig().close();
            }
        });
    }

    /** Renders one block with unity input on {@code channelIndex} only and checks the L/R peaks. */
    private static void assertPeaks(Mixer mixer, int channelIndex, double left, double right, String what) {
        int channels = mixer.getChannels().size();
        float[][][] inputs = new float[channels][2][FRAMES];
        Arrays.fill(inputs[channelIndex][0], 1.0f);
        Arrays.fill(inputs[channelIndex][1], 1.0f);
        float[][] output = new float[2][FRAMES];
        float[][][] returns = new float[mixer.getReturnBuses().size()][2][FRAMES];
        mixer.mixDown(inputs, output, returns, FRAMES);
        assertThat(peak(output[0])).as(what + " (L)").isCloseTo(left, within(TOLERANCE));
        assertThat(peak(output[1])).as(what + " (R)").isCloseTo(right, within(TOLERANCE));
    }

    private static double peak(float[] lane) {
        double max = 0.0;
        for (float sample : lane) {
            max = Math.max(max, Math.abs(sample));
        }
        return max;
    }
}
