package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.drag.DragSourceKind;
import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;
import com.benesquivelmusic.daw.app.ui.drag.DropTargetKind;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A resizable side panel for browsing audio samples, presets, project files,
 * and navigating the local file system (UI Design Book §5.5, §7.1, §7.3,
 * story 275).
 *
 * <p>The panel is a hand-built tab strip ({@link HBox} of unified
 * {@code .dawg-button} toggles, story 264) over a content
 * {@link StackPane} — <em>not</em> a {@link TabPane}. The active tab
 * carries an {@code -accent} 2 px under-text bar drawn as a child
 * {@link Rectangle} ({@code .browser-tab-indicator}), the same
 * "drawn bar, not a CSS border" discipline as {@link NotificationPill}
 * (§7.3 — no border swap, no layout shift).</p>
 *
 * <p>The four sections are:</p>
 * <ul>
 *   <li><b>Files</b> — local file-system tree filtered to audio types</li>
 *   <li><b>Samples</b> — sample library with per-row audition</li>
 *   <li><b>Presets</b> — plugin preset names (not audio — no audition)</li>
 *   <li><b>Project</b> — recently used audio files</li>
 * </ul>
 *
 * <p>A persistent search field sits below the tab strip; its text is
 * preserved across tab switches and is re-applied to the newly active
 * section's list. {@code Ctrl+F} while the panel is focused moves focus
 * into the search field.</p>
 *
 * <p>Every list row (Samples / Presets / Project) carries an audition
 * button when the row is an audio file. Clicking it drives the injected
 * {@link SampleAuditioner}; the icon swaps to a pause-circle while that
 * row is playing. With no auditioner installed the button is
 * {@code :disabled} (the unified {@code .dawg-button:disabled} rule).</p>
 */
public final class BrowserPanel extends VBox {

    private static final Logger LOG = Logger.getLogger(BrowserPanel.class.getName());

