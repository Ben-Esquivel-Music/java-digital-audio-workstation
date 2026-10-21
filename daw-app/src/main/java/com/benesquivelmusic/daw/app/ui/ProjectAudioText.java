package com.benesquivelmusic.daw.app.ui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The user-facing strings of the open project's audio (story 329): the open
 * announcement and its follow-up, the loading progress, the clip
 * placeholders and the missing-assets report, read from the application's
 * resource bundle (UI Skill §14).
 *
 * <p>Every key is read through {@link MessageFormat} at {@link Locale#ROOT},
 * so the bundle writes an apostrophe twice. A count key takes the count
 * twice ({@link #count}): the first argument chooses the plural form, the
 * second is the count as shown, without grouping.</p>
 */
final class ProjectAudioText {

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private ProjectAudioText() {
    }

    /**
     * Formats the message {@code key} with {@code arguments}.
     *
     * @param key       the bundle key
     * @param arguments the message arguments, in order
     * @return the formatted message, or the key itself when the bundle lacks it
     */
    static String text(String key, Object... arguments) {
        String pattern;
        try {
            pattern = MESSAGES.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
        return new MessageFormat(pattern, Locale.ROOT).format(arguments);
    }

    /**
     * Formats {@code seconds} with two decimals in the default format
     * locale, without the unit — the figure a missing-assets row's detail
     * gives for the same seconds (decision D24's rule).
     *
     * @param seconds the seconds
     * @return the formatted seconds
     */
    static String seconds(double seconds) {
        return new DecimalFormat("0.00",
                DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT))).format(seconds);
    }

    /**
     * Formats the count message {@code key} for {@code count}, followed by
     * {@code more} arguments ({@code {2}}, {@code {3}}, …).
     *
     * @param key   the bundle key of a count message
     * @param count the count; chooses the plural form
     * @param more  further arguments
     * @return the formatted message
     */
    static String count(String key, int count, Object... more) {
        Object[] arguments = new Object[2 + more.length];
        arguments[0] = count;
        arguments[1] = Integer.toString(count);
        System.arraycopy(more, 0, arguments, 2, more.length);
        return text(key, arguments);
    }
}
