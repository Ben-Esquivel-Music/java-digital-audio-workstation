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
import java.util.function.BooleanSupplier;
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
 *
 * <p>A commit — Enter or the focus leaving the editor — is refused while the
 * window's edit gate says no, whatever raised it: the tempo is not written,
 * nothing is reported, and the label, still showing the stored tempo, takes
 * the editor's place again (PR #989 review 5711460812).</p>
 *
 * <p>A commit whose text is not a tempo from 20 to 999 BPM — text that does
 * not parse, an out-of-range number, NaN or an infinity — is invalid input:
 * the tempo is not written, no history entry is added, and the status bar
 * and an ERROR notification say so (story 334, studio ruling E10.5).</p>
 */
final class TempoEditController {

    private final Label tempoLabel;
    private final Supplier<DawProject> project;
    private final Supplier<UndoManager> undoManager;
    private final Runnable syncMenuState;
    private final BiConsumer<String, DawIcon> statusBar;
    private final NotificationBar notificationBar;
    private final BooleanSupplier projectActionsAllowed;

    TempoEditController(Label tempoLabel,
                        Supplier<DawProject> project,
                        Supplier<UndoManager> undoManager,
                        Runnable syncMenuState,
                        BiConsumer<String, DawIcon> statusBar,
                        NotificationBar notificationBar,
                        BooleanSupplier projectActionsAllowed) {
        this.tempoLabel = Objects.requireNonNull(tempoLabel, "tempoLabel must not be null");
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.syncMenuState = Objects.requireNonNull(syncMenuState, "syncMenuState must not be null");
        this.statusBar = Objects.requireNonNull(statusBar, "statusBar must not be null");
        this.notificationBar = Objects.requireNonNull(
                notificationBar, "notificationBar must not be null");
        this.projectActionsAllowed = Objects.requireNonNull(
                projectActionsAllowed, "projectActionsAllowed must not be null");
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
        if (!projectActionsAllowed.getAsBoolean()) {
            hbox.getChildren().set(index, tempoLabel);
            return;
        }
        try {
            double newTempo = Double.parseDouble(editor.getText().strip());
            // NaN and the infinities parse; the transport refuses them as it
            // refuses an out-of-range tempo (story 334, studio ruling E10.5),
            // so they are invalid input here, before any history entry.
            if (!(newTempo >= 20.0 && newTempo <= 999.0)) {
                throw new IllegalArgumentException("tempo out of range");
            }
            if (setTempo(project.get(), undoManager.get(), projectActionsAllowed, newTempo)) {
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

    /**
     * One "Set Tempo to %.1f BPM" history step; the transport bar's edit and
     * an edited default tempo (story 335, studio ruling R4.3) share it. It
     * writes only the beat-0 BPM, as one undoable step whose undo restores the
     * prior BPM, and nothing while the window's edit gate says no or when the
     * tempo is already {@code newTempo}.
     *
     * @param project               the open project
     * @param history               its undo history
     * @param projectActionsAllowed the window's edit gate
     * @param newTempo              the tempo, 20 to 999 BPM
     * @return whether the step was written
     */
    static boolean setTempo(DawProject project, UndoManager history, BooleanSupplier projectActionsAllowed,
                            double newTempo) {
        if (!projectActionsAllowed.getAsBoolean()) {
            return false;
        }
        double oldTempo = project.getTransport().getTempo();
        if (Double.compare(newTempo, oldTempo) == 0) {
            return false;
        }
        history.execute(new UndoableAction() {
            @Override public String description() { return String.format("Set Tempo to %.1f BPM", newTempo); }
            @Override public void execute() { project.getTransport().setTempo(newTempo); }
            @Override public void undo() { project.getTransport().setTempo(oldTempo); }
        });
        return true;
    }
}