    /** Resource bundle for chrome strings (Skill §14) — Locale.ROOT. */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    /** Supported audio file extensions for filtering. */
    static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".wav", ".flac", ".mp3", ".aiff", ".ogg"
    );

    private static final double DEFAULT_WIDTH = 250.0;
    private static final double MIN_WIDTH = 180.0;
    private static final double ICON_SIZE = 14.0;
    private static final double TAB_ICON_SIZE = 12.0;
    private static final double AUDITION_ICON_SIZE = 16.0;
    private static final double INDICATOR_HEIGHT = 2.0;

    /**
     * The four browser sections. Replaces the former {@code TabPane} +
     * {@code Tab} model (story 275). Order is the tab-strip order.
     */
    public enum BrowserSection {
        /** Local file-system tree. */
        FILES,
        /** Sample library. */
        SAMPLES,
        /** Plugin preset names. */
        PRESETS,
        /** Recently used project audio files. */
        PROJECT
    }

    private final TextField searchField;

    private final HBox tabStrip;
    private final StackPane contentArea;
    private final Map<BrowserSection, Button> tabButtons = new EnumMap<>(BrowserSection.class);
    private final Map<BrowserSection, Rectangle> tabIndicators = new EnumMap<>(BrowserSection.class);
    private final Map<BrowserSection, Node> sectionContent = new EnumMap<>(BrowserSection.class);

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

    private BrowserSection activeSection = BrowserSection.FILES;

    /**
     * The injected single-channel auditioner (story 275). When
     * {@code null} every row's audition button is {@code :disabled}.
     */
    private SampleAuditioner sampleAuditioner;

    /**
     * The path currently being auditioned, or {@code null}. Used so only
     * the playing row shows the pause-circle glyph; the engine itself is
     * single-channel so at most one path is ever active.
     */
    private volatile String auditioningItem;

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

        // ── Persistent search field (story 275, UI Design Book §5.5) ────────
        // SEARCH icon (story 265) as a leading affordance; the text is
        // preserved across tab switches and re-applied on selectSection.
        searchField = new TextField();
        searchField.setPromptText(msg("browser.search.placeholder"));
        searchField.getStyleClass().add("search-field");
        HBox searchBar = new HBox(4);
        searchBar.getStyleClass().add("browser-search-bar");
        searchBar.setAlignment(Pos.CENTER_LEFT);
        Label searchIcon = new Label();
        searchIcon.setGraphic(IconNode.of(DawIcon.SEARCH, TAB_ICON_SIZE));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchBar.getChildren().addAll(searchIcon, searchField);

        // ── Files section — file-system tree ────────────────────────────────
        TreeItem<String> rootItem = new TreeItem<>("File System");
        rootItem.setExpanded(true);
        addUserHomeDirectories(rootItem);
        fileSystemTree = new TreeView<>(rootItem);
        fileSystemTree.setShowRoot(true);
        fileSystemTree.getStyleClass().add("browser-tree");
        VBox.setVgrow(fileSystemTree, Priority.ALWAYS);

        // Enable drag-and-drop from the file tree onto the arrangement view
        // (story 197 — preserved through the story-275 refactor).
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

        // ── Samples section ─────────────────────────────────────────────────
        sampleItems = FXCollections.observableArrayList();
        filteredSampleItems = new FilteredList<>(sampleItems, item -> true);
        samplesListView = new ListView<>(filteredSampleItems);
        samplesListView.setPlaceholder(new Label("No samples found"));
        samplesListView.getStyleClass().add("browser-list");
        samplesListView.setCellFactory(lv -> new BrowserRowCell());

        // Enable drag-and-drop from the samples list onto the arrangement view
        // (story 197 — preserved through the story-275 refactor).
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

        // ── Presets section ─────────────────────────────────────────────────
        presetItems = FXCollections.observableArrayList();
        filteredPresetItems = new FilteredList<>(presetItems, item -> true);
        presetsListView = new ListView<>(filteredPresetItems);
        presetsListView.setPlaceholder(new Label("No presets found"));
        presetsListView.getStyleClass().add("browser-list");
        presetsListView.setCellFactory(lv -> new BrowserRowCell());

        // ── Project Files section ───────────────────────────────────────────
        projectFileItems = FXCollections.observableArrayList();
        filteredProjectFileItems = new FilteredList<>(projectFileItems, item -> true);
        projectFilesListView = new ListView<>(filteredProjectFileItems);
        projectFilesListView.setPlaceholder(new Label("No project files"));
        projectFilesListView.getStyleClass().add("browser-list");
        projectFilesListView.setCellFactory(lv -> new BrowserRowCell());

        sectionContent.put(BrowserSection.FILES, fileSystemTree);
        sectionContent.put(BrowserSection.SAMPLES, samplesListView);
        sectionContent.put(BrowserSection.PRESETS, presetsListView);
        sectionContent.put(BrowserSection.PROJECT, projectFilesListView);

        // ── Hand-built tab strip (UI Design Book §2.5, §5.5, §7.3) ──────────
        tabStrip = new HBox(4);
        tabStrip.getStyleClass().add("browser-tab-strip");
        tabStrip.setAlignment(Pos.CENTER_LEFT);
        tabStrip.getChildren().addAll(
                buildTab(BrowserSection.FILES, "browser.tab.files", DawIcon.FOLDER),
                buildTab(BrowserSection.SAMPLES, "browser.tab.samples", DawIcon.WAVEFORM),
                buildTab(BrowserSection.PRESETS, "browser.tab.presets", DawIcon.KNOB),
                buildTab(BrowserSection.PROJECT, "browser.tab.project", DawIcon.ALBUM));

        contentArea = new StackPane();
        contentArea.getStyleClass().add("browser-content-area");
        contentArea.getChildren().addAll(
                fileSystemTree, samplesListView, presetsListView, projectFilesListView);
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        // ── Wire search filter ──────────────────────────────────────────────
        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyFilter(newValue));

        // ── Ctrl+F focuses the search field while the panel is focused ──────
        addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (KeyCombination.keyCombination("Shortcut+F").match(event)
                    || (event.isControlDown() && event.getCode() == KeyCode.F)) {
                searchField.requestFocus();
                event.consume();
            }
        });

        getChildren().addAll(headerLabel, searchBar, tabStrip, contentArea);

        selectSection(BrowserSection.FILES);

        LOG.fine("Browser panel created");
    }

    /**
     * Builds one tab: a {@code .dawg-button.size-default} (story 264, no
     * border) wrapped in a {@link StackPane} container that also holds
     * the under-text accent {@link Rectangle}. The Rectangle is
     * {@code managed=false} so it never perturbs layout (§7.3); its fill
     * is CSS-driven via {@code .browser-tab-indicator}.
     */
    private StackPane buildTab(BrowserSection section, String labelKey, DawIcon icon) {
        Button button = new Button(msg(labelKey));
        button.getStyleClass().addAll("dawg-button", "size-default", "browser-tab");
        button.setGraphic(IconNode.of(icon, TAB_ICON_SIZE));
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> selectSection(section));

        Rectangle indicator = new Rectangle(0, INDICATOR_HEIGHT);
        indicator.getStyleClass().add("browser-tab-indicator");
        indicator.setManaged(false);
        indicator.setMouseTransparent(true);
        indicator.setVisible(false);

        StackPane container = new StackPane(button, indicator);
        container.getStyleClass().add("browser-tab-container");
        StackPane.setAlignment(indicator, Pos.BOTTOM_CENTER);

        tabButtons.put(section, button);
        tabIndicators.put(section, indicator);
        return container;
    }

    /**
     * Returns the persistent search/filter text field.
     */
    public TextField getSearchField() {
        return searchField;
    }

    /**
     * @return the currently active browser section
     */
    public BrowserSection getActiveSection() {
        return activeSection;
    }

    /**
     * Selects {@code section}: shows its content, marks its tab active
     * (its accent indicator becomes visible, all others hidden), and
     * re-applies the retained search query to the newly active list so
     * the search text is preserved across tab switches (story 275).
     *
     * @param section the section to activate
     */
    public void selectSection(BrowserSection section) {
        this.activeSection = section;
        for (BrowserSection s : BrowserSection.values()) {
            boolean active = s == section;
            Node content = sectionContent.get(s);
            content.setVisible(active);
            content.setManaged(active);
            Button tab = tabButtons.get(s);
            if (active) {
                if (!tab.getStyleClass().contains("active")) {
                    tab.getStyleClass().add("active");
                }
            } else {
                tab.getStyleClass().remove("active");
            }
            tabIndicators.get(s).setVisible(active);
        }
        updateIndicatorWidth(section);
        // Re-apply the retained query to the now-active section's list.
        applyFilter(searchField.getText());
    }

    /**
     * Sizes the active tab's accent indicator to the rendered width of
     * the tab's label text (story 275 / §7.3 — an under-text bar, not a
     * full-width or border indicator). Recomputed on every section
     * change and after a layout pass.
     */
    private void updateIndicatorWidth(BrowserSection section) {
        Button tab = tabButtons.get(section);
        Rectangle indicator = tabIndicators.get(section);
        Runnable measure = () -> {
            double textWidth = measureLabelTextWidth(tab);
            if (textWidth > 0) {
                indicator.setWidth(textWidth);
            }
        };
        measure.run();
        // The button may not be laid out yet on first selection; remeasure
        // once a layout pass has produced a real text width.
        Platform.runLater(measure);
    }

    /**
     * Measures the rendered width of {@code button}'s text using the
     * button's resolved font, so the indicator hugs the glyphs rather
     * than the padded button box (§7.3 under-text bar).
     */
    private static double measureLabelTextWidth(Button button) {
        String text = button.getText();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Text helper = new Text(text);
        if (button.getFont() != null) {
            helper.setFont(button.getFont());
        }
        Bounds bounds = helper.getLayoutBounds();
        return bounds.getWidth();
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
     * Returns the tab {@link Button} for {@code section} — exposed for
     * styling/interaction tests.
     */
    Button getTabButton(BrowserSection section) {
        return tabButtons.get(section);
    }

    /**
     * Returns the accent indicator {@link Rectangle} for {@code section}
     * — exposed for styling tests. Only the active section's indicator
     * is visible.
     */
    Rectangle getTabIndicator(BrowserSection section) {
        return tabIndicators.get(section);
    }

    /**
     * Installs the single-channel {@link SampleAuditioner} that the
     * per-row audition buttons drive (story 275). When {@code null} (the
     * default), every audition button is {@code :disabled}.
     *
     * @param auditioner the auditioner, or {@code null} to disable
     */
    public void setSampleAuditioner(SampleAuditioner auditioner) {
        this.sampleAuditioner = auditioner;
        // Reflect playback-finished into the row icon state. The callback
        // fires on the player's daemon thread, so marshal to the FX
        // thread before touching the scene graph (JavaFX threading rule).
        if (auditioner != null) {
            auditioner.setOnPlaybackFinished(() -> Platform.runLater(() -> {
                auditioningItem = null;
                refreshListCells();
            }));
        }
    }

    /** @return the installed auditioner, primarily for tests. */
    Optional<SampleAuditioner> getSampleAuditioner() {
        return Optional.ofNullable(sampleAuditioner);
    }

    /**
     * Toggles audition for {@code item}: starts it (stopping any
     * previous preview — single-channel) or stops it if it is already
     * the playing row.
     */
    private void toggleAudition(String item) {
        if (sampleAuditioner == null || item == null) {
            return;
        }
        if (item.equals(auditioningItem)) {
            sampleAuditioner.stop();
            auditioningItem = null;
        } else {
            sampleAuditioner.play(Path.of(item));
            auditioningItem = item;
        }
        refreshListCells();
    }

    private boolean isAuditioning(String item) {
        return item != null && item.equals(auditioningItem);
    }

    /** Forces the list cells to re-render so audition glyphs update. */
    private void refreshListCells() {
        samplesListView.refresh();
        presetsListView.refresh();
        projectFilesListView.refresh();
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
        String lower = fileName.toLowerCase(Locale.ROOT);
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

    /**
     * Filters the active section's list against {@code filterText}. The
     * three {@link FilteredList}s each filter their own backing data, so
     * the search text is naturally scoped to whichever list is showing —
     * the presets list filters the presets index, never the samples
     * index (story 275 — search scoped to the active tab). Filtering all
     * three keeps the retained query consistent so a tab switch shows
     * already-filtered results without re-typing.
     */
    private void applyFilter(String filterText) {
        if (filterText == null || filterText.isBlank()) {
            filteredSampleItems.setPredicate(item -> true);
            filteredPresetItems.setPredicate(item -> true);
            filteredProjectFileItems.setPredicate(item -> true);
        } else {
            String lower = filterText.toLowerCase(Locale.ROOT);
            filteredSampleItems.setPredicate(item -> item.toLowerCase(Locale.ROOT).contains(lower));
            filteredPresetItems.setPredicate(item -> item.toLowerCase(Locale.ROOT).contains(lower));
            filteredProjectFileItems.setPredicate(item -> item.toLowerCase(Locale.ROOT).contains(lower));
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

    /**
     * Resolves a chrome string from the shared {@link ResourceBundle},
     * falling back to the raw key if absent (mirrors
     * {@link NotificationPill#msg(String)}).
     */
    static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * List row for the Samples / Presets / Project lists (story 275,
     * UI Design Book §5.5). Lays out the file name, an empty
     * {@code .numeric-caption} metadata cell (story 027 populates it —
     * intentional stub; cell factories stay cheap, no per-row file I/O),
     * and a right-edge 16 px audition button on audio rows.
     *
     * <p>Hover / selection visuals are CSS-driven via
     * {@code .browser-list .list-cell} (§7.1 glow veto, §7.3 border
     * veto) — this class adds no inline effect.</p>
     */
    private final class BrowserRowCell extends ListCell<String> {

        private final Label nameLabel = new Label();
        // Story 027 populates size / rate / bit-depth here. Left present
        // but empty so the row reserves the mono-numeric cell now (no
        // per-row blocking file I/O in the cell factory — perf rule).
        private final Label metadataLabel = new Label();
        private final Button auditionButton = new Button();
        private final HBox layout;

        BrowserRowCell() {
            getStyleClass().add("browser-list-cell");

            nameLabel.getStyleClass().add("browser-row-name");
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            metadataLabel.getStyleClass().add("numeric-caption");

            auditionButton.getStyleClass().addAll(
                    "dawg-button", "icon-only", "size-compact", "browser-audition-button");
            auditionButton.setFocusTraversable(false);
            auditionButton.setOnAction(e -> {
                e.consume();
                toggleAudition(getItem());
            });

            layout = new HBox(6, nameLabel, metadataLabel, auditionButton);
            layout.setAlignment(Pos.CENTER_LEFT);
            setText(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            nameLabel.setText(item);

            boolean audio = isAudioFile(item);
            // Presets are names, not audio — no audition affordance.
            auditionButton.setVisible(audio);
            auditionButton.setManaged(audio);
            // With no auditioner installed the button is :disabled (the
            // unified .dawg-button:disabled rule fades it to 0.45).
            auditionButton.setDisable(sampleAuditioner == null);
            if (audio) {
                boolean playing = isAuditioning(item);
                auditionButton.setGraphic(IconNode.of(
                        playing ? DawIcon.PAUSE_CIRCLE : DawIcon.PLAY,
                        AUDITION_ICON_SIZE));
                auditionButton.setTooltip(new Tooltip(
                        playing ? msg("browser.audition.stop")
                                : msg("browser.audition.play")));
            }
            setGraphic(layout);
        }
    }
}
