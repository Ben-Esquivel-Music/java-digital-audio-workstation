package com.benesquivelmusic.daw.app.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The user-facing strings of the close protocol, Save As and the takeover
 * flow (story 333; the studio engineer's rulings R1-R12 and the answers to
 * the design's questions): the close prompts and their buttons, the closing
 * progress surface's steps and notes, the doors' "Stop waiting" offer, the
 * takeover dialog, the lock indicator's tooltip and Save As's progress,
 * refusals and outcomes — read from the application's resource bundle
 * (javafx-application-design skill §14, "Accessibility, internationalization,
 * and quality"). The bundle's root values are the ruled wording, character
 * for character: typographic quotes around every quoted name, the ellipsis
 * and the em dash as printed, apostrophes ASCII.
 *
 * <p>Every key is read through {@link MessageFormat} at {@link Locale#ROOT}
 * (the {@code ProjectAudioText} idiom), so the bundle writes an apostrophe
 * twice; every argument is passed as a {@code String}, already formatted, so
 * nothing is grouped or localised a second time.</p>
 */
final class ProjectCloseText {

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private ProjectCloseText() {
    }

    /**
     * Formats the message {@code key} with {@code arguments}.
     *
     * @param key       the bundle key
     * @param arguments the message arguments, in order, already formatted
     * @return the formatted message, or the key itself when the bundle lacks it
     */
    static String text(String key, String... arguments) {
        String pattern;
        try {
            pattern = MESSAGES.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
        return new MessageFormat(pattern, Locale.ROOT).format(arguments);
    }
}
