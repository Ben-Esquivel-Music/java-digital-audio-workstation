package com.benesquivelmusic.daw.app.ui;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable record of a single notification event, stored in the
 * notification history for later review (story 273 — the history is the
 * single log feeding both the transient toast and the inspector
 * Notifications section).
 *
 * @param timestamp   the instant the notification was shown
 * @param level       the notification severity level
 * @param message     the notification message text
 * @param action      an optional action to re-trigger from the history
 *                     pill ("Configure input", "Show details", "Undo", …)
 * @param actionLabel an optional label for {@code action}; when both are
 *                     present the history pill renders a borderless
 *                     action button
 */
public record NotificationEntry(Instant timestamp,
                                NotificationLevel level,
                                String message,
                                Optional<Runnable> action,
                                Optional<String> actionLabel) {

    public NotificationEntry {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");
        // Defensive: an explicitly-passed null Optional becomes empty so
        // callers/tests built around the legacy 3-arg shape stay safe.
        action = action == null ? Optional.empty() : action;
        actionLabel = actionLabel == null ? Optional.empty() : actionLabel;
    }

    /**
     * Convenience constructor for a notification with no action affordance.
     * Preserves the legacy three-argument call shape used across existing
     * call sites and tests.
     */
    public NotificationEntry(Instant timestamp, NotificationLevel level, String message) {
        this(timestamp, level, message, Optional.empty(), Optional.empty());
    }
}
