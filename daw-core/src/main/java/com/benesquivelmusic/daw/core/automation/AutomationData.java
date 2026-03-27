package com.benesquivelmusic.daw.core.automation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Holds all automation lanes for a single track.
 *
 * <p>Each {@link AutomationParameter} has at most one lane. Lanes are created
 * on demand via {@link #getOrCreateLane(AutomationParameter)} and can be
 * queried via {@link #getLane(AutomationParameter)}.</p>
 */
public final class AutomationData {

    private final Map<AutomationParameter, AutomationLane> lanes =
            new EnumMap<>(AutomationParameter.class);

    /**
     * Returns the lane for the given parameter, or {@code null} if no lane
     * exists for that parameter.
     *
     * @param parameter the parameter
     * @return the lane, or {@code null}
     */
    public AutomationLane getLane(AutomationParameter parameter) {
        return lanes.get(parameter);
    }

    /**
     * Returns the lane for the given parameter, creating one if it does not
     * already exist.
     *
     * @param parameter the parameter
     * @return the existing or newly created lane
     */
    public AutomationLane getOrCreateLane(AutomationParameter parameter) {
        return lanes.computeIfAbsent(parameter, AutomationLane::new);
    }

    /**
     * Removes the lane for the given parameter.
     *
     * @param parameter the parameter whose lane should be removed
     * @return the removed lane, or {@code null} if none existed
     */
    public AutomationLane removeLane(AutomationParameter parameter) {
        return lanes.remove(parameter);
    }

    /**
     * Returns an unmodifiable view of all automation lanes.
     *
     * @return a map of parameter to lane
     */
    public Map<AutomationParameter, AutomationLane> getLanes() {
        return Collections.unmodifiableMap(lanes);
    }

    /** Returns the number of lanes that have been created. */
    public int getLaneCount() {
        return lanes.size();
    }

    /**
     * Returns the automated value for the given parameter at the specified
     * time. If no lane exists for the parameter, the parameter's default
     * value is returned.
     *
     * @param parameter   the parameter to query
     * @param timeInBeats the time position in beats
     * @return the automation value
     */
    public double getValueAtTime(AutomationParameter parameter, double timeInBeats) {
        AutomationLane lane = lanes.get(parameter);
        if (lane == null) {
            return parameter.getDefaultValue();
        }
        return lane.getValueAtTime(timeInBeats);
    }
}
