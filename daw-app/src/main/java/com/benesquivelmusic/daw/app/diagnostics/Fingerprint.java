package com.benesquivelmusic.daw.app.diagnostics;

import java.util.Objects;
import java.util.Optional;

/**
 * The dedupe key of a failure (story 336; Failure Surfacing book §3.1): with
 * a declared subject,
 * {@code category|subject|exceptionClass}; without one,
 * {@code category|exceptionClass|anchor}; for a real-time guard site
 * ({@link #ofSite}, story 337), {@code category|site}.
 *
 * <p>The anchor is {@code declaringClass.method:line} of the first frame,
 * counting from the throw site, that is host code (a class name starting
 * with {@code com.benesquivelmusic.}) or plugin code (a frame with no module
 * name that is not host code: the unnamed module of a plugin JAR's class
 * loader); with no such frame, frame 0; with no frames at all, {@code -}.
 * The raw throw site alone would give every {@code Objects.requireNonNull}
 * failure one fingerprint, and the second of two unrelated bugs would be
 * silently counted under the first.</p>
 *
 * @param category         the category slug
 * @param subject          the subject the failing site declared, if any
 * @param exceptionClass   the binary name of the unwrapped throwable's class
 * @param anchor           the anchor frame, or {@code -}
 * @param anchoredInPlugin whether the anchor is a plugin frame; {@code FailureFunnel.severityOf} decides when
 *                         that grades an {@code ExceptionInInitializerError} or {@code UnsatisfiedLinkError}
 *                         ERROR rather than FATAL
 */
public record Fingerprint(String category, Optional<String> subject, String exceptionClass,
                          String anchor, boolean anchoredInPlugin) {

    /** The prefix of every host class name. */
    static final String HOST_PREFIX = "com.benesquivelmusic.";

    /** The anchor when the throwable has no frames. */
    static final String NO_ANCHOR = "-";

    public Fingerprint {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(exceptionClass, "exceptionClass must not be null");
        Objects.requireNonNull(anchor, "anchor must not be null");
    }

    /**
     * The fingerprint of {@code unwrapped} in {@code category}.
     *
     * @param category  the category slug
     * @param subject   the subject the failing site declared, if any
     * @param unwrapped the throwable the carriers held ({@link GuardedThrowables#unwrapCarriers})
     * @return its fingerprint; reading the throwable never throws, whatever its frame array holds —
     *         {@link GuardedThrowables#frames} drops {@code null} elements
     */
    public static Fingerprint of(String category, Optional<String> subject, Throwable unwrapped) {
        Objects.requireNonNull(unwrapped, "unwrapped must not be null");
        StackTraceElement[] frames = GuardedThrowables.frames(unwrapped);
        StackTraceElement anchorFrame = null;
        boolean inPlugin = false;
        for (StackTraceElement frame : frames) {
            if (isHost(frame)) {
                anchorFrame = frame;
                break;
            }
            if (isPlugin(frame)) {
                anchorFrame = frame;
                inPlugin = true;
                break;
            }
        }
        if (anchorFrame == null && frames.length > 0) {
            anchorFrame = frames[0];
        }
        String anchor = anchorFrame == null ? NO_ANCHOR : anchorOf(anchorFrame);
        return new Fingerprint(category, subject, GuardedThrowables.className(unwrapped), anchor, inPlugin);
    }

    /**
     * The fingerprint of a real-time guard site, or of a one-shot subject,
     * in {@code category} (story 337, ruling E40): the audio health drain
     * counts every failure one guard site recorded under one fingerprint,
     * whatever was thrown, because its counters are exact while its stack
     * samples are not. The key is {@code category|site}; there is no
     * exception class and no anchor.
     *
     * @param category the category slug, for example {@code audio.callback}
     * @param site     the guard site, for example {@code device-callback}, or the one-shot subject's key
     * @return the fingerprint
     */
    public static Fingerprint ofSite(String category, String site) {
        Objects.requireNonNull(site, "site must not be null");
        return new Fingerprint(category, Optional.of(site), "", NO_ANCHOR, false);
    }

    /**
     * The key the rate limiter counts by.
     *
     * @return {@code category|site} for a {@linkplain #ofSite site fingerprint} (no exception class);
     *         {@code category|subject|exceptionClass} with a declared subject; else
     *         {@code category|exceptionClass|anchor}
     */
    public String key() {
        if (exceptionClass.isEmpty()) {
            return category + "|" + subject.orElse(NO_ANCHOR);
        }
        return subject.map(declared -> category + "|" + declared + "|" + exceptionClass)
                .orElseGet(() -> category + "|" + exceptionClass + "|" + anchor);
    }

    /** {@return the key} */
    @Override
    public String toString() {
        return key();
    }

    private static boolean isHost(StackTraceElement frame) {
        String className = frame.getClassName();
        return className != null && className.startsWith(HOST_PREFIX);
    }

    private static boolean isPlugin(StackTraceElement frame) {
        return frame.getModuleName() == null && !isHost(frame);
    }

    private static String anchorOf(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }
}
