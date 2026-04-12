package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;

/**
 * Audio waveform editor view for clip-level audio editing.
 *
 * <p>Provides a {@link WaveformDisplay} component with trim and
 * fade-in/fade-out handle buttons. Zoom controls live in the parent
 * {@link EditorView} toolbar and currently only affect the MIDI sub-view.</p>
 */
final class AudioEditorView extends VBox {

    private static final double TOOLBAR_ICON_SIZE = 14;

    private final WaveformDisplay waveformDisplay;
    private final Button trimBtn;
    private final Button fadeInBtn;
    private final Button fadeOutBtn;

    private Runnable onTrimAction;
    private Runnable onFadeInAction;
    private Runnable onFadeOutAction;

    private Track selectedTrack;

    /**
     * Creates a new audio editor view with waveform display and audio handles.
     */
    AudioEditorView() {
        Label audioLabel = new Label("Audio Waveform");
        audioLabel.getStyleClass().add("panel-header");
        audioLabel.setGraphic(IconNode.of(DawIcon.WAVEFORM, 14));
        audioLabel.setPadding(new Insets(0, 0, 4, 0));

        waveformDisplay = new WaveformDisplay();
        waveformDisplay.setPrefHeight(200);
        waveformDisplay.setMinHeight(100);
        VBox.setVgrow(waveformDisplay, Priority.ALWAYS);

        // Audio handle buttons
        trimBtn = new Button("Trim");
        trimBtn.setGraphic(IconNode.of(DawIcon.TRIM, TOOLBAR_ICON_SIZE));
        trimBtn.setTooltip(new Tooltip("Trim selection"));
        trimBtn.getStyleClass().add("editor-tool-button");
        trimBtn.setOnAction(event -> {
            if (onTrimAction != null) {
                onTrimAction.run();
            }
        });

        fadeInBtn = new Button("Fade In");
        fadeInBtn.setGraphic(IconNode.of(DawIcon.FADE_IN, TOOLBAR_ICON_SIZE));
        fadeInBtn.setTooltip(new Tooltip("Apply fade in"));
        fadeInBtn.getStyleClass().add("editor-tool-button");
        fadeInBtn.setOnAction(event -> {
            if (onFadeInAction != null) {
                onFadeInAction.run();
            }
        });

        fadeOutBtn = new Button("Fade Out");
        fadeOutBtn.setGraphic(IconNode.of(DawIcon.FADE_OUT, TOOLBAR_ICON_SIZE));
        fadeOutBtn.setTooltip(new Tooltip("Apply fade out"));
        fadeOutBtn.getStyleClass().add("editor-tool-button");
        fadeOutBtn.setOnAction(event -> {
            if (onFadeOutAction != null) {
                onFadeOutAction.run();
            }
        });

        updateAudioHandleButtons();

        HBox handles = new HBox(4, trimBtn, fadeInBtn, fadeOutBtn);
        handles.setAlignment(Pos.CENTER_LEFT);
        handles.setPadding(new Insets(4, 0, 0, 0));
        handles.getStyleClass().add("editor-audio-handles");

        getChildren().addAll(audioLabel, waveformDisplay, handles);
        setSpacing(4);
        VBox.setVgrow(this, Priority.ALWAYS);
    }

    // ── Accessors (for testing and delegation) ──────────────────────────────

    WaveformDisplay getWaveformDisplay() {
        return waveformDisplay;
    }

    Button getTrimButton() {
        return trimBtn;
    }

    Button getFadeInButton() {
        return fadeInBtn;
    }

    Button getFadeOutButton() {
        return fadeOutBtn;
    }

    // ── Tool state ──────────────────────────────────────────────────────────

    void setActiveEditTool(EditTool tool) {
        Cursor cursor = switch (tool) {
            case POINTER -> Cursor.DEFAULT;
            case PENCIL -> Cursor.CROSSHAIR;
            case ERASER -> Cursor.HAND;
            default -> Cursor.DEFAULT;
        };
        waveformDisplay.setCursor(cursor);
    }

    // ── Audio handle callbacks ──────────────────────────────────────────────

    void setOnTrimAction(Runnable handler) {
        this.onTrimAction = handler;
    }

    void setOnFadeInAction(Runnable handler) {
        this.onFadeInAction = handler;
    }

    void setOnFadeOutAction(Runnable handler) {
        this.onFadeOutAction = handler;
    }

    // ── Track state ─────────────────────────────────────────────────────────

    void setSelectedTrack(Track track) {
        this.selectedTrack = track;
        updateAudioHandleButtons();
    }

    /**
     * Enables or disables the Trim, Fade In, and Fade Out buttons based on
     * whether the selected track is an audio track with at least one clip.
     */
    private void updateAudioHandleButtons() {
        boolean disabled = selectedTrack == null
                || selectedTrack.getType() == TrackType.MIDI
                || selectedTrack.getClips().isEmpty();
        trimBtn.setDisable(disabled);
        fadeInBtn.setDisable(disabled);
        fadeOutBtn.setDisable(disabled);
    }
}
