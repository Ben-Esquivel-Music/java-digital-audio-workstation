package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/**
 * Manages the tempo label's double-click-to-edit behavior, including
 * committing the new tempo through the undo manager.
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of UI-specific tempo editing logic.</p>
 */
final class TempoEditController {

    interface Host {
        DawProject project();
        UndoManager undoManager();
        void updateUndoRedoState();
        void updateTempoDisplay();
        void updateStatusBar(String text, DawIcon icon);
        void showNotification(NotificationLevel level, String message);
    }

    private final Label tempoLabel;
    private final Host host;

    TempoEditController(Label tempoLabel, Host host) {
        this.tempoLabel = tempoLabel;
        this.host = host;
    }

    void install() {
        tempoLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                startEdit();
            }
        });
        tempoLabel.setTooltip(new Tooltip("Double-click to edit tempo"));
    }

    private void startEdit() {
        Parent parent = tempoLabel.getParent();
        if (!(parent instanceof HBox hbox)) return;
        int index = hbox.getChildren().indexOf(tempoLabel);
        if (index < 0) return;
        TextField editor = new TextField(String.format("%.1f", host.project().getTransport().getTempo()));
        editor.getStyleClass().add("tempo-editor");
        editor.setPrefWidth(80);
        editor.setOnAction(_ -> commit(editor, hbox, index));
        editor.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) commit(editor, hbox, index);
        });
        hbox.getChildren().set(index, editor);
        editor.requestFocus();
        editor.selectAll();
    }

    private void commit(TextField editor, HBox hbox, int index) {
        try {
            double newTempo = Double.parseDouble(editor.getText().strip());
            double oldTempo = host.project().getTransport().getTempo();
            if (Double.compare(newTempo, oldTempo) != 0) {
                host.undoManager().execute(new UndoableAction() {
                    @Override public String description() { return String.format("Set Tempo to %.1f BPM", newTempo); }
                    @Override public void execute() { host.project().getTransport().setTempo(newTempo); }
                    @Override public void undo() { host.project().getTransport().setTempo(oldTempo); }
                });
                host.updateUndoRedoState();
            }
            host.updateStatusBar(String.format("Tempo set to %.1f BPM", newTempo), DawIcon.METRONOME);
        } catch (IllegalArgumentException e) {
            host.updateStatusBar("Invalid tempo \u2014 must be 20\u2013999 BPM", DawIcon.ALERT);
            host.showNotification(NotificationLevel.ERROR, "Invalid tempo \u2014 must be 20\u2013999 BPM");
        }
        host.updateTempoDisplay();
        hbox.getChildren().set(index, tempoLabel);
    }
}
