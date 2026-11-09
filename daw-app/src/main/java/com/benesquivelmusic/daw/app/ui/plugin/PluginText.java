package com.benesquivelmusic.daw.app.ui.plugin;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The user-facing strings of the installed-plugin registry (story 334,
 * studio-engineer rulings E9, E20, G4, G5 and G11; critique J6): the startup
 * and save-failure notifications, the Manage Plugins list and the install
 * panel's refusals and replacement rows, read from the application's resource
 * bundle (UI Skill §14).
 *
 * <p>Every key is read through {@link MessageFormat} at {@link Locale#ROOT}
 * (as {@code ProjectAudioText} reads the open's strings), so the bundle writes
 * an apostrophe twice. A count key takes the count twice: the first argument
 * chooses the plural form, the second is the count as shown, without
 * grouping.</p>
 */
public final class PluginText {

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private PluginText() {
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
}
