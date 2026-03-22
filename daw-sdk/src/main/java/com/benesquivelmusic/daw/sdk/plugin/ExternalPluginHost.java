package com.benesquivelmusic.daw.sdk.plugin;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.List;

/**
 * Host interface for external native audio plugins (CLAP, LV2).
 *
 * <p>Extends both {@link DawPlugin} (lifecycle) and {@link AudioProcessor}
 * (real-time audio processing) to represent a native plugin loaded via
 * the Foreign Function &amp; Memory API (JEP 454). Implementations manage
 * the full lifecycle of the underlying native plugin instance, including
 * memory allocation, parameter control, state persistence, and latency
 * reporting.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #getDescriptor()} — obtain plugin metadata</li>
 *   <li>{@link #initialize(PluginContext)} — allocate native resources</li>
 *   <li>{@link #activate()} — prepare the plugin for audio processing</li>
 *   <li>{@link #process(float[][], float[][], int)} — real-time audio callback</li>
 *   <li>{@link #deactivate()} — stop audio processing</li>
 *   <li>{@link #dispose()} — free all native resources</li>
 * </ol>
 *
 * <h2>Parameter Control</h2>
 * <p>Use {@link #getParameters()} to discover the plugin's controllable
 * parameters, then {@link #getParameterValue(int)} and
 * {@link #setParameterValue(int, double)} to read/write parameter values.</p>
 *
 * <h2>State Persistence</h2>
 * <p>{@link #saveState()} and {@link #loadState(byte[])} allow the host
 * to save and restore the complete plugin state (including all parameter
 * values and internal configuration) for session persistence.</p>
 */
public interface ExternalPluginHost extends DawPlugin, AudioProcessor {

    /**
     * Returns the external plugin format (e.g., CLAP, LV2).
     *
     * @return the plugin format, never {@code null}
     */
    ExternalPluginFormat getFormat();

    /**
     * Returns all parameters exposed by this plugin.
     *
     * @return an unmodifiable list of parameter descriptors, never {@code null}
     */
    List<PluginParameter> getParameters();

    /**
     * Returns the current value of the specified parameter.
     *
     * @param parameterId the parameter identifier
     * @return the current parameter value
     * @throws IllegalArgumentException if no parameter with the given id exists
     */
    double getParameterValue(int parameterId);

    /**
     * Sets the value of the specified parameter.
     *
     * @param parameterId the parameter identifier
     * @param value       the new value (must be within the parameter's min/max range)
     * @throws IllegalArgumentException if no parameter with the given id exists
     *                                  or the value is outside the allowed range
     */
    void setParameterValue(int parameterId, double value);

    /**
     * Returns the processing latency introduced by this plugin, in samples.
     *
     * <p>The host uses this value for delay compensation across parallel
     * signal paths. A return value of {@code 0} indicates no additional
     * latency.</p>
     *
     * @return latency in sample frames, always &ge; 0
     */
    int getLatencySamples();

    /**
     * Saves the complete plugin state as an opaque byte array.
     *
     * <p>The returned bytes can later be passed to {@link #loadState(byte[])}
     * to restore the plugin to this exact configuration.</p>
     *
     * @return the serialized state, or an empty array if the plugin does
     *         not support state persistence
     */
    byte[] saveState();

    /**
     * Restores the plugin state from a previously saved byte array.
     *
     * @param state the serialized state obtained from {@link #saveState()}
     * @throws IllegalArgumentException if the state data is invalid or corrupt
     */
    void loadState(byte[] state);
}
