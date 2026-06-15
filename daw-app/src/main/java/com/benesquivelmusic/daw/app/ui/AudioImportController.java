package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.audioimport.AudioImportResult;
import com.benesquivelmusic.daw.core.audioimport.SupportedAudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles audio file import via file chooser and arrangement canvas drag-and-drop.
 *
 * <p>Extracted from {@code MainController} to separate audio import logic
 * from the main coordinator.</p>
 */
final class AudioImportController {

    private static final Logger LOG = Logger.getLogger(AudioImportController.class.getName());

    /**
     * Story 294 — direct functional deps replace the callback-up {@code Host}
     * (Control Synchronization Design Book §4.2/§9). The swappable project /
     * undo manager / collaborators / stage are live {@link Supplier}s; the
     * canvas-repaint / undo-menu / dirty / status / notification hooks are
     * {@link Runnable}/{@link BiConsumer} sinks. The old
     * {@code updateArrangementPlaceholder} hook is gone (the placeholder binds
     * {@code ProjectVM.tracks} now, story 293), but {@code refreshArrangementCanvas}
     * is kept: an import adds a clip to an existing track, which does not change
     * the track LIST, so the canvas needs an explicit repaint the reactive
     * {@code ProjectVM.tracks} binding does not cover.
     */
    record Deps(
            Supplier<DawProject> project,
            Supplier<UndoManager> undoManager,
            Supplier<TrackStripController> trackStripController,
            Supplier<TrackCreationController> trackCreationController,
            Supplier<MixerView> mixerView,
            Supplier<VBox> trackListPanel,
            Supplier<Stage> primaryStage,
            Runnable refreshArrangementCanvas,
            Runnable updateUndoRedoState,
            Runnable markProjectDirty,
            BiConsumer<String, DawIcon> updateStatusBar,
            BiConsumer<NotificationLevel, String> showNotification) {
    }

    private final Deps deps;

    AudioImportController(Deps deps) {
        this.deps = deps;
    }

    void onImportAudioFile() {
        Stage stage = deps.primaryStage().get();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Audio File");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files",
                        "*.wav", "*.flac", "*.aiff", "*.aif", "*.ogg", "*.mp3"),
                new FileChooser.ExtensionFilter("WAV Files", "*.wav"),
                new FileChooser.ExtensionFilter("FLAC Files", "*.flac"),
                new FileChooser.ExtensionFilter("AIFF Files", "*.aiff", "*.aif"),
                new FileChooser.ExtensionFilter("OGG Files", "*.ogg"),
                new FileChooser.ExtensionFilter("MP3 Files", "*.mp3"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        java.io.File selectedFile = chooser.showOpenDialog(stage);
        if (selectedFile == null) {
            return;
        }
        importAudioFile(selectedFile.toPath(), null);
    }

