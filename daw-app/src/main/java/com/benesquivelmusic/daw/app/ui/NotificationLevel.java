package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;

/**
 * Notification severity levels for the toast notification system.
 *
 * <p>Each level carries a distinct CSS style class, an icon from the DAW icon pack,
 * and an auto-dismiss duration. Higher-severity levels persist longer to ensure
 * the user has time to read the message.</p>
 */
public enum NotificationLevel {

    /** Successful operation (green). Auto-dismisses after 3 seconds. */
    SUCCESS("notification-success", DawIcon.SUCCESS, 3_000),

    /** Informational message (blue). Auto-dismisses after 3 seconds. */
    INFO("notification-info", DawIcon.INFO_CIRCLE, 3_000),

    /** Warning that deserves attention (orange). Auto-dismisses after 5 seconds. */
    WARNING("notification-warning", DawIcon.WARNING, 5_000),

    /** Error that requires acknowledgment (red). Auto-dismisses after 7 seconds. */
    ERROR("notification-error", DawIcon.ERROR, 7_000);

    private final String styleClass;
    private final DawIcon icon;
    private final long autoDismissMillis;

    NotificationLevel(String styleClass, DawIcon icon, long autoDismissMillis) {
        this.styleClass = styleClass;
        this.icon = icon;
        this.autoDismissMillis = autoDismissMillis;
    }

    /** Returns the CSS style class applied to the notification bar for this level. */
    public String styleClass() {
        return styleClass;
    }

    /** Returns the icon displayed in the notification for this level. */
    public DawIcon icon() {
        return icon;
    }

    /** Returns the auto-dismiss duration in milliseconds. */
    public long autoDismissMillis() {
        return autoDismissMillis;
    }
}
