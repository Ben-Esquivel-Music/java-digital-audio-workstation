package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import javafx.application.Platform;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(JavaFxToolkitExtension.class)
class ClipInteractionControllerTest {

    private UndoManager undoManager;
    private List<Track> tracks;
    private EditTool activeTool;
    private double pixelsPerBeat;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight;
    private int refreshCount;

    private double seekedPosition;
    private SelectionModel selectionModel;
    private String lastStatusBarText;
    private RippleMode rippleMode;
    private boolean snapEnabled;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
        tracks = new ArrayList<>();
        activeTool = EditTool.POINTER;
        pixelsPerBeat = 40.0;
        scrollXBeats = 0.0;
        scrollYPixels = 0.0;
        trackHeight = 80.0;
        refreshCount = 0;
        seekedPosition = -1.0;
        selectionModel = new SelectionModel();
        lastStatusBarText = null;
        rippleMode = RippleMode.OFF;
        snapEnabled = false;
    }

    private ClipInteractionController.Deps createHost() {
        // Story 293 — the production Host is retired; build a Deps record of
        // direct functional dependencies. Suppliers read the mutable test
        // fields live so per-test mutations (activeTool, scroll, rippleMode…)
        // take effect, exactly as the former anonymous Host did.
        return new ClipInteractionController.Deps(
                () -> tracks,
                () -> activeTool,
                () -> undoManager,
                () -> pixelsPerBeat,
                () -> scrollXBeats,
                () -> scrollYPixels,
                () -> trackHeight,
                () -> snapEnabled,
                () -> GridResolution.QUARTER,
                () -> 4,
                () -> refreshCount++,
                beat -> seekedPosition = beat,
                () -> selectionModel,
                text -> lastStatusBarText = text,
                () -> rippleMode,
                (level, message) -> { /* no-op in tests */ },
                () -> 120.0,
                () -> { /* onTimeStretchClip no-op */ },
                () -> { /* onPitchShiftClip no-op */ });
    }

    /**
     * Creates a synthetic primary-button mouse-pressed event at the given
     * local (x, y) coordinates.
     */
    private static MouseEvent mousePressed(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, false, false, false, null);
    }

    /**
     * Creates a synthetic primary-button mouse-released event at the given
     * local (x, y) coordinates.
     */
    private static MouseEvent mouseReleased(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_RELEASED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                false, false, false, false, false, false, null);
    }

    /**
     * Creates a synthetic primary-button mouse-dragged event at the given
     * local (x, y) coordinates.
     */
    private static MouseEvent mouseDragged(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_DRAGGED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, false, false, false, null);
    }

    /**
     * Creates a synthetic primary-button mouse-pressed event with Shift held.
     */
    private static MouseEvent mousePressedShift(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                true, false, false, false,
                true, false, false, false, false, false, null);
    }

    /**
     * Creates a synthetic primary-button mouse-pressed event with Ctrl+Alt
     * held. On Windows/Linux this satisfies {@link MouseEvent#isShortcutDown()}
     * plus {@link MouseEvent#isAltDown()}, which is the slip-edit activation
     * modifier combination (Story 139).
     */
    private static MouseEvent mousePressedCtrlAlt(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, true, true, false,
                true, false, false, false, false, false, null);
    }

    /**
     * Creates a synthetic primary-button mouse-dragged event with Ctrl+Alt
     * held (the slip-edit drag modifier combination).
     */
    private static MouseEvent mouseDraggedCtrlAlt(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_DRAGGED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, true, true, false,
                true, false, false, false, false, false, null);
    }

    /**
     * Creates a synthetic primary-button mouse-released event with Ctrl+Alt
     * held (the slip-edit release modifier combination).
     */
    private static MouseEvent mouseReleasedCtrlAlt(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_RELEASED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, true, true, false,
                false, false, false, false, false, false, null);
    }

    // ── Hit testing ──────────────────────────────────────────────────────────

    @Test
    void shouldResolveTrackIndex() throws Exception {

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                tracks.add(new Track("Track 1", TrackType.AUDIO));
                tracks.add(new Track("Track 2", TrackType.AUDIO));
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ref.set(new ClipInteractionController(canvas, createHost()));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        ClipInteractionController controller = ref.get();
        assertThat(controller.trackIndexAt(10.0)).isEqualTo(0);
        assertThat(controller.trackIndexAt(90.0)).isEqualTo(1);
        assertThat(controller.trackIndexAt(200.0)).isEqualTo(-1);
    }

    @Test
    void shouldResolveBeatPosition() throws Exception {

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ref.set(new ClipInteractionController(canvas, createHost()));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        ClipInteractionController controller = ref.get();
        // At 40 px/beat, 80 pixels = 2 beats
        assertThat(controller.beatAt(80.0)).isEqualTo(2.0);
    }

    @Test
    void shouldFindClipAtBeat() throws Exception {

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ref.set(new ClipInteractionController(canvas, createHost()));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        ClipInteractionController controller = ref.get();
        assertThat(controller.clipAt(track, 3.0)).isSameAs(clip);
        assertThat(controller.clipAt(track, 1.0)).isNull();
        assertThat(controller.clipAt(track, 6.0)).isNull();
    }

    // ── Pencil tool (via mouse event dispatch) ───────────────────────────────

    @Test
    void compPressOnATakeLaneBeginsASwipeOnThatLaneAndReleaseRecompilesTheComp() throws Exception {
        // Story 328 (book §5.6): the COMP tool resolves the take lane from the
        // pointer's Y — lane 2 sits at trackHeight + 2 × TAKE_LANE_HEIGHT —
        // and the swipe's release compiles that lane's slice onto the main lane.
        Track track = compedTrack();
        tracks.add(track);
        activeTool = EditTool.COMP;
        double lane2Y = trackHeight + 2 * ArrangementCanvas.TAKE_LANE_HEIGHT + 10.0;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                canvas.fireEvent(mousePressed(160.0, lane2Y));   // beat 4.0 on lane 2
                canvas.fireEvent(mouseReleased(240.0, lane2Y));  // beat 6.0
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getTakeComping().getCompRegions())
                .containsExactly(new com.benesquivelmusic.daw.core.comping.CompRegion(2, 4.0, 2.0));
        assertThat(track.getClips()).as("the comped clip plays the selection").hasSize(1);
        assertThat(track.getClips().getFirst().getAudioData())
                .isSameAs(track.getTakeComping().getTakeLane(2).getClips().getFirst().getAudioData());
        assertThat(undoManager.canUndo()).isTrue();
        assertThat(refreshCount).isEqualTo(1);
    }

    @Test
    void compPressOnTheMainLaneClearsALaneSoloAndRestoresTheComp() throws Exception {
        Track track = compedTrack();
        track.getTakeComping().addCompRegion(new com.benesquivelmusic.daw.core.comping.CompRegion(0, 0.0, 8.0));
        new CompToolHandler(track, undoManager).altClickLane(1);
        assertThat(track.getClips().getFirst().getAudioData())
                .isSameAs(track.getTakeComping().getTakeLane(1).getClips().getFirst().getAudioData());
        tracks.add(track);
        activeTool = EditTool.COMP;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                canvas.fireEvent(mousePressed(160.0, trackHeight / 2));  // the main lane
                canvas.fireEvent(mouseReleased(160.0, trackHeight / 2));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getTakeComping().getTakeLanes()).allMatch(lane -> !lane.isSoloed());
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getAudioData()).as("back to the comp")
                .isSameAs(track.getTakeComping().getTakeLane(0).getClips().getFirst().getAudioData());
        assertThat(track.getTakeComping().getCompRegions()).hasSize(1);
    }

    /** An audio track with three take lanes, each holding one 8-beat clip with its own audio array. */
    private static Track compedTrack() {
        Track track = new Track("Vox", TrackType.AUDIO);
        for (int i = 1; i <= 3; i++) {
            com.benesquivelmusic.daw.core.comping.TakeLane lane =
                    new com.benesquivelmusic.daw.core.comping.TakeLane("Take " + i);
            AudioClip clip = new AudioClip("Take " + i, 0.0, 8.0, null);
            clip.setAudioData(new float[][] { new float[16] });
            lane.addClip(clip);
            track.getTakeComping().addTakeLane(lane);
        }
        return track;
    }

    // ── Take lanes under a non-COMP tool (PR #984 review, F3) ────────────────

    @Test
    void eraserOnATakeLaneLeavesTheCompAboveItInPlace() throws Exception {
        activeTool = EditTool.ERASER;
        assertTakeLaneGestureChangesNothing(canvas ->
                canvas.fireEvent(mousePressed(80.0, takeLaneY(1))));   // beat 2: comp clip 0..4 above
    }

    @Test
    void scissorsOnATakeLaneDoNotSplitTheComp() throws Exception {
        activeTool = EditTool.SCISSORS;
        assertTakeLaneGestureChangesNothing(canvas ->
                canvas.fireEvent(mousePressed(80.0, takeLaneY(1))));
    }

    @Test
    void pointerDragOnATakeLaneDoesNotMoveTheComp() throws Exception {
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas -> {
            canvas.fireEvent(mousePressed(80.0, takeLaneY(1)));
            canvas.fireEvent(mouseDragged(160.0, takeLaneY(1)));
            canvas.fireEvent(mouseReleased(160.0, takeLaneY(1)));
        });
        assertThat(seekedPosition).as("no seek either: the press started no gesture").isEqualTo(-1.0);
    }

    @Test
    void pointerTrimAtACompEdgeOverATakeLaneDoesNotTrimTheComp() throws Exception {
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas -> {
            canvas.fireEvent(mousePressed(160.0, takeLaneY(0)));   // the comp boundary at beat 4
            canvas.fireEvent(mouseDragged(120.0, takeLaneY(0)));
            canvas.fireEvent(mouseReleased(120.0, takeLaneY(0)));
        });
    }

    @Test
    void glueOnATakeLaneDoesNotGlueTheComp() throws Exception {
        activeTool = EditTool.GLUE;
        assertTakeLaneGestureChangesNothing(canvas ->
                canvas.fireEvent(mousePressed(160.0, takeLaneY(2))));  // the boundary of the two comp clips
    }

    @Test
    void pencilOnATakeLaneAddsNoMainLaneClip() throws Exception {
        activeTool = EditTool.PENCIL;
        assertTakeLaneGestureChangesNothing(canvas ->
                canvas.fireEvent(mousePressed(400.0, takeLaneY(1))));  // beat 10: no clip above
    }

    @Test
    void rightClickOnATakeLaneNeitherSelectsTheCompNorOpensItsMenu() throws Exception {
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas ->
                canvas.fireEvent(mousePressedSecondary(80.0, takeLaneY(1))));
        assertThat(selectionModel.getSelectedClips()).isEmpty();
    }

    @Test
    void ctrlAltSlipOnATakeLaneDoesNotSlipTheComp() throws Exception {
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas -> {
            // Beat 5: the comp clip 4..8, source offset 4, so a slip is never clamped.
            canvas.fireEvent(mousePressedCtrlAlt(200.0, takeLaneY(1)));
            canvas.fireEvent(mouseDraggedCtrlAlt(240.0, takeLaneY(1)));
            canvas.fireEvent(mouseReleasedCtrlAlt(240.0, takeLaneY(1)));
        });
    }

    @Test
    void aRejectedTakeLanePressDoesNotBubbleToTheCanvasParent() throws Exception {
        activeTool = EditTool.ERASER;
        Track track = twoClipComp();
        tracks.add(track);
        AtomicReference<Integer> parentPresses = new AtomicReference<>(0);
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            javafx.scene.layout.StackPane parent = new javafx.scene.layout.StackPane(canvas);
            // A handler, not a filter: a filter runs before the canvas and
            // would see the press whether or not the canvas consumed it.
            parent.addEventHandler(MouseEvent.MOUSE_PRESSED, _ -> parentPresses.set(parentPresses.get() + 1));
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressed(80.0, takeLaneY(1)));
            canvas.fireEvent(mousePressed(400.0, trackHeight / 2));  // main lane, nothing under it
        });
        assertThat(parentPresses.get()).as("only the main-lane press reaches the parent").isEqualTo(1);
    }

    @Test
    void pointerHoverAtACompEdgeOverATakeLaneShowsNoResizeCursor() throws Exception {
        activeTool = EditTool.POINTER;
        Track track = twoClipComp();
        tracks.add(track);
        AtomicReference<javafx.scene.Cursor> overLane = new AtomicReference<>();
        AtomicReference<javafx.scene.Cursor> overMainLane = new AtomicReference<>();
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mouseMoved(160.0, takeLaneY(0)));
            overLane.set(canvas.getCursor());
            canvas.fireEvent(mouseMoved(160.0, trackHeight / 2));
            overMainLane.set(canvas.getCursor());
        });
        assertThat(overLane.get()).isEqualTo(javafx.scene.Cursor.DEFAULT);
        assertThat(overMainLane.get()).as("the same edge on the main lane still trims")
                .isEqualTo(javafx.scene.Cursor.H_RESIZE);
    }

    @Test
    void eraserOnTheMainLaneOfACompedTrackStillRemovesTheClipUnderIt() throws Exception {
        activeTool = EditTool.ERASER;
        Track track = twoClipComp();
        tracks.add(track);
        AudioClip firstComp = track.getClips().getFirst();
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressed(80.0, trackHeight / 2));
        });
        assertThat(track.getClips()).hasSize(1).doesNotContain(firstComp);
    }

    @Test
    void compAltClickOnATakeLaneStillAuditionsThatLane() throws Exception {
        activeTool = EditTool.COMP;
        Track track = twoClipComp();
        tracks.add(track);
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressedAlt(80.0, takeLaneY(2)));
        });
        assertThat(track.getTakeComping().getTakeLane(2).isSoloed()).isTrue();
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getAudioData())
                .isSameAs(track.getTakeComping().getTakeLane(2).getClips().getFirst().getAudioData());
    }

    @Test
    void pointerClickOnATakeLaneLocatesThePlayheadAndSelectsNothing() throws Exception {
        // PR #984 review: the Pointer edits nothing through a take lane, but
        // a click without drag still locates, as it does on empty space.
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas -> {
            canvas.fireEvent(mousePressed(200.0, takeLaneY(1)));   // beat 5: comp clip 4..8 above
            canvas.fireEvent(mouseReleased(200.0, takeLaneY(1)));
        });
        assertThat(seekedPosition).as("the playhead is located at the click").isEqualTo(5.0);
        assertThat(selectionModel.getSelectedClips()).as("the comp above is not selected").isEmpty();
        assertThat(selectionModel.hasSelection()).as("no time selection").isFalse();
    }

    @Test
    void pointerDragOnATakeLaneSelectsAndMovesNothing() throws Exception {
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas -> {
            canvas.fireEvent(mousePressed(80.0, takeLaneY(1)));
            canvas.fireEvent(mouseDragged(240.0, takeLaneY(2)));
            canvas.fireEvent(mouseReleased(240.0, takeLaneY(2)));
        });
        assertThat(seekedPosition).as("a drag does not locate").isEqualTo(-1.0);
        assertThat(selectionModel.getSelectedClips()).as("no rubber band selected the comp").isEmpty();
        assertThat(selectionModel.hasSelection()).as("no time selection").isFalse();
    }

    @Test
    void eraserHoverOverATakeLaneShowsTheDefaultCursor() throws Exception {
        activeTool = EditTool.ERASER;
        Track track = twoClipComp();
        tracks.add(track);
        AtomicReference<javafx.scene.Cursor> overLane = new AtomicReference<>();
        AtomicReference<javafx.scene.Cursor> overMainLane = new AtomicReference<>();
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mouseMoved(80.0, takeLaneY(1)));
            overLane.set(canvas.getCursor());
            canvas.fireEvent(mouseMoved(80.0, trackHeight / 2));
            overMainLane.set(canvas.getCursor());
        });
        assertThat(overLane.get()).as("a press there does nothing").isEqualTo(javafx.scene.Cursor.DEFAULT);
        assertThat(overMainLane.get()).as("the Eraser's cursor on the main lane").isEqualTo(javafx.scene.Cursor.HAND);
    }

    @Test
    void aClipDragTheToolChangedUnderDoesNotOutliveItsGesture() throws Exception {
        // PR #984 review: a Pointer clip drag whose release came under another
        // tool left the drag armed, and a later Pointer release that started
        // no drag of its own moved the old clip.
        assertTakeLaneGestureChangesNothing(canvas -> {
            activeTool = EditTool.POINTER;
            canvas.fireEvent(mousePressed(80.0, trackHeight / 2));       // the comp clip 0..4
            activeTool = EditTool.ERASER;                                // a tool shortcut mid-drag
            canvas.fireEvent(mouseReleased(80.0, trackHeight / 2));
            activeTool = EditTool.POINTER;
            canvas.fireEvent(mousePressedSecondary(400.0, trackHeight / 2)); // beat 10: empty, starts nothing
            canvas.fireEvent(mouseReleased(400.0, trackHeight / 2));
        });
    }

    @Test
    void aClipDragWhoseReleaseNeverCameIsCancelledByTheNextPress() throws Exception {
        // PR #984 review: only the press-time cancel guards a drag whose
        // release was lost. The next press starts nothing of its own, so
        // without that cancel its release would complete the old drag.
        assertTakeLaneGestureChangesNothing(canvas -> {
            activeTool = EditTool.POINTER;
            canvas.fireEvent(mousePressed(80.0, trackHeight / 2));          // the comp clip 0..4; release lost
            canvas.fireEvent(mousePressedSecondary(400.0, trackHeight / 2)); // beat 10: empty, starts nothing
            canvas.fireEvent(mouseDragged(440.0, trackHeight / 2));
            canvas.fireEvent(mouseReleased(440.0, trackHeight / 2));
        });
    }

    @Test
    void aClipDragTheToolChangedUnderEndsItsDragVisualAtTheRelease() throws Exception {
        // The release-time cancel: the drag visual ends at the release that
        // came under another tool, not at some later press.
        Track track = twoClipComp();
        tracks.add(track);
        AtomicReference<DragVisualAdvisor.State> whilePressed = new AtomicReference<>();
        AtomicReference<DragVisualAdvisor.State> afterRelease = new AtomicReference<>();
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
            DragVisualAdvisor advisor = new DragVisualAdvisor();
            controller.setDragVisualAdvisor(advisor);
            controller.install();
            activeTool = EditTool.POINTER;
            canvas.fireEvent(mousePressed(80.0, trackHeight / 2));
            whilePressed.set(advisor.state());
            activeTool = EditTool.ERASER;
            canvas.fireEvent(mouseReleased(80.0, trackHeight / 2));
            afterRelease.set(advisor.state());
        });
        assertThat(whilePressed.get()).as("premise: the press began a drag").isEqualTo(DragVisualAdvisor.State.DRAGGING);
        assertThat(afterRelease.get()).as("the release ended it").isNotEqualTo(DragVisualAdvisor.State.DRAGGING);
    }

    @Test
    void aTakeLaneLocateWhoseReleaseNeverCameDoesNotTakeOverTheNextTrim() throws Exception {
        // PR #984 review: a stale take-lane locate swallowed the next
        // gesture's release, so the trim never completed and the playhead
        // could jump.
        Track track = twoClipComp();
        tracks.add(track);
        AudioClip second = track.getClips().stream()
                .filter(clip -> clip.getStartBeat() == 4.0).findFirst().orElseThrow();
        activeTool = EditTool.POINTER;
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressed(200.0, takeLaneY(1)));       // a take-lane locate; release lost
            canvas.fireEvent(mousePressed(320.0, trackHeight / 2));    // the right edge of the clip 4..8
            canvas.fireEvent(mouseDragged(280.0, trackHeight / 2));
            canvas.fireEvent(mouseReleased(280.0, trackHeight / 2));   // beat 7
        });
        assertThat(undoManager.canUndo()).as("the trim recorded its undo entry").isTrue();
        assertThat(second.getEndBeat()).as("the trim completed").isEqualTo(7.0);
        assertThat(seekedPosition).as("no seek").isEqualTo(-1.0);
    }

    @Test
    void aTakeLaneClickTheToolChangedUnderDoesNotLocate() throws Exception {
        assertTakeLaneGestureChangesNothing(canvas -> {
            activeTool = EditTool.POINTER;
            canvas.fireEvent(mousePressed(200.0, takeLaneY(1)));
            activeTool = EditTool.ERASER;                              // a tool shortcut mid-click
            canvas.fireEvent(mouseReleased(200.0, takeLaneY(1)));
        });
        assertThat(seekedPosition).as("the abandoned click does not seek").isEqualTo(-1.0);
    }

    @Test
    void aShiftClickOnATakeLaneDoesNotLocateAsOnEmptySpace() throws Exception {
        activeTool = EditTool.POINTER;
        assertTakeLaneGestureChangesNothing(canvas -> {
            canvas.fireEvent(mousePressedShift(200.0, takeLaneY(1)));
            canvas.fireEvent(mouseReleased(200.0, takeLaneY(1)));
        });
        assertThat(seekedPosition).as("Shift-click seeks nowhere, as on empty space").isEqualTo(-1.0);
    }

    @Test
    void aTakeLaneClickSnapsTheLocateAsAnEmptySpaceClickDoes() throws Exception {
        activeTool = EditTool.POINTER;
        snapEnabled = true;
        Track track = twoClipComp();
        tracks.add(track);
        AtomicReference<Double> overTakeLane = new AtomicReference<>();
        AtomicReference<Double> overEmptySpace = new AtomicReference<>();
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressed(410.0, takeLaneY(1)));       // beat 10.25, off the grid
            canvas.fireEvent(mouseReleased(410.0, takeLaneY(1)));
            overTakeLane.set(seekedPosition);
            canvas.fireEvent(mousePressed(410.0, trackHeight / 2));    // the same beat on empty main lane
            canvas.fireEvent(mouseReleased(410.0, trackHeight / 2));
            overEmptySpace.set(seekedPosition);
        });
        assertThat(overEmptySpace.get()).as("premise: the empty-space click snapped")
                .isEqualTo(SnapQuantizer.quantize(10.25, GridResolution.QUARTER, 4)).isNotEqualTo(10.25);
        assertThat(overTakeLane.get()).as("the take-lane click locates to the same snapped beat")
                .isEqualTo(overEmptySpace.get());
    }

    /** Where a Pointer click lands on a {@link #twoClipComp()} track: both paths that seek on a click. */
    enum ClickSurface {
        /** Take lane 1, beat 10. */
        TAKE_LANE,
        /** The empty main lane at beat 10, after the comp: the rubber-band release. */
        EMPTY_SPACE
    }

    private double clickSurfaceY(ClickSurface surface) {
        return switch (surface) {
            case TAKE_LANE -> takeLaneY(1);
            case EMPTY_SPACE -> trackHeight / 2;
        };
    }

    @ParameterizedTest
    @EnumSource(ClickSurface.class)
    void aReleaseExactlyTheClickSlopFromThePressOnBothAxesIsStillAClickThatLocates(ClickSurface surface)
            throws Exception {
        // HEAD's empty-space rule: a drag is travel of more than 2 px on an axis.
        activeTool = EditTool.POINTER;
        Track track = twoClipComp();
        tracks.add(track);
        double y = clickSurfaceY(surface);
        double slop = ClipInteractionController.CLICK_SLOP_PX;
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressed(400.0, y));                       // beat 10
            canvas.fireEvent(mouseReleased(400.0 + slop, y + slop));
        });
        assertThat(seekedPosition).as("a click: it locates at the release")
                .isCloseTo((400.0 + slop) / pixelsPerBeat, within(1e-9));
    }

    @ParameterizedTest
    @EnumSource(ClickSurface.class)
    void aReleaseHalfAPixelPastTheClickSlopIsADragThatDoesNotLocate(ClickSurface surface) throws Exception {
        activeTool = EditTool.POINTER;
        Track track = twoClipComp();
        tracks.add(track);
        double y = clickSurfaceY(surface);
        double slop = ClipInteractionController.CLICK_SLOP_PX;
        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            canvas.fireEvent(mousePressed(400.0, y));                       // beat 10
            // Horizontal travel: on empty space a drag that covers no beats still seeks.
            canvas.fireEvent(mouseReleased(400.0 + slop + 0.5, y));
        });
        assertThat(seekedPosition).as("a drag does not locate").isEqualTo(-1.0);
    }

    /** The Y of the middle of take lane {@code laneIndex} under the first track. */
    private double takeLaneY(int laneIndex) {
        return trackHeight + laneIndex * ArrangementCanvas.TAKE_LANE_HEIGHT
                + ArrangementCanvas.TAKE_LANE_HEIGHT / 2;
    }

    /**
     * {@link #compedTrack()} with lane 0 selected over beats 0-4 and again
     * over beats 4-8, compiled: the main lane holds two adjacent comp clips
     * of the same take, which the Glue tool could merge.
     */
    private static Track twoClipComp() {
        Track track = compedTrack();
        track.getTakeComping().addCompRegion(new com.benesquivelmusic.daw.core.comping.CompRegion(0, 0.0, 4.0));
        track.getTakeComping().addCompRegion(new com.benesquivelmusic.daw.core.comping.CompRegion(0, 4.0, 4.0));
        new com.benesquivelmusic.daw.core.comping.CompileCompAction(track).execute();
        assertThat(track.getClips()).hasSize(2);
        return track;
    }

    /** What a gesture could change: a clip's identity and its placement. */
    private record ClipState(AudioClip clip, double startBeat, double durationBeats, double sourceOffsetBeats) {
        static List<ClipState> of(List<AudioClip> clips) {
            return clips.stream().map(clip -> new ClipState(clip, clip.getStartBeat(),
                    clip.getDurationBeats(), clip.getSourceOffsetBeats())).toList();
        }
    }

    /**
     * Runs {@code gesture} on a {@link #twoClipComp()} track under the active
     * tool and asserts it changed nothing: the main lane (every clip, its
     * identity, position, length and source offset), each take lane, the
     * comp selection, the lane solos and the undo history.
     */
    private void assertTakeLaneGestureChangesNothing(java.util.function.Consumer<ArrangementCanvas> gesture)
            throws Exception {
        Track track = twoClipComp();
        tracks.add(track);
        com.benesquivelmusic.daw.core.comping.TakeComping comping = track.getTakeComping();
        List<ClipState> mainLane = ClipState.of(track.getClips());
        List<List<ClipState>> lanes = comping.getTakeLanes().stream()
                .map(lane -> ClipState.of(lane.getClips())).toList();
        List<com.benesquivelmusic.daw.core.comping.CompRegion> regions = List.copyOf(comping.getCompRegions());

        runOnFxThread(() -> {
            ArrangementCanvas canvas = new ArrangementCanvas();
            canvas.setTracks(tracks);
            new ClipInteractionController(canvas, createHost()).install();
            gesture.accept(canvas);
        });

        assertThat(ClipState.of(track.getClips())).as("the main lane").isEqualTo(mainLane);
        assertThat(track.getClips()).allMatch(comping::isCompOutput);
        assertThat(comping.getTakeLanes().stream().map(lane -> ClipState.of(lane.getClips())).toList())
                .as("the take lanes").isEqualTo(lanes);
        assertThat(comping.getCompRegions()).isEqualTo(regions);
        assertThat(comping.getTakeLanes()).noneMatch(com.benesquivelmusic.daw.core.comping.TakeLane::isSoloed);
        assertThat(undoManager.canUndo()).as("no undo entry").isFalse();
    }

    /** Runs {@code body} on the FX thread and rethrows anything it threw. */
    private static void runOnFxThread(FxBody body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("failed on the FX thread", failure.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }

    private static MouseEvent mousePressedSecondary(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.SECONDARY, 1,
                false, false, false, false,
                false, false, true, false, false, false, null);
    }

    private static MouseEvent mousePressedAlt(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, true, false,
                true, false, false, false, false, false, null);
    }

    private static MouseEvent mouseMoved(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_MOVED,
                x, y, x, y, MouseButton.NONE, 0,
                false, false, false, false,
                false, false, false, false, false, false, null);
    }

    @Test
    void pencilShouldCreateClipViaMousePress() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.PENCIL;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click at x=160 (beat 4.0 at 40px/beat), y=40 (track 0)
                canvas.fireEvent(mousePressed(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getStartBeat()).isEqualTo(4.0);
        assertThat(track.getClips().getFirst().getDurationBeats())
                .isEqualTo(ClipInteractionController.DEFAULT_NEW_CLIP_DURATION);
        assertThat(refreshCount).isEqualTo(1);
    }

    @Test
    void pencilShouldNotCreateClipOnExistingClip() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        track.addClip(new AudioClip("Existing", 4.0, 4.0, null));
        tracks.add(track);
        activeTool = EditTool.PENCIL;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on the existing clip at beat 5.0 (x=200, y=40)
                canvas.fireEvent(mousePressed(200.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).hasSize(1);
        assertThat(refreshCount).isEqualTo(0);
    }

    // ── Eraser tool (via mouse event dispatch) ───────────────────────────────

    @Test
    void eraserShouldRemoveClipViaMousePress() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.ERASER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on clip at beat 3.0 (x=120, y=40)
                canvas.fireEvent(mousePressed(120.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).isEmpty();
        assertThat(refreshCount).isEqualTo(1);

        // Undo should restore
        undoManager.undo();
        assertThat(track.getClips()).hasSize(1);
    }

    // ── Scissors tool (via mouse event dispatch) ─────────────────────────────

    @Test
    void scissorsShouldSplitClipViaMousePress() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.SCISSORS;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click at beat 4.0 (x=160, y=40) inside the clip
                canvas.fireEvent(mousePressed(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).hasSize(2);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(refreshCount).isEqualTo(1);

        // Undo restores
        undoManager.undo();
        assertThat(track.getClips()).hasSize(1);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
    }

    // ── Glue tool (via mouse event dispatch) ─────────────────────────────────

    @Test
    void glueShouldMergeAdjacentClipsViaMousePress() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip first = new AudioClip("Part A", 0.0, 4.0, null);
        AudioClip second = new AudioClip("Part B", 4.0, 4.0, null);
        track.addClip(first);
        track.addClip(second);
        tracks.add(track);
        activeTool = EditTool.GLUE;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click at the boundary (beat 4.0 = x=160, y=40)
                canvas.fireEvent(mousePressed(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getDurationBeats()).isEqualTo(8.0);
        assertThat(refreshCount).isEqualTo(1);

        // Undo restores both clips
        undoManager.undo();
        assertThat(track.getClips()).hasSize(2);
    }

    // ── Pointer tool (drag via mouse events) ─────────────────────────────────

    @Test
    void pointerShouldMoveClipViaDrag() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Press on clip at beat 3.0 (x=120, y=40)
                canvas.fireEvent(mousePressed(120.0, 40.0));
                // Release at beat 7.0 (x=280, y=40), same track
                canvas.fireEvent(mouseReleased(280.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Clip should have moved from beat 2.0 to beat 6.0
        // (released at beat 7.0, drag offset was 1.0 beat into the clip)
        assertThat(clip.getStartBeat()).isEqualTo(6.0);
        assertThat(refreshCount).isEqualTo(2);
    }

    @Test
    void pointerMoveOutsideSelectionShouldNotRipple() throws Exception {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip moved = new AudioClip("Moved", 4.0, 4.0, null);
        AudioClip later = new AudioClip("Later", 20.0, 4.0, null);
        track.addClip(moved);
        track.addClip(later);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        rippleMode = RippleMode.PER_TRACK;
        selectionModel.setSelection(10.0, 18.0); // moved clip start (4.0) is outside

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Move clip from 4 -> 6 beats (press at 5, release at 7).
                canvas.fireEvent(mousePressed(200.0, 40.0));
                canvas.fireEvent(mouseReleased(280.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(moved.getStartBeat()).isEqualTo(6.0);
        assertThat(later.getStartBeat()).isEqualTo(20.0);
    }

    // ── Cross-track move ─────────────────────────────────────────────────────

    @Test
    void crossTrackMoveShouldTransferClip() {
        Track source = new Track("Track 1", TrackType.AUDIO);
        Track target = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        source.addClip(clip);

        CrossTrackMoveAction action =
                new CrossTrackMoveAction(source, target, clip, 8.0);
        undoManager.execute(action);

        assertThat(source.getClips()).isEmpty();
        assertThat(target.getClips()).hasSize(1);
        assertThat(clip.getStartBeat()).isEqualTo(8.0);

        undoManager.undo();

        assertThat(source.getClips()).hasSize(1);
        assertThat(target.getClips()).isEmpty();
        assertThat(clip.getStartBeat()).isEqualTo(2.0);
    }

    @Test
    void crossTrackMoveActionShouldHaveCorrectDescription() {
        Track source = new Track("Track 1", TrackType.AUDIO);
        Track target = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);

        CrossTrackMoveAction action =
                new CrossTrackMoveAction(source, target, clip, 8.0);

        assertThat(action.description()).isEqualTo("Move Clip to Track");
    }

    // ── Default clip duration constant ───────────────────────────────────────

    @Test
    void defaultNewClipDurationShouldBeFourBeats() {
        assertThat(ClipInteractionController.DEFAULT_NEW_CLIP_DURATION).isEqualTo(4.0);
    }

    // ── Pointer click-to-seek on empty space ─────────────────────────────────

    @Test
    void pointerShouldSeekWhenClickingEmptySpace() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click at x=160 (beat 4.0 at 40px/beat), y=40 (track 0, no clips)
                canvas.fireEvent(mousePressed(160.0, 40.0));
                canvas.fireEvent(mouseReleased(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(seekedPosition).isEqualTo(4.0);
    }

    @Test
    void pointerShouldSeekWhenClickingBelowAllTracks() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click below all tracks (y=200, track height 80 with 1 track)
                canvas.fireEvent(mousePressed(200.0, 200.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(seekedPosition).isEqualTo(5.0);
    }

    // ── Time selection ──────────────────────────────────────────────────────

    @Test
    void dragOnEmptySpaceShouldRubberBandSelectClips() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Clip A", 1.0, 3.0, null);
        AudioClip clip2 = new AudioClip("Clip B", 5.0, 2.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Drag from beat 0.5 (x=20) to beat 4.5 (x=180) across clip1 region
                canvas.fireEvent(mousePressed(20.0, 40.0));
                canvas.fireEvent(mouseDragged(180.0, 40.0));
                canvas.fireEvent(mouseReleased(180.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasClipSelection()).isTrue();
        assertThat(selectionModel.isClipSelected(clip1)).isTrue();
        assertThat(selectionModel.isClipSelected(clip2)).isFalse();
    }

    @Test
    void clickWithoutDragShouldClearSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-set a selection
        selectionModel.setSelection(1.0, 5.0);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on empty space and release immediately
                canvas.fireEvent(mousePressed(120.0, 40.0));
                canvas.fireEvent(mouseReleased(120.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasSelection()).isFalse();
        // Should also have seeked to the position
        assertThat(seekedPosition).isEqualTo(3.0);
    }

    @Test
    void shiftClickShouldExtendSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-set a selection from 2.0 to 6.0
        selectionModel.setSelection(2.0, 6.0);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                canvas.setSelectionRange(true, 2.0, 6.0);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Shift-click below tracks at beat 10.0 (x=400, y=200) to extend
                canvas.fireEvent(mousePressedShift(400.0, 200.0));
                canvas.fireEvent(mouseReleased(400.0, 200.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasSelection()).isTrue();
        assertThat(selectionModel.getStartBeat()).isEqualTo(2.0);
        assertThat(selectionModel.getEndBeat()).isEqualTo(10.0);
    }

    @Test
    void clickOnClipShouldClearSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-set a selection
        selectionModel.setSelection(0.0, 10.0);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on the clip at beat 3.0 (x=120)
                canvas.fireEvent(mousePressed(120.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasSelection()).isFalse();
    }

    @Test
    void selectionDragShouldUpdateStatusBar() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Drag below tracks from beat 1.0 to beat 5.0
                canvas.fireEvent(mousePressed(40.0, 200.0));
                canvas.fireEvent(mouseDragged(200.0, 200.0));
                canvas.fireEvent(mouseReleased(200.0, 200.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(lastStatusBarText).isNotNull();
        assertThat(lastStatusBarText).contains("Selection");
    }

    @Test
    void hitTestSelectionHandleShouldDetectLeftEdge() throws Exception {

        selectionModel.setSelection(4.0, 8.0);
        // At 40px/beat, left edge is at x=160, right edge at x=320

        AtomicReference<ClipInteractionController.SelectionEdge> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());

                // Test hit at left edge (x=160)
                ref.set(controller.hitTestSelectionHandle(160.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(ClipInteractionController.SelectionEdge.LEFT);
    }

    @Test
    void hitTestSelectionHandleShouldDetectRightEdge() throws Exception {

        selectionModel.setSelection(4.0, 8.0);

        AtomicReference<ClipInteractionController.SelectionEdge> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());

                // Test hit at right edge (x=320)
                ref.set(controller.hitTestSelectionHandle(320.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isEqualTo(ClipInteractionController.SelectionEdge.RIGHT);
    }

    @Test
    void hitTestSelectionHandleShouldReturnNullWhenNoSelection() throws Exception {

        AtomicReference<ClipInteractionController.SelectionEdge> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());

                ref.set(controller.hitTestSelectionHandle(160.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNull();
    }

    @Test
    void hitTestSelectionHandleShouldReturnNullWhenFarFromEdge() throws Exception {

        selectionModel.setSelection(4.0, 8.0);

        AtomicReference<ClipInteractionController.SelectionEdge> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());

                // Test hit far from either edge (x=240 = beat 6.0, middle of selection)
                ref.set(controller.hitTestSelectionHandle(240.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNull();
    }

    @Test
    void dragBelowTracksShouldCreateTimeSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Press below tracks (y=200, height 80 with 1 track)
                canvas.fireEvent(mousePressed(80.0, 200.0));
                canvas.fireEvent(mouseDragged(320.0, 200.0));
                canvas.fireEvent(mouseReleased(320.0, 200.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasSelection()).isTrue();
        assertThat(selectionModel.getStartBeat()).isEqualTo(2.0);
        assertThat(selectionModel.getEndBeat()).isEqualTo(8.0);
    }

    @Test
    void shiftClickOnSelectionEdgeShouldPreserveSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-set a selection from 2.0 to 6.0
        selectionModel.setSelection(2.0, 6.0);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                canvas.setSelectionRange(true, 2.0, 6.0);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Shift-click exactly on the left edge (beat 2.0, x=80)
                // lo == hi so selection should be preserved, not cleared
                canvas.fireEvent(mousePressedShift(80.0, 40.0));
                canvas.fireEvent(mouseReleased(80.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Selection should still be active (not cleared by the lo==hi branch)
        assertThat(selectionModel.hasSelection()).isTrue();
        assertThat(selectionModel.getStartBeat()).isEqualTo(2.0);
        assertThat(selectionModel.getEndBeat()).isEqualTo(6.0);
    }

    @Test
    void clearingSelectionShouldClearStatusBar() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-set a selection so status bar would show "Selection: ..."
        selectionModel.setSelection(1.0, 5.0);
        lastStatusBarText = "Selection: 1.00 – 5.00 (4.00 beats)";

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on empty space and release — clears selection
                canvas.fireEvent(mousePressed(120.0, 40.0));
                canvas.fireEvent(mouseReleased(120.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasSelection()).isFalse();
        // Status bar should have been cleared (no stale selection text)
        assertThat(lastStatusBarText).isEmpty();
    }

    // ── Rubber-band multi-clip selection ─────────────────────────────────────

    @Test
    void rubberBandShouldSelectMultipleClips() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        AudioClip clip3 = new AudioClip("Hat", 12.0, 2.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        track.addClip(clip3);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Start rubber-band at beat 9.0 (x=360, empty space between clips 2 and 3),
                // drag backwards to beat 0.0 (x=0) to encompass clips 1 and 2
                canvas.fireEvent(mousePressed(360.0, 10.0));
                canvas.fireEvent(mouseDragged(0.0, 70.0));
                canvas.fireEvent(mouseReleased(0.0, 70.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasClipSelection()).isTrue();
        assertThat(selectionModel.isClipSelected(clip1)).isTrue();
        assertThat(selectionModel.isClipSelected(clip2)).isTrue();
        assertThat(selectionModel.isClipSelected(clip3)).isFalse();
    }

    @Test
    void rubberBandShouldSelectAcrossMultipleTracks() throws Exception {

        Track track1 = new Track("Track 1", TrackType.AUDIO);
        Track track2 = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Vocal", 2.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Bass", 3.0, 3.0, null);
        track1.addClip(clip1);
        track2.addClip(clip2);
        tracks.add(track1);
        tracks.add(track2);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Rubber-band drag spanning both tracks (y: 10 to 150)
                canvas.fireEvent(mousePressed(60.0, 10.0));
                canvas.fireEvent(mouseDragged(280.0, 150.0));
                canvas.fireEvent(mouseReleased(280.0, 150.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isClipSelected(clip1)).isTrue();
        assertThat(selectionModel.isClipSelected(clip2)).isTrue();
    }

    @Test
    void shiftRubberBandShouldAddToExistingSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 8.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-select clip1
        selectionModel.selectClip(track, clip1);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Shift + rubber-band drag over clip2 region (beats 7–13)
                canvas.fireEvent(mousePressedShift(280.0, 10.0));
                canvas.fireEvent(mouseDragged(520.0, 70.0));
                canvas.fireEvent(mouseReleased(520.0, 70.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isClipSelected(clip1)).isTrue();
        assertThat(selectionModel.isClipSelected(clip2)).isTrue();
    }

    @Test
    void rubberBandWithoutDragShouldClearClipSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Kick", 0.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-select a clip
        selectionModel.selectClip(track, clip);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on empty space in the track (after the clip)
                canvas.fireEvent(mousePressed(200.0, 40.0));
                canvas.fireEvent(mouseReleased(200.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.hasClipSelection()).isFalse();
    }

    @Test
    void shiftClickOnClipShouldToggleSelection() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-select clip1
        selectionModel.selectClip(track, clip1);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Shift-click on clip2 at beat 5.0 (x=200)
                canvas.fireEvent(mousePressedShift(200.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isClipSelected(clip1)).isTrue();
        assertThat(selectionModel.isClipSelected(clip2)).isTrue();
    }

    @Test
    void shiftClickOnSelectedClipShouldDeselectIt() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Kick", 0.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-select the clip
        selectionModel.selectClip(track, clip);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Shift-click on the already selected clip at beat 2.0 (x=80)
                canvas.fireEvent(mousePressedShift(80.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isClipSelected(clip)).isFalse();
    }

    @Test
    void clickOnClipShouldSelectOnlyThatClip() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-select both clips
        selectionModel.selectClip(track, clip1);
        selectionModel.toggleClipSelection(track, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on clip2 at beat 5.0 (x=200) and release without drag
                // — should deselect clip1 (selection collapses on release)
                canvas.fireEvent(mousePressed(200.0, 40.0));
                canvas.fireEvent(mouseReleased(200.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isClipSelected(clip2)).isTrue();
        assertThat(selectionModel.isClipSelected(clip1)).isFalse();
    }

    // ── MIDI clip interaction tests ─────────────────────────────────────────

    @Test
    void shouldFindMidiClipAtBeat() throws Exception {

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        Track midiTrack = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 8–16 = beats 2.0–4.0
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 8, 8, 100));

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ref.set(new ClipInteractionController(canvas, createHost()));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        ClipInteractionController controller = ref.get();
        assertThat(controller.midiClipAt(midiTrack, 3.0)).isSameAs(midiTrack.getMidiClip());
        assertThat(controller.midiClipAt(midiTrack, 1.0)).isNull();
        assertThat(controller.midiClipAt(midiTrack, 5.0)).isNull();
    }

    @Test
    void shouldNotFindMidiClipOnAudioTrack() throws Exception {

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        Track audioTrack = new Track("Audio 1", TrackType.AUDIO);
        audioTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 4, 100));

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ref.set(new ClipInteractionController(canvas, createHost()));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        ClipInteractionController controller = ref.get();
        assertThat(controller.midiClipAt(audioTrack, 0.5)).isNull();
    }

    @Test
    void clickOnMidiClipShouldSelectIt() throws Exception {

        Track midiTrack = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 0–8 = beats 0.0–2.0
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 0, 8, 100));
        tracks.add(midiTrack);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click at beat 1.0 (x=40) on the MIDI clip
                canvas.fireEvent(mousePressed(40.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isMidiClipSelected(midiTrack.getMidiClip())).isTrue();
    }

    @Test
    void shiftClickOnMidiClipShouldToggleSelection() throws Exception {

        Track midiTrack = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 0–8 = beats 0.0–2.0
        MidiClip midiClip = midiTrack.getMidiClip();
        midiClip.addNote(MidiNoteData.of(60, 0, 8, 100));
        tracks.add(midiTrack);
        activeTool = EditTool.POINTER;

        // Pre-select the MIDI clip
        selectionModel.selectMidiClip(midiTrack, midiClip);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Shift-click at beat 1.0 (x=40) to toggle off
                canvas.fireEvent(mousePressedShift(40.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isMidiClipSelected(midiClip)).isFalse();
    }

    @Test
    void rubberBandShouldSelectMidiClips() throws Exception {

        Track midiTrack = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 4–12 = beats 1.0–3.0
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 4, 8, 100));
        tracks.add(midiTrack);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Rubber-band from beat 5.0 (x=200, empty) to beat 0.0 (x=0)
                canvas.fireEvent(mousePressed(200.0, 10.0));
                canvas.fireEvent(mouseDragged(0.0, 70.0));
                canvas.fireEvent(mouseReleased(0.0, 70.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isMidiClipSelected(midiTrack.getMidiClip())).isTrue();
    }

    @Test
    void rubberBandShouldSelectBothAudioAndMidiClips() throws Exception {

        Track audioTrack = new Track("Audio 1", TrackType.AUDIO);
        AudioClip audioClip = new AudioClip("Vocal", 0.0, 4.0, null);
        audioTrack.addClip(audioClip);
        tracks.add(audioTrack);

        Track midiTrack = new Track("MIDI 1", TrackType.MIDI);
        // Notes at columns 4–12 = beats 1.0–3.0
        midiTrack.getMidiClip().addNote(MidiNoteData.of(60, 4, 8, 100));
        tracks.add(midiTrack);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Rubber-band spanning both tracks (y: 10 to 150)
                canvas.fireEvent(mousePressed(200.0, 10.0));
                canvas.fireEvent(mouseDragged(0.0, 150.0));
                canvas.fireEvent(mouseReleased(0.0, 150.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(selectionModel.isClipSelected(audioClip)).isTrue();
        assertThat(selectionModel.isMidiClipSelected(midiTrack.getMidiClip())).isTrue();
    }

    // ── Group move (drag multiple selected clips) ────────────────────────────

    @Test
    void groupDragShouldMoveAllSelectedClips() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        // Pre-select both clips
        selectionModel.selectClip(track, clip1);
        selectionModel.toggleClipSelection(track, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on clip1 at beat 2.0 (x=80, y=40) — already selected
                canvas.fireEvent(mousePressed(80.0, 40.0));
                // Release at beat 4.0 (x=160, y=40) — delta of 2.0 beats
                canvas.fireEvent(mouseReleased(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Both clips should have moved by 2.0 beats
        assertThat(clip1.getStartBeat()).isEqualTo(2.0);
        assertThat(clip2.getStartBeat()).isEqualTo(6.0);
    }

    @Test
    void groupDragShouldBeUndoableAsSingleAction() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.POINTER;
        selectionModel.selectClip(track, clip1);
        selectionModel.toggleClipSelection(track, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                canvas.fireEvent(mousePressed(80.0, 40.0));
                canvas.fireEvent(mouseReleased(200.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Undo should restore both clips to original positions
        undoManager.undo();
        assertThat(clip1.getStartBeat()).isEqualTo(0.0);
        assertThat(clip2.getStartBeat()).isEqualTo(4.0);
    }

    @Test
    void groupDragShouldSupportCrossTrackMove() throws Exception {

        Track track1 = new Track("Track 1", TrackType.AUDIO);
        Track track2 = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track1.addClip(clip1);
        track1.addClip(clip2);
        tracks.add(track1);
        tracks.add(track2);
        activeTool = EditTool.POINTER;
        selectionModel.selectClip(track1, clip1);
        selectionModel.toggleClipSelection(track1, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Press on clip1 at track 0 (y=40), release on track 1 (y=120)
                canvas.fireEvent(mousePressed(80.0, 40.0));
                canvas.fireEvent(mouseReleased(80.0, 120.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Both clips should have moved to track2
        assertThat(track1.getClips()).isEmpty();
        assertThat(track2.getClips()).hasSize(2);
    }

    // ── Group eraser (delete all selected clips) ─────────────────────────────

    @Test
    void eraserShouldDeleteAllSelectedClips() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.ERASER;
        // Pre-select both clips
        selectionModel.selectClip(track, clip1);
        selectionModel.toggleClipSelection(track, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on clip1 with eraser — should delete both selected clips
                canvas.fireEvent(mousePressed(80.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).isEmpty();
        assertThat(refreshCount).isEqualTo(1);
    }

    @Test
    void eraserGroupDeleteShouldBeUndoableAsSingleAction() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.ERASER;
        selectionModel.selectClip(track, clip1);
        selectionModel.toggleClipSelection(track, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                canvas.fireEvent(mousePressed(80.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Single undo should restore both clips
        undoManager.undo();
        assertThat(track.getClips()).hasSize(2);
    }

    // ── Slip edit (Ctrl+Alt+drag) — Story 139 ───────────────────────────────

    @Test
    void ctrlAltDragOnAudioClipShouldSlipSourceOffset() throws Exception {
        Track track = new Track("Track 1", TrackType.AUDIO);
        // A 4-beat clip starting at beat 2 with an initial source offset of 10 beats.
        AudioClip clip = new AudioClip("Loop", 2.0, 4.0, null);
        clip.setSourceOffsetBeats(10.0);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Press inside the clip body with Ctrl+Alt held.
                // pixelsPerBeat=40, scroll=0, so clip occupies x=[80, 240], y=[0, 80].
                canvas.fireEvent(mousePressedCtrlAlt(120.0, 40.0));
                // Drag 40 pixels right (= 1 beat at 40 px/beat).
                canvas.fireEvent(mouseDraggedCtrlAlt(160.0, 40.0));
                canvas.fireEvent(mouseReleasedCtrlAlt(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Content dragged right by 1 beat → source offset decreases by 1 beat (10 → 9).
        assertThat(clip.getSourceOffsetBeats()).isCloseTo(9.0, within(1e-6));
        // Timeline position and duration are untouched by slip.
        assertThat(clip.getStartBeat()).isEqualTo(2.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
    }

    @Test
    void ctrlAltDragOnAudioClipShouldBeUndoable() throws Exception {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Loop", 2.0, 4.0, null);
        clip.setSourceOffsetBeats(10.0);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                canvas.fireEvent(mousePressedCtrlAlt(120.0, 40.0));
                canvas.fireEvent(mouseDraggedCtrlAlt(200.0, 40.0));
                canvas.fireEvent(mouseReleasedCtrlAlt(200.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // 80 px drag right → 2 beat content slide → offset 10 - 2 = 8.
        assertThat(clip.getSourceOffsetBeats()).isCloseTo(8.0, within(1e-6));

        undoManager.undo();

        assertThat(clip.getSourceOffsetBeats()).isCloseTo(10.0, within(1e-6));
    }

    @Test
    void ctrlAltDragLeftShouldClampAtLowerSourceEdge() throws Exception {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Loop", 2.0, 4.0, null);
        // Only 0.5 beats of headroom at the start of the source.
        clip.setSourceOffsetBeats(0.5);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Press inside the clip body (x=160, clip spans 80..240 px).
                canvas.fireEvent(mousePressedCtrlAlt(160.0, 40.0));
                // Drag far left (−4 beats worth). Sign convention: dragging left
                // slides content left on the timeline → source offset INCREASES.
                // That direction is unbounded when source length is unknown, so
                // we instead drag right to exercise the lower-clamp at offset 0.
                canvas.fireEvent(mouseDraggedCtrlAlt(320.0, 40.0));
                canvas.fireEvent(mouseReleasedCtrlAlt(320.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Dragged 160 px right = 4 beats content slide → requested offset 0.5 - 4 = -3.5,
        // clamped to 0. Clip should land exactly at source offset 0.
        assertThat(clip.getSourceOffsetBeats()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void ctrlAltDragOnMidiClipShouldShiftAllNotesByTheSameDelta() throws Exception {
        Track track = new Track("MIDI Track", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        // Add three notes at columns 4, 8, 12 (= beats 1.0, 2.0, 3.0 at 0.25 beats/col).
        midiClip.addNote(MidiNoteData.of(60, 4, 2, 100));
        midiClip.addNote(MidiNoteData.of(62, 8, 2, 100));
        midiClip.addNote(MidiNoteData.of(64, 12, 2, 100));
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // The MIDI clip spans beats 1..3.5 → pixels 40..140 at 40 px/beat.
                // Press inside the clip, drag +1 beat (40 px) right.
                canvas.fireEvent(mousePressedCtrlAlt(80.0, 40.0));
                canvas.fireEvent(mouseDraggedCtrlAlt(120.0, 40.0));
                canvas.fireEvent(mouseReleasedCtrlAlt(120.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // +1 beat = +4 columns. Every note shifts by exactly 4 columns.
        List<MidiNoteData> notes = midiClip.getNotes();
        assertThat(notes).hasSize(3);
        assertThat(notes.get(0).startColumn()).isEqualTo(8);
        assertThat(notes.get(1).startColumn()).isEqualTo(12);
        assertThat(notes.get(2).startColumn()).isEqualTo(16);
    }

    @Test
    void ctrlAltDragOnMidiClipShouldClampAtColumnZero() throws Exception {
        Track track = new Track("MIDI Track", TrackType.MIDI);
        MidiClip midiClip = track.getMidiClip();
        // Earliest note at column 2 — we can only slip left by 2 columns.
        midiClip.addNote(MidiNoteData.of(60, 2, 2, 100));
        midiClip.addNote(MidiNoteData.of(62, 6, 2, 100));
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // MIDI clip spans beats 0.5..2.0 → pixels 20..80 at 40 px/beat.
                // Press at x=40 (inside), drag −4 beats left (off-screen but fine).
                canvas.fireEvent(mousePressedCtrlAlt(40.0, 40.0));
                canvas.fireEvent(mouseDraggedCtrlAlt(-120.0, 40.0));
                canvas.fireEvent(mouseReleasedCtrlAlt(-120.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Requested delta = −16 columns, clamped to −2 so earliest note lands on column 0.
        List<MidiNoteData> notes = midiClip.getNotes();
        assertThat(notes.get(0).startColumn()).isEqualTo(0);
        assertThat(notes.get(1).startColumn()).isEqualTo(4);
    }

    @Test
    void pointerDragWithoutCtrlAltShouldNotSlip() throws Exception {
        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Loop", 2.0, 4.0, null);
        clip.setSourceOffsetBeats(10.0);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Plain pointer drag (no modifiers) inside the clip body.
                canvas.fireEvent(mousePressed(120.0, 40.0));
                canvas.fireEvent(mouseDragged(160.0, 40.0));
                canvas.fireEvent(mouseReleased(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // A plain drag is a move, not a slip — source offset is unchanged.
        assertThat(clip.getSourceOffsetBeats()).isCloseTo(10.0, within(1e-6));
    }

    @Test
    void eraserOnUnselectedClipShouldDeleteOnlyThatClip() throws Exception {

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("Kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("Snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);
        tracks.add(track);
        activeTool = EditTool.ERASER;
        // Only select clip2
        selectionModel.selectClip(track, clip2);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                canvas.setTracks(tracks);
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click on clip1 (not selected) — should only delete clip1
                canvas.fireEvent(mousePressed(80.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst()).isSameAs(clip2);
    }
}
