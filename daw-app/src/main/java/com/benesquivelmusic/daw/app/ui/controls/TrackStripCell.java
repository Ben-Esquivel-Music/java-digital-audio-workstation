package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.sdk.model.Track;

import javafx.scene.control.ListCell;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * {@link ListCell} that renders a {@link Track} into a single reusable
 * {@link TrackStrip}.
 *
 * <h2>Cell-reuse contract</h2>
 *
 * <p><strong>Important:</strong> the arrangement view's
 * {@code ListView<Track>} cell factory builds <em>one</em>
 * {@code TrackStrip} per cell and recycles it via JavaFX's standard cell
 * virtualization — it does <em>not</em> {@code new TrackStrip()} per
 * item. In {@link #updateItem(Track, boolean)}, the cell sets the
 * recycled strip's properties from the new track (instead of replacing
 * any binding), so only the ~20–30 visible strips exist in the scene
 * graph at any time. At 200 tracks this is the difference between 200
 * embedded-meter {@link javafx.animation.AnimationTimer}s and ~30.
 *
 * <p>Do <strong>not</strong> regress this contract by re-instantiating
 * {@code TrackStrip} per item in a future refactor.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * ListView<Track> tracks = new ListView<>();
 * tracks.setCellFactory(lv -> new TrackStripCell());
 * }</pre>
 */
public class TrackStripCell extends ListCell<Track> {

    private final TrackStrip strip;

    /** Creates a cell with a freshly-instantiated {@link TrackStrip}. */
    public TrackStripCell() {
        this(new TrackStrip());
    }

    /**
     * Creates a cell that reuses the supplied strip. Test seam.
     *
     * @param strip the per-cell {@link TrackStrip} instance (never {@code null});
     *              callers must NOT share the same strip across multiple cells.
     */
    public TrackStripCell(TrackStrip strip) {
        this.strip = Objects.requireNonNull(strip, "strip");
        setText(null);
        // The strip is the only graphic; the default ListCell text is
        // suppressed (we use setGraphic in updateItem).
    }

    /** @return the per-cell {@link TrackStrip} (test seam). */
    public TrackStrip strip() {
        return strip;
    }

    /**
     * Called by the ListView whenever the cell's index changes —
     * including insert/remove operations on items above this cell that
     * do NOT trigger {@link #updateItem(Track, boolean)}. Keeps the
     * strip's display index consistent with the actual list position.
     */
    @Override
    public void updateIndex(int index) {
        super.updateIndex(index);
        // index == -1 means the cell is no longer associated with an item.
        if (index >= 0 && getItem() != null) {
            strip.setTrackIndex(index + 1);
        }
    }

    /**
     * Syncs the {@link TrackStrip#selectedProperty()} with the ListView's
     * selection state so the strip's {@code :selected} pseudo-class (and
     * its {@code -accent-soft} background) tracks cell selection correctly.
     */
    @Override
    public void updateSelected(boolean selected) {
        super.updateSelected(selected);
        strip.setSelected(selected);
    }

    @Override
    protected void updateItem(Track item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            strip.setSelected(false);
            return;
        }
        // Rebind the recycled strip to the new track. The strip is
        // reused, so this is plain setter assignment — NOT a fresh
        // `new TrackStrip()`. The cell-reuse contract (see class doc)
        // depends on this.
        strip.setTrackId(item.id());
        strip.setTrackIndex(getIndex() >= 0 ? getIndex() + 1 : 1);
        strip.setTrackName(item.name());
        // The Track record doesn't currently carry a colour; fall back
        // to the strip's current swatch so the cell can be themed
        // through a separate property if the model grows one.
        if (strip.getTrackColor() == null) {
            strip.setTrackColor(Color.web("#7C8CFF"));
        }
        strip.setMuted(item.muted());
        strip.setSoloed(item.solo());
        strip.setArmed(item.armed());
        setGraphic(strip);
    }
}
