package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds all automation lanes for a single track.
 *
 * <p>Automation lanes are stored in two separate maps: mixer-channel parameter
 * lanes are keyed by {@link AutomationParameter} (an {@link EnumMap} for
 * efficiency), and plugin parameter lanes are keyed by
 * {@link PluginParameterTarget} (a {@link LinkedHashMap} so insertion order is
 * preserved for the UI).</p>
 *
 * <p>Use {@link #getOrCreateLane(AutomationParameter)} for the built-in
 * mixer parameters and {@link #getOrCreatePluginLane(PluginParameterTarget)}
 * for plugin parameters. Each parameter/target has at most one lane.</p>
 */
public final class AutomationData {

    private final Map<AutomationParameter, AutomationLane> lanes =
            new EnumMap<>(AutomationParameter.class);

    private final Map<PluginParameterTarget, AutomationLane> pluginLanes =
            new LinkedHashMap<>();

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
     * Returns an unmodifiable view of all mixer-channel automation lanes.
     *
     * @return a map of parameter to lane
     */
    public Map<AutomationParameter, AutomationLane> getLanes() {
        return Collections.unmodifiableMap(lanes);
    }

    /**
     * Returns the number of mixer-channel lanes. Plugin-parameter lanes are
     * reported separately by {@link #getPluginLaneCount()}.
     */
    public int getLaneCount() {
        return lanes.size();
    }

    /**
     * Returns whether the given parameter has an active automation lane with
     * at least one point. A lane with zero points is not considered active.
     *
     * @param parameter the parameter to check
     * @return {@code true} if automation data exists for this parameter
     */
    @RealTimeSafe
    public boolean hasActiveAutomation(AutomationParameter parameter) {
        AutomationLane lane = lanes.get(parameter);
        return lane != null && lane.getPointCount() > 0;
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
    @RealTimeSafe
    public double getValueAtTime(AutomationParameter parameter, double timeInBeats) {
        AutomationLane lane = lanes.get(parameter);
        if (lane == null) {
            return parameter.getDefaultValue();
        }
        return lane.getValueAtTime(timeInBeats);
    }

    // ── Plugin parameter lanes ─────────────────────────────────────────────

    /**
     * Returns the plugin-parameter lane for the given target, or {@code null}
     * if no lane exists.
     *
     * @param target the plugin parameter target
     * @return the lane, or {@code null}
     */
    public AutomationLane getPluginLane(PluginParameterTarget target) {
        return pluginLanes.get(target);
    }

    /**
     * Returns the lane for the given plugin-parameter target, creating one if
     * it does not already exist.
     *
     * @param target the plugin parameter target
     * @return the existing or newly created lane
     */
    public AutomationLane getOrCreatePluginLane(PluginParameterTarget target) {
        return pluginLanes.computeIfAbsent(target, AutomationLane::new);
    }

    /**
     * Removes the lane for the given plugin parameter target.
     *
     * @param target the plugin parameter target whose lane should be removed
     * @return the removed lane, or {@code null} if none existed
     */
    public AutomationLane removePluginLane(PluginParameterTarget target) {
        return pluginLanes.remove(target);
    }

    /**
     * Returns an unmodifiable view of all plugin-parameter automation lanes.
     *
     * @return a map of plugin parameter target to lane
     */
    public Map<PluginParameterTarget, AutomationLane> getPluginLanes() {
        return Collections.unmodifiableMap(pluginLanes);
    }

    /** Returns the number of plugin-parameter lanes that have been created. */
    public int getPluginLaneCount() {
        return pluginLanes.size();
    }

    /**
     * Returns whether the given plugin-parameter target has a lane with at
     * least one point.
     *
     * @param target the plugin parameter target
     * @return {@code true} if automation data exists for this target
     */
    @RealTimeSafe
    public boolean hasActiveAutomation(PluginParameterTarget target) {
        AutomationLane lane = pluginLanes.get(target);
        return lane != null && lane.getPointCount() > 0;
    }

    /**
     * Returns the automated value for the given plugin-parameter target at
     * the specified time. If no lane exists, the target's default value is
     * returned.
     *
     * @param target      the plugin parameter target to query
     * @param timeInBeats the time position in beats
     * @return the automation value
     */
    @RealTimeSafe
    public double getValueAtTime(PluginParameterTarget target, double timeInBeats) {
        AutomationLane lane = pluginLanes.get(target);
        if (lane == null) {
            return target.getDefaultValue();
        }
        return lane.getValueAtTime(timeInBeats);
    }
}
