package com.benesquivelmusic.daw.core.plugin.parameter;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages A/B comparison between two parameter states.
 *
 * <p>Users can toggle between state A and state B to audition the difference
 * between two sets of parameter values. The active state is applied to the
 * underlying {@link PluginParameterState}, while the inactive state is
 * preserved in a snapshot.</p>
 */
public final class ABComparison {

    /** Identifies which state slot is currently active. */
    public enum Slot { A, B }

    private final PluginParameterState state;
    private Map<Integer, Double> snapshotA;
    private Map<Integer, Double> snapshotB;
    private Slot activeSlot;

    /**
     * Creates a new A/B comparison manager.
     *
     * <p>Both A and B are initialized to the current state values.</p>
     *
     * @param state the parameter state to manage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public ABComparison(PluginParameterState state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.snapshotA = state.getAllValues();
        this.snapshotB = state.getAllValues();
        this.activeSlot = Slot.A;
    }

    /**
     * Returns the currently active slot.
     *
     * @return the active slot
     */
    public Slot getActiveSlot() {
        return activeSlot;
    }

    /**
     * Toggles between A and B.
     *
     * <p>Saves the current state into the active slot, switches to the
     * other slot, and loads its saved values into the state.</p>
     */
    public void toggle() {
        if (activeSlot == Slot.A) {
            snapshotA = state.getAllValues();
            state.loadValues(snapshotB);
            activeSlot = Slot.B;
        } else {
            snapshotB = state.getAllValues();
            state.loadValues(snapshotA);
            activeSlot = Slot.A;
        }
    }

    /**
     * Copies the current state into the inactive slot.
     *
     * <p>After this call, both A and B contain the same parameter values.</p>
     */
    public void copyActiveToInactive() {
        Map<Integer, Double> current = state.getAllValues();
        if (activeSlot == Slot.A) {
            snapshotA = current;
            snapshotB = current;
        } else {
            snapshotB = current;
            snapshotA = current;
        }
    }

    /**
     * Returns a snapshot of the values stored in the given slot.
     *
     * <p>If the slot is currently active, returns the live state values.</p>
     *
     * @param slot the slot to query
     * @return the parameter values for the slot
     */
    public Map<Integer, Double> getSlotValues(Slot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (slot == activeSlot) {
            return state.getAllValues();
        }
        return slot == Slot.A ? Map.copyOf(snapshotA) : Map.copyOf(snapshotB);
    }
}
