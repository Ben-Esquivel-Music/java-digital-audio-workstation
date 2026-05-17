package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;

/**
 * Notification severity levels for the toast notification system.
 *
 * <p>Each level carries a distinct CSS style class and an icon from the DAW
 * icon pack. A per-level {@link #autoDismissMillis()} hint is retained for
 * callers that want severity-scaled timing, but the transient toast
 * ({@code NotificationBar}) uses a flat duration for all levels per UI
 * Design Book §5.10 (story 273) and does not consult this value.</p>
 */
public enum NotificationLevel {

    /** Successful operation (green); 3 s severity hint. */
    SUCCESS("notification-success", DawIcon.SUCCESS, 3_000),

    /** Informational message (blue); 3 s severity hint. */
    INFO("notification-info", DawIcon.INFO_CIRCLE, 3_000),

    /** Warning that deserves attention (orange); 5 s severity hint. */
    WARNING("notification-warning", DawIcon.WARNING, 5_000),

    /** Error that requires acknowledgment (red); 7 s severity hint. */
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

    /**
     * Returns the per-level severity-timing hint in milliseconds. Not
     * consulted by the transient toast, which is flat per §5.10 (story
     * 273); retained for callers that want severity-scaled timing.
     */
    public long autoDismissMillis() {
        return autoDismissMillis;
    }
}
