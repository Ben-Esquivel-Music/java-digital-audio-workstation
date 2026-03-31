package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CrossTrackMoveAction;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClipInteractionControllerTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private UndoManager undoManager;
    private List<Track> tracks;
    private EditTool activeTool;
    private double pixelsPerBeat;
    private double scrollXBeats;
    private double scrollYPixels;
    private double trackHeight;
    private int refreshCount;

    private double seekedPosition;

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

    // ── Hit testing ──────────────────────────────────────────────────────────

    @Test
    void shouldResolveTrackIndex() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                tracks.add(new Track("Track 1", TrackType.AUDIO));
                tracks.add(new Track("Track 2", TrackType.AUDIO));
                ArrangementCanvas canvas = new ArrangementCanvas();
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.PENCIL;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        track.addClip(new AudioClip("Existing", 4.0, 4.0, null));
        tracks.add(track);
        activeTool = EditTool.PENCIL;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.ERASER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.SCISSORS;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
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
        assertThat(refreshCount).isEqualTo(1);
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                controller.install();

                // Click at x=160 (beat 4.0 at 40px/beat), y=40 (track 0, no clips)
                canvas.fireEvent(mousePressed(160.0, 40.0));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(seekedPosition).isEqualTo(4.0);
    }

    @Test
    void pointerShouldSeekWhenClickingBelowAllTracks() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.POINTER;

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
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
}
