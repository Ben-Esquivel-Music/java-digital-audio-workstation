package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.SaveScope;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell.Severity;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.time.Instant;
import java.time.ZoneId;
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
 * cell's error severity are {@code bind(...)} to the model too. The checkpoint
 * countdown's {@code nextCheckpointInstant} / {@code interval} are bound to the
 * model and its {@code reduceMotion} to the {@code MotionManager} (story 279).</p>
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

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

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

        savedCell.textProperty().bind(Bindings.createStringBinding(
                () -> {
                    // §4.5 — while a long operation runs (archive / restore /
                    // slow save registered on the model's operations list), the
                    // Saved cell shows its progress; on failure it shows the
                    // persistent error; otherwise the last-save time + scope (the
                    // flash for a fast save is itself the success signal).
                    if (model.isSaving()) {
                        var ops = model.getOperations();
                        return "◐ " + (ops.isEmpty() ? "Working…" : ops.get(0).name());
                    }
                    if (!model.isLastSaveSucceeded()) {
                        return "⚠ Save failed";
                    }
                    Instant t = model.getLastSaveTime();
                    if (t == null) {
                        return "Not saved yet";
                    }
                    String scope = model.getLastSaveScope() == SaveScope.DELTA ? "delta" : "full";
                    return "✓ Saved " + CLOCK.format(t) + " (" + scope + ")";
                },
                model.savingProperty(), model.getOperations(),
                model.lastSaveSucceededProperty(), model.lastSaveTimeProperty(),
                model.lastSaveScopeProperty()));
        savedCell.severityProperty().bind(Bindings.createObjectBinding(
                () -> model.isLastSaveSucceeded() ? Severity.NORMAL : Severity.CRITICAL,
                model.lastSaveSucceededProperty()));

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
        installTooltip(savedCell);
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
        if (overflowPopup.isShowing()) {
            overflowPopup.hide();
        }
        sessionCell.textProperty().unbind();
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
