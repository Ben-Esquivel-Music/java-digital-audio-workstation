package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.ui.NotificationLevel;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

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
 * @param threadName the failing thread's raw name, which may be empty; for a real-time report, the guard
 *                   site that recorded it (story 337, ruling E43)
 * @param failure    the original throwable, carriers included, which the log writes with its whole chain;
 *                   empty only for a real-time fact recorded without one — {@code audio.config} (story 337,
 *                   ruling E25)
 */
public record FailureFact(Origin origin, Severity severity, String category, Fingerprint fingerprint,
                          String message, long count, Instant first, Instant last,
                          String threadName, Optional<Throwable> failure) {

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
        DECLARING_SITE,
        /** A guard on a real-time render path recorded it into the audio health record (story 337). */
        RT_GUARD,
        /** A native upcall's guard — a device callback or a device notification — counted it (story 337). */
        NATIVE_UPCALL,
        /** The audio worker pool counted a task that threw (story 337, ruling E27). */
        WORKER_POOL,
        /** A unit of work of the FX pulse threw — a participant, keyed work or a channel drain (story 337). */
        PULSE_DRAIN,
        /** An event bus consumer threw while an event was delivered to it (story 337, ruling E38). */
        BUS_DELIVERY
    }

    /** Book §3.1 severity: story 336 produces FATAL and ERROR, story 337 adds WARNING. */
    public enum Severity {
        /** The application may not work correctly from now on. */
        FATAL,
        /** Something failed; the application carries on. */
        ERROR,
        /**
         * Something failed and was contained: a first real-time occurrence, or a
         * consumer the pulse or the bus isolated (story 337, ruling E43).
         */
        WARNING;

        /**
         * The toast level for this severity (book §5.4 "Level fits severity"):
         * {@link NotificationLevel#WARNING} for WARNING (story 337, ruling E43);
         * {@link NotificationLevel#ERROR} for FATAL and ERROR, because the level
         * enum has no FATAL ({@code NotificationLevel.java:13-25}).
         *
         * @return the toast level
         */
        public NotificationLevel notificationLevel() {
            return this == WARNING ? NotificationLevel.WARNING : NotificationLevel.ERROR;
        }

        /**
         * The level a record of this severity is logged at: {@link Level#WARNING}
         * for WARNING (story 337, ruling E43), {@link Level#SEVERE} otherwise.
         *
         * @return the log level
         */
        public Level logLevel() {
            return this == WARNING ? Level.WARNING : Level.SEVERE;
        }
    }
}
