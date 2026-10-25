package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.NamedOperation;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.SaveScope;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell.Severity;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Skin for {@link SessionStatusStrip} (Project Manager Design Book §4.4, story
 * 295). Builds the eight cells, binds each to the single
 * {@link ProjectOperationProgress} model, and performs the §4.4 responsive
 * layout: two rows when wide, collapsing to a single row with a priority
 * overflow popover when narrow.
 *
 * <h2>Binding-only (§8)</h2>
 *
 * <p>Every cell's text is a {@code bind(...)} to a {@code StringBinding} over
 * the model — there is no {@code Label.setText} anywhere in this file. The
 * Disk cell's {@link Severity} (the §6.2 amber/red threshold) and the Saved
 * cell's severity are {@code bind(...)} to the model too. The checkpoint
 * countdown's {@code nextCheckpointInstant} / {@code interval} are bound to the
 * model and its {@code reduceMotion} to the {@code MotionManager} (story 279).</p>
 *
 * <h2>The Saved cell (§4.5, story 331 rulings G4, K1, L1 and L2)</h2>
 *
 * <p>The cell renders the model's head-save and checkpoint facts, and holds
 * no state of its own beyond its flash. Its text is the first of five that
 * holds: a head save still running after 500&nbsp;ms
 * ({@code "◐ Saving project…"}); a standing head failure
 * ({@code "⚠ Save failed · last saved HH:mm"}); a standing checkpoint failure
 * ({@code "⚠ Checkpoint failed · last good HH:mm"}, or
 * {@code "⚠ Checkpoint failed"} alone when no good checkpoint is known); any
 * other running operation ({@code "◐ <name>"}); the last save
 * ({@code "✓ Saved HH:mm:ss"}, or {@code "Not saved yet"}). The checkpoint
 * failure and the other operation also show the last head save, when there
 * is one ({@code " · saved HH:mm:ss"}, ruling L2), so a head save that
 * succeeds while either stands changes the text. A time of another day than
 * the model's {@linkplain ProjectOperationProgress#todayProperty() today}
 * carries its date first ({@code yyyy-MM-dd}); the skin reads no clock — the
 * model's today moves on with the strip's poll (ruling L1). The severity is
 * decided apart from the text: {@link Severity#CRITICAL} while a head failure
 * stands, {@link Severity#WARN} while only a checkpoint failure does,
 * {@link Severity#NORMAL} otherwise — so a retry in progress stays red. A
 * checkpoint never changes the text; it shows in the tooltip, which names
 * each standing failure's cause, the last save and the last good checkpoint
 * ({@code "Last checkpoint HH:mm:ss · <file>"}). The accessible text says the
 * state in words, its severity first; the accessible help is the tooltip.</p>
 *
 * <p>Each head save success — each advance of
 * {@link ProjectOperationProgress#savesSucceededProperty()} — flashes the
 * cell once, whichever text it shows, the amber checkpoint failure included
 * (ruling L2): it carries {@link StatusStripCell#FLASH_PSEUDO_CLASS} for
 * {@link SessionStatusStrip#SAVED_FLASH_DURATION}. Under Reduce Motion there
 * is no flash, as {@code ClockStatusIndicator} drops its pulse, and the new
 * seconds carry the signal; nor while the strip's window is not showing (a
 * hidden window keeps its {@code Scene}, so the window is asked). A
 * checkpoint, a failure and a project replacement never advance that signal,
 * so they never flash. {@link #dispose()} stops the flash.</p>
 *
 * <h2>Priority overflow (§4.4, {@code javafx-application-design} §10)</h2>
 *
 * <p>At {@value SessionStatusStrip#TWO_ROW_MIN_WIDTH}&nbsp;px and wider the
 * strip shows two full rows. Below that it collapses to a single row, hiding
 * collapsible cells in the fixed priority order Workspace → Schema → Lock →
 * Disk → Journal; the Session, Saved and Checkpoint cells never collapse. The
 * hidden cells move into the trailing {@code ⋯} overflow popover. (Stage 1
 * simplification: a collapsed cell is moved whole into the popover rather than
 * rendered icon-only inline as §4.4's mockup ultimately envisions.)</p>
 */
public final class SessionStatusStripSkin extends SkinBase<SessionStatusStrip> {

    // ── Collapse thresholds (px), in priority order. A collapsible cell is
    //    hidden when the strip is narrower than its threshold. ───────────────
    private static final double T_WORKSPACE = SessionStatusStrip.TWO_ROW_MIN_WIDTH; // 1200
    private static final double T_SCHEMA = 1140.0;
    private static final double T_LOCK = 1080.0;
    private static final double T_DISK = 1020.0;
    private static final double T_JOURNAL = 960.0;

    /** Hover-popover show delay (§4.4 / §10). */
    private static final javafx.util.Duration TOOLTIP_DELAY = javafx.util.Duration.millis(350);

    /** A failure's "last saved" / "last good" time on the Saved cell: hour and minute (ruling G4 (b)). */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    /** A head save's time on the Saved cell, and every time in its tooltip: with seconds (ruling G4 (c)). */
    private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    /** The date put before a time of another day than today (ruling K1). */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private static final PseudoClass FLASH = PseudoClass.getPseudoClass(StatusStripCell.FLASH_PSEUDO_CLASS);

    private final ProjectOperationProgress model;

    // ── Cells ─────────────────────────────────────────────────────────────
    private final StatusStripCell sessionCell = new StatusStripCell();
    private final StatusStripCell savedCell = new StatusStripCell();
    private final CheckpointCountdownCanvas checkpointCountdown = new CheckpointCountdownCanvas();
    private final StatusStripCell journalCell = new StatusStripCell();
    private final StatusStripCell diskCell = new StatusStripCell();
    private final StatusStripCell lockCell = new StatusStripCell();
    private final StatusStripCell schemaCell = new StatusStripCell();
    private final StatusStripCell workspaceCell = new StatusStripCell();

    /** The Saved cell's tooltip: each standing failure's cause, the last save and the last good checkpoint. */
    private final Tooltip savedTooltip = new Tooltip();

    /**
     * The Saved cell's one flash for a head save success: the cell carries
     * {@link #FLASH} until this ends, {@link SessionStatusStrip#SAVED_FLASH_DURATION}
     * after it started. Stopped in {@link #dispose()}.
     */
    private final Timeline savedFlash = new Timeline(new KeyFrame(SessionStatusStrip.SAVED_FLASH_DURATION,
            _ -> savedCell.pseudoClassStateChanged(FLASH, false)));

    /**
     * Flashes the Saved cell on each advance of the model's head-save success
     * signal. An invalidation listener fires only when the property goes from
     * valid to invalid; the model reads the count before it sets the next
     * one, so every advance does.
     */
    private final InvalidationListener savedFlashTrigger = _ -> flashSavedCell();

    // ── Layout containers ───────────────────────────────────────────────────
    private final HBox rowTop = new HBox();
    private final HBox rowBottom = new HBox();
    private final VBox rows = new VBox();

    // ── Overflow ──────────────────────────────────────────────────────────
    private final Button overflowButton = new Button("⋯"); // ⋯
    private final VBox overflowContent = new VBox();
    private final Popup overflowPopup = new Popup();

    private final ChangeListener<Number> widthListener;

    /**
     * Builds the cells, binds them to the model, and installs the responsive
     * layout listener.
     *
     * @param strip the owning {@link SessionStatusStrip}
     */
    public SessionStatusStripSkin(SessionStatusStrip strip) {
        super(strip);
        this.model = strip.progress();

        rowTop.getStyleClass().add("status-strip-row");
        rowBottom.getStyleClass().add("status-strip-row");
        rows.getStyleClass().add("status-strip-rows");
        overflowButton.getStyleClass().add("status-strip-overflow");
        overflowButton.setFocusTraversable(false);
        overflowContent.getStyleClass().add("status-strip-overflow-popover");
        overflowPopup.getContent().add(overflowContent);
        overflowPopup.setAutoHide(true);
        overflowButton.setOnAction(_ -> toggleOverflowPopup());
        schemaCell.setFocusTraversable(true);
        schemaCell.setOnMouseClicked(_ -> getSkinnable().getOnSchemaAction().run());

        bindCells();
        installTooltips();
        model.savesSucceededProperty().addListener(savedFlashTrigger);

        rows.getChildren().setAll(rowTop, rowBottom);
        getChildren().add(rows);

        widthListener = (_, _, w) -> reconcile(w.doubleValue());
        strip.widthProperty().addListener(widthListener);
        reconcile(strip.getWidth());
    }

    // ── Model → cell bindings (binding-only; no Label.setText) ───────────────

    private void bindCells() {
        sessionCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    String name = model.getSessionName();
                    if (name == null || name.isBlank()) {
                        return "No session";
                    }
                    return "● " + name + " · " + formatElapsed(model.getSessionElapsed());
                },
                model.sessionNameProperty(), model.sessionElapsedProperty()));

        // §4.5, story 331 rulings G4 and K1: the Saved cell renders the
        // model's head-save and checkpoint facts (see the class note); a fast
        // save's success signal is the flash (flashSavedCell), not the text.
        Observable[] savedFacts = {
                model.getOperations(),
                model.lastSaveSucceededProperty(), model.lastSaveTimeProperty(), model.lastSaveScopeProperty(),
                model.saveFailureCauseProperty(), model.saveFailureTimeProperty(),
                model.lastCheckpointSucceededProperty(), model.checkpointFailureCauseProperty(),
                model.checkpointFailureTimeProperty(), model.lastCheckpointTimeProperty(),
                model.lastCheckpointFileProperty(), model.todayProperty()};
        savedCell.textProperty().bind(Bindings.createStringBinding(this::savedText, savedFacts));
        savedCell.severityProperty().bind(Bindings.createObjectBinding(this::savedSeverity,
                model.lastSaveSucceededProperty(), model.lastCheckpointSucceededProperty()));
        savedTooltip.textProperty().bind(Bindings.createStringBinding(this::savedDetail, savedFacts));
        savedCell.accessibleTextProperty().bind(Bindings.createStringBinding(
                () -> spokenSavedState(savedCell.getSeverity(), savedCell.getText()),
                savedCell.severityProperty(), savedCell.textProperty()));
        savedCell.accessibleHelpProperty().bind(savedTooltip.textProperty());

        journalCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    int n = model.getJournalEventsQueued();
                    String base = "Journal: " + n + (n == 1 ? " event queued" : " events queued");
                    // §6.3 — surface the back-pressure cause inline on non-NORMAL
                    // so the amber/red cell also says *why* (story 298).
                    return switch (model.getJournalBackpressure()) {
                        case SLOW -> base + " · disk slow";
                        case UNRESPONSIVE -> base + " · disk unresponsive";
                        case NORMAL -> base;
                    };
                },
                model.journalEventsQueuedProperty(), model.journalBackpressureProperty()));
        // §6.3 — NORMAL→neutral, SLOW→amber (WARN), UNRESPONSIVE→red (CRITICAL).
        // Severity flips style classes on the cell itself, so it is correct even
        // in a headless, unrealized-skin test (story 298).
        journalCell.severityProperty().bind(Bindings.createObjectBinding(
                () -> switch (model.getJournalBackpressure()) {
                    case NORMAL -> Severity.NORMAL;
                    case SLOW -> Severity.WARN;
                    case UNRESPONSIVE -> Severity.CRITICAL;
                },
                model.journalBackpressureProperty()));

        diskCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    long bytes = model.getFreeDiskBytes();
                    if (bytes < 0) {
                        return "Disk: —";
                    }
                    double mins = model.getEstimatedRecordMinutes();
                    if (mins < 0) {
                        return "Disk: " + formatBytes(bytes);
                    }
                    return "Disk: " + formatBytes(bytes) + " · " + formatCaptureLeft(mins);
                },
                model.freeDiskBytesProperty(), model.estimatedRecordMinutesProperty()));
        diskCell.severityProperty().bind(Bindings.createObjectBinding(
                () -> severityForRecordMinutes(model.getEstimatedRecordMinutes()),
                model.estimatedRecordMinutesProperty()));

        lockCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    String holder = model.getLockHolderLabel();
                    if (holder == null || holder.isBlank()) {
                        return "Lock: —";
                    }
                    return "Lock: " + holder + " · " + (model.isLockFresh() ? "live" : "stale");
                },
                model.lockHolderLabelProperty(), model.lockFreshProperty()));

        schemaCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    int cur = model.getCurrentSchemaVersion();
                    if (cur < 0) {
                        return "Schema: —";
                    }
                    int backup = model.getBackupSchemaVersion();
                    String s = "Schema: v" + cur;
                    if (backup >= 0) {
                        s += " · backup v" + backup + " available";
                    }
                    return s;
                },
                model.currentSchemaVersionProperty(), model.backupSchemaVersionProperty()));

        workspaceCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    String ws = model.getWorkspaceName();
                    if (ws == null || ws.isBlank()) {
                        return "Workspace: —";
                    }
                    return "Workspace: " + ws;
                },
                model.workspaceNameProperty()));

        // Checkpoint countdown — the model feeds both the next-fire instant and
        // the interval (the bar denominator); MotionManager gates the animation
        // (story 279).
        checkpointCountdown.nextCheckpointInstantProperty().bind(model.nextCheckpointInstantProperty());
        checkpointCountdown.intervalProperty().bind(model.checkpointIntervalProperty());
        checkpointCountdown.reduceMotionProperty().bind(
                getSkinnable().motionManager().reduceMotionProperty());
    }

    private void installTooltips() {
        installTooltip(sessionCell);
        // The Saved cell's tooltip carries more than its text (ruling G4 (b));
        // set as the cell's own tooltip so it can be read back.
        savedTooltip.setShowDelay(TOOLTIP_DELAY);
        savedCell.setTooltip(savedTooltip);
        installTooltip(journalCell);
        installTooltip(diskCell);
        installTooltip(lockCell);
        installTooltip(schemaCell);
        installTooltip(workspaceCell);
    }

    private static void installTooltip(StatusStripCell cell) {
        Tooltip tip = new Tooltip();
        tip.setShowDelay(TOOLTIP_DELAY);
        tip.textProperty().bind(cell.textProperty());
        Tooltip.install(cell, tip);
    }

    // ── The Saved cell (story 331, rulings G4 and K1) ─────────────────────────

    /** The Saved cell's text: the first of five that holds, in precedence order (see the class note). */
    private String savedText() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = model.getToday();
        List<NamedOperation> operations = model.getOperations();
        if (operations.stream().anyMatch(SessionStatusStripSkin::isHeadSave)) {
            return "◐ " + ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME;
        }
        Instant lastSaved = model.getLastSaveTime();
        if (!model.isLastSaveSucceeded()) {
            return lastSaved == null
                    ? "⚠ Save failed"
                    : "⚠ Save failed · last saved " + when(lastSaved, CLOCK, zone, today);
        }
        if (!model.isLastCheckpointSucceeded()) {
            Instant lastGood = model.getLastCheckpointTime();
            String failed = lastGood == null
                    ? "⚠ Checkpoint failed"
                    : "⚠ Checkpoint failed · last good " + when(lastGood, CLOCK, zone, today);
            return failed + headSaveTime(lastSaved, zone, today);
        }
        if (!operations.isEmpty()) {
            return "◐ " + operations.getFirst().name() + headSaveTime(lastSaved, zone, today);
        }
        return lastSaved == null ? "Not saved yet" : "✓ Saved " + when(lastSaved, CLOCK_SECONDS, zone, today);
    }

    /**
     * The head save's time after a checkpoint failure or another operation
     * (ruling L2): {@code " · saved HH:mm:ss"}, the date first when it is not
     * of today — so a Ctrl+S that succeeds while either stands changes the
     * text, with or without its flash — or nothing when it was never saved.
     */
    private static String headSaveTime(Instant lastSaved, ZoneId zone, LocalDate today) {
        return lastSaved == null ? "" : " · saved " + when(lastSaved, CLOCK_SECONDS, zone, today);
    }

    /** The Saved cell's severity, decided apart from its text (ruling G4 (a)). */
    private Severity savedSeverity() {
        if (!model.isLastSaveSucceeded()) {
            return Severity.CRITICAL;
        }
        return model.isLastCheckpointSucceeded() ? Severity.NORMAL : Severity.WARN;
    }

    /**
     * The Saved cell's tooltip, one fact a line: the running operations, each
     * standing failure with its time and cause, the last save with its scope,
     * and the last good checkpoint with its file — or, while a checkpoint
     * failure stands with none known, that none was found (ruling K1).
     */
    private String savedDetail() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = model.getToday();
        List<String> lines = new ArrayList<>();
        for (NamedOperation operation : model.getOperations()) {
            lines.add(operation.name());
        }
        if (!model.isLastSaveSucceeded()) {
            lines.add(failureLine("Save failed", model.getSaveFailureTime(), model.getSaveFailureCause(),
                    zone, today));
        }
        if (!model.isLastCheckpointSucceeded()) {
            lines.add(failureLine("Checkpoint failed", model.getCheckpointFailureTime(),
                    model.getCheckpointFailureCause(), zone, today));
        }
        Instant lastSaved = model.getLastSaveTime();
        lines.add(lastSaved == null
                ? "Not saved yet"
                : "Last saved " + when(lastSaved, CLOCK_SECONDS, zone, today)
                        + (model.getLastSaveScope() == SaveScope.DELTA ? " (delta)" : " (full)"));
        Instant lastGood = model.getLastCheckpointTime();
        Path lastGoodFile = model.getLastCheckpointFile();
        if (lastGood != null || lastGoodFile != null) {
            StringBuilder line = new StringBuilder("Last checkpoint");
            if (lastGood != null) {
                line.append(' ').append(when(lastGood, CLOCK_SECONDS, zone, today));
            }
            if (lastGoodFile != null) {
                line.append(" · ").append(lastGoodFile.getFileName());
            }
            lines.add(line.toString());
        } else if (!model.isLastCheckpointSucceeded()) {
            lines.add("No earlier checkpoint of this project was found.");
        }
        return String.join("\n", lines);
    }

    /** A standing failure's tooltip line: what failed, when, and why — the cause never shows as "null". */
    private static String failureLine(String what, Instant time, Throwable cause, ZoneId zone, LocalDate today) {
        String at = time == null ? "" : " at " + when(time, CLOCK_SECONDS, zone, today);
        return cause == null ? what + at : what + at + ": " + CheckpointStatusListener.reasonOf(cause);
    }

    /**
     * {@code time} on {@code clock} in {@code zone}, with its date first when
     * that is not {@code today} (ruling K1): {@code "18:10"},
     * {@code "2026-10-22 18:10"}.
     */
    static String when(Instant time, DateTimeFormatter clock, ZoneId zone, LocalDate today) {
        ZonedDateTime local = time.atZone(zone);
        String clockTime = clock.format(local);
        return local.toLocalDate().equals(today) ? clockTime : DAY.format(local) + " " + clockTime;
    }

    /**
     * The Saved cell's accessible text ({@code javafx-application-design}
     * §14): its state in words, the severity first, without the glyph a
     * screen reader would read out or skip — {@code "Error: Save failed, last
     * saved 14:22"}.
     */
    static String spokenSavedState(Severity severity, String text) {
        String state = text.replaceFirst("^[◐⚠✓] ", "").replace(" · ", ", ");
        return switch (severity) {
            case CRITICAL -> "Error: " + state;
            case WARN -> "Warning: " + state;
            case NORMAL -> state;
        };
    }

    private static boolean isHeadSave(NamedOperation operation) {
        return ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME.equals(operation.name());
    }

    /**
     * Flashes the Saved cell once for a head save success (ruling G4 (c), PM
     * book §4.5): restarted by a success during a flash. Under Reduce Motion
     * nothing flashes — as {@code ClockStatusIndicator} drops its pulse; the
     * new seconds carry the signal — and nothing flashes while the strip's
     * window is not showing. FX thread.
     */
    private void flashSavedCell() {
        if (!getSkinnable().motionManager().isAnimationAllowed() || !isShowing(savedCell)) {
            return;
        }
        savedCell.pseudoClassStateChanged(FLASH, true);
        savedFlash.playFromStart();
    }

    /** Whether {@code node}'s window shows: a hidden window keeps its {@code Scene}, so the window is asked. */
    private static boolean isShowing(Node node) {
        Scene scene = node.getScene();
        Window window = scene == null ? null : scene.getWindow();
        return window != null && window.isShowing();
    }

    // ── Responsive layout / overflow (§4.4) ──────────────────────────────────

    private void reconcile(double width) {
        List<StatusStripCell> hidden = new ArrayList<>();
        // Collapse order: Workspace first, Journal last (§4.4).
        if (width < T_WORKSPACE) {
            hidden.add(workspaceCell);
        }
        if (width < T_SCHEMA) {
            hidden.add(schemaCell);
        }
        if (width < T_LOCK) {
            hidden.add(lockCell);
        }
        if (width < T_DISK) {
            hidden.add(diskCell);
        }
        if (width < T_JOURNAL) {
            hidden.add(journalCell);
        }

        boolean twoRows = width >= SessionStatusStrip.TWO_ROW_MIN_WIDTH;
        if (twoRows) {
            // Wide: two full rows, every fact inline, no overflow.
            rowTop.getChildren().setAll(sessionCell, savedCell, checkpointCountdown, journalCell);
            rowBottom.getChildren().setAll(diskCell, lockCell, schemaCell, workspaceCell);
            setRowBottomShown(true);
            overflowContent.getChildren().clear();
            setOverflowButtonShown(false);
            if (overflowPopup.isShowing()) {
                overflowPopup.hide();
            }
            return;
        }

        // Narrow: a single row of the never-collapse cells plus the still-visible
        // collapsible cells in inline order, then the overflow button.
        List<Node> single = new ArrayList<>();
        single.add(sessionCell);
        single.add(savedCell);
        single.add(checkpointCountdown);
        if (!hidden.contains(journalCell)) {
            single.add(journalCell);
        }
        if (!hidden.contains(diskCell)) {
            single.add(diskCell);
        }
        if (!hidden.contains(lockCell)) {
            single.add(lockCell);
        }
        if (!hidden.contains(schemaCell)) {
            single.add(schemaCell);
        }
        if (!hidden.contains(workspaceCell)) {
            single.add(workspaceCell);
        }
        single.add(overflowButton);
        rowTop.getChildren().setAll(single);
        rowBottom.getChildren().clear();
        setRowBottomShown(false);

        // The popover holds exactly the hidden cells, in collapse order.
        overflowContent.getChildren().setAll(hidden);
        boolean any = !hidden.isEmpty();
        setOverflowButtonShown(any);
        if (!any && overflowPopup.isShowing()) {
            overflowPopup.hide();
        }
    }

    private void setRowBottomShown(boolean shown) {
        rowBottom.setVisible(shown);
        rowBottom.setManaged(shown);
    }

    private void setOverflowButtonShown(boolean shown) {
        overflowButton.setVisible(shown);
        overflowButton.setManaged(shown);
    }

    private void toggleOverflowPopup() {
        if (overflowPopup.isShowing()) {
            overflowPopup.hide();
            return;
        }
        Bounds b = overflowButton.localToScreen(overflowButton.getBoundsInLocal());
        if (b != null) {
            overflowPopup.show(overflowButton, b.getMinX(), b.getMinY() - overflowContent.prefHeight(-1));
        }
    }

    // ── Accessors for the control (delegated to from SessionStatusStrip) ─────

    StatusStripCell sessionCell() { return sessionCell; }
    StatusStripCell savedCell() { return savedCell; }
    CheckpointCountdownCanvas checkpointCountdown() { return checkpointCountdown; }
    StatusStripCell journalCell() { return journalCell; }
    StatusStripCell diskCell() { return diskCell; }
    StatusStripCell lockCell() { return lockCell; }
    StatusStripCell schemaCell() { return schemaCell; }
    StatusStripCell workspaceCell() { return workspaceCell; }
    Button overflowButton() { return overflowButton; }

    List<StatusStripCell> overflowCells() {
        List<StatusStripCell> cells = new ArrayList<>();
        for (Node n : overflowContent.getChildren()) {
            if (n instanceof StatusStripCell cell) {
                cells.add(cell);
            }
        }
        return cells;
    }

    // ── Formatting helpers (pure, package-private) ───────────────────────────

    /** Maps estimated record-minutes to a cell severity (§6.2: <30 amber, <5 red). */
    static Severity severityForRecordMinutes(double minutes) {
        if (minutes < 0) {
            return Severity.NORMAL; // unknown
        }
        if (minutes < 5) {
            return Severity.CRITICAL;
        }
        if (minutes < 30) {
            return Severity.WARN;
        }
        return Severity.NORMAL;
    }

    /** Formats an elapsed session duration as {@code "Hh Mm"} / {@code "Mm"}. */
    static String formatElapsed(java.time.Duration elapsed) {
        if (elapsed == null || elapsed.isNegative()) {
            return "0m";
        }
        long totalMinutes = elapsed.toMinutes();
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /**
     * Humanises a byte count: one decimal at terabyte scale ({@code "X.X TB"}),
     * whole units below it ({@code "X GB"} / {@code "X MB"} / {@code "X KB"},
     * matching the §4.4 mockup's "412 GB"). Returns {@code "—"} for a negative
     * (unknown) count.
     */
    static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        double tb = gb / 1024.0;
        if (tb >= 1.0) {
            return String.format(Locale.ROOT, "%.1f TB", tb);
        }
        if (gb >= 1.0) {
            return String.format(Locale.ROOT, "%.0f GB", gb);
        }
        if (mb >= 1.0) {
            return String.format(Locale.ROOT, "%.0f MB", mb);
        }
        return String.format(Locale.ROOT, "%.0f KB", kb);
    }

    /** Formats capture room as {@code "X h capture left"} / {@code "X min capture left"}. */
    static String formatCaptureLeft(double minutes) {
        if (minutes < 0) {
            return "—";
        }
        if (minutes >= 60) {
            return String.format(Locale.ROOT, "%.0f h capture left", minutes / 60.0);
        }
        return String.format(Locale.ROOT, "%.0f min capture left", minutes);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        getSkinnable().widthProperty().removeListener(widthListener);
        model.savesSucceededProperty().removeListener(savedFlashTrigger);
        savedFlash.stop();
        savedCell.pseudoClassStateChanged(FLASH, false);
        if (overflowPopup.isShowing()) {
            overflowPopup.hide();
        }
        sessionCell.textProperty().unbind();
        savedCell.accessibleTextProperty().unbind();
        savedCell.accessibleHelpProperty().unbind();
        savedTooltip.textProperty().unbind();
        savedCell.textProperty().unbind();
        savedCell.severityProperty().unbind();
        journalCell.textProperty().unbind();
        journalCell.severityProperty().unbind();
        diskCell.textProperty().unbind();
        diskCell.severityProperty().unbind();
        lockCell.textProperty().unbind();
        schemaCell.textProperty().unbind();
        workspaceCell.textProperty().unbind();
        checkpointCountdown.nextCheckpointInstantProperty().unbind();
        checkpointCountdown.intervalProperty().unbind();
        checkpointCountdown.reduceMotionProperty().unbind();
        super.dispose();
    }
}
