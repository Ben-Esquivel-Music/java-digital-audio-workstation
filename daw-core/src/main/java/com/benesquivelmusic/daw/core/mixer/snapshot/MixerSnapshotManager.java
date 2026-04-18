package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.Mixer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages the collection of saved {@link MixerSnapshot}s for a project,
 * together with two dedicated A/B recall slots used for single-key mix
 * comparison during playback.
 *
 * <p>Up to {@value #MAX_SNAPSHOTS} general snapshots may be stored per
 * project. The A and B slots are independent of the general list — either
 * may hold a snapshot, and {@link #toggleAB(Mixer)} flips between them,
 * applying the newly selected snapshot to the mixer and leaving the project
 * state reversible through the undo stack when invoked via
 * {@link com.benesquivelmusic.daw.core.mixer.snapshot.RecallSnapshotAction}.</p>
 */
public final class MixerSnapshotManager {

    /** Maximum number of snapshots that may be stored per project. */
    public static final int MAX_SNAPSHOTS = 32;

    /** Identifies one of the two dedicated A/B recall slots. */
    public enum Slot { A, B }

    private final List<MixerSnapshot> snapshots = new ArrayList<>();
    private MixerSnapshot slotA;
    private MixerSnapshot slotB;
    private Slot activeSlot = Slot.A;

    /**
     * Adds a new snapshot to the project.
     *
     * @param snapshot the snapshot to add
     * @throws IllegalStateException if the project already holds
     *         {@value #MAX_SNAPSHOTS} snapshots
     */
    public void addSnapshot(MixerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshots.size() >= MAX_SNAPSHOTS) {
            throw new IllegalStateException(
                    "cannot exceed " + MAX_SNAPSHOTS + " snapshots");
        }
        snapshots.add(snapshot);
    }

    /**
     * Removes the snapshot at the given index.
     *
     * @param index the zero-based snapshot index
     * @return the removed snapshot
     */
    public MixerSnapshot removeSnapshot(int index) {
        return snapshots.remove(index);
    }

    /**
     * Removes the given snapshot.
     *
     * @param snapshot the snapshot to remove
     * @return {@code true} if the snapshot was removed
     */
    public boolean removeSnapshot(MixerSnapshot snapshot) {
        return snapshots.remove(snapshot);
    }

    /** Returns an unmodifiable view of the saved snapshots, in insertion order. */
    public List<MixerSnapshot> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    /** Returns the number of saved snapshots. */
    public int getSnapshotCount() {
        return snapshots.size();
    }

    // ── A/B slots ───────────────────────────────────────────────────────────

    /** Returns the snapshot currently stored in the A slot, or {@code null}. */
    public MixerSnapshot getSlotA() {
        return slotA;
    }

    /** Returns the snapshot currently stored in the B slot, or {@code null}. */
    public MixerSnapshot getSlotB() {
        return slotB;
    }

    /** Returns the snapshot stored in the given slot, or {@code null}. */
    public MixerSnapshot getSlot(Slot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        return slot == Slot.A ? slotA : slotB;
    }

    /**
     * Stores a snapshot in the given A/B slot. Pass {@code null} to clear the slot.
     *
     * @param slot     the slot to set
     * @param snapshot the snapshot to store, or {@code null} to clear
     */
    public void setSlot(Slot slot, MixerSnapshot snapshot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (slot == Slot.A) {
            slotA = snapshot;
        } else {
            slotB = snapshot;
        }
    }

    /** Returns which A/B slot is currently considered active. */
    public Slot getActiveSlot() {
        return activeSlot;
    }

    /** Sets the active A/B slot without applying it to any mixer. */
    public void setActiveSlot(Slot slot) {
        this.activeSlot = Objects.requireNonNull(slot, "slot must not be null");
    }

    /**
     * Toggles between the A and B slots, applying the newly active slot's
     * snapshot to the given mixer. If the target slot is empty, the active
     * slot is flipped but the mixer is left unchanged.
     *
     * @param mixer the mixer to update
     * @return the snapshot that was applied, or {@code null} if the target slot was empty
     */
    public MixerSnapshot toggleAB(Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        activeSlot = (activeSlot == Slot.A) ? Slot.B : Slot.A;
        MixerSnapshot next = getSlot(activeSlot);
        if (next != null) {
            next.applyTo(mixer);
        }
        return next;
    }
}
