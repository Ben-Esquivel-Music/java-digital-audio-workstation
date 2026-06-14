package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.vm.command.HistoryCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.RedoCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.UndoCommand;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Binds the undo/redo controls — toolbar buttons and Edit-menu items — to a
 * {@link HistoryVM} and routes their gestures to {@link HistoryCommand}s (Control
 * Synchronization Design Book §6.9, §4.4; story 292). It replaces the hand-pushed
 * {@code MainController.updateUndoRedoState()} that poked both surfaces from one
 * place (§1.2): each control now <em>binds</em> the VM instead.
 *
 * <ul>
 *   <li>A control's {@code disable} state is a pure function of the VM
 *       ({@code !canUndo} / {@code !canRedo}) — the button greys out and the menu
 *       item disables automatically, no poke required.</li>
 *   <li>A menu item's text and a button's tooltip follow the VM's next-undo /
 *       next-redo label (e.g. "Undo Add Track").</li>
 *   <li>A click raises a {@link UndoCommand} / {@link RedoCommand} through the
 *       shared {@link Consumer}&lt;{@link HistoryCommand}&gt; sink (§2.8 — the
 *       same intent as {@code Ctrl+Z} and the menu), never a write-back.</li>
 * </ul>
 *
 * <p>{@link #dispose()} removes every binding and handler so no leak survives the
 * control's lifetime ({@code javafx-application-design} §3/§4).</p>
 */
public final class HistoryControlBinder {

    private final HistoryVM vm;
    private final Consumer<HistoryCommand> commandSink;
    private final List<Runnable> disposers = new ArrayList<>();

    /**
     * Creates a binder over {@code vm} that emits commands into {@code commandSink}.
     *
     * @param vm          the history view-model the controls observe; must not be {@code null}
     * @param commandSink where control gestures are dispatched; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public HistoryControlBinder(HistoryVM vm, Consumer<HistoryCommand> commandSink) {
        this.vm = Objects.requireNonNull(vm, "vm must not be null");
        this.commandSink = Objects.requireNonNull(commandSink, "commandSink must not be null");
    }

    /**
     * Binds an Undo button: {@code disable} follows {@code !canUndo}, its tooltip
     * follows the next-undo label, and a click raises {@link UndoCommand}.
     *
     * @param undo the undo button; must not be {@code null}
     */
    public void bindUndoButton(ButtonBase undo) {
        bindButton(undo, vm.canUndoProperty(), vm.undoLabelProperty(), "Undo", new UndoCommand());
    }

    /**
     * Binds a Redo button: {@code disable} follows {@code !canRedo}, its tooltip
     * follows the next-redo label, and a click raises {@link RedoCommand}.
     *
     * @param redo the redo button; must not be {@code null}
     */
    public void bindRedoButton(ButtonBase redo) {
        bindButton(redo, vm.canRedoProperty(), vm.redoLabelProperty(), "Redo", new RedoCommand());
    }

    /**
     * Binds an Undo menu item: {@code disable} follows {@code !canUndo}, its text
     * follows the next-undo label ("Undo" / "Undo &lt;description&gt;"), and a click
     * raises {@link UndoCommand}.
     *
     * @param undo the Edit &rarr; Undo menu item; must not be {@code null}
     */
    public void bindUndoMenuItem(MenuItem undo) {
        bindMenuItem(undo, vm.canUndoProperty(), vm.undoLabelProperty(), "Undo", new UndoCommand());
    }

    /**
     * Binds a Redo menu item: {@code disable} follows {@code !canRedo}, its text
     * follows the next-redo label, and a click raises {@link RedoCommand}.
     *
     * @param redo the Edit &rarr; Redo menu item; must not be {@code null}
     */
    public void bindRedoMenuItem(MenuItem redo) {
        bindMenuItem(redo, vm.canRedoProperty(), vm.redoLabelProperty(), "Redo", new RedoCommand());
    }

    private void bindButton(ButtonBase button, ReadOnlyBooleanProperty available,
                            ReadOnlyStringProperty label, String verb, HistoryCommand command) {
        Objects.requireNonNull(button, "button must not be null");
        button.disableProperty().bind(available.not());
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(Bindings.createStringBinding(
                () -> labelText(verb, label.get()), label));
        button.setTooltip(tip);
        button.setOnAction(_ -> commandSink.accept(command));
        disposers.add(() -> {
            button.disableProperty().unbind();
            tip.textProperty().unbind();
            button.setTooltip(null);
            button.setOnAction(null);
        });
    }

    private void bindMenuItem(MenuItem item, ReadOnlyBooleanProperty available,
                              ReadOnlyStringProperty label, String verb, HistoryCommand command) {
        Objects.requireNonNull(item, "item must not be null");
        item.disableProperty().bind(available.not());
        item.textProperty().bind(Bindings.createStringBinding(
                () -> labelText(verb, label.get()), label));
        item.setOnAction(_ -> commandSink.accept(command));
        disposers.add(() -> {
            item.disableProperty().unbind();
            item.textProperty().unbind();
            item.setOnAction(null);
        });
    }

    /** "{@code Undo}" when there is no description, else "{@code Undo <description>}". */
    private static String labelText(String verb, String description) {
        return description == null || description.isEmpty() ? verb : verb + " " + description;
    }

    /** Removes every binding and handler installed by this binder. Idempotent. */
    public void dispose() {
        for (Runnable d : disposers) {
            d.run();
        }
        disposers.clear();
    }
}
