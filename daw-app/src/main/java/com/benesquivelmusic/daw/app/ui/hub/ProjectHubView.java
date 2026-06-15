package com.benesquivelmusic.daw.app.ui.hub;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The story-296 §4.2 <strong>Project Hub</strong> — a full-window project
 * browser that replaces the §1.4 "Recent Projects" filename context menu.
 *
 * <p>A one-off application layout, so it subclasses {@link BorderPane} directly
 * rather than the {@code Control + Skin} pattern reserved for reusable widgets
 * ({@code javafx-application-design} skill §3: "for one-off layouts inside an
 * app, prefer a plain {@code Region}/{@code Pane} subclass"). The reusable tile
 * it composes — {@link ProjectCard} — <em>is</em> a {@code Control + Skin}.</p>
 *
 * <h2>Layout (per the §4.2 mockup)</h2>
 *
 * <ul>
 *   <li><b>Top</b> — a toolbar: a Workspace label, a {@link #searchField search}
 *       field that live-filters cards by name, a Grid/List view toggle, and a
 *       {@link #sortChoice sort} control (Recently opened / Name).</li>
 *   <li><b>Center</b> — a {@link ScrollPane} of three sections in order
 *       <b>Pinned</b>, <b>Recent</b>, <b>Archived (.dawz)</b>; each is a
 *       {@code .panel-header} label over a {@code .project-hub-grid}
 *       {@link FlowPane} of cards, and is hidden when it has no cards.</li>
 *   <li><b>Bottom</b> — the §1.4 <b>detail strip</b>: every fact the model
 *       carries about the selected card (path, schema, size-on-disk broken down,
 *       sessions, sample rate, lock) plus {@code [Reveal]} and {@code [Open ▸]}
 *       actions. A muted placeholder when nothing is selected.</li>
 * </ul>
 *
 * <h2>Scanning &amp; threading ({@code javafx-application-design} §11)</h2>
 *
 * <p>Each project-directory card is populated by a background virtual-thread
 * {@link ProjectHealthScanner#scan(Path, Consumer) scan} whose result is
 * marshalled back to the FX thread through the story-289 {@link FxDispatcher};
 * the FX thread never walks a project tree. The scan threads are collected into
 * {@link #pendingScans()} so a test can join them. Archive {@code .dawz} entries
 * are files (not directories): they are populated directly on the FX thread
 * (name = file name, size = file length) without a directory scan.</p>
 *
 * <h2>Detail-strip facts</h2>
 *
 * <p>The strip reads the selected card's <em>current</em> property values
 * (schema, lock, sessions, …) plus the {@link ProjectDiskUsage} breakdown
 * retained per card in {@link #usageByCard} (captured in the scan consumer), so
 * it can show "audio / checkpoints / archives" without re-walking the tree.</p>
 *
 * <h2>Styling</h2>
 *
 * <p>Loads its own {@code project-hub.css} via {@link #getStylesheets()}; it is
 * a {@link Region}, not a {@code Control}, so it has no
 * {@code getUserAgentStylesheet()}. That sheet consumes the role tokens
 * ({@code -surface-1}, {@code -line-soft}, {@code -text}, {@code -text-hi},
 * {@code -text-mute}, {@code -accent}) defined on the scene's {@code .root-pane}
 * — they resolve by CSS inheritance because the Hub is shown in a scene that
 * loads the app {@code styles.css}.</p>
 *
 * @see ProjectCard
 * @see ProjectHealthScanner
 */
public final class ProjectHubView extends BorderPane {

    /** Stable style class — selectable as {@code .project-hub}. */
    public static final String DEFAULT_STYLE_CLASS = "project-hub";

    private static final String SORT_RECENT = "Recently opened";
    private static final String SORT_NAME = "Name";

    private final ProjectHealthScanner scanner;
    private final FxDispatcher dispatcher;
    private final Consumer<Path> onOpen;
    private final Consumer<Path> onReveal;
    private final Consumer<Path> onRestoreArchive;
    private final Runnable onClearRecent;

    /** Display-order model lists (independent of the search filter). */
    private final List<ProjectCard> pinned = new ArrayList<>();
    private final List<ProjectCard> recent = new ArrayList<>();
    private final List<ProjectCard> archived = new ArrayList<>();

    /** Every card → its project (dir) or archive (.dawz) path. */
    private final Map<ProjectCard, Path> pathByCard = new IdentityHashMap<>();
    /** Card → its last scan's disk-usage breakdown (for the detail strip). */
    private final Map<ProjectCard, ProjectDiskUsage> usageByCard = new IdentityHashMap<>();

    /** Every scan thread started at construction, so a test can join them. */
    private final List<Thread> pendingScans = new ArrayList<>();

    // Toolbar controls.
    private final TextField searchField = new TextField();
    private final ToggleButton gridToggle = new ToggleButton("Grid");
    private final ToggleButton listToggle = new ToggleButton("List");
    private final ChoiceBox<String> sortChoice = new ChoiceBox<>();
    private final Button clearRecentButton = new Button("Clear Recent");

    // Section containers (header + grid each; whole VBox hidden when empty).
    private final FlowPane pinnedGrid = newGrid();
    private final FlowPane recentGrid = newGrid();
    private final FlowPane archivedGrid = newGrid();
    private final VBox pinnedSection = newSection("PINNED", pinnedGrid);
    private final VBox recentSection = newSection("RECENT", recentGrid);
    private final VBox archivedSection = newSection("ARCHIVED (.dawz)", archivedGrid);

    // Detail strip.
    private final ProjectDetailStrip detailStrip;

    private ProjectCard selected;

    /**
     * Builds the full Project Hub.
     *
     * @param recentPaths      recent project directories, most-recent-first; {@code null} → empty
     * @param pinnedPaths      pinned project directories; {@code null} → empty
     * @param archivePaths     {@code .dawz} archive files; may be empty / {@code null}
     * @param scanner          the virtual-thread health/size scanner; must not be {@code null}
     * @param dispatcher       the FX marshalling seam; must not be {@code null}
     * @param onOpen           opens a project directory; must not be {@code null}
     * @param onReveal         reveals a directory in the OS file browser; must not be {@code null}
     * @param onRestoreArchive restores a {@code .dawz} archive; must not be {@code null}
     * @param onClearRecent    clears the recent-projects list (the §1.4 "Clear
     *                         Recent" affordance carried over from the old menu);
     *                         must not be {@code null} (use {@code () -> {}} for none)
     */
    public ProjectHubView(
            List<Path> recentPaths,
            List<Path> pinnedPaths,
            List<Path> archivePaths,
            ProjectHealthScanner scanner,
            FxDispatcher dispatcher,
            Consumer<Path> onOpen,
            Consumer<Path> onReveal,
            Consumer<Path> onRestoreArchive,
            Runnable onClearRecent) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen must not be null");
        this.onReveal = Objects.requireNonNull(onReveal, "onReveal must not be null");
        this.onRestoreArchive =
                Objects.requireNonNull(onRestoreArchive, "onRestoreArchive must not be null");
        this.onClearRecent = Objects.requireNonNull(onClearRecent, "onClearRecent must not be null");

        getStyleClass().add(DEFAULT_STYLE_CLASS);

        this.detailStrip = new ProjectDetailStrip();

        setTop(buildToolbar());
        setCenter(buildCenter());
        setBottom(detailStrip);

        // Build cards in the §4.2 order: pinned, then recent, then archives.
        for (Path p : safe(pinnedPaths)) {
            addDirectoryCard(p, pinnedGrid, pinned, true);
        }
        for (Path p : safe(recentPaths)) {
            addDirectoryCard(p, recentGrid, recent, false);
        }
        for (Path p : safe(archivePaths)) {
            addArchiveCard(p);
        }

        applySort();
        reconcileSectionVisibility();
        clearRecentButton.setDisable(recent.isEmpty());
        detailStrip.showPlaceholder();

        getStylesheets().add(Objects.requireNonNull(
                ProjectHubView.class.getResource("project-hub.css"),
                "project-hub.css not on classpath").toExternalForm());
    }

    /**
     * Convenience constructor: no pinned or archived projects, and a no-op
     * archive-restore. Used by the §4.2 "open the Hub" call site (which also
     * wires Clear Recent) and tests.
     *
     * @param recentPaths recent project directories, most-recent-first; {@code null} → empty
     * @param scanner     the scanner; must not be {@code null}
     * @param dispatcher  the FX dispatcher; must not be {@code null}
     * @param onOpen      opens a project directory; must not be {@code null}
     * @param onReveal    reveals a directory in the OS file browser; must not be {@code null}
     * @param onClearRecent clears the recent-projects list; must not be {@code null}
     */
    public ProjectHubView(
            List<Path> recentPaths,
            ProjectHealthScanner scanner,
            FxDispatcher dispatcher,
            Consumer<Path> onOpen,
            Consumer<Path> onReveal,
            Runnable onClearRecent) {
        this(recentPaths, List.of(), List.of(), scanner, dispatcher,
                onOpen, onReveal, p -> { }, onClearRecent);
    }

    /**
     * Convenience constructor with no Clear-Recent action (a no-op). Used by the
     * {@code RecentProjectsStoreFeedsHubTest} acceptance test.
     *
     * @param recentPaths recent project directories, most-recent-first; {@code null} → empty
     * @param scanner     the scanner; must not be {@code null}
     * @param dispatcher  the FX dispatcher; must not be {@code null}
     * @param onOpen      opens a project directory; must not be {@code null}
     * @param onReveal    reveals a directory in the OS file browser; must not be {@code null}
     */
    public ProjectHubView(
            List<Path> recentPaths,
            ProjectHealthScanner scanner,
            FxDispatcher dispatcher,
            Consumer<Path> onOpen,
            Consumer<Path> onReveal) {
        this(recentPaths, scanner, dispatcher, onOpen, onReveal, () -> { });
    }

    // ── toolbar ──────────────────────────────────────────────────────────────

    private Node buildToolbar() {
        Label workspace = new Label("Workspace: Personal");
        workspace.getStyleClass().add("project-hub-workspace");

        searchField.setPromptText("Search");
        searchField.getStyleClass().add("project-hub-search");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((_, _, q) -> applySearch(q));

        ToggleGroup viewGroup = new ToggleGroup();
        gridToggle.setToggleGroup(viewGroup);
        listToggle.setToggleGroup(viewGroup);
        gridToggle.setSelected(true);
        gridToggle.getStyleClass().add("project-hub-view-toggle");
        listToggle.getStyleClass().add("project-hub-view-toggle");
        // Keep one always selected (a ToggleGroup otherwise allows none), and
        // relayout the grids when the mode flips.
        viewGroup.selectedToggleProperty().addListener((_, old, now) -> {
            if (now == null) {
                gridToggle.setSelected(old == gridToggle);
            }
            applyViewMode();
        });
        HBox viewToggles = new HBox(gridToggle, listToggle);
        viewToggles.getStyleClass().add("project-hub-view-toggles");

        sortChoice.getItems().setAll(SORT_RECENT, SORT_NAME);
        sortChoice.setValue(SORT_RECENT);
        sortChoice.getStyleClass().add("project-hub-sort");
        sortChoice.valueProperty().addListener((_, _, _) -> applySort());

        // §1.4 — the "Clear Recent" affordance the old recent-projects menu had,
        // carried onto the Hub toolbar. Disabled when there is nothing to clear.
        clearRecentButton.getStyleClass().add("project-hub-clear-recent");
        clearRecentButton.setOnAction(_ -> clearRecent());

        HBox toolbar = new HBox(workspace, searchField,
                new Label("View:"), viewToggles,
                new Label("Sort:"), sortChoice,
                clearRecentButton);
        toolbar.getStyleClass().add("project-hub-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    private Node buildCenter() {
        VBox sections = new VBox(pinnedSection, recentSection, archivedSection);
        sections.getStyleClass().add("project-hub-sections");

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("project-hub-scroll");
        return scroll;
    }

    // ── card construction ──────────────────────────────────────────────────────

    private void addDirectoryCard(Path dir, FlowPane grid, List<ProjectCard> model, boolean pin) {
        ProjectCard card = new ProjectCard();
        card.setPinned(pin);
        // Seed a name immediately (the dir name) so the card reads sensibly
        // before the async scan lands and so name-sort is deterministic at once.
        card.setName(HubFormat.baseName(dir));
        wireSelection(card);
        card.setOnMouseClicked(e -> {
            select(card);
            if (e.getClickCount() >= 2) {
                onOpen.accept(dir);
            }
        });

        model.add(card);
        grid.getChildren().add(card);
        pathByCard.put(card, dir);

        // Scan on a virtual thread; retain the disk-usage breakdown for the
        // detail strip, apply onto the card, and refresh the strip if selected.
        Thread t = scanner.scan(dir, r -> {
            usageByCard.put(card, r.diskUsage());
            card.applyScanResult(r);
            // The scan replaced the seeded directory name with the metadata name,
            // so re-evaluate this card against the live search query and re-order
            // its section under a "Name" sort (otherwise the card would sit in the
            // wrong alphabetical slot / be wrongly shown or hidden).
            applySearchToCard(card);
            sortGrid(model, grid, sortByName());
            if (card.isSelected()) {
                detailStrip.show(card);
            }
        });
        pendingScans.add(t);
    }

    private void addArchiveCard(Path archive) {
        ProjectCard card = new ProjectCard();
        card.setName(HubFormat.baseName(archive));
        // Archives are .dawz files, not directories — populate name + size
        // directly on the FX thread; no tree scan, no health classification.
        card.setSizeOnDiskBytes(fileSizeOf(archive));
        wireSelection(card);
        card.setOnMouseClicked(e -> {
            select(card);
            if (e.getClickCount() >= 2) {
                onRestoreArchive.accept(archive);
            }
        });

        archived.add(card);
        archivedGrid.getChildren().add(card);
        pathByCard.put(card, archive);
    }

    private void wireSelection(ProjectCard card) {
        card.setFocusTraversable(true);
    }

    private void select(ProjectCard card) {
        for (ProjectCard other : pathByCard.keySet()) {
            if (other != card) {
                other.setSelected(false);
            }
        }
        card.setSelected(true);
        selected = card;
        detailStrip.show(card);
    }

    // ── search / sort / view-mode ──────────────────────────────────────────────

    private void applySearch(String query) {
        String needle = normalise(query);
        for (ProjectCard card : pathByCard.keySet()) {
            setCardVisible(card, nameMatches(card, needle));
        }
        dropSelectionIfHidden();
    }

    /**
     * Re-evaluates a single card's visibility against the <em>live</em> search
     * query — called after an async scan lands a card's real name (the bulk
     * {@link #applySearch} ran earlier against the seeded directory name).
     */
    private void applySearchToCard(ProjectCard card) {
        setCardVisible(card, nameMatches(card, currentNeedle()));
        dropSelectionIfHidden();
    }

    /** The live, normalised search needle from the search field. */
    private String currentNeedle() {
        return normalise(searchField.getText());
    }

    private static String normalise(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean nameMatches(ProjectCard card, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        String name = card.getName();
        return name != null && name.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void setCardVisible(ProjectCard card, boolean visible) {
        card.setVisible(visible);
        card.setManaged(visible);
    }

    /**
     * Clears the selection (and the detail strip) when the selected card has been
     * filtered out, so its Reveal / Open ▸ actions never act on a card the user
     * can no longer see.
     */
    private void dropSelectionIfHidden() {
        if (selected != null && !selected.isVisible()) {
            selected.setSelected(false);
            selected = null;
            detailStrip.showPlaceholder();
        }
    }

    private void applySort() {
        boolean byName = sortByName();
        sortGrid(recent, recentGrid, byName);
        sortGrid(pinned, pinnedGrid, byName);
        sortGrid(archived, archivedGrid, byName);
    }

    private boolean sortByName() {
        return SORT_NAME.equals(sortChoice.getValue());
    }

    /**
     * Clears the recent-projects list: runs the injected {@link #onClearRecent}
     * (the store mutation), then empties the Recent section in place so the open
     * Hub reflects the cleared list immediately.
     */
    private void clearRecent() {
        onClearRecent.run();
        if (selected != null && recent.contains(selected)) {
            selected.setSelected(false);
            selected = null;
            detailStrip.showPlaceholder();
        }
        for (ProjectCard card : recent) {
            pathByCard.remove(card);
            usageByCard.remove(card);
        }
        recent.clear();
        recentGrid.getChildren().clear();
        reconcileSectionVisibility();
        clearRecentButton.setDisable(true);
    }

    /**
     * Reorders a grid's children. "Recently opened" keeps the model's insertion
     * order (the given most-recent-first order); "Name" sorts by card name. The
     * model list itself is left in its original order so {@link #recentCards()}
     * and friends always report the canonical given order.
     */
    private void sortGrid(List<ProjectCard> model, FlowPane grid, boolean byName) {
        List<ProjectCard> ordered = new ArrayList<>(model);
        if (byName) {
            ordered.sort(Comparator.comparing(
                    c -> c.getName() == null ? "" : c.getName(),
                    String.CASE_INSENSITIVE_ORDER));
        }
        grid.getChildren().setAll(ordered);
    }

    private void applyViewMode() {
        boolean list = listToggle.isSelected();
        // List view = one card per row; Grid view = the cards' natural tile width,
        // wrapped across the viewport. The centre ScrollPane is fitToWidth, so the
        // FlowPane is stretched to the viewport and wraps at that width regardless
        // of prefWrapLength — a single column therefore can't be expressed through
        // the wrap length. Instead the list column is forced by binding each card's
        // preferred width to the grid width (one card fills a row, the next wraps),
        // and released back to the computed tile width for the grid.
        for (FlowPane grid : List.of(pinnedGrid, recentGrid, archivedGrid)) {
            for (Node child : grid.getChildren()) {
                if (child instanceof ProjectCard card) {
                    card.prefWidthProperty().unbind();
                    if (list) {
                        card.prefWidthProperty().bind(grid.widthProperty());
                    } else {
                        card.setPrefWidth(Region.USE_COMPUTED_SIZE);
                    }
                }
            }
        }
    }

    private void reconcileSectionVisibility() {
        setSectionVisible(pinnedSection, !pinned.isEmpty());
        setSectionVisible(recentSection, !recent.isEmpty());
        setSectionVisible(archivedSection, !archived.isEmpty());
    }

    private static void setSectionVisible(Node section, boolean on) {
        section.setVisible(on);
        section.setManaged(on);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FlowPane newGrid() {
        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("project-hub-grid");
        return grid;
    }

    private static VBox newSection(String headerText, FlowPane grid) {
        Label header = new Label(headerText);
        header.getStyleClass().add("panel-header");
        VBox section = new VBox(header, grid);
        section.getStyleClass().add("project-hub-section");
        return section;
    }

    private static List<Path> safe(List<Path> paths) {
        return paths == null ? List.of() : paths;
    }

    private static long fileSizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1L;
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    // ── test / controller accessors ─────────────────────────────────────────────

    /**
     * @return the Recent cards in display order — the full model list,
     *         independent of any active search filter
     */
    public List<ProjectCard> recentCards() {
        return List.copyOf(recent);
    }

    /** @return the Pinned cards in display order (full model list). */
    public List<ProjectCard> pinnedCards() {
        return List.copyOf(pinned);
    }

    /** @return the Archived ({@code .dawz}) cards in display order (full model list). */
    public List<ProjectCard> archivedCards() {
        return List.copyOf(archived);
    }

    /**
     * @return every scan thread started at construction (one per project-directory
     *         card; archives are not scanned), so a test can join on completion
     */
    public List<Thread> pendingScans() {
        return List.copyOf(pendingScans);
    }

    /** @return the currently selected card, or {@code null} when none is selected. */
    public ProjectCard selectedCard() {
        return selected;
    }

    /** @return the project (or archive) path backing {@code card}, or {@code null}. */
    public Path pathFor(ProjectCard card) {
        return pathByCard.get(card);
    }

    /** @return the bottom detail-strip node (the §1.4 fix surface). */
    public Node detailStrip() {
        return detailStrip;
    }

    /** @return the live search field (prompt "Search"); live-filters cards by name. */
    public TextField searchField() {
        return searchField;
    }

    /** @return the sort control ("Recently opened" / "Name"). */
    public ChoiceBox<String> sortChoice() {
        return sortChoice;
    }

    /** @return the Grid view toggle (selected by default; cards wrap across the viewport). */
    public ToggleButton gridToggle() {
        return gridToggle;
    }

    /** @return the List view toggle (selecting it lays the cards out one per row). */
    public ToggleButton listToggle() {
        return listToggle;
    }

    /** @return the toolbar "Clear Recent" button (disabled when nothing is recent). */
    public Button clearRecentButton() {
        return clearRecentButton;
    }

    /**
     * The §1.4 detail strip: the selected project's facts plus Reveal / Open.
     * A nested {@link Region} so the Hub can keep its label fields private while
     * still exposing the strip node via {@link ProjectHubView#detailStrip()}.
     */
    private final class ProjectDetailStrip extends VBox {

        private final Label heading = new Label();
        private final Label pathValue = newValue();
        private final Label schemaValue = newValue();
        private final Label sizeValue = newValue();
        private final Label sessionsValue = newValue();
        private final Label sampleRateValue = newValue();
        private final Label lockValue = newValue();
        private final Button revealButton = new Button("Reveal");
        private final Button openButton = new Button("Open ▸");
        private final Label placeholder = new Label("Select a project");

        ProjectDetailStrip() {
            getStyleClass().add("project-hub-detail");

            heading.getStyleClass().add("project-hub-detail-heading");
            placeholder.getStyleClass().add("project-hub-detail-placeholder");

            revealButton.getStyleClass().add("project-hub-detail-action");
            openButton.getStyleClass().add("project-hub-detail-action");
            revealButton.setOnAction(_ -> fireAction(onReveal));
            openButton.setOnAction(_ -> fireAction(onOpen));

            HBox actions = new HBox(revealButton, openButton);
            actions.getStyleClass().add("project-hub-detail-actions");
            actions.setAlignment(Pos.CENTER_LEFT);

            HBox headerRow = new HBox(heading, spacer(), actions);
            headerRow.getStyleClass().add("project-hub-detail-header");
            headerRow.setAlignment(Pos.CENTER_LEFT);

            getChildren().setAll(
                    headerRow,
                    row("Path:", pathValue),
                    row("Schema:", schemaValue),
                    row("Size on disk:", sizeValue),
                    row("Sessions:", sessionsValue),
                    row("Sample rate:", sampleRateValue),
                    row("Lock:", lockValue),
                    placeholder);
        }

        private void fireAction(Consumer<Path> action) {
            if (selected == null) {
                return;
            }
            Path path = pathByCard.get(selected);
            if (path != null) {
                action.accept(path);
            }
        }

        void showPlaceholder() {
            heading.setText("Selected: —");
            setRowsVisible(false);
            placeholder.setVisible(true);
            placeholder.setManaged(true);
            revealButton.setDisable(true);
            openButton.setDisable(true);
        }

        void show(ProjectCard card) {
            Path dir = pathByCard.get(card);
            heading.setText("Selected: " + safeName(card));
            pathValue.setText(dir == null ? "—" : dir.toString());
            schemaValue.setText(formatSchema(card));
            sizeValue.setText(formatSize(card));
            sessionsValue.setText(formatSessions(card));
            sampleRateValue.setText("—"); // Stage 2 — no sample-rate fact yet.
            lockValue.setText(formatLock(card));

            setRowsVisible(true);
            placeholder.setVisible(false);
            placeholder.setManaged(false);
            revealButton.setDisable(false);
            openButton.setDisable(false);
        }

        private void setRowsVisible(boolean on) {
            for (Node child : getChildren()) {
                if (child instanceof DetailRow) {
                    child.setVisible(on);
                    child.setManaged(on);
                }
            }
        }

        private String formatSchema(ProjectCard card) {
            int schema = card.getSchemaVersion();
            if (schema < 0) {
                return "—";
            }
            String base = "v" + schema + " (current)";
            int backup = card.getBackupSchemaVersion();
            return backup >= 0 ? base + " · backup v" + backup + " available" : base;
        }

        private String formatSize(ProjectCard card) {
            long total = card.getSizeOnDiskBytes();
            String headline = HubFormat.formatBytes(total);
            ProjectDiskUsage usage = usageByCard.get(card);
            if (usage == null) {
                return headline;
            }
            // Stage 2 has no journal / snapshots — show only the categories
            // ProjectDiskUsage actually breaks out (audio / checkpoints / archives).
            return headline
                    + "   audio " + HubFormat.formatBytes(usage.assetsBytes())
                    + " · checkpoints " + HubFormat.formatBytes(usage.autosavesBytes())
                    + " · archives " + HubFormat.formatBytes(usage.archivesBytes());
        }

        private String formatSessions(ProjectCard card) {
            int count = card.getSessionCount();
            return count < 0 ? "—" : Integer.toString(count);
        }

        private String formatLock(ProjectCard card) {
            String holder = card.getLockHolderLabel();
            if (holder == null || holder.isBlank()) {
                return "—";
            }
            return holder + " · " + (card.isLockStale() ? "stale" : "live");
        }

        private String safeName(ProjectCard card) {
            String name = card.getName();
            return name == null || name.isBlank() ? "—" : name;
        }
    }

    private static Label newValue() {
        Label label = new Label("—");
        label.getStyleClass().add("project-hub-detail-value");
        return label;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static DetailRow row(String key, Label value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("project-hub-detail-key");
        return new DetailRow(keyLabel, value);
    }

    /** A {@code key: value} line in the detail strip — typed so the strip can
     *  toggle every fact row's visibility without touching the header / actions. */
    private static final class DetailRow extends HBox {
        DetailRow(Label key, Label value) {
            super(key, value);
            getStyleClass().add("project-hub-detail-row");
            setAlignment(Pos.CENTER_LEFT);
        }
    }
}
