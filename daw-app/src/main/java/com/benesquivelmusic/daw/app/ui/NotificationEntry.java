package com.benesquivelmusic.daw.app.ui;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable record of a single notification event, stored in the
 * notification history for later review.
 *
 * @param timestamp the instant the notification was shown
 * @param level     the notification severity level
 * @param message   the notification message text
 */
public record NotificationEntry(Instant timestamp, NotificationLevel level, String message) {

    public NotificationEntry {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
