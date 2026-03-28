package com.benesquivelmusic.daw.core.performance;

/**
 * Callback interface for receiving performance warnings from the
 * {@link PerformanceMonitor}.
 *
 * <p>Implementations should be lightweight — the callback may be invoked
 * from the audio thread when a warning condition is first detected.</p>
 */
@FunctionalInterface
public interface PerformanceWarningListener {

    /**
     * Called when a performance warning condition is detected or cleared.
     *
     * @param metrics the current performance metrics snapshot that triggered
     *                the warning (or cleared a previous warning)
     */
    void onPerformanceWarning(PerformanceMetrics metrics);
}
