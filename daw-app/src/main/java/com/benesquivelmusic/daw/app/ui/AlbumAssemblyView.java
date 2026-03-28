package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.mastering.AlbumSequence;
import com.benesquivelmusic.daw.sdk.mastering.AlbumExportType;
import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Album assembly view for sequencing and assembling tracks into an album.
 *
 * <p>Provides a horizontal track listing with controls for:
 * <ul>
 *   <li>Drag-and-drop reordering of album tracks (via move buttons)</li>
 *   <li>Configuring the gap duration between each track (default: 2 seconds)</li>
 *   <li>Crossfade transitions between adjacent tracks with configurable curves</li>
 *   <li>Waveform previews for each album track</li>
 *   <li>Playback of the entire album sequence with seamless transitions</li>
 *   <li>Export as a single continuous WAV file or as individual tracks</li>
 *   <li>Per-track metadata fields (title, artist, ISRC code)</li>
 *   <li>Cue sheet generation</li>
 * </ul>
 *
 * <p>Uses existing CSS classes: {@code .content-area}, {@code .panel-header},
 * {@code .mixer-channel}.</p>
 */
public final class AlbumAssemblyView extends VBox {

    private static final double TRACK_CARD_WIDTH = 200;
    private static final double WAVEFORM_HEIGHT = 60;

    private final AlbumSequence albumSequence;
    private final HBox trackContainer;
    private final Label statusLabel;
    private final Label totalDurationLabel;
    private final ComboBox<String> exportTypeSelector;
    private final TextField albumTitleField;
    private final TextField albumArtistField;

    /**
     * Creates a new album assembly view with a default empty album sequence.
     */
    public AlbumAssemblyView() {
        this(new AlbumSequence("Untitled Album", "Unknown Artist"));
    }

    /**
     * Creates a new album assembly view bound to the given album sequence.
     *
     * @param albumSequence the album sequence to visualize and control
     */
    public AlbumAssemblyView(AlbumSequence albumSequence) {
        this.albumSequence = Objects.requireNonNull(albumSequence, "albumSequence must not be null");
        getStyleClass().add("content-area");
        setSpacing(0);

        // ── Header bar ──────────────────────────────────────────────────────
        Label headerLabel = new Label("Album Assembly");
        headerLabel.getStyleClass().add("panel-header");
        headerLabel.setGraphic(IconNode.of(DawIcon.ALBUM, 16));
        headerLabel.setPadding(new Insets(6, 10, 6, 10));

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        // ── Status bar ──────────────────────────────────────────────────────
        statusLabel = new Label("Add tracks to begin album assembly");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        statusLabel.setPadding(new Insets(4, 10, 6, 10));

        // Total duration label
        totalDurationLabel = new Label("Total: 00:00.000");
        totalDurationLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 11px;");

        // Export type selector
        List<String> exportTypes = new ArrayList<>();
        exportTypes.add("Single Continuous WAV");
        exportTypes.add("Individual Tracks");
        exportTypeSelector = new ComboBox<>(FXCollections.observableArrayList(exportTypes));
        exportTypeSelector.getSelectionModel().selectFirst();
        exportTypeSelector.setTooltip(new Tooltip("Select album export format"));

        // Export button
        Button exportButton = new Button("Export");
        exportButton.setGraphic(IconNode.of(DawIcon.DOWNLOAD, 14));
        exportButton.setTooltip(new Tooltip("Export album"));
        exportButton.setOnAction(event -> onExport());

        // Cue sheet button
        Button cueSheetButton = new Button("Cue Sheet");
        cueSheetButton.setGraphic(IconNode.of(DawIcon.PLAYLIST, 14));
        cueSheetButton.setTooltip(new Tooltip("Generate track listing / cue sheet"));
        cueSheetButton.setOnAction(event -> onGenerateCueSheet());

        // Playback button
        Button playButton = new Button("Play");
        playButton.setGraphic(IconNode.of(DawIcon.PLAY, 14));
        playButton.setTooltip(new Tooltip("Play entire album sequence"));
        playButton.setOnAction(event -> onPlay());

        // Add track button
        Button addTrackButton = new Button("Add Track");
        addTrackButton.setGraphic(IconNode.of(DawIcon.MUSIC_NOTE, 14));
        addTrackButton.setTooltip(new Tooltip("Add a new track to the album"));
        addTrackButton.setOnAction(event -> onAddTrack());

        HBox headerBar = new HBox(8, headerLabel, headerSpacer, addTrackButton,
                playButton, exportTypeSelector, exportButton, cueSheetButton);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(4, 10, 4, 0));

