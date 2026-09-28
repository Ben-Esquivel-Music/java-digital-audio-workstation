package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.ChannelLinkManager;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — "Link Sends" is wired (Audio Engine Wiring Design Book §5.6
 * "Stereo link"): on a linked pair with {@code linkSends}, a send-level edit
 * and a tap-point change on one member mirror to the partner's send for the
 * same return bus through {@code ChannelLinkManager.applySendChange}, and the
 * partner's row (slider + tap glyph) reflects the mirrored model value without
 * re-mirroring. With {@code linkSends} off, or with no link, nothing mirrors.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LinkSendsUiTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    private record Rig(DawProject project, MixerChannel leftCh, MixerChannel rightCh,
                       UUID leftId, UUID rightId, MixerChannel verb) {

        static Rig build() {
            DawProject project = new DawProject("Link", FORMAT);
            Track left = project.createAudioTrack("Left");
            Track right = project.createAudioTrack("Right");
            MixerChannel verb = project.getMixer().addReturnBus("Verb");
            return new Rig(project, project.getMixerChannelForTrack(left),
                    project.getMixerChannelForTrack(right),
                    UUID.fromString(left.getId()), UUID.fromString(right.getId()), verb);
        }
    }

    @Test
    void sendLevelAndTapMirrorToTheLinkedPartnerInBothDirections() throws Exception {
        Rig rig = Rig.build();
        ChannelLinkManager links = rig.project().getChannelLinkManager();

        ArrangementStripFixture.onFx(() -> {
            links.link(ChannelLink.ofPair(rig.leftId(), rig.rightId()).withLinkSends(true));
            MixerView view = new MixerView(rig.project(), new UndoManager());
            try {
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                MixerView.SendRow rightRow = view.getSendRow(rig.rightId(), rig.verb());
                assertThat(leftRow).isNotNull();
                assertThat(rightRow).isNotNull();
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).as("no send before the edit").isNull();

                // Level: left → right (the partner's send is created on demand).
                leftRow.slider().setValue(0.7);
                Send leftSend = rig.leftCh().getSendForTarget(rig.verb());
                Send rightSend = rig.rightCh().getSendForTarget(rig.verb());
                assertThat(leftSend).isNotNull();
                assertThat(leftSend.getLevel()).isEqualTo(0.7);
                assertThat(rightSend).as("partner send created by the mirror").isNotNull();
                assertThat(rightSend.getLevel()).isEqualTo(0.7);
                assertThat(rightRow.slider().getValue()).as("partner row reflects the model").isEqualTo(0.7);

                // Tap: the cycler mirrors the tap point too.
                assertThat(leftSend.getTap()).isEqualTo(SendTap.POST_FADER);
                leftRow.tapButton().fire();
                assertThat(leftSend.getTap()).isEqualTo(SendTap.PRE_FADER);
                assertThat(rightSend.getTap()).as("partner tap follows").isEqualTo(SendTap.PRE_FADER);
                assertThat(leftRow.tapButton().getText()).isEqualTo("F");
                assertThat(rightRow.tapButton().getText()).as("partner glyph follows").isEqualTo("F");

                // Reverse direction: right → left, and the reflection did not
                // ping-pong (the left level is exactly the one edit).
                rightRow.slider().setValue(0.4);
                assertThat(rightSend.getLevel()).isEqualTo(0.4);
                assertThat(leftSend.getLevel()).isEqualTo(0.4);
                assertThat(leftRow.slider().getValue()).isEqualTo(0.4);
                assertThat(leftSend.getTap()).as("tap untouched by a level edit").isEqualTo(SendTap.PRE_FADER);
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void nothingMirrorsWhenLinkSendsIsOffOrThePairIsUnlinked() throws Exception {
        Rig rig = Rig.build();
        ChannelLinkManager links = rig.project().getChannelLinkManager();

        ArrangementStripFixture.onFx(() -> {
            // Linked, but sends not linked (the chain-glyph toggle's default).
            links.link(ChannelLink.ofPair(rig.leftId(), rig.rightId()).withLinkSends(false));
            assertThat(links.getLink(rig.leftId()).linkSends()).isFalse();
            MixerView view = new MixerView(rig.project(), new UndoManager());
            try {
                view.getSendRow(rig.leftId(), rig.verb()).slider().setValue(0.5);
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.5);
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).as("linkSends off").isNull();
                assertThat(view.getSendRow(rig.rightId(), rig.verb()).slider().getValue()).isEqualTo(0.0);

                // Turn it on through the popover's model path: the manager's
                // replace() fires the view's link listener, which rebuilds the strips.
                links.replace(links.getLink(rig.leftId()).withLinkSends(true));
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                assertThat(leftRow.slider().getValue()).as("rebuilt row seeded from the model").isEqualTo(0.5);
                leftRow.slider().setValue(0.6);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).as("now mirrored").isEqualTo(0.6);

                // Unlinked: no mirroring at all.
                links.unlink(rig.leftId());
                view.getSendRow(rig.leftId(), rig.verb()).slider().setValue(0.9);
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.9);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).as("unlinked").isEqualTo(0.6);
            } finally {
                view.dispose();
            }
        });
    }

    /**
     * Story 322 fix round 1 (S6): a linked send drag is ONE history entry for
     * the pair — the per-tick mirror moved the partner's send too, so undo
     * restores both sends (level and existence) and redo re-applies both, with
     * both rows re-seeded from the model; a tap edit on a linked pair is one
     * entry the same way.
     */
    @Test
    void undoOfALinkedSendDragRestoresBothSendsAndRedoReappliesBoth() throws Exception {
        Rig rig = Rig.build();
        ChannelLinkManager links = rig.project().getChannelLinkManager();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            links.link(ChannelLink.ofPair(rig.leftId(), rig.rightId()).withLinkSends(true));
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                MixerView.SendRow rightRow = view.getSendRow(rig.rightId(), rig.verb());
                int entriesBefore = undo.undoSize();

                // A drag over a pair with no sends yet: both are created live.
                drag(leftRow.slider(), 0.7);
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.7);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.7);
                assertThat(undo.undoSize()).as("one entry for the pair").isEqualTo(entriesBefore + 1);

                assertThat(undo.undo()).isTrue();
                assertThat(rig.leftCh().getSendForTarget(rig.verb())).as("source send undone").isNull();
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).as("partner send undone").isNull();
                assertThat(leftRow.slider().getValue()).as("source row re-seeded").isEqualTo(0.0);
                assertThat(rightRow.slider().getValue()).as("partner row re-seeded").isEqualTo(0.0);

                assertThat(undo.redo()).isTrue();
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.7);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).as("partner redone").isEqualTo(0.7);
                assertThat(leftRow.slider().getValue()).isEqualTo(0.7);
                assertThat(rightRow.slider().getValue()).as("partner row re-seeded on redo").isEqualTo(0.7);

                // A drag over existing sends: undo restores both levels.
                drag(rightRow.slider(), 0.3);
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.3);
                assertThat(undo.undoSize()).isEqualTo(entriesBefore + 2);
                assertThat(undo.undo()).isTrue();
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).as("source back").isEqualTo(0.7);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).as("partner back").isEqualTo(0.7);
                assertThat(leftRow.slider().getValue()).isEqualTo(0.7);
                assertThat(rightRow.slider().getValue()).isEqualTo(0.7);

                // The tap cycler on a linked pair: one entry, undo restores both taps.
                leftRow.tapButton().fire();
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getTap()).isEqualTo(SendTap.PRE_FADER);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getTap()).isEqualTo(SendTap.PRE_FADER);
                assertThat(undo.undoSize()).isEqualTo(entriesBefore + 2);
                assertThat(undo.undo()).isTrue();
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getTap()).isEqualTo(SendTap.POST_FADER);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getTap()).as("partner tap back").isEqualTo(SendTap.POST_FADER);
                assertThat(leftRow.tapButton().getText()).isEqualTo("P");
                assertThat(rightRow.tapButton().getText()).as("partner glyph re-seeded").isEqualTo("P");
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void undoOfAnUnlinkedSendDragTouchesOnlyTheDraggedChannel() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();
        rig.rightCh().addSend(new Send(rig.verb(), 0.5, SendTap.POST_FADER));

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                MixerView.SendRow rightRow = view.getSendRow(rig.rightId(), rig.verb());
                assertThat(rightRow.slider().getValue()).isEqualTo(0.5);

                drag(leftRow.slider(), 0.7);
                assertThat(rig.leftCh().getSendForTarget(rig.verb()).getLevel()).isEqualTo(0.7);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).as("no link, no mirror").isEqualTo(0.5);

                assertThat(undo.undo()).isTrue();
                assertThat(rig.leftCh().getSendForTarget(rig.verb())).as("dragged send undone").isNull();
                assertThat(leftRow.slider().getValue()).isEqualTo(0.0);
                assertThat(rig.rightCh().getSendForTarget(rig.verb()).getLevel()).as("the other channel untouched").isEqualTo(0.5);
                assertThat(rightRow.slider().getValue()).isEqualTo(0.5);
            } finally {
                view.dispose();
            }
        });
    }

    /**
     * Story 322 fix round 2 (R2-3): a press / release on a send slider that
     * moves nothing — no tick fired — writes nothing and records nothing.
     * Before the guard the release restored the (unchanged) pre-drag state
     * and executed {@code SetSendRoutingAction} unconditionally, which
     * CREATED a 0.0 send on the source and, on a linked pair, on the partner
     * too — a send a SEND_LEVEL lane would then drive.
     */
    @Test
    void aPressAndReleaseWithoutAMoveOnALinkedPairCreatesNoSendAndNoEntry() throws Exception {
        Rig rig = Rig.build();
        ChannelLinkManager links = rig.project().getChannelLinkManager();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            links.link(ChannelLink.ofPair(rig.leftId(), rig.rightId()).withLinkSends(true));
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                assertThat(rig.leftCh().getSendForTarget(rig.verb())).isNull();
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).isNull();
                int entriesBefore = undo.undoSize();

                pressAndRelease(leftRow.slider());

                assertThat(rig.leftCh().getSendForTarget(rig.verb())).as("no send on the source").isNull();
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).as("no send on the partner").isNull();
                assertThat(undo.undoSize()).as("no entry").isEqualTo(entriesBefore);
                assertThat(leftRow.slider().getValue()).isEqualTo(0.0);
            } finally {
                view.dispose();
            }
        });
    }

    @Test
    void aPressAndReleaseWithoutAMoveOnAnUnlinkedChannelCreatesNoSendAndNoEntry() throws Exception {
        Rig rig = Rig.build();
        UndoManager undo = new UndoManager();

        ArrangementStripFixture.onFx(() -> {
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                int entriesBefore = undo.undoSize();

                pressAndRelease(leftRow.slider());

                assertThat(rig.leftCh().getSendForTarget(rig.verb())).as("no send").isNull();
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).isNull();
                assertThat(undo.undoSize()).as("no entry").isEqualTo(entriesBefore);
            } finally {
                view.dispose();
            }
        });
    }

    /**
     * Story 322 fix round 2 (R2-3): a drag that returns to its start value
     * leaves the live model exactly where it was — the source's send and the
     * linked partner's, which the per-tick mirror moved out and back — so
     * the release compares live state with the pre-drag capture and records
     * nothing (the round-1 release pushed a "Set Send Routing" entry that
     * undid to the same values).
     */
    @Test
    void aDragThatReturnsToItsStartLeavesTheModelUnchangedAndRecordsNoEntry() throws Exception {
        Rig rig = Rig.build();
        ChannelLinkManager links = rig.project().getChannelLinkManager();
        UndoManager undo = new UndoManager();
        rig.leftCh().addSend(new Send(rig.verb(), 0.3, SendTap.PRE_FADER));
        rig.rightCh().addSend(new Send(rig.verb(), 0.3, SendTap.PRE_FADER));

        ArrangementStripFixture.onFx(() -> {
            links.link(ChannelLink.ofPair(rig.leftId(), rig.rightId()).withLinkSends(true));
            MixerView view = new MixerView(rig.project(), undo);
            try {
                MixerView.SendRow leftRow = view.getSendRow(rig.leftId(), rig.verb());
                MixerView.SendRow rightRow = view.getSendRow(rig.rightId(), rig.verb());
                assertThat(leftRow.slider().getValue()).isEqualTo(0.3);
                Send leftSend = rig.leftCh().getSendForTarget(rig.verb());
                Send rightSend = rig.rightCh().getSendForTarget(rig.verb());
                int entriesBefore = undo.undoSize();

                leftRow.slider().fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                leftRow.slider().setValue(0.5);
                assertThat(leftSend.getLevel()).as("the tick moved the model live").isEqualTo(0.5);
                assertThat(rightSend.getLevel()).as("and the mirror followed").isEqualTo(0.5);
                leftRow.slider().setValue(0.3);
                leftRow.slider().fireEvent(mouse(MouseEvent.MOUSE_RELEASED));

                assertThat(rig.leftCh().getSendForTarget(rig.verb())).as("same source send").isSameAs(leftSend);
                assertThat(rig.rightCh().getSendForTarget(rig.verb())).as("same partner send").isSameAs(rightSend);
                assertThat(leftSend.getLevel()).isEqualTo(0.3);
                assertThat(rightSend.getLevel()).isEqualTo(0.3);
                assertThat(leftSend.getTap()).isEqualTo(SendTap.PRE_FADER);
                assertThat(rightSend.getTap()).isEqualTo(SendTap.PRE_FADER);
                assertThat(undo.undoSize()).as("no entry for a drag back to its start").isEqualTo(entriesBefore);
                assertThat(leftRow.slider().getValue()).isEqualTo(0.3);
                assertThat(rightRow.slider().getValue()).isEqualTo(0.3);
            } finally {
                view.dispose();
            }
        });
    }

    /** The production gesture: press, the value ticks (model + mirror follow live), release. */
    private static void drag(Slider slider, double to) {
        slider.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
        slider.setValue(to);
        slider.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
    }

    /** A click on the slider that never moves it: press, release, no tick. */
    private static void pressAndRelease(Slider slider) {
        slider.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
        slider.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
    }

    private static MouseEvent mouse(EventType<MouseEvent> type) {
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false,
                type == MouseEvent.MOUSE_PRESSED, false, false, false, false, false, null);
    }
}