    boolean importAudioFile(Path file, Track targetTrack) {
        DawProject project = deps.project().get();
        AudioFileImporter importer = new AudioFileImporter(project);
        double playheadBeat = project.getTransport().getPositionInBeats();
        boolean createdNewTrack = (targetTrack == null);

        try {
            AudioImportResult result = importer.importFile(file, playheadBeat, targetTrack);

            HBox trackItem = createdNewTrack
                    ? deps.trackStripController().get().addTrackToUI(result.track()) : null;
            if (createdNewTrack) {
                deps.trackCreationController().get().setAudioTrackCounter(
                        deps.trackCreationController().get().getAudioTrackCounter() + 1);
            }

            deps.undoManager().get().execute(new UndoableAction() {
                private boolean initialExecute = true;
                @Override public String description() { return "Import Audio File"; }
                @Override public void execute() {
                    if (initialExecute) {
                        initialExecute = false;
                        return;
                    }
                    if (createdNewTrack) {
                        project.addTrack(result.track());
                        if (trackItem != null) {
                            deps.trackListPanel().get().getChildren().add(trackItem);
                        }
                        deps.trackCreationController().get().setAudioTrackCounter(
                                deps.trackCreationController().get().getAudioTrackCounter() + 1);
                    }
                    result.track().addClip(result.clip());
                    deps.mixerView().get().refresh();
                }
                @Override public void undo() {
                    result.track().removeClip(result.clip());
                    if (createdNewTrack) {
                        project.removeTrack(result.track());
                        if (trackItem != null) {
                            deps.trackListPanel().get().getChildren().remove(trackItem);
                        }
                        deps.trackCreationController().get().setAudioTrackCounter(
                                deps.trackCreationController().get().getAudioTrackCounter() - 1);
                    }
                    deps.mixerView().get().refresh();
                }
            });

            deps.refreshArrangementCanvas().run();
            deps.updateUndoRedoState().run();
            deps.mixerView().get().refresh();
            deps.markProjectDirty().run();

            double durationSeconds = 0.0;
            float[][] audioData = result.clip().getAudioData();
            if (audioData != null && audioData.length > 0) {
                int sampleRate = (int) project.getFormat().sampleRate();
                durationSeconds = (double) audioData[0].length / sampleRate;
            }
            String fileName = file.getFileName().toString();
            String formatName = SupportedAudioFormat.fromPath(file)
                    .map(f -> f.name())
                    .orElse("Audio");
            String durationStr = String.format("%.1fs", durationSeconds);
            String conversionNote = result.wasConverted() ? ", sample rate converted" : "";
            deps.showNotification().accept(NotificationLevel.SUCCESS,
                    "Imported: " + fileName + " (" + formatName + ", " + durationStr + conversionNote + ")");
            deps.updateStatusBar().accept("Imported audio file: " + fileName, DawIcon.WAVEFORM);
            LOG.fine(() -> "Imported audio file: " + file);
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to import audio file: " + file, e);
            deps.showNotification().accept(NotificationLevel.ERROR, "Import failed: " + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Unsupported audio file: " + file, e);
            deps.showNotification().accept(NotificationLevel.ERROR, "Import failed: " + e.getMessage());
            return false;
        }
    }

    void installArrangementCanvasDragDrop(ArrangementCanvas canvas) {
        canvas.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                boolean hasAudio = event.getDragboard().getFiles().stream()
                        .anyMatch(f -> SupportedAudioFormat.isSupported(f.toPath()));
                if (hasAudio) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
            } else if (event.getDragboard().hasString()) {
                String path = event.getDragboard().getString();
                if (SupportedAudioFormat.isSupported(Path.of(path))) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
            }
            event.consume();
        });

        canvas.setOnDragDropped(event -> {
            boolean success = false;
            List<Path> filesToImport = new ArrayList<>();

            if (event.getDragboard().hasFiles()) {
                for (java.io.File f : event.getDragboard().getFiles()) {
                    if (SupportedAudioFormat.isSupported(f.toPath())) {
                        filesToImport.add(f.toPath());
                    }
                }
            } else if (event.getDragboard().hasString()) {
                String pathStr = event.getDragboard().getString();
                Path path = Path.of(pathStr);
                if (SupportedAudioFormat.isSupported(path) && Files.isRegularFile(path)) {
                    filesToImport.add(path);
                }
            }

            if (!filesToImport.isEmpty()) {
                DawProject project = deps.project().get();
                int trackIndex = canvas.trackIndexAtY(event.getY());
                Track targetTrack = null;
                if (trackIndex >= 0 && trackIndex < project.getTracks().size()) {
                    Track candidate = project.getTracks().get(trackIndex);
                    if (candidate.getType() == TrackType.AUDIO) {
                        targetTrack = candidate;
                    }
                }

                double dropBeat = event.getX() / canvas.getPixelsPerBeat()
                        + canvas.getScrollXBeats();
                dropBeat = Math.max(0.0, dropBeat);

                double savedPlayhead = project.getTransport().getPositionInBeats();
                project.getTransport().setPositionInBeats(dropBeat);
                try {
                    success = importAudioFile(filesToImport.getFirst(), targetTrack);
                } finally {
                    project.getTransport().setPositionInBeats(savedPlayhead);
                }
            }

            event.setDropCompleted(success);
            event.consume();
        });
    }
}