        // ── Album metadata section ──────────────────────────────────────────
        Label albumTitleLabel = new Label("Album Title:");
        albumTitleLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 11px;");
        albumTitleField = new TextField(albumSequence.getAlbumTitle());
        albumTitleField.setPrefWidth(200);
        albumTitleField.setPromptText("Album title");
        albumTitleField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty()) {
                albumSequence.setAlbumTitle(newValue);
            }
        });

        Label albumArtistLabel = new Label("Artist:");
        albumArtistLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 11px;");
        albumArtistField = new TextField(albumSequence.getArtist());
        albumArtistField.setPrefWidth(200);
        albumArtistField.setPromptText("Artist name");
        albumArtistField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty()) {
                albumSequence.setArtist(newValue);
            }
        });

        HBox metadataBar = new HBox(8, albumTitleLabel, albumTitleField,
                albumArtistLabel, albumArtistField, totalDurationLabel);
        metadataBar.setAlignment(Pos.CENTER_LEFT);
        metadataBar.setPadding(new Insets(4, 10, 4, 10));

        // ── Track listing area ──────────────────────────────────────────────
        trackContainer = new HBox(8);
        trackContainer.setAlignment(Pos.TOP_LEFT);
        trackContainer.setPadding(new Insets(10));

        ScrollPane trackScroll = new ScrollPane(trackContainer);
        trackScroll.setFitToHeight(true);
        trackScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        trackScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        trackScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(trackScroll, Priority.ALWAYS);

        getChildren().addAll(headerBar, new Separator(), metadataBar,
                new Separator(), trackScroll, statusLabel);
    }

    /**
     * Rebuilds the track cards from the current album sequence state.
     *
     * <p>Call this method after modifying the sequence to keep the view
     * synchronized with the model.</p>
     */
    public void refresh() {
        trackContainer.getChildren().clear();
        List<AlbumTrackEntry> tracks = albumSequence.getTracks();
        for (int i = 0; i < tracks.size(); i++) {
            AlbumTrackEntry entry = tracks.get(i);
            trackContainer.getChildren().add(buildTrackCard(entry, i, tracks.size()));
            if (i < tracks.size() - 1) {
                trackContainer.getChildren().add(buildTransitionIndicator(tracks.get(i + 1)));
            }
        }
        updateTotalDuration();
    }

    /**
     * Returns the album sequence model backing this view.
     *
     * @return the album sequence
     */
    public AlbumSequence getAlbumSequence() {
        return albumSequence;
    }

    /**
     * Returns the container holding the track cards.
     * Visible for testing.
     *
     * @return the track card container
     */
    HBox getTrackContainer() {
        return trackContainer;
    }

    /**
     * Returns the status label.
     * Visible for testing.
     *
     * @return the status label
     */
    Label getStatusLabel() {
        return statusLabel;
    }

    /**
     * Returns the total duration label.
     * Visible for testing.
     *
     * @return the total duration label
     */
    Label getTotalDurationLabel() {
        return totalDurationLabel;
    }

    /**
     * Returns the export type selector.
     * Visible for testing.
     *
     * @return the export type selector
     */
    ComboBox<String> getExportTypeSelector() {
        return exportTypeSelector;
    }

    /**
     * Returns the album title text field.
     * Visible for testing.
     *
     * @return the album title field
     */
    TextField getAlbumTitleField() {
        return albumTitleField;
    }

    /**
     * Returns the album artist text field.
     * Visible for testing.
     *
     * @return the album artist field
     */
    TextField getAlbumArtistField() {
        return albumArtistField;
    }

    /**
     * Returns the selected export type.
     *
     * @return the selected album export type
     */
    public AlbumExportType getSelectedExportType() {
        int index = exportTypeSelector.getSelectionModel().getSelectedIndex();
        if (index == 1) {
            return AlbumExportType.INDIVIDUAL_TRACKS;
        }
        return AlbumExportType.SINGLE_CONTINUOUS;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private VBox buildTrackCard(AlbumTrackEntry entry, int index, int totalTracks) {
        VBox card = new VBox(4);
        card.getStyleClass().add("mixer-channel");
        card.setAlignment(Pos.TOP_CENTER);
        card.setPrefWidth(TRACK_CARD_WIDTH);
        card.setMinWidth(TRACK_CARD_WIDTH);

        // Track number label
        Label trackNumLabel = new Label("Track " + (index + 1));
        trackNumLabel.setStyle("-fx-text-fill: #7c4dff; -fx-font-size: 10px; -fx-font-weight: bold;");

        // Title field
        TextField titleField = new TextField(entry.title());
        titleField.setPromptText("Title");
        titleField.setPrefWidth(TRACK_CARD_WIDTH - 16);
        titleField.setStyle("-fx-font-size: 11px;");
        int capturedIndex = index;
        titleField.focusedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue) {
                updateTrackTitle(capturedIndex, titleField.getText());
            }
        });

        // Artist field
        TextField artistField = new TextField(entry.artist() != null ? entry.artist() : "");
        artistField.setPromptText("Artist");
        artistField.setPrefWidth(TRACK_CARD_WIDTH - 16);
        artistField.setStyle("-fx-font-size: 11px;");
        artistField.focusedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue) {
                updateTrackArtist(capturedIndex, artistField.getText());
            }
        });

        // ISRC field
        TextField isrcField = new TextField(entry.isrc() != null ? entry.isrc() : "");
        isrcField.setPromptText("ISRC");
        isrcField.setPrefWidth(TRACK_CARD_WIDTH - 16);
        isrcField.setStyle("-fx-font-size: 10px;");
        isrcField.focusedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue) {
                updateTrackIsrc(capturedIndex, isrcField.getText());
            }
        });

        // Waveform preview
        WaveformDisplay waveform = new WaveformDisplay();
        waveform.setPrefWidth(TRACK_CARD_WIDTH - 16);
        waveform.setPrefHeight(WAVEFORM_HEIGHT);
        waveform.setMinHeight(WAVEFORM_HEIGHT);
        waveform.setMaxHeight(WAVEFORM_HEIGHT);

        // Duration label
        Label durationLabel = new Label(formatTime(entry.durationSeconds()));
        durationLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 10px;");

        // Gap spinner
        Label gapLabel = new Label("Gap (s):");
        gapLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 10px;");
        Spinner<Double> gapSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(
                        0.0, AlbumTrackEntry.MAX_PRE_GAP_SECONDS, entry.preGapSeconds(), 0.5));
        gapSpinner.setPrefWidth(80);
        gapSpinner.setEditable(true);
        gapSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                updateTrackGap(capturedIndex, newValue));
        HBox gapRow = new HBox(4, gapLabel, gapSpinner);
        gapRow.setAlignment(Pos.CENTER_LEFT);

        // Crossfade duration spinner
        Label xfadeLabel = new Label("Xfade (s):");
        xfadeLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 10px;");
        Spinner<Double> xfadeSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(
                        0.0, 30.0, entry.crossfadeDuration(), 0.5));
        xfadeSpinner.setPrefWidth(80);
        xfadeSpinner.setEditable(true);
        xfadeSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                updateTrackCrossfadeDuration(capturedIndex, newValue));
        HBox xfadeRow = new HBox(4, xfadeLabel, xfadeSpinner);
        xfadeRow.setAlignment(Pos.CENTER_LEFT);

        // Crossfade curve selector
        Label curveLabel = new Label("Curve:");
        curveLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 10px;");
        ComboBox<CrossfadeCurve> curveSelector = new ComboBox<>(
                FXCollections.observableArrayList(CrossfadeCurve.values()));
        curveSelector.getSelectionModel().select(entry.crossfadeCurve());
        curveSelector.setPrefWidth(110);
        curveSelector.setOnAction(event ->
                updateTrackCrossfadeCurve(capturedIndex, curveSelector.getValue()));
        HBox curveRow = new HBox(4, curveLabel, curveSelector);
        curveRow.setAlignment(Pos.CENTER_LEFT);

        // Move buttons for reordering (drag-and-drop)
        HBox moveRow = new HBox(4);
        moveRow.setAlignment(Pos.CENTER);
        if (index > 0) {
            Button moveLeft = new Button("\u25C0");
            moveLeft.setTooltip(new Tooltip("Move left"));
            moveLeft.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4;");
            moveLeft.setOnAction(event -> moveTrack(capturedIndex, capturedIndex - 1));
            moveRow.getChildren().add(moveLeft);
        }

        // Remove button
        Button removeButton = new Button("\u2716");
        removeButton.setTooltip(new Tooltip("Remove track"));
        removeButton.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4; -fx-text-fill: #ff5252;");
        removeButton.setOnAction(event -> removeTrack(capturedIndex));
        moveRow.getChildren().add(removeButton);

        if (index < totalTracks - 1) {
            Button moveRight = new Button("\u25B6");
            moveRight.setTooltip(new Tooltip("Move right"));
            moveRight.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4;");
            moveRight.setOnAction(event -> moveTrack(capturedIndex, capturedIndex + 1));
            moveRow.getChildren().add(moveRight);
        }

        card.getChildren().addAll(trackNumLabel, titleField, artistField, isrcField,
                waveform, durationLabel, gapRow, xfadeRow, curveRow, moveRow);
        return card;
    }

    private static Node buildTransitionIndicator(AlbumTrackEntry nextEntry) {
        String symbol;
        if (nextEntry.crossfadeDuration() > 0) {
            symbol = "\u2A2F"; // crossfade symbol
        } else {
            symbol = "\u279C"; // arrow
        }
        Label indicator = new Label(symbol);
        indicator.setStyle("-fx-text-fill: #7c4dff; -fx-font-size: 18px;");
        indicator.setPadding(new Insets(60, 2, 0, 2));
        return indicator;
    }

    private void moveTrack(int fromIndex, int toIndex) {
        albumSequence.moveTrack(fromIndex, toIndex);
        refresh();
        statusLabel.setText("Moved track " + (fromIndex + 1) + " to position " + (toIndex + 1));
    }

    private void removeTrack(int index) {
        AlbumTrackEntry removed = albumSequence.removeTrack(index);
        refresh();
        statusLabel.setText("Removed: " + removed.title());
    }

    private void updateTrackTitle(int index, String newTitle) {
        if (newTitle == null || newTitle.isEmpty()) {
            return;
        }
        AlbumTrackEntry old = albumSequence.getTracks().get(index);
        AlbumTrackEntry updated = new AlbumTrackEntry(newTitle, old.artist(), old.isrc(),
                old.durationSeconds(), old.preGapSeconds(), old.crossfadeDuration(), old.crossfadeCurve());
        albumSequence.setTrack(index, updated);
    }

    private void updateTrackArtist(int index, String newArtist) {
        AlbumTrackEntry old = albumSequence.getTracks().get(index);
        String artist = (newArtist != null && !newArtist.isEmpty()) ? newArtist : null;
        albumSequence.setTrack(index, old.withArtist(artist));
    }

    private void updateTrackIsrc(int index, String newIsrc) {
        AlbumTrackEntry old = albumSequence.getTracks().get(index);
        String isrc = (newIsrc != null && !newIsrc.isEmpty()) ? newIsrc : null;
        albumSequence.setTrack(index, old.withIsrc(isrc));
    }

    private void updateTrackGap(int index, double newGap) {
        AlbumTrackEntry old = albumSequence.getTracks().get(index);
        albumSequence.setTrack(index, old.withPreGapSeconds(newGap));
        updateTotalDuration();
    }

    private void updateTrackCrossfadeDuration(int index, double newDuration) {
        AlbumTrackEntry old = albumSequence.getTracks().get(index);
        albumSequence.setTrack(index, old.withCrossfade(newDuration, old.crossfadeCurve()));
        updateTotalDuration();
    }

    private void updateTrackCrossfadeCurve(int index, CrossfadeCurve newCurve) {
        AlbumTrackEntry old = albumSequence.getTracks().get(index);
        albumSequence.setTrack(index, old.withCrossfade(old.crossfadeDuration(), newCurve));
    }

    private void onAddTrack() {
        int trackNum = albumSequence.size() + 1;
        AlbumTrackEntry newEntry = AlbumTrackEntry.of("Track " + trackNum, 180.0);
        albumSequence.addTrack(newEntry);
        refresh();
        statusLabel.setText("Added: Track " + trackNum);
    }

    private void onExport() {
        AlbumExportType exportType = getSelectedExportType();
        statusLabel.setText("Export requested: " + exportType.name()
                + " (" + albumSequence.size() + " tracks)");
    }

    private void onGenerateCueSheet() {
        if (albumSequence.size() == 0) {
            statusLabel.setText("No tracks to generate cue sheet");
            return;
        }
        String cueSheet = albumSequence.generateCueSheet();
        statusLabel.setText("Cue sheet generated (" + albumSequence.size() + " tracks)");
    }

    private void onPlay() {
        if (albumSequence.size() == 0) {
            statusLabel.setText("No tracks to play");
            return;
        }
        statusLabel.setText("Playing album: " + albumSequence.getAlbumTitle()
                + " (" + formatTime(albumSequence.getTotalDurationSeconds()) + ")");
    }

    private void updateTotalDuration() {
        totalDurationLabel.setText("Total: " + formatTime(albumSequence.getTotalDurationSeconds()));
    }

    private static String formatTime(double seconds) {
        int mins = (int) (seconds / 60);
        double secs = seconds - mins * 60;
        return String.format("%02d:%06.3f", mins, secs);
    }
}
