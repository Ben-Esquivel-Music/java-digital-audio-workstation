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

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Manages the tempo label's double-click-to-edit behavior, including
 * committing the new tempo through the undo manager.
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of UI-specific tempo editing logic.</p>
 *
 * <p>Story 293 — the former {@code Host} callback-up interface is retired
 * (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §9). Collaborators arrive directly:
 * live {@link Supplier}s for the swappable project/undo manager (read fresh
 * on every access — this controller is init-only and is not reconstructed on
 * project load), a {@code syncMenuState} action, a {@code statusBar} sink, and
 * the {@link NotificationBar}.</p>
 */
final class TempoEditController {

    private final Label tempoLabel;
    private final Supplier<DawProject> project;
    private final Supplier<UndoManager> undoManager;
    private final Runnable syncMenuState;
    private final BiConsumer<String, DawIcon> statusBar;
    private final NotificationBar notificationBar;

    TempoEditController(Label tempoLabel,
                        Supplier<DawProject> project,
                        Supplier<UndoManager> undoManager,
                        Runnable syncMenuState,
                        BiConsumer<String, DawIcon> statusBar,
                        NotificationBar notificationBar) {
        this.tempoLabel = Objects.requireNonNull(tempoLabel, "tempoLabel must not be null");
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.syncMenuState = Objects.requireNonNull(syncMenuState, "syncMenuState must not be null");
        this.statusBar = Objects.requireNonNull(statusBar, "statusBar must not be null");
        this.notificationBar = Objects.requireNonNull(
                notificationBar, "notificationBar must not be null");
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
        TextField editor = new TextField(String.format("%.1f", project.get().getTransport().getTempo()));
        // .tempo-editor supplies the surface/border/sizing; .numeric-value
        // supplies the mono 12 px / 500 typography (story 266 / §3.2).
        editor.getStyleClass().addAll("tempo-editor", "numeric-value");
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
            double oldTempo = project.get().getTransport().getTempo();
            if (Double.compare(newTempo, oldTempo) != 0) {
                undoManager.get().execute(new UndoableAction() {
                    @Override public String description() { return String.format("Set Tempo to %.1f BPM", newTempo); }
                    @Override public void execute() { project.get().getTransport().setTempo(newTempo); }
                    @Override public void undo() { project.get().getTransport().setTempo(oldTempo); }
                });
                syncMenuState.run();
            }
            statusBar.accept(String.format("Tempo set to %.1f BPM", newTempo), DawIcon.METRONOME);
        } catch (IllegalArgumentException e) {
            statusBar.accept("Invalid tempo \u2014 must be 20\u2013999 BPM", DawIcon.ALERT);
            notificationBar.show(NotificationLevel.ERROR, "Invalid tempo \u2014 must be 20\u2013999 BPM");
        }
        // Story 293: the tempo label binds TransportVM.tempo \u2014 no imperative
        // updateTempoDisplay() to forward.
        hbox.getChildren().set(index, tempoLabel);
    }
}
