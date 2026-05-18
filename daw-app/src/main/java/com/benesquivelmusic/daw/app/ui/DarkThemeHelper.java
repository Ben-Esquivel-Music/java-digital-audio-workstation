package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;

/**
 * Thin {@code @Deprecated} shim that delegates to {@link ThemeManager}
 * (story 277, UI Design Book §6 Phase 3).
 *
 * <p>Originally this utility only applied the single dark stylesheet.
 * Phase-3 theming makes {@link ThemeManager} the single source of "the
 * ordered stylesheet URL list to apply" (base {@code styles.css} + the
 * active theme overlay). Routing every dialog and standalone-window
 * call site through {@code ThemeManager.getDefault()} means all of them
 * — including {@code DawgDialog} and the ~30 legacy dialogs that still
 * call {@code DarkThemeHelper.applyTo(this)} — re-theme for free when
 * the user switches theme, with zero churn at the call sites.</p>
 *
 * <p>This shim is retained for one release cycle so the existing
 * callers keep compiling; new code should call
 * {@link ThemeManager#getDefault()} directly. The public method
 * signatures are unchanged.</p>
 *
 * @deprecated since 0.1.0, scheduled for removal after the 0.2.0
 *             release cycle — use {@link ThemeManager} (via
 *             {@link ThemeManager#getDefault()}) — this shim only
 *             forwards to it.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public final class DarkThemeHelper {

    private DarkThemeHelper() {
        // utility class
    }

    /**
     * Returns the external-form URL of the application's base
     * (Palette-A) stylesheet.
     *
     * @return the base stylesheet URL string
     * @deprecated since 0.1.0, scheduled for removal — use
     *             {@link ThemeManager#baseStylesheetUrl()}
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public static String getStylesheetUrl() {
        return ThemeManager.getDefault().baseStylesheetUrl();
    }

    /**
     * Applies the active theme (base {@code styles.css} + overlay) to a
     * {@link Dialog}'s pane and registers it for live re-theming.
     *
     * @param dialog the dialog to style
     * @deprecated since 0.1.0, scheduled for removal — use
     *             {@code ThemeManager.getDefault().applyTo(dialog.getDialogPane())}
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public static void applyTo(Dialog<?> dialog) {
        ThemeManager.getDefault().applyTo(dialog.getDialogPane());
    }

    /**
     * Applies the active theme (base {@code styles.css} + overlay) to a
     * {@link Scene} and registers it for live re-theming.
     *
     * @param scene the scene to style
     * @deprecated since 0.1.0, scheduled for removal — use
     *             {@code ThemeManager.getDefault().applyTo(scene)}
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public static void applyTo(Scene scene) {
        ThemeManager.getDefault().applyTo(scene);
    }
}
