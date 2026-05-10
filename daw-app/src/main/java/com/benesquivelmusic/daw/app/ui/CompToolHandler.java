package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.SetCompRegionAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import java.util.Objects;

/**
 * Headless logic for the comp tool ({@link EditTool#COMP}).
 *
 * <p>Translates pointer gestures (begin / drag / end + Alt-modifier) into
 * undoable comping actions on a {@link TakeComping}. Kept JavaFX-free so it
 * can be exercised in headless tests without a display.</p>
 *
 * <ul>
 *   <li>{@link #beginSwipe(int, double)} starts a swipe at a beat position
 *       on a take lane index.</li>
 *   <li>{@link #endSwipe(double)} finalizes the swipe by committing a
 *       {@link CompRegion} via {@link SetCompRegionAction} (routed through
 *       the {@link UndoManager}). Overlapping regions on other take lanes
 *       are deselected by {@link TakeComping#addCompRegion(CompRegion)}.</li>
 *   <li>{@link #altClickLane(int)} solos the given take lane (auditioning),
 *       unsoloing every other lane.</li>
 *   <li>{@link #clickMainLane()} clears any solo state, returning to
 *       composite playback.</li>
 * </ul>
 */
public final class CompToolHandler {

    private final TakeComping comping;
    private final UndoManager undoManager;

    private int swipeTakeIndex = -1;
    private double swipeStartBeat = 0.0;
    private boolean swipeActive;

    /**
     * Creates a new comp-tool handler.
     *
     * @param comping     the take comping to mutate
     * @param undoManager the undo manager that all comp actions route through
     */
    public CompToolHandler(TakeComping comping, UndoManager undoManager) {
        this.comping = Objects.requireNonNull(comping, "comping must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
    }

    /**
     * Begins a comp swipe gesture on the take lane at {@code takeIndex}
     * starting at {@code startBeat} on the timeline.
     */
    public void beginSwipe(int takeIndex, double startBeat) {
        if (takeIndex < 0 || takeIndex >= comping.getTakeLaneCount()) {
            throw new IndexOutOfBoundsException("takeIndex out of range: " + takeIndex);
        }
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must be >= 0: " + startBeat);
        }
        this.swipeTakeIndex = takeIndex;
        this.swipeStartBeat = startBeat;
        this.swipeActive = true;
    }

    /**
     * Finalizes the in-flight swipe at {@code endBeat}, committing the
     * resulting {@link CompRegion} via the {@link UndoManager}. If the swipe
     * is empty (zero or negative duration) the gesture is silently
     * cancelled.
     *
     * @return the committed {@link CompRegion}, or {@code null} if cancelled
     */
    public CompRegion endSwipe(double endBeat) {
        if (!swipeActive) {
            return null;
        }
        if (endBeat < 0) {
            throw new IllegalArgumentException("endBeat must be >= 0: " + endBeat);
        }
        swipeActive = false;
        double lo = Math.min(swipeStartBeat, endBeat);
        double hi = Math.max(swipeStartBeat, endBeat);
        double duration = hi - lo;
        if (duration <= 0) {
            return null;
        }
        CompRegion region = new CompRegion(swipeTakeIndex, lo, duration);
        undoManager.execute(new SetCompRegionAction(comping, region));
        return region;
    }

    /** Returns whether a swipe gesture is currently in flight. */
    public boolean isSwipeActive() {
        return swipeActive;
    }

    /**
     * Alt-click on a take lane: solo that lane and unsolo every other lane.
     * Mirrors the standard pro-DAW take-audition gesture.
     *
     * @param takeIndex the take lane index to solo
     */
    public void altClickLane(int takeIndex) {
        if (takeIndex < 0 || takeIndex >= comping.getTakeLaneCount()) {
            throw new IndexOutOfBoundsException("takeIndex out of range: " + takeIndex);
        }
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            TakeLane lane = comping.getTakeLane(i);
            lane.setSoloed(i == takeIndex);
        }
    }

    /**
     * Click on the main (composite) lane: clears any take-lane solo state,
     * restoring composite playback.
     */
    public void clickMainLane() {
        for (TakeLane lane : comping.getTakeLanes()) {
            lane.setSoloed(false);
        }
    }
}
