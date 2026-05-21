package com.benesquivelmusic.daw.app.ui.motion;

import java.util.Optional;

/**
 * A mockable seam for detecting the operating system's "reduce motion"
 * accessibility preference (story 279).
 *
 * <p>{@link MotionManager} seeds its initial {@code reduceMotion} value
 * from this hint when no in-app preference has been persisted yet (the
 * very first launch). The seam exists so {@code OsHintDetectionTest} can
 * inject a deterministic mock instead of probing the real platform — the
 * production constructor uses {@link PlatformMotionHint}, the test
 * constructor accepts any {@code OsMotionHint}.</p>
 *
 * <p>The result is an {@link Optional}: {@code Optional.empty()} means
 * the OS preference could <em>not</em> be determined (an unsupported
 * platform, a detection error, or — on macOS — the absence of an
 * Objective-C runtime call path). A present value is the OS's actual
 * choice: {@code true} = the user has asked the OS to reduce motion,
 * {@code false} = motion is allowed.</p>
 */
@FunctionalInterface
public interface OsMotionHint {

    /**
     * Detects the OS-level reduce-motion preference.
     *
     * <p>Implementations must never throw — any detection failure is
     * reported as {@link Optional#empty()} rather than an exception, so
     * {@link MotionManager} construction can never be broken by a
     * platform probe.</p>
     *
     * @return {@code Optional.of(true)} if the OS requests reduced
     *         motion, {@code Optional.of(false)} if motion is allowed,
     *         or {@code Optional.empty()} if the preference cannot be
     *         determined on this platform
     */
    Optional<Boolean> reduceMotionPreferred();
}
