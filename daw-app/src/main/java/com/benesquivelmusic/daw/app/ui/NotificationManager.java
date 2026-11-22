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
     * Shows {@code message} to the user with one action the user can choose,
     * for example "Reconnect". The same threading rule as
     * {@link #notify(String)} applies; the action runs on the UI thread when
     * chosen. The default shows the message alone, for a surface that offers
     * no actions.
     *
     * @param message     human-readable message to display; must not be null
     * @param actionLabel the action's label; must not be null
     * @param action      what the action does; must not be null
     */
    default void notify(String message, String actionLabel, Runnable action) {
        Objects.requireNonNull(actionLabel, "actionLabel must not be null");
        Objects.requireNonNull(action, "action must not be null");
        notify(message);
    }

    /**
     * Shows {@code message} at the information level rather than as a
     * warning: news that needs no action, such as an external clock that
     * locked again (story 338, ruling E110). The same threading rule as
     * {@link #notify(String)} applies. The default shows it as
     * {@link #notify(String)} does, for a surface that has no levels.
     *
     * @param message human-readable message to display; must not be null
     */
    default void notifyInformation(String message) {
        notify(message);
    }

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
