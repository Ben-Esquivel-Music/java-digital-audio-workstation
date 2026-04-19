package com.benesquivelmusic.daw.core.plugin;

import java.time.Instant;

/**
 * Immutable record of a fault thrown by a plugin during audio processing.
 *
 * <p>Published on the {@link PluginInvocationSupervisor} event stream whenever
 * a plugin's {@code process}/{@code processDouble} call throws — the host
 * zeroes the output and bypasses the slot, then emits one of these for the UI
 * and the persistent fault log.</p>
 *
 * @param pluginId             stable identifier for the plugin (usually the slot name or
 *                             {@link com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor#id()})
 * @param exceptionClass       fully-qualified name of the thrown exception
 * @param message              exception message (may be {@code null}/empty)
 * @param stackTrace           formatted stack trace captured off the audio thread
 * @param clock                timestamp at which the fault was detected
 * @param faultCountThisSession total faults recorded for this plugin in the current session
 * @param quarantined          {@code true} when the fault exceeded the session threshold
 *                             and the slot is being held bypassed
 */
public record PluginFault(
        String pluginId,
        String exceptionClass,
        String message,
        String stackTrace,
        Instant clock,
        int faultCountThisSession,
        boolean quarantined
) {
}
