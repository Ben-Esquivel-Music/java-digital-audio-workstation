package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.ui.motion.MotionManager;

import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

import java.util.List;
import java.util.Objects;

/**
 * The Session Status Strip {@link Control} — a persistent two-row strip across
 * the bottom of the window that promotes the §2.2 invisible signals (save,
 * checkpoint, journal, lock, disk, session, schema, workspace) to first-class
 * chrome (Project Manager Design Book §4.4, story 295 — Stage 1 of the §7
 * migration path). This single strip <em>is</em> the §2.2 "state is always
 * visible" principle.
 *
 * <h2>Binds only to {@link ProjectOperationProgress}</h2>
 *
 * <p>The strip's cells {@code bind(...)} to the single §5.5
 * {@link ProjectOperationProgress} model and nothing else — no controller
 * pokes {@code Label.setText} (the §8 rejection of "status text in
 * {@code Label.setText}"). Each fact is its own small {@link StatusStripCell}
 * (so a theme can restyle a cell without touching the strip), except the
 * checkpoint countdown, which is a {@link CheckpointCountdownCanvas}
 * ({@code Canvas} + 1&nbsp;Hz {@code AnimationTimer}, §6).</p>
 *
 * <h2>Eight cells, two rows, priority overflow (§4.4)</h2>
 *
 * <p>Row 1: Session · Saved · Checkpoint · Journal. Row 2: Disk · Lock ·
 * Schema · Workspace. Below 1200&nbsp;px the strip collapses to a single row,
 * hiding collapsible cells in the fixed priority order Workspace → Schema →
 * Lock → Disk → Journal; the Session, Saved and Checkpoint cells never
 * collapse. A trailing {@code ⋯} overflow button reveals the hidden cells in a
 * popover ({@code javafx-application-design} §10). All layout/overflow logic
 * lives in {@link SessionStatusStripSkin}; this control only holds the model,
 * the {@link MotionManager} (for the §279 reduce-motion countdown gate), and
 * the public API.</p>
 *
 * <p>Control/Skin split per {@code javafx-application-design} §3: the skin
 * builds and binds the cells. The cell accessors below delegate to the
 * realized skin — a test must attach the strip to a {@code Scene} and call
 * {@code applyCss()} before reading them (the {@code javafx-application-design}
 * §3 / headless-skin discipline).</p>
 */
public final class SessionStatusStrip extends Control {

    /** Stable style class — selectable as {@code .session-status-strip}. */
    public static final String DEFAULT_STYLE_CLASS = "session-status-strip";

    /**
     * Width (px) below which the strip collapses from two rows to a single row
     * and begins hiding collapsible cells in priority order (§4.4).
     */
    public static final double TWO_ROW_MIN_WIDTH = 1200.0;

    /**
     * Width (px) below which the trailing {@code ⋯} overflow popover is the
     * documented access point for every hidden cell (§4.4). The overflow
     * button is shown whenever <em>any</em> cell is hidden (which first occurs
     * just under {@link #TWO_ROW_MIN_WIDTH}); by 900&nbsp;px the popover is the
     * sole way to reach the collapsed cells, as the spec requires.
     */
    public static final double SINGLE_ROW_OVERFLOW_WIDTH = 900.0;

    private final ProjectOperationProgress progress;
    private final MotionManager motionManager;

    /**
     * Creates a strip bound to {@code progress}, using the app-wide
     * {@link MotionManager#getDefault()} to gate the countdown animation.
     *
     * @param progress the single status model; must not be {@code null}
     */
    public SessionStatusStrip(ProjectOperationProgress progress) {
        this(progress, MotionManager.getDefault());
    }

    /**
     * Creates a strip bound to {@code progress}, gating the countdown
     * animation on {@code motionManager} (story 279).
     *
     * @param progress      the single status model; must not be {@code null}
     * @param motionManager the reduce-motion source; must not be {@code null}
     */
    public SessionStatusStrip(ProjectOperationProgress progress, MotionManager motionManager) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.motionManager = Objects.requireNonNull(motionManager, "motionManager must not be null");
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleRoleDescription("Session status strip");
        setFocusTraversable(false);
    }

    /** @return the bound status model (read by the skin). */
    public ProjectOperationProgress progress() {
        return progress;
    }

    /** @return the reduce-motion source (read by the skin to gate the countdown). */
    public MotionManager motionManager() {
        return motionManager;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SessionStatusStripSkin(this);
    }

    private SessionStatusStripSkin skin() {
        Skin<?> skin = getSkin();
        if (!(skin instanceof SessionStatusStripSkin sss)) {
            throw new IllegalStateException(
                    "SessionStatusStrip skin is not realized yet — attach the strip "
                            + "to a Scene and call applyCss() before reading its cells.");
        }
        return sss;
    }

    // ── Cell accessors (delegate to the realized skin) ────────────────────────

    /** @return the Session cell (name + elapsed). */
    public StatusStripCell sessionCell() { return skin().sessionCell(); }
    /** @return the Saved cell (last-save time / scope / error). */
    public StatusStripCell savedCell() { return skin().savedCell(); }
    /** @return the live checkpoint-countdown cell. */
    public CheckpointCountdownCanvas checkpointCountdown() { return skin().checkpointCountdown(); }
    /** @return the Journal cell (events queued). */
    public StatusStripCell journalCell() { return skin().journalCell(); }
    /** @return the Disk cell (free space / capture estimate, §6.2 amber/red). */
    public StatusStripCell diskCell() { return skin().diskCell(); }
    /** @return the Lock cell (holder + freshness). */
    public StatusStripCell lockCell() { return skin().lockCell(); }
    /** @return the Schema cell (current + backup version). */
    public StatusStripCell schemaCell() { return skin().schemaCell(); }
    /** @return the Workspace cell. */
    public StatusStripCell workspaceCell() { return skin().workspaceCell(); }

    // ── Overflow diagnostics (for the §4.4 overflow tests) ────────────────────

    /** @return the trailing {@code ⋯} overflow button. */
    public Button overflowButton() { return skin().overflowButton(); }

    /** @return whether the overflow button is currently shown (a cell is hidden). */
    public boolean isOverflowButtonVisible() { return skin().overflowButton().isVisible(); }

    /**
     * @return the cells currently collapsed into the overflow popover, in the
     *         §4.4 collapse order (Workspace, Schema, Lock, Disk, Journal)
     */
    public List<StatusStripCell> overflowCells() { return skin().overflowCells(); }
}
