package com.benesquivelmusic.daw.app.ui.dialogs;

import javafx.geometry.Rectangle2D;

/**
 * Test-only access to {@link DawgDialog}'s package-private screen-cap seam
 * for tests outside {@code ui.dialogs} (story 335 review, 2b-conformance S3 /
 * engineer S3): the Settings dialog lives in {@code ui}, and its reachability
 * is checked against the ruled 1280&nbsp;×&nbsp;720 class of screen, which the
 * headless platform's 1000&nbsp;×&nbsp;1000 screen does not provide.
 */
public final class DawgDialogCapSeam {

    private DawgDialogCapSeam() {
    }

    /**
     * Caps {@code dialog} against {@code bounds} instead of its owner's
     * screen; {@code null} restores the real screen.
     *
     * @param dialog the dialog
     * @param bounds the visual bounds to cap against, or {@code null}
     */
    public static void capBounds(DawgDialog<?> dialog, Rectangle2D bounds) {
        dialog.capBounds(bounds);
    }
}
