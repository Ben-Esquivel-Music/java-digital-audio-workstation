package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.drag.DragSourceKind;
import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;
import com.benesquivelmusic.daw.app.ui.drag.DropTargetKind;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A resizable side panel for browsing audio samples, presets, project files,
 * and navigating the local file system.
 *
 * <p>The panel contains a search/filter bar at the top and a tabbed section
 * with four tabs:</p>
 * <ul>
 *   <li><b>File System</b> — Navigate local directories filtered by audio file type</li>
 *   <li><b>Samples</b> — Dedicated sample library with preview playback</li>
 *   <li><b>Presets</b> — Plugin preset browser</li>
 *   <li><b>Project Files</b> — Quick access to recently used audio files</li>
 * </ul>
 *
 * <p>Uses existing CSS classes and follows the dark neon theme styling of the
 * application.</p>
 */
public final class BrowserPanel extends VBox {

    private static final Logger LOG = Logger.getLogger(BrowserPanel.class.getName());

    /** Supported audio file extensions for filtering. */
    static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".wav", ".flac", ".mp3", ".aiff", ".ogg"
    );

    private static final double DEFAULT_WIDTH = 250.0;
    private static final double MIN_WIDTH = 180.0;
    private static final double ICON_SIZE = 14.0;
    private static final double PREVIEW_ICON_SIZE = 12.0;

    private final TextField searchField;
    private final TabPane tabPane;
    private final TreeView<String> fileSystemTree;
    private final ListView<String> samplesListView;
    private final ListView<String> presetsListView;
    private final ListView<String> projectFilesListView;

    private final ObservableList<String> sampleItems;
    private final ObservableList<String> presetItems;
    private final ObservableList<String> projectFileItems;
    private final FilteredList<String> filteredSampleItems;
    private final FilteredList<String> filteredPresetItems;
    private final FilteredList<String> filteredProjectFileItems;

    private final Button previewPlayButton;
    private final Button previewStopButton;
    private final Slider previewVolumeSlider;
    private final Label previewMetadataLabel;
    private final HBox previewControlBar;

    /**
     * The shared {@link DragVisualAdvisor} consulted on every sample-drag
     * gesture (story 197). Optional — when {@code null} the panel falls
     * back to bare JavaFX drag-and-drop with no ghost / highlight.
     */
    private DragVisualAdvisor dragVisualAdvisor;

    /**
     * Creates a new browser panel with default width.
     */
    public BrowserPanel() {
        getStyleClass().add("browser-panel");
        setPrefWidth(DEFAULT_WIDTH);
        setMinWidth(MIN_WIDTH);
        setSpacing(6);
        setPadding(new Insets(8));

        // ── Header ──────────────────────────────────────────────────────────
        Label headerLabel = new Label("BROWSER");
        headerLabel.getStyleClass().add("panel-header");
        headerLabel.setGraphic(IconNode.of(DawIcon.LIBRARY, ICON_SIZE));

        // ── Search/Filter bar ───────────────────────────────────────────────
        searchField = new TextField();
        searchField.setPromptText("Filter files...");
        searchField.getStyleClass().add("browser-search-field");
        HBox searchBar = new HBox(4);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        Label searchIcon = new Label();
        searchIcon.setGraphic(IconNode.of(DawIcon.SEARCH, 12));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBar.getChildren().addAll(searchIcon, searchField);

        // ── File System tab ─────────────────────────────────────────────────
        TreeItem<String> rootItem = new TreeItem<>("File System");
        rootItem.setExpanded(true);
        addUserHomeDirectories(rootItem);
        fileSystemTree = new TreeView<>(rootItem);
        fileSystemTree.setShowRoot(true);
        fileSystemTree.getStyleClass().add("browser-tree");
        VBox.setVgrow(fileSystemTree, Priority.ALWAYS);

        // Enable drag-and-drop from the file tree onto the arrangement view
        fileSystemTree.setOnDragDetected(event -> {
            TreeItem<String> selected = fileSystemTree.getSelectionModel().getSelectedItem();
            if (selected != null && isAudioFile(selected.getValue())) {
                String filePath = resolveTreeItemPath(selected);
                if (filePath != null) {
                    Dragboard db = fileSystemTree.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(filePath);
                    db.setContent(content);
                    notifyAdvisorBeginSampleDrag(selected.getValue(), event.getScreenX(), event.getScreenY());
                }
            }
            event.consume();
        });
        fileSystemTree.setOnDragDone(this::notifyAdvisorEndDrag);

        Tab fileSystemTab = new Tab("Files");
        fileSystemTab.setClosable(false);
        fileSystemTab.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
        fileSystemTab.setContent(fileSystemTree);

        // ── Samples tab ─────────────────────────────────────────────────────
        sampleItems = FXCollections.observableArrayList();
        filteredSampleItems = new FilteredList<>(sampleItems, item -> true);
        samplesListView = new ListView<>(filteredSampleItems);
        samplesListView.setPlaceholder(new Label("No samples found"));
        samplesListView.getStyleClass().add("browser-list");

        // Enable drag-and-drop from the samples list onto the arrangement view
        samplesListView.setOnDragDetected(event -> {
            String selected = samplesListView.getSelectionModel().getSelectedItem();
            if (selected != null && isAudioFile(selected)) {
                Dragboard db = samplesListView.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putString(selected);
                db.setContent(content);
                notifyAdvisorBeginSampleDrag(selected, event.getScreenX(), event.getScreenY());
            }
            event.consume();
        });
        samplesListView.setOnDragDone(this::notifyAdvisorEndDrag);

        Tab samplesTab = new Tab("Samples");
        samplesTab.setClosable(false);
        samplesTab.setGraphic(IconNode.of(DawIcon.WAVEFORM, 12));
        samplesTab.setContent(samplesListView);

        // ── Presets tab ─────────────────────────────────────────────────────
        presetItems = FXCollections.observableArrayList();
        filteredPresetItems = new FilteredList<>(presetItems, item -> true);
        presetsListView = new ListView<>(filteredPresetItems);
        presetsListView.setPlaceholder(new Label("No presets found"));
        presetsListView.getStyleClass().add("browser-list");

        Tab presetsTab = new Tab("Presets");
        presetsTab.setClosable(false);
        presetsTab.setGraphic(IconNode.of(DawIcon.KNOB, 12));
        presetsTab.setContent(presetsListView);

        // ── Project Files tab ───────────────────────────────────────────────
        projectFileItems = FXCollections.observableArrayList();
        filteredProjectFileItems = new FilteredList<>(projectFileItems, item -> true);
        projectFilesListView = new ListView<>(filteredProjectFileItems);
        projectFilesListView.setPlaceholder(new Label("No project files"));
        projectFilesListView.getStyleClass().add("browser-list");

        Tab projectFilesTab = new Tab("Project");
        projectFilesTab.setClosable(false);
        projectFilesTab.setGraphic(IconNode.of(DawIcon.ALBUM, 12));
        projectFilesTab.setContent(projectFilesListView);

        // ── Tab pane ────────────────────────────────────────────────────────
        tabPane = new TabPane(fileSystemTab, samplesTab, presetsTab, projectFilesTab);
        tabPane.getStyleClass().add("browser-tab-pane");
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        // ── Preview controls ────────────────────────────────────────────────
        previewPlayButton = new Button();
        previewPlayButton.setGraphic(IconNode.of(DawIcon.PLAY, PREVIEW_ICON_SIZE));
        previewPlayButton.getStyleClass().add("browser-preview-button");
        previewPlayButton.setTooltip(new Tooltip("Play selected sample"));

        previewStopButton = new Button();
        previewStopButton.setGraphic(IconNode.of(DawIcon.STOP, PREVIEW_ICON_SIZE));
        previewStopButton.getStyleClass().add("browser-preview-button");
        previewStopButton.setTooltip(new Tooltip("Stop preview"));

        previewVolumeSlider = new Slider(0.0, 1.0, 1.0);
        previewVolumeSlider.setPrefWidth(80);
        previewVolumeSlider.setMaxWidth(100);
        previewVolumeSlider.getStyleClass().add("browser-volume-slider");
        previewVolumeSlider.setTooltip(new Tooltip("Preview volume"));

        Label volumeIcon = new Label();
        volumeIcon.setGraphic(IconNode.of(DawIcon.VOLUME_UP, 10));

        previewControlBar = new HBox(4);
        previewControlBar.setAlignment(Pos.CENTER_LEFT);
        previewControlBar.setPadding(new Insets(4, 0, 0, 0));
        previewControlBar.getChildren().addAll(previewPlayButton, previewStopButton, volumeIcon, previewVolumeSlider);

        previewMetadataLabel = new Label("");
        previewMetadataLabel.getStyleClass().add("browser-metadata-label");
        previewMetadataLabel.setMaxWidth(Double.MAX_VALUE);

        // ── Wire search filter ──────────────────────────────────────────────
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            applyFilter(newValue);
        });

        getChildren().addAll(headerLabel, searchBar, tabPane, previewControlBar, previewMetadataLabel);

        LOG.fine("Browser panel created");
    }

    /**
     * Returns the search/filter text field.
     */
    public TextField getSearchField() {
        return searchField;
    }

    /**
     * Returns the tab pane containing the browser sections.
     */
    public TabPane getTabPane() {
        return tabPane;
    }

    /**
     * Returns the file system tree view.
     */
    public TreeView<String> getFileSystemTree() {
        return fileSystemTree;
    }

    /**
     * Returns the samples list view.
     */
    public ListView<String> getSamplesListView() {
        return samplesListView;
    }

    /**
     * Returns the presets list view.
     */
    public ListView<String> getPresetsListView() {
        return presetsListView;
    }

    /**
     * Returns the project files list view.
     */
    public ListView<String> getProjectFilesListView() {
        return projectFilesListView;
    }

    /**
     * Returns the play button for sample preview.
     */
    public Button getPreviewPlayButton() {
        return previewPlayButton;
    }

    /**
     * Returns the stop button for sample preview.
     */
    public Button getPreviewStopButton() {
        return previewStopButton;
    }

    /**
     * Returns the volume slider for sample preview.
     */
    public Slider getPreviewVolumeSlider() {
        return previewVolumeSlider;
    }

    /**
     * Returns the label displaying metadata for the selected sample.
     */
    public Label getPreviewMetadataLabel() {
        return previewMetadataLabel;
    }

    /**
     * Returns the preview control bar container.
     */
    public HBox getPreviewControlBar() {
        return previewControlBar;
    }

    /**
     * Adds sample file paths to the samples list.
     *
     * @param paths the file paths to add
     */
    public void addSamples(List<String> paths) {
        sampleItems.addAll(paths);
    }

    /**
     * Adds preset names to the presets list.
     *
     * @param names the preset names to add
     */
    public void addPresets(List<String> names) {
        presetItems.addAll(names);
    }

    /**
     * Adds project file paths to the project files list.
     *
     * @param paths the file paths to add
     */
    public void addProjectFiles(List<String> paths) {
        projectFileItems.addAll(paths);
    }

    /**
     * Clears all items in the samples list.
     */
    public void clearSamples() {
        sampleItems.clear();
    }

    /**
     * Clears all items in the presets list.
     */
    public void clearPresets() {
        presetItems.clear();
    }

    /**
     * Clears all items in the project files list.
     */
    public void clearProjectFiles() {
        projectFileItems.clear();
    }

    /**
     * Returns whether the given file name has a supported audio file extension.
     *
     * @param fileName the file name to check
     * @return {@code true} if the file has a supported audio extension
     */
    public static boolean isAudioFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String lower = fileName.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Installs the shared {@link DragVisualAdvisor}. When set, every
     * sample drag from the file tree or samples list will consult the
     * advisor so the application's drag-feedback layer (ghost preview,
     * drop-zone highlight, snap indicator, modifier-key cursor) renders
     * over the gesture — see user story 197.
     *
     * @param advisor the shared advisor, or {@code null} to disable
     */
    public void setDragVisualAdvisor(DragVisualAdvisor advisor) {
        this.dragVisualAdvisor = advisor;
    }

    /** Returns the advisor, primarily for tests. */
    DragVisualAdvisor getDragVisualAdvisor() {
        return dragVisualAdvisor;
    }

    /**
     * Begins a SAMPLE-kind drag on the shared advisor (no-op if no
     * advisor is installed or a drag is already in progress on the
     * advisor — the latter can happen if the previous gesture's
     * drag-done event was lost).
     */
    private void notifyAdvisorBeginSampleDrag(String label, double screenX, double screenY) {
        if (dragVisualAdvisor == null) {
            return;
        }
        if (dragVisualAdvisor.state() != DragVisualAdvisor.State.IDLE) {
            return;
        }
        try {
            dragVisualAdvisor.beginDrag(
                    DragSourceKind.SAMPLE,
                    label == null || label.isEmpty() ? "sample" : label,
                    screenX, screenY,
                    /* ghostWidth */ 80, /* ghostHeight */ 24);
        } catch (RuntimeException ignored) {
            // Advisor must never break the underlying JavaFX drag.
        }
    }

    /**
     * Notifies the advisor when JavaFX signals the drag-done event
     * (commit on a successful drop, otherwise cancel).
     */
    private void notifyAdvisorEndDrag(javafx.scene.input.DragEvent event) {
        if (dragVisualAdvisor == null) {
            return;
        }
        try {
            switch (dragVisualAdvisor.state()) {
                case DRAGGING -> {
                    if (event.getTransferMode() != null) {
                        dragVisualAdvisor.commit();
                    } else {
                        dragVisualAdvisor.cancel();
                        // Sample drag has no source-position revert animation
                        // to play (the source list-cell stays where it is);
                        // immediately complete the revert so the advisor
                        // returns to IDLE.
                        dragVisualAdvisor.revertCompleted();
                    }
                }
                case REVERTING -> dragVisualAdvisor.revertCompleted();
                case IDLE -> { /* nothing to do */ }
            }
        } catch (RuntimeException ignored) {
            // Advisor must never break the underlying JavaFX drag.
        }
    }

    /**
     * Returns true if a sample (or any other source) drag is currently
     * in progress on the shared advisor. Helper for tests and
     * presenters that need to know whether to render the overlay layer.
     */
    boolean isAdvisorDragging() {
        return dragVisualAdvisor != null
                && dragVisualAdvisor.state() == DragVisualAdvisor.State.DRAGGING;
    }

    /**
     * Convenience to ask whether the given drop target is valid for a
     * sample drag. Uses the advisor's pure {@code canDropOn} query so
     * no mutable visual state is touched. Returns {@code false} when
     * no advisor is installed or no drag is in progress.
     */
    boolean isOverValidSampleDropTarget(DropTargetKind target) {
        if (dragVisualAdvisor == null
                || dragVisualAdvisor.state() != DragVisualAdvisor.State.DRAGGING) {
            return false;
        }
        return DragVisualAdvisor.canDropOn(DragSourceKind.SAMPLE, target);
    }

    private void applyFilter(String filterText) {
        if (filterText == null || filterText.isBlank()) {
            filteredSampleItems.setPredicate(item -> true);
            filteredPresetItems.setPredicate(item -> true);
            filteredProjectFileItems.setPredicate(item -> true);
        } else {
            String lower = filterText.toLowerCase();
            filteredSampleItems.setPredicate(item -> item.toLowerCase().contains(lower));
            filteredPresetItems.setPredicate(item -> item.toLowerCase().contains(lower));
            filteredProjectFileItems.setPredicate(item -> item.toLowerCase().contains(lower));
        }
    }

    private void addUserHomeDirectories(TreeItem<String> root) {
        Path userHome = Path.of(System.getProperty("user.home"));
        File homeDir = userHome.toFile();
        if (homeDir.exists() && homeDir.isDirectory()) {
            TreeItem<String> homeItem = new TreeItem<>(homeDir.getName());
            homeItem.setExpanded(false);
            File[] children = homeDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isHidden()) {
                        continue;
                    }
                    if (child.isDirectory()) {
                        TreeItem<String> dirItem = new TreeItem<>(child.getName());
                        // Add audio files inside the directory (one level)
                        File[] grandChildren = child.listFiles();
                        if (grandChildren != null) {
                            for (File gc : grandChildren) {
                                if (gc.isFile() && isAudioFile(gc.getName())) {
                                    dirItem.getChildren().add(new TreeItem<>(gc.getName()));
                                }
                            }
                        }
                        homeItem.getChildren().add(dirItem);
                    } else if (child.isFile() && isAudioFile(child.getName())) {
                        homeItem.getChildren().add(new TreeItem<>(child.getName()));
                    }
                }
            }
            root.getChildren().add(homeItem);
        }
    }

    /**
     * Resolves the full file system path for a selected tree item by walking
     * up the tree hierarchy. Returns {@code null} if the path cannot be resolved.
     *
     * <p>The tree structure is:
     * <pre>
     *   File System (root)
     *     └── &lt;homeDirName&gt;        ← represents user.home
     *           ├── subdir/
     *           │     └── file.wav
     *           └── file.wav
     * </pre>
     * The first segment after root is the home directory name, which maps to
     * the parent of user.home + that name (i.e., user.home itself).
     */
    private static String resolveTreeItemPath(TreeItem<String> item) {
        if (item == null) {
            return null;
        }
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        TreeItem<String> current = item;
        while (current != null && current.getParent() != null) {
            segments.push(current.getValue());
            current = current.getParent();
        }
        if (segments.isEmpty()) {
            return null;
        }
        // The first segment is the home directory name; resolve from its parent
        Path userHome = Path.of(System.getProperty("user.home"));
        Path base = userHome.getParent();
        if (base == null) {
            base = userHome;
        }
        Path resolved = base;
        for (String segment : segments) {
            resolved = resolved.resolve(segment);
        }
        return resolved.toString();
    }
}
