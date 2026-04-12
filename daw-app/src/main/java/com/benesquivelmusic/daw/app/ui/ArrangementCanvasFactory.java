package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Factory that assembles the arrangement canvas, timeline ruler, and clip
 * interaction controller, returning the three components as a result record.
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of arrangement UI assembly details.</p>
 */
final class ArrangementCanvasFactory {

    interface Host {
        DawProject project();
        UndoManager undoManager();
        SelectionModel selectionModel();
        EditTool activeEditTool();
        boolean isSnapEnabled();
        GridResolution gridResolution();
        void refreshCanvas();
        void seekToPosition(double beat);
        void updateStatusBar(String text);
    }

    record Result(ArrangementCanvas canvas, TimelineRuler ruler, ClipInteractionController clipInteraction) {}

    private ArrangementCanvasFactory() {}

    static Result create(StackPane arrangementContentPane, Host host,
                         Consumer<Double> seekConsumer) {
        ArrangementCanvas canvas = new ArrangementCanvas();
        arrangementContentPane.getChildren().addFirst(canvas);
        canvas.prefWidthProperty().bind(arrangementContentPane.widthProperty());
        canvas.prefHeightProperty().bind(arrangementContentPane.heightProperty());

        TimelineRuler ruler = new TimelineRuler(host.project().getTransport());
        Parent contentParent = arrangementContentPane.getParent();
        if (contentParent instanceof VBox vbox) {
            int idx = vbox.getChildren().indexOf(arrangementContentPane);
            if (idx >= 0) { vbox.getChildren().add(idx, ruler); }
        }
        ruler.addSeekListener(seekConsumer::accept);

        ClipInteractionController clipInteraction = new ClipInteractionController(canvas,
                new ClipInteractionController.Host() {
                    @Override public List<Track> tracks() { return host.project().getTracks(); }
                    @Override public EditTool activeTool() { return host.activeEditTool(); }
                    @Override public UndoManager undoManager() { return host.undoManager(); }
                    @Override public double pixelsPerBeat() { return canvas.getPixelsPerBeat(); }
                    @Override public double scrollXBeats() { return canvas.getScrollXBeats(); }
                    @Override public double scrollYPixels() { return canvas.getScrollYPixels(); }
                    @Override public double trackHeight() { return canvas.getTrackHeight(); }
                    @Override public boolean snapEnabled() { return host.isSnapEnabled(); }
                    @Override public GridResolution gridResolution() { return host.gridResolution(); }
                    @Override public int beatsPerBar() { return host.project().getTransport().getTimeSignatureNumerator(); }
                    @Override public void refreshCanvas() { host.refreshCanvas(); }
                    @Override public void seekToPosition(double beat) { host.seekToPosition(beat); }
                    @Override public SelectionModel selectionModel() { return host.selectionModel(); }
                    @Override public void updateStatusBar(String text) { host.updateStatusBar(text); }
                });
        clipInteraction.install();

        return new Result(canvas, ruler, clipInteraction);
    }
}
