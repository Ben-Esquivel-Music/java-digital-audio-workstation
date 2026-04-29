package com.benesquivelmusic.daw.app.ui.drag;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Single point of consultation for every draggable source in the DAW.
 *
 * <p>This class implements the user-story-197 requirement that every
 * draggable surface (clips, plugins, samples) consults <em>the same</em>
 * advisor, so drag visual feedback is consistent across the application.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>The presenter calls {@link #beginDrag(DragSourceKind, String,
 *       double, double, double, double)} when a press-and-drag gesture
 *       crosses the drag-threshold. The advisor enters the
 *       {@code DRAGGING} state and remembers the source's origin.</li>
 *   <li>For every cursor move or modifier change, the presenter calls
 *       {@link #update(DropTargetKind, double, String, Set)} and renders
 *       the resulting {@link DragVisualState}.</li>
 *   <li>On a successful drop the presenter calls {@link #commit()}; on
 *       Esc or right-click cancel it calls {@link #cancel()}, which
 *       returns a {@link CancelRevert} describing the revert animation
 *       to play.</li>
 * </ol>
 *
 * <h2>Compatibility matrix</h2>
 * <pre>
 *                    TRACK_LANE  INSERT_SLOT  SEND_SLOT
 *   CLIP                 yes        no           no
 *   PLUGIN               no         yes          yes
 *   SAMPLE               yes        no           no
 * </pre>
 *
 * <h2>Cursor priority</h2>
 * If the target is invalid the cursor is always {@link DragCursor#NO_DROP}.
 * Otherwise modifiers select the cursor in the following priority order:
 * {@link DragModifier#DUPLICATE} → {@link DragCursor#COPY},
 * {@link DragModifier#LINK} → {@link DragCursor#LINK},
 * {@link DragModifier#DISABLE_SNAP} → {@link DragCursor#NO_SNAP},
 * else {@link DragCursor#DEFAULT}.
 *
 * <p>The class is intentionally UI-toolkit-agnostic — it accepts plain
 * {@code double} pixel positions and emits plain records. All JavaFX
 * adapter code lives in the presenter.</p>
 */
public final class DragVisualAdvisor {

    /** State machine. */
    public enum State { IDLE, DRAGGING, REVERTING }

    /** Default RGBA tint for a valid drop target. */
    private static final String VALID_TINT = "5fa8ff40";

    private final AnimationProfile profile;

    // Mutable drag state — guarded by the host's threading model
    // (presenter is expected to drive the advisor on the JavaFX
    // Application Thread).
    private State state = State.IDLE;
    private DragSourceKind sourceKind;
    private String sourceLabel;
    private double originX;
    private double originY;
    private double ghostWidth;
    private double ghostHeight;

    /** Constructs an advisor with the given animation profile. */
    public DragVisualAdvisor(AnimationProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /** Constructs an advisor with the {@link AnimationProfile#DEFAULT default profile}. */
    public DragVisualAdvisor() {
        this(AnimationProfile.DEFAULT);
    }

    /** Returns the animation profile shared with the host's animation controller. */
    public AnimationProfile profile() {
        return profile;
    }

    /** Returns the current state. */
    public State state() {
        return state;
    }

    /**
     * Begins a drag.
     *
     * @param kind        kind of source being dragged
     * @param label       short human-readable label for the ghost preview
     * @param originX     X coordinate of the source's origin (used by
     *                    {@link #cancel()} to revert)
     * @param originY     Y coordinate of the source's origin
     * @param ghostWidth  preferred ghost width in pixels
     * @param ghostHeight preferred ghost height in pixels
     * @return the initial {@link DragVisualState} — a ghost over the
     *         origin with no highlight and the default cursor
     * @throws IllegalStateException if a drag is already in progress
     */
    public DragVisualState beginDrag(DragSourceKind kind,
                                     String label,
                                     double originX,
                                     double originY,
                                     double ghostWidth,
                                     double ghostHeight) {
        if (state == State.DRAGGING) {
            throw new IllegalStateException("drag already in progress");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        if (ghostWidth <= 0 || ghostHeight <= 0) {
            throw new IllegalArgumentException("ghost size must be positive");
        }
        this.sourceKind = kind;
        this.sourceLabel = label;
        this.originX = originX;
        this.originY = originY;
        this.ghostWidth = ghostWidth;
        this.ghostHeight = ghostHeight;
        this.state = State.DRAGGING;

        return new DragVisualState(
                buildGhost(),
                DropTargetHighlight.NONE,
                DragCursor.DEFAULT,
                SnapIndicator.HIDDEN);
    }

    /**
     * Updates the visual state based on the current cursor position and
     * active modifier set.
     *
     * @param target        kind of drop target underneath the cursor;
     *                      may be {@link DropTargetKind#NONE}
     * @param snappedXPx    arrangement X coordinate the snap-quantizer
     *                      currently rounds to (ignored unless
     *                      {@code target == TRACK_LANE})
     * @param snapValueLabel human label of the active snap value
     *                      (e.g. {@code "1/4"})
     * @param modifiers     active drag modifiers
     * @return a fully populated {@link DragVisualState}
     * @throws IllegalStateException if no drag is in progress
     */
    public DragVisualState update(DropTargetKind target,
                                  double snappedXPx,
                                  String snapValueLabel,
                                  Set<DragModifier> modifiers) {
        if (state != State.DRAGGING) {
            throw new IllegalStateException(
                    "no drag in progress (state=" + state + ")");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapValueLabel, "snapValueLabel");
        Objects.requireNonNull(modifiers, "modifiers");
        Set<DragModifier> mods = modifiers.isEmpty()
                ? EnumSet.noneOf(DragModifier.class)
                : EnumSet.copyOf(modifiers);

        boolean valid = canDropOn(sourceKind, target);
        DropTargetHighlight highlight = (target == DropTargetKind.NONE)
                ? DropTargetHighlight.NONE
                : new DropTargetHighlight(target, valid, valid ? VALID_TINT : "");

        DragCursor cursor = selectCursor(valid, mods);

        SnapIndicator snap = computeSnap(target, snappedXPx, snapValueLabel,
                mods.contains(DragModifier.DISABLE_SNAP));

        return new DragVisualState(buildGhost(), highlight, cursor, snap);
    }

    /**
     * Cancels the drag and returns the parameters of the revert
     * animation the presenter should run.
     *
     * <p>The advisor transitions to {@link State#REVERTING}; once the
     * presenter has finished playing the animation it must call
     * {@link #revertCompleted()} to return the advisor to {@link State#IDLE}.</p>
     *
     * @return a {@link CancelRevert} describing where to slide the source
     *         back to, and how long the slide should take
     * @throws IllegalStateException if no drag is in progress
     */
    public CancelRevert cancel() {
        if (state != State.DRAGGING) {
            throw new IllegalStateException(
                    "no drag in progress (state=" + state + ")");
        }
        state = State.REVERTING;
        return new CancelRevert(originX, originY, profile.cancelRevert());
    }

    /**
     * Notifies the advisor that the cancel-revert animation has finished
     * playing. Returns the advisor to {@link State#IDLE} and clears all
     * drag state.
     */
    public void revertCompleted() {
        if (state != State.REVERTING) {
            throw new IllegalStateException(
                    "no revert in progress (state=" + state + ")");
        }
        clear();
    }

    /**
     * Commits a successful drop. Returns the advisor to
     * {@link State#IDLE} and clears all drag state.
     *
     * @throws IllegalStateException if no drag is in progress
     */
    public void commit() {
        if (state != State.DRAGGING) {
            throw new IllegalStateException(
                    "no drag in progress (state=" + state + ")");
        }
        clear();
    }

    /**
     * Returns the source's origin while a drag is in progress, allowing
     * tests and the presenter to verify revert positions.
     */
    public Optional<double[]> sourceOrigin() {
        return state == State.IDLE
                ? Optional.empty()
                : Optional.of(new double[]{originX, originY});
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GhostPreview buildGhost() {
        return new GhostPreview(
                sourceKind,
                sourceKind.defaultGhostStyle(),
                sourceLabel,
                ghostWidth,
                ghostHeight,
                profile.ghostOpacity());
    }

    private void clear() {
        state = State.IDLE;
        sourceKind = null;
        sourceLabel = null;
        originX = 0;
        originY = 0;
        ghostWidth = 0;
        ghostHeight = 0;
    }

    /** Compatibility matrix encoded as a static helper for testability. */
    static boolean canDropOn(DragSourceKind kind, DropTargetKind target) {
        if (kind == null || target == null || target == DropTargetKind.NONE) {
            return false;
        }
        return switch (kind) {
            case CLIP, SAMPLE -> target == DropTargetKind.TRACK_LANE;
            case PLUGIN       -> target == DropTargetKind.INSERT_SLOT
                              || target == DropTargetKind.SEND_SLOT;
        };
    }

    private static DragCursor selectCursor(boolean valid,
                                           Set<DragModifier> mods) {
        if (!valid) {
            // Even with modifiers, an invalid target wins.
            return DragCursor.NO_DROP;
        }
        if (mods.contains(DragModifier.DUPLICATE))    return DragCursor.COPY;
        if (mods.contains(DragModifier.LINK))         return DragCursor.LINK;
        if (mods.contains(DragModifier.DISABLE_SNAP)) return DragCursor.NO_SNAP;
        return DragCursor.DEFAULT;
    }

    private static SnapIndicator computeSnap(DropTargetKind target,
                                             double snappedXPx,
                                             String snapValueLabel,
                                             boolean disableSnap) {
        if (target != DropTargetKind.TRACK_LANE || disableSnap) {
            return SnapIndicator.HIDDEN;
        }
        return new SnapIndicator(snappedXPx, snapValueLabel, true);
    }

    /**
     * Description of the revert animation produced by {@link #cancel()}.
     *
     * @param targetX target X coordinate to slide the source to
     * @param targetY target Y coordinate to slide the source to
     * @param duration animation duration sourced from
     *                 {@link AnimationProfile#cancelRevert()}
     */
    public record CancelRevert(double targetX, double targetY,
                               java.time.Duration duration) { }
}
