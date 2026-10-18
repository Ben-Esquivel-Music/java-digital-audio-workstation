package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.SetCompRegionAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import java.util.Objects;

/**
 * Headless logic for the comp tool ({@link EditTool#COMP}).
 *
 * <p>Translates pointer gestures (begin / drag / end + Alt-modifier) into
 * comping actions on a {@link Track}'s {@link TakeComping}. Kept JavaFX-free
 * so it can be exercised in headless tests without a display. Every gesture
 * is audible at once (story 328, book §5.6): the track's main lane holds the
 * compiled comp, re-compiled after each gesture through the core's
 * {@link CompileCompAction}; the lanes are never touched.</p>
 *
 * <ul>
 *   <li>{@link #beginSwipe(int, double)} starts a swipe at a beat position
 *       on a take lane index.</li>
 *   <li>{@link #endSwipe(double)} finalizes the swipe by committing a
 *       {@link CompRegion} via {@link SetCompRegionAction} (routed through
 *       the {@link UndoManager}), which re-compiles the comp. Overlapping
 *       regions on other take lanes are deselected by
 *       {@link TakeComping#addCompRegion(CompRegion)}.</li>
 *   <li>{@link #altClickLane(int)} solos the given take lane (auditioning),
 *       unsoloing every other lane; the main lane then plays that lane.</li>
 *   <li>{@link #clickMainLane()} clears any solo state, returning to
 *       composite playback.</li>
 * </ul>
 */
public final class CompToolHandler {

    private final Track track;
    private final TakeComping comping;
    private final UndoManager undoManager;

    private int swipeTakeIndex = -1;
    private double swipeStartBeat = 0.0;
    private boolean swipeActive;

    /**
     * Creates a new comp-tool handler.
     *
     * @param track       the track whose take comping to mutate
     * @param undoManager the undo manager that all comp actions route through
     */
    public CompToolHandler(Track track, UndoManager undoManager) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.comping = track.getTakeComping();
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
     * resulting {@link CompRegion} via the {@link UndoManager} and
     * re-compiling the track's comp. If the swipe is empty (zero or negative
     * duration) the gesture is silently cancelled.
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
        undoManager.execute(new SetCompRegionAction(track, region));
        return region;
    }

    /** Returns whether a swipe gesture is currently in flight. */
    public boolean isSwipeActive() {
        return swipeActive;
    }

    /**
     * Alt-click on a take lane: solo that lane and unsolo every other lane,
     * and re-compile so the main lane plays the soloed lane whole. Mirrors
     * the standard pro-DAW take-audition gesture. An audition is not an
     * edit: it is not recorded in the undo history. It cannot orphan the
     * main lane either (story 328 review): every undo that touches the comp
     * — a swipe's, a recorded take's — recompiles from the live comping
     * state through {@link CompileCompAction}, removing whatever the comp
     * occupies at that moment, this audition's clips included, rather than
     * restoring a snapshot taken before the audition.
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
        new CompileCompAction(track).execute();
    }

    /**
     * Click on the main (composite) lane: clears any take-lane solo state
     * and re-compiles, restoring composite playback.
     */
    public void clickMainLane() {
        boolean wasSoloed = false;
        for (TakeLane lane : comping.getTakeLanes()) {
            wasSoloed |= lane.isSoloed();
            lane.setSoloed(false);
        }
        if (wasSoloed) {
            new CompileCompAction(track).execute();
        }
    }
}
