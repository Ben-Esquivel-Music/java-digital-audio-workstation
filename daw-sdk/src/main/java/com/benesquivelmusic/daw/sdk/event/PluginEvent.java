package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Plugin lifecycle event hierarchy.
 *
 * <p>Emitted when a plugin instance is loaded, unloaded, bypassed
 * (toggled), has a parameter changed, or crashes (the host catches a
 * fault from the plugin's processing or UI thread). Consumers locate
 * the plugin via {@link #pluginInstanceId()} and read its current
 * descriptor from the project snapshot.</p>
 */
public sealed interface PluginEvent extends DawEvent
        permits PluginEvent.Loaded,
                PluginEvent.Unloaded,
                PluginEvent.Bypassed,
                PluginEvent.ParameterChanged,
                PluginEvent.Crashed {

    /** Returns the id of the affected plugin instance. */
    UUID pluginInstanceId();

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when a plugin instance is loaded into the audio graph.
     *
     * @param pluginInstanceId id of the loaded plugin instance
     * @param timestamp        wall-clock instant of the event
     */
    record Loaded(UUID pluginInstanceId, Instant timestamp) implements PluginEvent {
        public Loaded {
            Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a plugin instance is removed from the audio graph.
     *
     * @param pluginInstanceId id of the unloaded plugin instance
     * @param timestamp        wall-clock instant of the event
     */
    record Unloaded(UUID pluginInstanceId, Instant timestamp) implements PluginEvent {
        public Unloaded {
            Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a plugin instance's bypass flag changes.
     *
     * @param pluginInstanceId id of the affected plugin instance
     * @param bypassed         the new bypass state
     * @param timestamp        wall-clock instant of the event
     */
    record Bypassed(UUID pluginInstanceId, boolean bypassed, Instant timestamp) implements PluginEvent {
        public Bypassed {
            Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a plugin parameter value changes.
     *
     * @param pluginInstanceId id of the affected plugin instance
     * @param parameterId      id of the parameter that changed; must not be null
     * @param timestamp        wall-clock instant of the event
     */
    record ParameterChanged(UUID pluginInstanceId, String parameterId, Instant timestamp)
            implements PluginEvent {
        public ParameterChanged {
            Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
            Objects.requireNonNull(parameterId, "parameterId must not be null");
            if (parameterId.isBlank()) {
                throw new IllegalArgumentException("parameterId must not be blank");
            }
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the host catches a fault from a plugin's processing
     * or UI thread and isolates the instance.
     *
     * @param pluginInstanceId id of the crashed plugin instance
     * @param reason           a short human-readable description of the fault; must not be null
     * @param timestamp        wall-clock instant of the event
     */
    record Crashed(UUID pluginInstanceId, String reason, Instant timestamp) implements PluginEvent {
        public Crashed {
            Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
