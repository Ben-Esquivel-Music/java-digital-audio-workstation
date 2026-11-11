package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;

import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The keyboard stop of an offline insert's row on the editing rack and in the
 * Inspector (story 334, ruling R-N1): the row's state mark, made a button that
 * stands where a working row's own stop stands. Assistive technology reads it
 * as {@code <name>, offline}, with why the insert is offline and what to do as
 * its help; Enter, Space, a click or an invoke shows that text under it, and a
 * second press, Escape or the focus leaving hides it. It opens no editor and
 * changes nothing (ruling E3). The caller adds its text or glyph.
 */
public final class OfflineInsertMark {

    private OfflineInsertMark() {
    }

    /**
     * @param insertName   the insert's name
     * @param details      the row's tooltip: why the insert is offline and what to do
     * @param styleClasses the button's style classes, which replace {@code button}: the
     *                     application's {@code .button} rule would repaint the surface's
     *                     warn or muted role and its size
     * @return the mark
     */
    public static Button create(String insertName, String details, String... styleClasses) {
        Objects.requireNonNull(insertName, "insertName must not be null");
        Objects.requireNonNull(details, "details must not be null");
        Button mark = new Button();
        mark.getStyleClass().setAll(styleClasses);
        mark.setAccessibleText(OfflineInsertText.accessible(insertName));
        mark.setAccessibleHelp(details);
        Tooltip shown = new Tooltip(details);
        shown.setAutoHide(true);
        mark.setOnAction(_ -> {
            if (shown.isShowing()) {
                shown.hide();
                return;
            }
            Bounds bounds = mark.localToScreen(mark.getBoundsInLocal());
            if (bounds != null) {
                shown.show(mark, bounds.getMinX(), bounds.getMaxY());
            }
        });
        // Escape hides the text the mark shows and goes no further: neither the
        // Inspector's collapse nor the transport's Stop sees it.
        mark.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && shown.isShowing()) {
                shown.hide();
                event.consume();
            }
        });
        mark.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                shown.hide();
            }
        });
        return mark;
    }
}
