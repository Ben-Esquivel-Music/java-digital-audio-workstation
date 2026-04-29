package com.benesquivelmusic.daw.core.concurrent;

/**
 * Coarse-grained category that tells {@link DawTaskRunner} which
 * executor a {@link DawTask} should run on.
 *
 * <h2>Routing rules</h2>
 * <ul>
 *   <li><b>I/O-bound work</b> ({@link #IMPORT}, {@link #EXPORT},
 *       {@link #AUTOSAVE}, {@link #SCAN}, {@link #ANALYSIS}) runs on
 *       the virtual-thread-per-task executor (JEP 444). Each task gets
 *       its own virtual thread; no pool sizing, no bounded queue.</li>
 *   <li><b>Short CPU-bound work</b> ({@link #COMPUTE}) runs on a
 *       bounded platform thread pool sized to
 *       {@code Runtime.getRuntime().availableProcessors()} so it does
 *       not oversubscribe the CPU.</li>
 * </ul>
 *
 * <p>Realtime audio callbacks must <strong>never</strong> use this
 * runner — virtual threads are not for deadline-critical work.</p>
 */
public enum TaskCategory {
    /** Reading audio / project files from disk (I/O-bound). */
    IMPORT,
    /** Writing audio / project / bundle files to disk (I/O-bound). */
    EXPORT,
    /** Periodic project autosave / checkpoint serialization (I/O-bound). */
    AUTOSAVE,
    /** Background directory / file scans, e.g. for the browser panel (I/O-bound). */
    SCAN,
    /** Offline analysis: peaks, spectrum, loudness (mostly I/O-bound). */
    ANALYSIS,
    /** Short, bursty CPU-bound work that should not oversubscribe the CPU. */
    COMPUTE
}
