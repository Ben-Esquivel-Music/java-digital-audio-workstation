package com.benesquivelmusic.daw.app.ui.hub;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.SessionStatusDiskScanner;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The story-296 §4.1 Welcome screen — shown on launch when no project is open.
 *
 * <p>A one-off application layout, so it subclasses {@link BorderPane} directly
 * rather than the {@code Control + Skin} pattern ({@code javafx-application-design}
 * §3 / §16: "one-off layout inside an app → subclass {@code Region}/{@code VBox}"
 * — the reusable {@link ProjectCard} <em>is</em> a {@code Control}, the screen
 * that arranges them is not).</p>
 *
 * <h2>Three regions, Recover first (§4.1)</h2>
 *
 * <p>The {@linkplain #sectionsBox() centre VBox} stacks, in order:</p>
 * <ol>
 *   <li><b>Recover</b> — projects whose {@code .project.lock} shows the prior
 *       process did not exit cleanly. Each gets a row with a project card and
 *       {@code [Discard recovery]} / {@code [Recover session ▸]} buttons. The
 *       region is hidden and unmanaged when there is nothing to recover.</li>
 *   <li><b>Continue</b> — the remaining recent projects as a grid of cards.</li>
 *   <li><b>Start</b> — four large tiles: New, Open, Restore archive, Import.</li>
 * </ol>
 *
 * <p>The "did not exit cleanly" split is computed <strong>synchronously</strong>
 * at construction via {@link ProjectHealthScanner#isRecoverable(Path, InstantSource)}
 * — a single cheap lock-file read per recent path, not a tree walk — so a card's
 * Recover-vs-Continue placement is deterministic the instant the view is built
 * (the {@code WelcomeRecoverBeforeRecentTest} asserts placement with no
 * dispatcher pump). The Recover-scan reuses the existing {@code ProjectLockManager}
 * staleness APIs and invents no new crash flag (§4.1 note; story goal
 * "Recover-scan reuses existing lock APIs").</p>
 *
 * <h2>Size / health off the FX thread</h2>
 *
 * <p>Each rendered card's name/size/health badge is filled by a background
 * virtual-thread scan kicked off through
 * {@link ProjectHealthScanner#scanInto(Path, ProjectCard)}, whose result crosses
 * to the FX thread only through the story-289 {@link FxDispatcher} — the FX
 * thread never walks a project tree ({@code javafx-application-design} §11). The
 * footer disk line is likewise filled off the FX thread (see
 * {@link #startDiskScan()}).</p>
 *
 * <h2>Stage 2 routing</h2>
 *
 * <p>{@code [Recover session ▸]} routes to {@code onOpenProject}; Stage 2 only
 * surfaces the recoverable candidates and hands them to the open flow. The real
 * recovery dialog and journal replay are <strong>story 298</strong> — this view
 * adds no files to the project directory.</p>
 */
public final class WelcomeView extends BorderPane {

    /** Style class on the root — selectable as {@code .welcome-view}. */
    public static final String ROOT_STYLE_CLASS = "welcome-view";

    private final ProjectHealthScanner scanner;
    private final FxDispatcher dispatcher;
    private final InstantSource clock;

    // ── classified inputs (computed once, synchronously, at construction) ──────
    private final List<Path> recoverPaths = new ArrayList<>();
    private final List<Path> continuePaths = new ArrayList<>();
    private final List<ProjectCard> continueCards = new ArrayList<>();
    private final List<Thread> pendingScans = new ArrayList<>();

    // ── live nodes the tests reach for ─────────────────────────────────────────
    private final VBox sectionsBox = new VBox();
    private final VBox recoverSection = new VBox();
    private final VBox continueSection = new VBox();
    private final Label diskLine = new Label("Free disk: —");

    /**
     * Full constructor.
     *
     * @param recentPaths       the recent project directories, most-recent first;
     *                          {@code null} is treated as empty
     * @param scanner           the background health/size scanner; must not be {@code null}
     * @param dispatcher        the FX marshalling seam; must not be {@code null}
     * @param clock             the clock used to classify a lock as stale (tests
     *                          inject a fixed clock); must not be {@code null}
     * @param onNewProject      invoked by the Start "New project" tile; must not be {@code null}
     * @param onOpenProject     invoked with a project directory to open a recent
     *                          card or route a {@code [Recover session ▸]}; must not be {@code null}
     * @param onOpenFromDisk    invoked by the Start "Open project…" tile; must not be {@code null}
     * @param onRestoreArchive  invoked by the Start "Restore archive…" tile; must not be {@code null}
     * @param onImportDawproject invoked by the Start "Import DAWproject" tile; must not be {@code null}
     */
    public WelcomeView(List<Path> recentPaths,
                       ProjectHealthScanner scanner,
                       FxDispatcher dispatcher,
                       InstantSource clock,
                       Runnable onNewProject,
                       Consumer<Path> onOpenProject,
                       Runnable onOpenFromDisk,
                       Runnable onRestoreArchive,
                       Runnable onImportDawproject) {
        this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(onNewProject, "onNewProject must not be null");
        Objects.requireNonNull(onOpenProject, "onOpenProject must not be null");
        Objects.requireNonNull(onOpenFromDisk, "onOpenFromDisk must not be null");
        Objects.requireNonNull(onRestoreArchive, "onRestoreArchive must not be null");
        Objects.requireNonNull(onImportDawproject, "onImportDawproject must not be null");

        getStyleClass().add(ROOT_STYLE_CLASS);
        getStylesheets().add(Objects.requireNonNull(
                WelcomeView.class.getResource("welcome-view.css"),
                "welcome-view.css not on classpath").toExternalForm());

        classify(recentPaths == null ? List.of() : recentPaths);

        buildRecoverSection(onOpenProject);
        buildContinueSection(onOpenProject);
        VBox startSection = buildStartSection(
                onNewProject, onOpenFromDisk, onRestoreArchive, onImportDawproject);

        sectionsBox.getStyleClass().add("welcome-sections");
        // Order is the contract: Recover ▸ Continue ▸ Start (§4.1, "Recover ahead
        // of recent — the user just lost work").
        sectionsBox.getChildren().addAll(recoverSection, continueSection, startSection);
        setCenter(sectionsBox);

        setBottom(buildFooter());

        startDiskScan();
    }

    /**
     * Convenience constructor using the {@linkplain InstantSource#system() system
     * clock} for the stale-lock classification.
     */
    public WelcomeView(List<Path> recentPaths,
                       ProjectHealthScanner scanner,
                       FxDispatcher dispatcher,
                       Runnable onNewProject,
                       Consumer<Path> onOpenProject,
                       Runnable onOpenFromDisk,
                       Runnable onRestoreArchive,
                       Runnable onImportDawproject) {
        this(recentPaths, scanner, dispatcher, InstantSource.system(),
                onNewProject, onOpenProject, onOpenFromDisk, onRestoreArchive, onImportDawproject);
    }

    // ── classification ─────────────────────────────────────────────────────────

    /**
     * Splits the recent paths into Recover (stale-lock → did not exit cleanly)
     * and Continue, synchronously and in order. A single cheap lock-file read per
     * path keeps placement deterministic for the test.
     */
    private void classify(List<Path> recentPaths) {
        for (Path path : recentPaths) {
            if (path == null) {
                continue;
            }
            if (ProjectHealthScanner.isRecoverable(path, clock)) {
                recoverPaths.add(path);
            } else {
                continuePaths.add(path);
            }
        }
    }

    // ── Recover region ───────────────────────────────────────────────────────

    private void buildRecoverSection(Consumer<Path> onOpenProject) {
        recoverSection.getStyleClass().add("welcome-recover");
        Label header = new Label("RECOVER");
        header.getStyleClass().add("welcome-section-header");
        recoverSection.getChildren().add(header);

        // Only the first (top) recover row carries the default button: JavaFX
        // activates a single default button on Enter, and more than one would both
        // ambiguously style every row as primary and make Enter recover an
        // arbitrary project rather than the top one.
        boolean first = true;
        for (Path path : recoverPaths) {
            recoverSection.getChildren().add(buildRecoverRow(path, onOpenProject, first));
            first = false;
        }

        // Nothing to recover → take the region out of the layout entirely so
        // Continue sits at the top (§4.1: Recover only when there is work lost).
        boolean hasRecoverable = !recoverPaths.isEmpty();
        recoverSection.setVisible(hasRecoverable);
        recoverSection.setManaged(hasRecoverable);
    }

    private HBox buildRecoverRow(Path path, Consumer<Path> onOpenProject, boolean makeDefault) {
        HBox row = new HBox();
        row.getStyleClass().add("welcome-recover-row");
        row.setAlignment(Pos.CENTER_LEFT);

        ProjectCard card = new ProjectCard();
        pendingScans.add(scanner.scanInto(path, card)); // size/health off the FX thread

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox();
        actions.getStyleClass().add("welcome-recover-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button discard = new Button("Discard recovery");
        discard.getStyleClass().add("welcome-recover-discard");
        // Stage-2 stub: the destructive discard-recovery flow is owned by the
        // story-298 recovery dialog; here it is inert so the row reads correctly.
        discard.setDisable(true);

        Button recover = new Button("Recover session ▸");
        recover.getStyleClass().add("welcome-recover-action");
        recover.setDefaultButton(makeDefault);
        // Stage 2 only routes the candidate into the open flow; the real recovery
        // dialog + journal replay is story 298.
        recover.setOnAction(_ -> onOpenProject.accept(path));

        actions.getChildren().addAll(discard, recover);
        row.getChildren().addAll(card, spacer, actions);
        return row;
    }

    // ── Continue region ──────────────────────────────────────────────────────

    private void buildContinueSection(Consumer<Path> onOpenProject) {
        continueSection.getStyleClass().add("welcome-continue");
        Label header = new Label("CONTINUE");
        header.getStyleClass().add("welcome-section-header");

        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("welcome-grid");
        for (Path path : continuePaths) {
            ProjectCard card = new ProjectCard();
            pendingScans.add(scanner.scanInto(path, card)); // size/health off the FX thread
            wireOpen(card, path, onOpenProject); // double-click / Enter / Space reopens it
            continueCards.add(card);
            grid.getChildren().add(card);
        }

        continueSection.getChildren().addAll(header, grid);
    }

    /**
     * Makes a Continue {@link ProjectCard} actually open its project — a primary
     * double-click plus keyboard {@code Enter}/{@code Space} activation, matching
     * the card's {@code AccessibleRole.BUTTON} ({@code javafx-application-design}
     * §14) and the Project Hub's double-click-to-open. Without this the Continue
     * cards render but do nothing, stranding the user on the launch screen instead
     * of letting them return to recent work.
     */
    private static void wireOpen(ProjectCard card, Path path, Consumer<Path> onOpen) {
        card.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() >= 2) {
                onOpen.accept(path);
            }
        });
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                onOpen.accept(path);
                e.consume();
            }
        });
    }

    // ── Start region ───────────────────────────────────────────────────────────

    private VBox buildStartSection(Runnable onNewProject,
                                   Runnable onOpenFromDisk,
                                   Runnable onRestoreArchive,
                                   Runnable onImportDawproject) {
        VBox start = new VBox();
        start.getStyleClass().add("welcome-start");
        Label header = new Label("START");
        header.getStyleClass().add("welcome-section-header");

        FlowPane tiles = new FlowPane();
        tiles.getStyleClass().add("welcome-tiles");
        tiles.getChildren().addAll(
                tile("New project", "Start fresh", onNewProject),
                tile("Open project…", "From any directory", onOpenFromDisk),
                tile("Restore archive…", ".dawz portable", onRestoreArchive),
                tile("Import DAWproject", "Interchange", onImportDawproject));

        start.getChildren().addAll(header, tiles);
        return start;
    }

    private static Button tile(String title, String subtitle, Runnable action) {
        // A two-line tile: bold title over a muted subtitle, stacked in the
        // button's graphic so the whole tile is one focusable, themeable control.
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("welcome-tile-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("welcome-tile-subtitle");

        VBox content = new VBox(titleLabel, subtitleLabel);
        content.getStyleClass().add("welcome-tile-content");
        content.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.getStyleClass().add("tile");
        button.setGraphic(content);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(_ -> action.run());
        return button;
    }

    // ── Footer ───────────────────────────────────────────────────────────────

    private Region buildFooter() {
        HBox footer = new HBox();
        footer.getStyleClass().add("welcome-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        Label workspace = new Label("Workspace: Personal");
        workspace.getStyleClass().add("welcome-workspace");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        diskLine.getStyleClass().add("welcome-disk-line");

        footer.getChildren().addAll(workspace, spacer, diskLine);
        return footer;
    }

    /**
     * Fills the footer disk line off the FX thread. {@code usableSpaceBytes} and
     * the capture estimate are computed on a background virtual thread (story
     * 205); the formatted text crosses back to the FX thread through the
     * dispatcher ({@code javafx-application-design} §11 — the FX thread must
     * never call {@link java.nio.file.FileStore#getUsableSpace()}).
     */
    private void startDiskScan() {
        Path home = Path.of(System.getProperty("user.home", "."));
        Thread t = Thread.ofVirtual().name("daw-welcome-disk-scan").unstarted(() -> {
            long free = ProjectDiskUsage.usableSpaceBytes(home);
            String text = diskLineText(free);
            dispatcher.onFx(() -> diskLine.setText(text));
        });
        t.start();
        pendingScans.add(t);
    }

    /**
     * Builds the §1.8 disk line — {@code "Free disk: 412 GB"} plus, when both the
     * free space and the capture estimate are known, {@code " ≈ N h record"} at
     * {@link AudioFormat#STUDIO_QUALITY}. A negative (unknown) free count yields
     * the bare {@code "Free disk: —"} seed text.
     */
    private static String diskLineText(long free) {
        String base = "Free disk: " + HubFormat.formatBytes(free);
        if (free < 0) {
            return base;
        }
        double minutes = SessionStatusDiskScanner.estimatedRecordMinutes(
                free, AudioFormat.STUDIO_QUALITY);
        if (minutes <= 0) {
            return base;
        }
        long hours = Math.round(minutes / 60.0);
        return base + " ≈ " + hours + " h record";
    }

    // ── test accessors (public — the flat …app.ui test reads these) ────────────

    /** @return the Recover region node (hidden + unmanaged when nothing to recover). */
    public Region recoverSection() {
        return recoverSection;
    }

    /** @return the Continue region node. */
    public Region continueSection() {
        return continueSection;
    }

    /** @return the common parent VBox of the regions (its child order is the §4.1 contract). */
    public Pane sectionsBox() {
        return sectionsBox;
    }

    /** @return the recent paths classified as recoverable (stale lock), in order. */
    public List<Path> recoverPaths() {
        return List.copyOf(recoverPaths);
    }

    /** @return the recent paths classified as Continue (not recoverable), in order. */
    public List<Path> continuePaths() {
        return List.copyOf(continuePaths);
    }

    /**
     * @return the Continue cards in display order; each opens its project on a
     *         primary double-click or keyboard {@code Enter}/{@code Space}
     */
    public List<ProjectCard> continueCards() {
        return List.copyOf(continueCards);
    }

    /** @return the background scan threads started by this view (card scans + the disk scan). */
    public List<Thread> pendingScans() {
        return List.copyOf(pendingScans);
    }
}
