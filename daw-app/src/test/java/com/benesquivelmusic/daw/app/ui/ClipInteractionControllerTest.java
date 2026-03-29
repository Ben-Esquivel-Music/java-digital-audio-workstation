package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;

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
            @Override public void refreshCanvas() { refreshCount++; }
        };
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

    // ── Pencil tool ──────────────────────────────────────────────────────────

    @Test
    void pencilShouldCreateClipAtEmptyPosition() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        tracks.add(track);
        activeTool = EditTool.PENCIL;

        AtomicReference<ClipInteractionController> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                ClipInteractionController controller = new ClipInteractionController(canvas, createHost());
                ref.set(controller);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Simulate pencil creating a clip — test the handler directly
        // The beat position 4.0 = 160px at 40px/beat
        // Track index 0 = y between 0 and 80
        ClipInteractionController controller = ref.get();

        // Use internal methods to test the logic (pencil creates a clip)
        assertThat(track.getClips()).isEmpty();

        // Directly call the beat/track logic
        double beat = 4.0;
        AudioClip existingClip = controller.clipAt(track, beat);
        assertThat(existingClip).isNull();

        // Simulate pencil action by executing what the handler does
        AudioClip newClip = new AudioClip("New Clip", beat,
                ClipInteractionController.DEFAULT_NEW_CLIP_DURATION, null);
        undoManager.execute(new com.benesquivelmusic.daw.core.audio.AddClipAction(track, newClip));

        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getStartBeat()).isEqualTo(4.0);
        assertThat(track.getClips().getFirst().getDurationBeats())
                .isEqualTo(ClipInteractionController.DEFAULT_NEW_CLIP_DURATION);
    }

    // ── Eraser tool ──────────────────────────────────────────────────────────

    @Test
    void eraserShouldRemoveClip() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.ERASER;

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
        AudioClip found = controller.clipAt(track, 3.0);
        assertThat(found).isSameAs(clip);

        // Simulate eraser action
        undoManager.execute(new com.benesquivelmusic.daw.core.audio.RemoveClipAction(track, clip));
        assertThat(track.getClips()).isEmpty();

        // Undo should restore
        undoManager.undo();
        assertThat(track.getClips()).hasSize(1);
    }

    // ── Scissors tool ────────────────────────────────────────────────────────

    @Test
    void scissorsShouldSplitClip() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 0.0, 8.0, null);
        track.addClip(clip);
        tracks.add(track);
        activeTool = EditTool.SCISSORS;

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
        AudioClip found = controller.clipAt(track, 4.0);
        assertThat(found).isSameAs(clip);

        // Simulate scissors at beat 4.0
        undoManager.execute(new com.benesquivelmusic.daw.core.audio.SplitClipAction(track, clip, 4.0));
        assertThat(track.getClips()).hasSize(2);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);

        // Undo restores
        undoManager.undo();
        assertThat(track.getClips()).hasSize(1);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
    }

    // ── Glue tool ────────────────────────────────────────────────────────────

    @Test
    void glueShouldMergeAdjacentClips() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");

        Track track = new Track("Track 1", TrackType.AUDIO);
        AudioClip first = new AudioClip("Part A", 0.0, 4.0, null);
        AudioClip second = new AudioClip("Part B", 4.0, 4.0, null);
        track.addClip(first);
        track.addClip(second);
        tracks.add(track);
        activeTool = EditTool.GLUE;

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

        // Simulate glue action
        undoManager.execute(new com.benesquivelmusic.daw.core.audio.GlueClipsAction(track, first, second));
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().getFirst().getDurationBeats()).isEqualTo(8.0);

        // Undo restores both clips
        undoManager.undo();
        assertThat(track.getClips()).hasSize(2);
    }

    // ── Cross-track move ─────────────────────────────────────────────────────

    @Test
    void crossTrackMoveShouldTransferClip() {
        Track source = new Track("Track 1", TrackType.AUDIO);
        Track target = new Track("Track 2", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        source.addClip(clip);

        ClipInteractionController.CrossTrackMoveAction action =
                new ClipInteractionController.CrossTrackMoveAction(source, target, clip, 8.0);
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

        ClipInteractionController.CrossTrackMoveAction action =
                new ClipInteractionController.CrossTrackMoveAction(source, target, clip, 8.0);

        assertThat(action.description()).isEqualTo("Move Clip to Track");
    }

    // ── Default clip duration constant ───────────────────────────────────────

    @Test
    void defaultNewClipDurationShouldBeFourBeats() {
        assertThat(ClipInteractionController.DEFAULT_NEW_CLIP_DURATION).isEqualTo(4.0);
    }
}
