package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;

/**
 * Application-layer abstraction over the user-visible notification surface
 * (toast, system tray balloon, in-app banner). Kept intentionally tiny so
 * tests can substitute a recording double, and so production wiring can
 * choose between JavaFX-based notifications and OS-native notifiers
 * without changing call sites.
 */
@FunctionalInterface
public interface NotificationManager {

    /**
     * Shows {@code message} to the user. Implementations must not block the
     * audio or device-event thread; they should hand the message to the UI
     * thread and return immediately.
     *
     * @param message human-readable message to display; must not be null
     */
    void notify(String message);

    /**
     * Returns a {@link NotificationManager} that drops every message — useful
     * as a default when no UI is wired in (tests, headless CLI tools).
     *
     * @return a no-op notification manager
     */
    static NotificationManager noop() {
        return message -> Objects.requireNonNull(message, "message must not be null");
    }
}
