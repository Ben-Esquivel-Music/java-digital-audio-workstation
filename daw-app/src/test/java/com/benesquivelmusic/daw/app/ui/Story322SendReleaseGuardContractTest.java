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
 * Story 322 contract (probe-derived, permanent; fix-round 2 probe "c") — the
 * no-op send-release guard (R2-3) on a three-track project: middle track
 * linked to the third with Link Sends, odd gains / pans, two return buses,
 * PRE_INSERTS sends at 0.29 on the pair, an unlinked 0.44 POST_FADER send.
 * A linked drag that wanders and returns to 0.29 records nothing and keeps
 * both PRE_INSERTS taps; a click without a move on the unlinked channel
 * changes nothing while a click that moves it IS one entry; a real linked
 * drag is still one entry undoing / redoing both sides; and the guard's
 * partner half — a drag back to its start that CREATED the partner's send —
 * is one entry that undoes to no partner send and redoes it at the exact tap.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322SendReleaseGuardContractTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 128);

    private record Rig(DawProject project, MixerChannel kick, MixerChannel voxL, MixerChannel voxR,
                       MixerChannel verb, MixerChannel delay) {

        static Rig build() {
            DawProject project = new DawProject("Probe2", FORMAT);
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
            voxR.setVolume(0.81);
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

        void assertBystanders() {
            assertThat(send(kick, verb).getLevel()).isEqualTo(0.44);
            assertThat(send(kick, verb).getTap()).isEqualTo(SendTap.POST_FADER);
            assertThat(send(kick, delay)).isNull();
            assertThat(send(voxL, verb).getLevel()).isEqualTo(0.13);
            assertThat(send(voxR, verb).getLevel()).isEqualTo(0.13);
            assertThat(kick.getVolume()).isEqualTo(0.37);
            assertThat(voxL.getVolume()).isEqualTo(0.37);
            assertThat(voxR.getVolume()).isEqualTo(0.81);
            assertThat(voxL.getPan()).isEqualTo(-0.62);
            assertThat(voxR.getPan()).isEqualTo(0.81);
        }
    }

    @Test
    void aLinkedPreInsertsDragThatWandersAndReturnsTo029RecordsNothingAndKeepsBothTaps() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow lDelay = view.getSendRow(rig.voxL().getId(), rig.delay());
                MixerView.SendRow rDelay = view.getSendRow(rig.voxR().getId(), rig.delay());
                Send leftSend = rig.send(rig.voxL(), rig.delay());
                Send rightSend = rig.send(rig.voxR(), rig.delay());
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                int before = undo.undoSize();

                lDelay.slider().fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                lDelay.slider().setValue(0.55);
                assertThat(leftSend.getLevel()).isEqualTo(0.55);
                assertThat(rightSend.getLevel()).as("mirror followed live").isEqualTo(0.55);
                lDelay.slider().setValue(0.02);
                assertThat(rightSend.getLevel()).isEqualTo(0.02);
                lDelay.slider().setValue(0.29);
                lDelay.slider().fireEvent(mouse(MouseEvent.MOUSE_RELEASED));

                assertThat(rig.send(rig.voxL(), rig.delay())).isSameAs(leftSend);
                assertThat(rig.send(rig.voxR(), rig.delay())).isSameAs(rightSend);
                assertThat(leftSend.getLevel()).isEqualTo(0.29);
                assertThat(rightSend.getLevel()).isEqualTo(0.29);
                assertThat(leftSend.getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rightSend.getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(lDelay.tapButton().getText()).isEqualTo("I");
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");
                assertThat(undo.undoSize()).as("no entry").isEqualTo(before);
                assertThat(undo.canRedo()).isFalse();
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);
                rig.assertBystanders();
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void aClickWithoutAMoveOnAnUnlinkedChannelWithAnExisting044SendChangesNothing() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow kVerb = view.getSendRow(rig.kick().getId(), rig.verb());
                Send kickSend = rig.send(rig.kick(), rig.verb());
                assertThat(kVerb.slider().getValue()).isEqualTo(0.44);
                int before = undo.undoSize();

                kVerb.slider().fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                kVerb.slider().fireEvent(mouse(MouseEvent.MOUSE_RELEASED));

                assertThat(rig.send(rig.kick(), rig.verb())).isSameAs(kickSend);
                assertThat(kickSend.getLevel()).isEqualTo(0.44);
                assertThat(kickSend.getTap()).isEqualTo(SendTap.POST_FADER);
                assertThat(undo.undoSize()).isEqualTo(before);
                assertThat(kVerb.slider().getValue()).isEqualTo(0.44);
                rig.assertBystanders();

                // A click that DOES move the send to its minimum is a real change: one entry, undo back to 0.44.
                kVerb.slider().fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                kVerb.slider().setValue(0.0);
                kVerb.slider().fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
                assertThat(kickSend.getLevel()).isEqualTo(0.0);
                assertThat(undo.undoSize()).isEqualTo(before + 1);
                assertThat(undo.undo()).isTrue();
                assertThat(rig.send(rig.kick(), rig.verb())).isSameAs(kickSend);
                assertThat(kickSend.getLevel()).isEqualTo(0.44);
                assertThat(kickSend.getTap()).isEqualTo(SendTap.POST_FADER);
                assertThat(kVerb.slider().getValue()).isEqualTo(0.44);
                assertThat(rig.send(rig.voxL(), rig.verb()).getLevel()).as("unlinked: nothing mirrored").isEqualTo(0.13);
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void aRealLinkedDrag029To055StillRecordsOneEntryThatUndoesAndRedoesBothSides() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow lDelay = view.getSendRow(rig.voxL().getId(), rig.delay());
                MixerView.SendRow rDelay = view.getSendRow(rig.voxR().getId(), rig.delay());
                int before = undo.undoSize();

                drag(lDelay.slider(), 0.55);
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rig.send(rig.voxR(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(undo.undoSize()).isEqualTo(before + 1);
                assertThat(undo.undoDescription()).isEqualTo("Set Send Routing");

                assertThat(undo.undo()).isTrue();
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.29);
                assertThat(rig.send(rig.voxL(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rig.send(rig.voxR(), rig.delay()).getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);

                assertThat(undo.redo()).isTrue();
                assertThat(rig.send(rig.voxL(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(rig.send(rig.voxR(), rig.delay()).getLevel()).isEqualTo(0.55);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.55);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.55);
                assertThat(lDelay.tapButton().getText()).isEqualTo("I");
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");
                rig.assertBystanders();
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void aLinkedDragBackToItsStartThatCreatedThePartnersSendIsOneEntryUndoneToNoPartnerSend() throws Exception {
        Rig rig = Rig.build();
        rig.voxR().removeSend(rig.send(rig.voxR(), rig.delay()));
        assertThat(rig.send(rig.voxR(), rig.delay())).isNull();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow lDelay = view.getSendRow(rig.voxL().getId(), rig.delay());
                MixerView.SendRow rDelay = view.getSendRow(rig.voxR().getId(), rig.delay());
                Send leftSend = rig.send(rig.voxL(), rig.delay());
                assertThat(rDelay.slider().getValue()).isEqualTo(0.0);
                int before = undo.undoSize();

                lDelay.slider().fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                lDelay.slider().setValue(0.5);
                assertThat(rig.send(rig.voxR(), rig.delay())).as("mirror created the partner send").isNotNull();
                lDelay.slider().setValue(0.29);
                lDelay.slider().fireEvent(mouse(MouseEvent.MOUSE_RELEASED));

                // The source is back where it was, but the partner GAINED a send: the model changed.
                assertThat(rig.send(rig.voxL(), rig.delay())).isSameAs(leftSend);
                assertThat(leftSend.getLevel()).isEqualTo(0.29);
                assertThat(leftSend.getTap()).isEqualTo(SendTap.PRE_INSERTS);
                Send created = rig.send(rig.voxR(), rig.delay());
                assertThat(created).isNotNull();
                assertThat(created.getLevel()).isEqualTo(0.29);
                assertThat(created.getTap()).as("created at the source's tap").isEqualTo(SendTap.PRE_INSERTS);
                assertThat(undo.undoSize()).as("one entry for the partner's creation").isEqualTo(before + 1);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);
                assertThat(rDelay.tapButton().getText()).isEqualTo("I");

                assertThat(undo.undo()).isTrue();
                assertThat(rig.send(rig.voxR(), rig.delay())).as("partner creation undone").isNull();
                assertThat(rig.send(rig.voxL(), rig.delay())).isSameAs(leftSend);
                assertThat(leftSend.getLevel()).isEqualTo(0.29);
                assertThat(leftSend.getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.0);
                assertThat(lDelay.slider().getValue()).isEqualTo(0.29);

                assertThat(undo.redo()).isTrue();
                Send recreated = rig.send(rig.voxR(), rig.delay());
                assertThat(recreated).isNotNull();
                assertThat(recreated.getLevel()).isEqualTo(0.29);
                assertThat(recreated.getTap()).isEqualTo(SendTap.PRE_INSERTS);
                assertThat(rDelay.slider().getValue()).isEqualTo(0.29);
                rig.assertBystanders();
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
