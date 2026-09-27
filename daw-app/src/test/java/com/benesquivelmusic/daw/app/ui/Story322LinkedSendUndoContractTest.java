package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendTap;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.event.EventType;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract (probe-derived, permanent; fix-round 1 probe "a") — the
 * Link Sends undo compound (S6) under non-default inputs: three tracks (the
 * MIDDLE one linked to the third with Link Sends), odd gains / pans, TWO
 * return buses whose sends sit at different taps, and a drag 0.13 -> 0.71 on
 * the middle track. Undo / redo must move exactly the pair's sends to the
 * dragged bus and both sliders, nothing else.
 *
 * <p>The PRE_INSERTS cases pin the trap {@code SendMode} cannot express that
 * tap: the compound's partner steps carry the exact {@code SendTap}, so a
 * linked drag over PRE_INSERTS sends — and one that CREATES the partner's send
 * — keeps / reproduces PRE_INSERTS on both members through undo and redo.
 * {@code LinkSendsUiTest} exercises POST_FADER / PRE_FADER only.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322LinkedSendUndoContractTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 128);

    private record Rig(DawProject project,
                       MixerChannel kick, MixerChannel voxL, MixerChannel voxR,
                       MixerChannel verb, MixerChannel delay) {

        static Rig build() {
            DawProject project = new DawProject("Probe", FORMAT);
            Track kickTrack = project.createAudioTrack("Kick");
            Track voxLTrack = project.createAudioTrack("Vox L");
            Track voxRTrack = project.createAudioTrack("Vox R");
            MixerChannel kick = project.getMixerChannelForTrack(kickTrack);
            MixerChannel voxL = project.getMixerChannelForTrack(voxLTrack);
            MixerChannel voxR = project.getMixerChannelForTrack(voxRTrack);
            kick.setVolume(0.37);
            kick.setPan(-0.62);
            voxL.setVolume(0.37);
            voxL.setPan(-0.62);
            voxR.setVolume(0.37);
            voxR.setPan(0.81);
            MixerChannel verb = project.getMixer().getAuxBus();
            MixerChannel delay = project.getMixer().addReturnBus("Delay");
            kick.addSend(new Send(verb, 0.44, SendTap.POST_FADER));
            voxL.addSend(new Send(verb, 0.13, SendTap.POST_FADER));
            voxR.addSend(new Send(verb, 0.13, SendTap.POST_FADER));
            voxL.addSend(new Send(delay, 0.29, SendTap.PRE_INSERTS));
            voxR.addSend(new Send(delay, 0.29, SendTap.PRE_INSERTS));
            project.getChannelLinkManager().link(
                    ChannelLink.ofPair(voxL.getId(), voxR.getId()).withLinkSends(true));
            return new Rig(project, kick, voxL, voxR, verb, delay);
        }

        Send send(MixerChannel channel, MixerChannel bus) {
            return channel.getSendForTarget(bus);
        }

        void assertUntouchedBystanders() {
            assertThat(send(kick, verb).getLevel()).as("unlinked kick send").isEqualTo(0.44);
            assertThat(send(kick, verb).getTap()).isEqualTo(SendTap.POST_FADER);
            assertThat(send(kick, delay)).as("kick never sent to delay").isNull();
        }
    }

    @Test
    void aLinkedDrag013To071OnTheMiddleTrackUndoesAndRedoesBothVerbSendsAndBothSlidersOnly() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow lVerb = view.getSendRow(rig.voxL().getId(), rig.verb());
                MixerView.SendRow rVerb = view.getSendRow(rig.voxR().getId(), rig.verb());
                MixerView.SendRow lDelay = view.getSendRow(rig.voxL().getId(), rig.delay());
                MixerView.SendRow rDelay = view.getSendRow(rig.voxR().getId(), rig.delay());
                MixerView.SendRow kVerb = view.getSendRow(rig.kick().getId(), rig.verb());
                assertThat(lVerb.slider().getValue()).isEqualTo(0.13);
                assertThat(rVerb.slider().getValue()).isEqualTo(0.13);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(kVerb.slider().getValue()).isEqualTo(0.44);
                assertThat(lDelay.tapButton().getText()).isEqualTo("I");
                int before = undo.undoSize();

                drag(lVerb.slider(), 0.71);
                assertThat(rig.send(rig.voxL(), rig.verb()).getLevel()).as("source").isEqualTo(0.71);
                assertThat(rig.send(rig.voxR(), rig.verb()).getLevel()).as("partner mirrored").isEqualTo(0.71);
                assertThat(rig.send(rig.voxL(), rig.verb()).getTap()).isEqualTo(SendTap.POST_FADER);
                assertThat(rig.send(rig.voxR(), rig.verb()).getTap()).isEqualTo(SendTap.POST_FADER);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).as("other bus untouched").isEqualTo(0.29);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rig.send(rig.voxR(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                rig.assertUntouchedBystanders();
                assertThat(lVerb.slider().getValue()).isEqualTo(0.71);
                assertThat(rVerb.slider().getValue()).as("partner slider").isEqualTo(0.71);
                assertThat(undo.undoSize()).as("ONE entry for the linked drag").isEqualTo(before + 1);
                assertThat(undo.undoDescription()).isEqualTo("Set Send Routing");

                assertThat(undo.undo()).isTrue();
                assertThat(rig.send(rig.voxL(), rig.verb()).getLevel()).as("source undone").isEqualTo(0.13);
                assertThat(rig.send(rig.voxR(), rig.verb()).getLevel()).as("partner undone").isEqualTo(0.13);
                assertThat(lVerb.slider().getValue()).as("source slider re-seeded").isEqualTo(0.13);
                assertThat(rVerb.slider().getValue()).as("partner slider re-seeded").isEqualTo(0.13);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);
                rig.assertUntouchedBystanders();
                assertThat(kVerb.slider().getValue()).isEqualTo(0.44);

                assertThat(undo.redo()).isTrue();
                assertThat(rig.send(rig.voxL(), rig.verb()).getLevel()).as("source redone").isEqualTo(0.71);
                assertThat(rig.send(rig.voxR(), rig.verb()).getLevel()).as("partner redone").isEqualTo(0.71);
                assertThat(lVerb.slider().getValue()).isEqualTo(0.71);
                assertThat(rVerb.slider().getValue()).isEqualTo(0.71);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.29);
                rig.assertUntouchedBystanders();
                assertThat(undo.undoSize()).isEqualTo(before + 1);
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void aLinkedDragOnPreInsertsSendsKeepsBothTapsAndGlyphsThroughUndoAndRedo() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow lDelay = view.getSendRow(rig.voxL().getId(), rig.delay());
                MixerView.SendRow rDelay = view.getSendRow(rig.voxR().getId(), rig.delay());

                drag(rDelay.slider(), 0.55);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).as("partner mirrored").isEqualTo(0.55);
                assertThat(rig.send(rig.voxR(), rig.delay()).getTap()).as("source tap kept").isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).as("partner tap kept").isEqualTo(SendTap.PRE_INSERTS);
                assertThat(lDelay.tapButton().getText()).isEqualTo("I");
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");

                assertThat(undo.undo()).isTrue();
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxR(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(lDelay.tapButton().getText()).isEqualTo("I");
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");

                assertThat(undo.redo()).isTrue();
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(rig.send(rig.voxR(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                rig.assertUntouchedBystanders();
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void aLinkedDragThatCreatesThePartnerSendAtThePreInsertsTapIsUndoneToNoSendAndRedoneExactly() throws Exception {
        Rig rig = Rig.build();
        rig.voxR().removeSend(rig.send(rig.voxR(), rig.delay()));
        assertThat(rig.send(rig.voxR(), rig.delay())).isNull();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow lDelay = view.getSendRow(rig.voxL().getId(), rig.delay());
                MixerView.SendRow rDelay = view.getSendRow(rig.voxR().getId(), rig.delay());
                assertThat(rDelay.slider().getValue()).isEqualTo(0.0);
                int before = undo.undoSize();

                drag(lDelay.slider(), 0.71);
                Send created = rig.send(rig.voxR(), rig.delay());
                assertThat(created).as("partner send created by the linked drag").isNotNull();
                assertThat(created.getLevel()).isEqualTo(0.71);
                assertThat(created.getTap()).as("created at the exact source tap").isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.71);
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");
                assertThat(undo.undoSize()).isEqualTo(before + 1);

                assertThat(undo.undo()).isTrue();
                assertThat(rig.send(rig.voxR(), rig.delay())).as("partner creation undone").isNull();
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rDelay.slider().getValue()).as("partner row shows no send").isEqualTo(0.0);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(lDelay.tapButton().getText()).isEqualTo("I");

                assertThat(undo.redo()).isTrue();
                Send recreated = rig.send(rig.voxR(), rig.delay());
                assertThat(recreated).isNotNull();
                assertThat(recreated.getLevel()).isEqualTo(0.71);
                assertThat(recreated.getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.71);
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");
                rig.assertUntouchedBystanders();
            } finally {
                view.dispose();
            }
        });
    }

    private static void drag(Slider slider, double to) {
        slider.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
        slider.setValue(to);
        slider.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
    }

    private static MouseEvent mouse(EventType<MouseEvent> type) {
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false,
                type == MouseEvent.MOUSE_PRESSED, false, false, false, false, false, null);
    }
}
