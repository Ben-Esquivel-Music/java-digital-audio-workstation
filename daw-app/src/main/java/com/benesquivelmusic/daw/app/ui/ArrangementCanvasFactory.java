package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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
        RippleMode rippleMode();
        void showNotification(NotificationLevel level, String message);
        // Story 042 — Time-Stretching and Pitch-Shifting clip context menu.
        void onTimeStretchClip();
        void onPitchShiftClip();
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
        // Story 328 — punch frames are PROJECT-rate; the ruler converts them to
        // beats at that rate so the drawn region matches the frames the capture
        // gate acts on (book §5.6). Re-applied on every project switch by
        // MainController.rebuildViewModels().
        ruler.setSampleRate(host.project().getFormat().sampleRate());
        Parent contentParent = arrangementContentPane.getParent();
        if (contentParent instanceof VBox vbox) {
            int idx = vbox.getChildren().indexOf(arrangementContentPane);
            if (idx >= 0) { vbox.getChildren().add(idx, ruler); }
        }
        ruler.addSeekListener(seekConsumer::accept);
        // A loop or punch gesture that changed the transport marks the project
        // dirty (PR #984 review). Read live: the project is swapped on Open / New,
        // and the ruler is rebound to the new transport, not rebuilt.
        ruler.setOnTransportEdited(() -> host.project().markDirty());

        // Story 293 — the ClipInteractionController's former Host is retired;
        // adapt this factory's host into a Deps record of direct functional
        // dependencies. Geometry getters read the live canvas; tool/snap/grid/
        // ripple/selection read the factory host; the swappable undo manager is
        // read live via host::undoManager.
        ClipInteractionController clipInteraction = new ClipInteractionController(canvas,
                new ClipInteractionController.Deps(
                        () -> host.project().getTracks(),
                        host::activeEditTool,
                        host::undoManager,
                        canvas::getPixelsPerBeat,
                        canvas::getScrollXBeats,
                        canvas::getScrollYPixels,
                        canvas::getTrackHeight,
                        host::isSnapEnabled,
                        host::gridResolution,
                        () -> host.project().getTransport().getTimeSignatureNumerator(),
                        host::refreshCanvas,
                        host::seekToPosition,
                        host::selectionModel,
                        host::updateStatusBar,
                        host::rippleMode,
                        host::showNotification,
                        () -> host.project().getTransport().getTempo(),
                        host::onTimeStretchClip,
                        host::onPitchShiftClip,
                        // Read live: the project is swapped on Open / New.
                        () -> host.project().markDirty()));
        clipInteraction.install();

        return new Result(canvas, ruler, clipInteraction);
    }
}
