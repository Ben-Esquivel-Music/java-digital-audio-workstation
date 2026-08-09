package com.benesquivelmusic.daw.app.ui.dialogs;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

import java.util.Objects;

/**
 * Construction helpers for content-owned {@link DawgDialog} hosts.
 *
 * <p>JavaFX refuses to close a {@code Dialog<Void>} whose result is
 * {@code null} unless its pane contains a cancel-data button. A host whose
 * content owns the visible footer therefore carries one hidden
 * {@link ButtonType#CANCEL}: it is lifecycle plumbing for Esc, the window
 * close request, the header close glyph, and programmatic {@code close()}.
 * It is never another visible action.</p>
 */
public final class DialogDismissibility {

    private DialogDismissibility() {
        // utility class
    }

    /**
     * Installs one invisible, unmanaged cancel-data button.
     *
     * <p>{@link DawgDialog} initially owns a fully wired header close glyph.
     * Adding a cancel-data button normally retracts it because a visible
     * footer Cancel makes the glyph redundant. This helper captures and
     * restores that same node after hiding the plumbing button, preserving
     * the glyph's existing action, accessibility text, and style without
     * weakening the visible-footer rule.</p>
     *
     * @param dialog the content-owned host; must not be {@code null}
     * @return the installed hidden button
     */
    public static Button installHiddenCancel(DawgDialog<?> dialog) {
        Objects.requireNonNull(dialog, "dialog must not be null");
        Node contentOwnedCloseGlyph = dialog.getGraphic();

        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button hiddenCancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        hiddenCancel.setVisible(false);
        hiddenCancel.setManaged(false);

        if (contentOwnedCloseGlyph != null && dialog.getGraphic() == null) {
            dialog.setGraphic(contentOwnedCloseGlyph);
        }
        return hiddenCancel;
    }
}
