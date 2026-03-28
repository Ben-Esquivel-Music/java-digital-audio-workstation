package com.benesquivelmusic.daw.app.ui;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

import java.net.URL;
import java.util.Objects;

/**
 * Utility class for applying the application's dark theme stylesheet
 * consistently to dialogs and standalone windows.
 *
 * <p>JavaFX dialogs and secondary stages do not automatically inherit the
 * main scene's stylesheets. This helper resolves the shared stylesheet
 * URL once and provides convenience methods to apply it to any
 * {@link Dialog} or {@link Scene}.</p>
 */
public final class DarkThemeHelper {

    private static final String STYLESHEET_PATH = "styles.css";

    private static volatile String resolvedStylesheetUrl;

    private DarkThemeHelper() {
        // utility class
    }

    /**
     * Returns the external-form URL of the application's dark theme
     * stylesheet, resolving it lazily on first access.
     *
     * @return the stylesheet URL string
     * @throws IllegalStateException if the stylesheet resource cannot be found
     */
    public static String getStylesheetUrl() {
        String url = resolvedStylesheetUrl;
        if (url == null) {
            URL resource = DarkThemeHelper.class.getResource(STYLESHEET_PATH);
            Objects.requireNonNull(resource,
                    "Dark theme stylesheet not found: " + STYLESHEET_PATH);
            url = resource.toExternalForm();
            resolvedStylesheetUrl = url;
        }
        return url;
    }

    /**
     * Applies the dark theme stylesheet to a {@link Dialog}'s pane.
     *
     * <p>This should be called at the end of every dialog constructor
     * to ensure the dialog inherits the application's dark neon theme.</p>
     *
     * @param dialog the dialog to style
     */
    public static void applyTo(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        String url = getStylesheetUrl();
        if (!pane.getStylesheets().contains(url)) {
            pane.getStylesheets().add(url);
        }
        if (!pane.getStyleClass().contains("root-pane")) {
            pane.getStyleClass().add("root-pane");
        }
    }

    /**
     * Applies the dark theme stylesheet to a {@link Scene}.
     *
     * <p>Use this for standalone floating windows (e.g., spectrum analyzer,
     * loudness meter) that are not part of the main application scene.</p>
     *
     * @param scene the scene to style
     */
    public static void applyTo(Scene scene) {
        String url = getStylesheetUrl();
        if (!scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }
}
