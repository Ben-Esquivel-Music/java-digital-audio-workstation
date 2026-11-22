package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthText;

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Objects;

/**
 * The xrun log (story 338, rulings E20, E22 and E24): the
 * take's dropout figures — "This take: …" while a take records, "Last take:
 * …" after it, until the next take or Clear — and the newest lines of the
 * log, each read out by its own cell. Clear, a button in the body, forgets
 * the log, restarts the xrun count and forgets the last take's figures; the
 * dialog stays open.
 *
 * <p>Non-modal and owned by the main window, so the person keeps working
 * while it is open; one instance per window, built when first opened and
 * re-presented by every later open. Its list shows the window's
 * {@link EngineHealthStatus} log itself, so it grows while it is open. The
 * footer's Close only hides it.</p>
 *
 * <p>FX thread.</p>
 */
final class XrunLogDialog extends DawgDialog<Void> {

    private final Label take = new Label();
    private final ListView<String> entries;
    private final Button clear = new Button(EngineHealthText.text("xrunLog.clear"));

    /**
     * Creates the log over {@code status}, owned by {@code owner}. Not shown
     * until {@link #present()}.
     *
     * @param owner  the main window, or {@code null} when there is none
     * @param status the window's engine-health model
     * @param onClear the log's Clear: the journal, the count and the last take's figures
     */
    XrunLogDialog(Window owner, EngineHealthStatus status, Runnable onClear) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(onClear, "onClear must not be null");
        setTitle(EngineHealthText.text("xrunLog.title"));
        setHeaderText(EngineHealthText.text("xrunLog.title"));
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        sized(Size.MEDIUM);
        getDialogPane().getStyleClass().add("xrun-log-dialog");

        take.getStyleClass().addAll("dawg-dialog-message", "numeric-value", "xrun-log-take");
        take.setWrapText(true);
        take.textProperty().bind(Bindings.createStringBinding(() -> takeLine(status),
                status.takeDropoutsProperty()));
        take.accessibleTextProperty().bind(take.textProperty());
        addSection(EngineHealthText.text("xrunLog.section.take"), take);

        entries = new ListView<>(status.xrunLog());
        entries.getStyleClass().add("xrun-log-entries");
        entries.setPlaceholder(new Label(EngineHealthText.text("xrunLog.placeholder")));
        entries.setCellFactory(_ -> new EntryCell());
        clear.getStyleClass().addAll("dawg-button", "size-default", "secondary", "xrun-log-clear");
        clear.setAccessibleText(EngineHealthText.text("xrunLog.clear.accessible"));
        clear.setOnAction(_ -> onClear.run());
        addSection(EngineHealthText.text("xrunLog.section.entries"), new VBox(8, entries, clear));

        secondary(EngineHealthText.text("xrunLog.close"), null);
        Node close = closeButton();
        if (close != null) {
            close.setAccessibleText(EngineHealthText.text("xrunLog.close.accessible"));
        }
    }

    /** Shows the log, or brings it to the front when it already shows. */
    void present() {
        if (!isShowing()) {
            show();
            return;
        }
        Window window = getDialogPane().getScene() == null ? null : getDialogPane().getScene().getWindow();
        if (window instanceof Stage stage) {
            stage.toFront();
        }
        if (window != null) {
            window.requestFocus();
        }
    }

    /** @return the take's figures line, as shown */
    Label takeLabel() {
        return take;
    }

    /** @return the list of the log's lines */
    ListView<String> entryList() {
        return entries;
    }

    /** @return the Clear button */
    Button clearButton() {
        return clear;
    }

    /** "This take: K dropouts (B blocks lost, F frames truncated)", or "Last take: …" (ruling E24). */
    private static String takeLine(EngineHealthStatus status) {
        return status.takeDropoutsProperty().get().map(figures -> EngineHealthText.text(
                        figures.recording() ? "xrunLog.take.this" : "xrunLog.take.last",
                        figures.dropouts(), Long.toString(figures.dropouts()),
                        figures.blocksLost(), Long.toString(figures.blocksLost()),
                        figures.framesTruncated(), Long.toString(figures.framesTruncated())))
                .orElseGet(() -> EngineHealthText.text("xrunLog.take.none"));
    }

    private Node closeButton() {
        for (ButtonType type : getDialogPane().getButtonTypes()) {
            if (type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) {
                return getDialogPane().lookupButton(type);
            }
        }
        return null;
    }

    /** A log line, read out as it is written. */
    private static final class EntryCell extends ListCell<String> {

        @Override
        protected void updateItem(String line, boolean empty) {
            super.updateItem(line, empty);
            String shown = empty || line == null ? null : line;
            setText(shown);
            setAccessibleText(shown);
        }
    }
}
