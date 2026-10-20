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
     * <p>Nor does it change what a save writes — every project writer saves
     * the selection, not the soloed lane ({@link TakeComping#clipsAsSaved})
     * — with one exception: the audition replaces the comp output on the
     * main lane, so a hand edit to a comp clip (a fade, a trim, a split) is
     * discarded when the audition ends, and from its start a save writes the
     * selection compile instead of the edited clip (future story 353). Only
     * the start of an audition can do that: Alt-click while another lane is
     * already soloed replaces audition output, never comp output. The
     * result tells the caller to mark the project dirty and say so.</p>
     *
     * @param takeIndex the take lane index to solo
     * @return {@code true} if this started an audition that discards edits to
     *         the comp output — the saved comp changed; {@code false} for a
     *         pure listen over an unedited comp, or a switch between auditions
     */
    public boolean altClickLane(int takeIndex) {
        if (takeIndex < 0 || takeIndex >= comping.getTakeLaneCount()) {
            throw new IndexOutOfBoundsException("takeIndex out of range: " + takeIndex);
        }
        boolean discardsCompEdits = !comping.isAuditioning()
                && !comping.compOutputSavesAsSelection(track);
        for (int i = 0; i < comping.getTakeLaneCount(); i++) {
            TakeLane lane = comping.getTakeLane(i);
            lane.setSoloed(i == takeIndex);
        }
        new CompileCompAction(track).execute();
        return discardsCompEdits;
    }

    /**
     * Click on the main (composite) lane: ends any lane audition
     * ({@link TakeComping#endAuditions()}) and, if one was playing,
     * re-compiles, restoring composite playback. It never changes what a
     * save writes: during the audition the saved comp was already the
     * selection compile, which is what this puts back on the main lane.
     */
    public void clickMainLane() {
        if (comping.endAuditions()) {
            new CompileCompAction(track).execute();
        }
    }
}
