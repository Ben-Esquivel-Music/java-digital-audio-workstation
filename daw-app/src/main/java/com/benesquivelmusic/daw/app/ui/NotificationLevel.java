package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;

/**
 * Notification severity levels for the toast notification system.
 *
 * <p>Each level carries a distinct CSS style class and an icon from the
 * DAW icon pack. The transient toast ({@code NotificationBar}) uses a
 * flat auto-dismiss duration for all levels per UI Design Book §5.10
 * (story 273), so the level no longer carries a per-level timing.</p>
 */
public enum NotificationLevel {

    /** Successful operation (green). */
    SUCCESS("notification-success", DawIcon.SUCCESS),

    /** Informational message (blue). */
    INFO("notification-info", DawIcon.INFO_CIRCLE),

    /** Warning that deserves attention (orange). */
    WARNING("notification-warning", DawIcon.WARNING),

    /** Error that requires acknowledgment (red). */
    ERROR("notification-error", DawIcon.ERROR);

    private final String styleClass;
    private final DawIcon icon;

    NotificationLevel(String styleClass, DawIcon icon) {
        this.styleClass = styleClass;
        this.icon = icon;
    }

    /** Returns the CSS style class applied to the notification bar for this level. */
    public String styleClass() {
        return styleClass;
    }

    /** Returns the icon displayed in the notification for this level. */
    public DawIcon icon() {
        return icon;
    }
}
