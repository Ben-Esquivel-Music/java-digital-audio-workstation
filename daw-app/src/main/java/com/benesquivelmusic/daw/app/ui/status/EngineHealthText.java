package com.benesquivelmusic.daw.app.ui.status;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The user-facing strings of the engine's health (story 338): the session
 * status strip's Engine and Xruns cells and the xrun log, read from the
 * application's resource bundle ({@code javafx-application-design} §14).
 *
 * <p>Every key is read through {@link MessageFormat} at {@link Locale#ROOT},
 * so the bundle writes an apostrophe twice; numbers and times are formatted
 * for {@link Locale#ROOT} too, so a figure reads the same on every machine.
 * A key with a plural form takes its count twice: as a number, which chooses
 * the form, and as the text shown, without grouping.</p>
 */
public final class EngineHealthText {

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private static final DateTimeFormatter MILLISECONDS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private EngineHealthText() {
    }

    /**
     * Formats the message {@code key} with {@code arguments}.
     *
     * @param key       the bundle key
     * @param arguments the message arguments, in order
     * @return the formatted message, or the key itself when the bundle lacks it
     */
    public static String text(String key, Object... arguments) {
        String pattern;
        try {
            pattern = MESSAGES.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
        return new MessageFormat(pattern, Locale.ROOT).format(arguments);
    }

    /**
     * {@code time} as {@code hh:mm:ss.mmm} in {@code zone}: an xrun log entry's time (ruling E20).
     *
     * @param time the time
     * @param zone the zone shown
     * @return the time of day, to the millisecond
     */
    public static String toTheMillisecond(Instant time, ZoneId zone) {
        return MILLISECONDS.format(time.atZone(zone));
    }

    /**
     * {@code time} as {@code hh:mm:ss} in {@code zone} (rulings E23 and E27).
     *
     * @param time the time
     * @param zone the zone shown
     * @return the time of day, to the second
     */
    public static String toTheSecond(Instant time, ZoneId zone) {
        return SECONDS.format(time.atZone(zone));
    }

    /**
     * {@code time} as {@code hh:mm} in {@code zone}: the spoken time (ruling E23).
     *
     * @param time the time
     * @param zone the zone shown
     * @return the time of day, to the minute
     */
    public static String toTheMinute(Instant time, ZoneId zone) {
        return MINUTES.format(time.atZone(zone));
    }

    /**
     * {@code seconds} as {@code m:ss.s}: the transport position of an xrun
     * log entry (ruling E20), in tenths, rounded down.
     *
     * @param seconds the position, in seconds; a negative one reads as 0
     * @return the position
     */
    public static String position(double seconds) {
        long tenths = (long) Math.floor(Math.max(0.0, seconds) * 10.0);
        return (tenths / 600) + ":" + String.format(Locale.ROOT, "%02d.%d", (tenths % 600) / 10, tenths % 10);
    }

    /**
     * {@code millis} with one decimal: a late block's time and period (ruling E20).
     *
     * @param millis the milliseconds
     * @return the milliseconds, one decimal
     */
    public static String milliseconds(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }
}
