package com.benesquivelmusic.daw.app.ui;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    private ClipInteractionController.Host createHost() {
        return new ClipInteractionController.Host() {
            @Override public List<Track> tracks() { return tracks; }
            @Override public EditTool activeTool() { return activeTool; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public double pixelsPerBeat() { return pixelsPerBeat; }
            @Override public double scrollXBeats() { return scrollXBeats; }
            @Override public double scrollYPixels() { return scrollYPixels; }
            @Override public double trackHeight() { return trackHeight; }
            @Override public boolean snapEnabled() { return false; }
            @Override public GridResolution gridResolution() { return GridResolution.QUARTER; }
            @Override public int beatsPerBar() { return 4; }
            @Override public void refreshCanvas() { refreshCount++; }
            @Override public void seekToPosition(double beat) { seekedPosition = beat; }
            @Override public SelectionModel selectionModel() { return selectionModel; }
            @Override public void updateStatusBar(String text) { lastStatusBarText = text; }
            @Override public RippleMode rippleMode() {
                return rippleMode;
            }
            @Override public void showNotification(NotificationLevel level, String message) {
                // no-op in tests
            }
        };
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
