package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ClipEditControllerTest {

    @Test
    void deleteSelectionWithRippleUsesTimeSelectionBounds() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);
        project.setRippleMode(RippleMode.PER_TRACK);

        AudioClip removed = new AudioClip("removed", 4.0, 2.0, null);
        AudioClip later = new AudioClip("later", 16.0, 4.0, null);
        track.addClip(removed);
        track.addClip(later);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.setSelection(4.0, 12.0);
        selectionModel.selectClip(track, removed);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onDeleteSelection();

        assertThat(track.getClips()).doesNotContain(removed);
        assertThat(later.getStartBeat()).isEqualTo(8.0);

        undoManager.undo();

        assertThat(track.getClips()).contains(removed, later);
        assertThat(removed.getStartBeat()).isEqualTo(4.0);
        assertThat(later.getStartBeat()).isEqualTo(16.0);
    }

    // ── Slip keyboard shortcuts — Story 139 ─────────────────────────────────

    @Test
    void onSlipRightByGridShouldDecreaseAudioSourceOffset() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("Loop", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(5.0);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        // gridStepBeats() = 1.0 in TestHost — +1 beat right = -1 beat offset.
        controller.onSlipRightByGrid();

        assertThat(clip.getSourceOffsetBeats()).isCloseTo(4.0, within(1e-6));
    }

    @Test
    void onSlipLeftByGridShouldIncreaseAudioSourceOffset() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("Loop", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(5.0);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        // −1 beat left on timeline → +1 beat source offset.
        controller.onSlipLeftByGrid();

        assertThat(clip.getSourceOffsetBeats()).isCloseTo(6.0, within(1e-6));
    }

    @Test
    void onSlipRightByFineShouldMoveByOneSample() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("Loop", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(5.0);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        double oneSampleInBeats =
                project.getTransport().getTempoMap()
                        .getTempoAtBeat(project.getTransport().getPositionInBeats())
                        / (60.0 * project.getFormat().sampleRate());
        controller.onSlipRightByFine();

        assertThat(clip.getSourceOffsetBeats()).isCloseTo(5.0 - oneSampleInBeats, within(1e-6));
    }

    @Test
    void slipShortcutShouldBeUndoable() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("Loop", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(5.0);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onSlipRightByGrid();
        assertThat(clip.getSourceOffsetBeats()).isCloseTo(4.0, within(1e-6));

        undoManager.undo();
        assertThat(clip.getSourceOffsetBeats()).isCloseTo(5.0, within(1e-6));
    }

    @Test
    void slipClampingShouldSurfaceHitEdgeNotification() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        // Source offset is already 0 — slipping right (= decreasing offset) must clamp.
        AudioClip clip = new AudioClip("Loop", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(0.0);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onSlipRightByGrid();

        assertThat(clip.getSourceOffsetBeats()).isCloseTo(0.0, within(1e-6));
        assertThat(host.lastNotificationLevel).isEqualTo(NotificationLevel.INFO);
        assertThat(host.lastNotificationMessage).contains("clamped");
    }

    @Test
    void onSlipRightByGridShouldShiftMidiNotesByGridColumns() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("MIDI Track", TrackType.MIDI);
        project.addTrack(track);

        MidiClip midiClip = track.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 2, 100));
        midiClip.addNote(MidiNoteData.of(62, 4, 2, 100));
        midiClip.addNote(MidiNoteData.of(64, 8, 2, 100));

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectMidiClip(track, midiClip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        // gridStepBeats() = 1.0 → +4 columns (0.25 beats per column).
        controller.onSlipRightByGrid();

        List<MidiNoteData> notes = midiClip.getNotes();
        assertThat(notes.get(0).startColumn()).isEqualTo(4);
        assertThat(notes.get(1).startColumn()).isEqualTo(8);
        assertThat(notes.get(2).startColumn()).isEqualTo(12);
    }

    @Test
    void onSlipLeftByFineShouldBeNoOpForMidiWhenBelowColumnResolution() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("MIDI Track", TrackType.MIDI);
        project.addTrack(track);

        MidiClip midiClip = track.getMidiClip();
        // Earliest note at column 0; one-sample slip is below MIDI column resolution.
        midiClip.addNote(MidiNoteData.of(60, 0, 2, 100));
        midiClip.addNote(MidiNoteData.of(62, 4, 2, 100));

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectMidiClip(track, midiClip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onSlipLeftByFine();

        List<MidiNoteData> notes = midiClip.getNotes();
        assertThat(notes.get(0).startColumn()).isEqualTo(0);
        assertThat(notes.get(1).startColumn()).isEqualTo(4);
        assertThat(host.lastNotificationLevel).isNull();
    }

    @Test
    void slipWithNoSelectionShouldBeNoOp() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("Loop", 0.0, 4.0, null);
        clip.setSourceOffsetBeats(5.0);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        // No clip selected.

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onSlipRightByGrid();
        controller.onSlipLeftByFine();

        assertThat(clip.getSourceOffsetBeats()).isCloseTo(5.0, within(1e-6));
        assertThat(undoManager.canUndo()).isFalse();
    }

    // ── Nudge keyboard actions — Issue 566 ──────────────────────────────────

    @Test
    void onNudgeRightShouldShiftSelectedClipByOneGridStep() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("c", 2.0, 4.0, null);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        // Default NudgeSettings = 1 grid step; gridStepBeats() = 1.0 in TestHost.
        controller.onNudgeRight();

        assertThat(clip.getStartBeat()).isCloseTo(3.0, within(1e-6));

        undoManager.undo();
        assertThat(clip.getStartBeat()).isCloseTo(2.0, within(1e-6));
    }

    @Test
    void onNudgeLeftLargeAppliesTenTimesMultiplier() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("c", 20.0, 4.0, null);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onNudgeLeftLarge(); // -10 * 1 grid step = -10 beats

        assertThat(clip.getStartBeat()).isCloseTo(10.0, within(1e-6));
    }

    @Test
    void onNudgeRightSampleAppliesOneSample() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip clip = new AudioClip("c", 2.0, 4.0, null);
        track.addClip(clip);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, clip);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onNudgeRightSample();

        // 1 sample @ 44100Hz, 120 BPM = 120/60/44100 beats
        double expectedDelta = 120.0 / 60.0 / 44100.0;
        assertThat(clip.getStartBeat()).isCloseTo(2.0 + expectedDelta, within(1e-9));
    }

    @Test
    void multiSelectionNudgeIsSingleUndoStep() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip c1 = new AudioClip("a", 4.0, 2.0, null);
        AudioClip c2 = new AudioClip("b", 8.0, 2.0, null);
        AudioClip c3 = new AudioClip("c", 12.0, 2.0, null);
        track.addClip(c1); track.addClip(c2); track.addClip(c3);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, c1);
        selectionModel.toggleClipSelection(track, c2);
        selectionModel.toggleClipSelection(track, c3);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onNudgeRight();

        assertThat(c1.getStartBeat()).isCloseTo(5.0, within(1e-6));
        assertThat(c2.getStartBeat()).isCloseTo(9.0, within(1e-6));
        assertThat(c3.getStartBeat()).isCloseTo(13.0, within(1e-6));

        // One undo step reverts ALL three clips.
        undoManager.undo();
        assertThat(c1.getStartBeat()).isCloseTo(4.0, within(1e-6));
        assertThat(c2.getStartBeat()).isCloseTo(8.0, within(1e-6));
        assertThat(c3.getStartBeat()).isCloseTo(12.0, within(1e-6));
        assertThat(undoManager.canUndo()).isFalse();
    }

    @Test
    void nudgeAppliesToTimeSelectionWhenNoClipSelected() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip inside = new AudioClip("inside", 5.0, 1.0, null);
        AudioClip outside = new AudioClip("outside", 20.0, 1.0, null);
        track.addClip(inside); track.addClip(outside);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        // Time selection, no clip selection.
        selectionModel.setSelection(4.0, 10.0);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onNudgeRight();

        assertThat(inside.getStartBeat()).isCloseTo(6.0, within(1e-6));
        assertThat(outside.getStartBeat()).isCloseTo(20.0, within(1e-6));
    }

    @Test
    void nudgeLeftIsBoundaryClampedAtBeatZero() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);

        AudioClip c = new AudioClip("c", 0.5, 1.0, null);
        track.addClip(c);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();
        selectionModel.selectClip(track, c);

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        // 10× nudge left = -10 beats but must clamp at 0.
        controller.onNudgeLeftLarge();

        assertThat(c.getStartBeat()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void nudgeWithNoSelectionIsNoOp() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = new Track("Track 1", TrackType.AUDIO);
        project.addTrack(track);
        AudioClip c = new AudioClip("c", 4.0, 2.0, null);
        track.addClip(c);

        UndoManager undoManager = new UndoManager();
        SelectionModel selectionModel = new SelectionModel();

        TestHost host = new TestHost(project, undoManager, selectionModel);
        ClipEditController controller = new ClipEditController(host);

        controller.onNudgeRight();
        controller.onNudgeLeftSample();

        assertThat(c.getStartBeat()).isCloseTo(4.0, within(1e-6));
        assertThat(undoManager.canUndo()).isFalse();
    }

    private static final class TestHost implements ClipEditController.Host {
        private final DawProject project;
        private final UndoManager undoManager;
        private final SelectionModel selectionModel;
        private final ClipboardManager clipboardManager = new ClipboardManager();

        // Captured notification for slip-edge assertions.
        NotificationLevel lastNotificationLevel;
        String lastNotificationMessage;

        private TestHost(DawProject project, UndoManager undoManager, SelectionModel selectionModel) {
            this.project = project;
            this.undoManager = undoManager;
            this.selectionModel = selectionModel;
        }

        @Override public DawProject project() { return project; }
        @Override public UndoManager undoManager() { return undoManager; }
        @Override public ClipboardManager clipboardManager() { return clipboardManager; }
        @Override public SelectionModel selectionModel() { return selectionModel; }
        @Override public void refreshArrangementCanvas() { }
        @Override public void updateUndoRedoState() { }
        @Override public void syncMenuState() { }
        @Override public void markProjectDirty() { }
        @Override public void updateStatusBar(String text, DawIcon icon) { }
        @Override public void showNotificationWithUndo(NotificationLevel level, String message, Runnable undoCallback) { }
        @Override public void showNotification(NotificationLevel level, String message) {
            this.lastNotificationLevel = level;
            this.lastNotificationMessage = message;
        }
        @Override public EditorView editorView() { return null; }
        @Override public RippleMode rippleMode() { return project.getRippleMode(); }
        @Override public double gridStepBeats() { return 1.0; }
    }
}
