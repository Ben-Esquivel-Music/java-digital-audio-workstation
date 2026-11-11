package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.core.plugin.PluginResolution;

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
 *
 * <p>It also names a plugin's exception the one way every error the insert
 * rack, the plugin menu and the install panel build from one does
 * ({@link #failureName}).</p>
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

    /**
     * Names {@code failure} in an error the engineer reads (story 334, PR #990
     * review, ruling R-C3.4; studio-signal-flow "Diagnosis and result": name
     * the first thing that went wrong) by the one rule the plugin loader uses
     * too ({@link PluginResolution.FailedToLoad#messageOrName}): its message
     * when it has one that is not blank, else its class's simple name, else —
     * an anonymous class, or one whose simple name cannot be read — its full
     * class name, so a missing message never shows as {@code null}.
     *
     * @param failure the exception to name
     * @return the text that names it
     */
    public static String failureName(Throwable failure) {
        return PluginResolution.FailedToLoad.messageOrName(failure);
    }
}
