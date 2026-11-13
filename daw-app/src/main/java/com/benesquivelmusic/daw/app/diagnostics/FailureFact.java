package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.ui.NotificationLevel;

import java.time.Instant;
import java.util.Objects;

/**
 * The one failure fact both the log record and the toast are built from
 * (story 336; Failure Surfacing book §3.1): where the failure was caught, how
 * serious it is, its category and fingerprint, the base user-facing sentence,
 * how often its fingerprint has occurred and when, the failing thread's name,
 * and the original throwable.
 *
 * @param origin     which boundary caught it
 * @param severity   how serious it is ({@code FailureFunnel.severityOf})
 * @param category   the category slug, for example {@code ui.handler}; the slug never appears in user copy
 * @param fingerprint the dedupe key
 * @param message    the base user-facing sentence ({@link FailureCopy#base}), never the slug
 * @param count      the occurrences of the fingerprint since it was first seen; at least 1
 * @param first      when the fingerprint was first seen (wall clock)
 * @param last       when this occurrence happened (wall clock)
 * @param threadName the failing thread's raw name, which may be empty
 * @param failure    the original throwable, carriers included; the log writes its whole chain
 */
public record FailureFact(Origin origin, Severity severity, String category, Fingerprint fingerprint,
                          String message, long count, Instant first, Instant last,
                          String threadName, Throwable failure) {

    public FailureFact {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(last, "last must not be null");
        Objects.requireNonNull(threadName, "threadName must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1: " + count);
        }
    }

    /** Which boundary caught the failure (book §3.1 "origin"). Later stages add their own origins. */
    public enum Origin {
        /** The JavaFX Application Thread's own uncaught-exception handler. */
        FX_HANDLER,
        /** The process-wide default uncaught-exception handler. */
        DEFAULT_HANDLER,
        /** A site that declared its subject and reported the failure itself ({@link DeclaredSubject}). */
        DECLARING_SITE
    }

    /** Book §3.1 severity; this story produces only these two (later stages add WARNING and INFO). */
    public enum Severity {
        /** The application may not work correctly from now on. */
        FATAL,
        /** Something failed; the application carries on. */
        ERROR;

        /**
         * The toast level for this severity (book §5.4 "Level fits severity"): both are
         * {@link NotificationLevel#ERROR}, because the level enum has no FATAL
         * ({@code NotificationLevel.java:13-25}).
         *
         * @return {@link NotificationLevel#ERROR}
         */
        public NotificationLevel notificationLevel() {
            return NotificationLevel.ERROR;
        }
    }
}
