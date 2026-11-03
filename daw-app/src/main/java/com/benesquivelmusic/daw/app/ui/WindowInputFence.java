package com.benesquivelmusic.daw.app.ui;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.control.Dialog;
import javafx.scene.input.InputEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The input fence of the close protocol (story 333, design §1.5; the
 * studio engineer's rulings R2 and R12 #4): from the moment the close starts
 * waiting on disk work until it ends or the window hides, every input event
 * — mouse, key, scroll, touch, drag and context-menu request — to every
 * window that was showing then is consumed: the main window's menu bar,
 * accelerators and controls, the floating dock panels, the insert rack's
 * and plug-ins' editor stages, the Welcome and Hub windows, popups and the
 * palette. One event filter on each such {@link Window} does it: a window's
 * filter sees an event before its scene does. Nothing greys: the window does
 * not flash on a fast close, and the root pane's disable state stays the
 * installation fence's alone.
 *
 * <p>A window that becomes showing while the fence is up gets the same
 * filter — a floating panel, a combo box's list, a dialog a control opened —
 * unless it is one of the close's own surfaces: a window marked by
 * {@link #markCloseSurface(Window)} (the unsaved-changes prompt, the take
 * rule's confirmation, the takeover dialog, the Closing and Save As progress
 * dialogs, the Save As refusal), or a window such a surface owns, at any
 * depth, through owners that are each showing and not fenced, such as a
 * tooltip of the prompt. The close's native choosers are not JavaFX windows
 * and are never fenced. A window event is not an input event, so a second
 * close request still reaches the close. {@link #lower()} removes the filter
 * from exactly the windows it was added to, those fenced after the raise
 * included, and stops watching for new ones (the remembered-subject rule of
 * the javafx skill §4). FX-confined.</p>
 *
 * <p>The fence alone does not make the window inert. An assistive
 * technology's action and a focus change reach a control with no input
 * event, so they pass it; every edit those raise is refused where its intent
 * is handled, by {@code MainController.projectActionsAllowed()}, which reads
 * {@link #isRaised()} through {@code ExitCoordinator.isWindowInert()}. A
 * plug-in editor body's write through its parameter store is refused by the
 * store, through the gate the editor session installs, and a built-in body's
 * direct write to its plug-in, a Stop-like act that only ends work already
 * running excepted, reads that gate; a third-party body's write around its
 * store is outside what the host can refuse (PR #989 review 5711460812).</p>
 */
final class WindowInputFence {

    /** The close-surface mark's key: private to this class, so no other code can name it. */
    private static final Object CLOSE_SURFACE = new Object();

    private final ReadOnlyBooleanWrapper raised = new ReadOnlyBooleanWrapper(this, "raised");
    private final EventHandler<InputEvent> consume = Event::consume;
    private final List<Window> fenced = new ArrayList<>();
    private final ListChangeListener<Window> shown = this::fenceTheShown;

    /**
     * Marks {@code window} as one of the close's own surfaces: a marked window that shows while the fence is up is
     * not fenced, nor is a window it owns that shows while it is showing. Call it before the window shows. FX thread.
     */
    static void markCloseSurface(Window window) {
        Objects.requireNonNull(window, "window must not be null").getProperties().put(CLOSE_SURFACE, Boolean.TRUE);
    }

    /**
     * Marks the window of {@code dialog}, which exists from its construction, as
     * {@link #markCloseSurface(Window)} does. FX thread.
     */
    static void markCloseSurface(Dialog<?> dialog) {
        // A dialog's stage and scene are made with the dialog (JavaFX's HeavyweightDialog), before any show.
        markCloseSurface(Objects.requireNonNull(dialog, "dialog must not be null")
                .getDialogPane().getScene().getWindow());
    }

    /**
     * Fences every window showing now, and every window shown until {@link #lower()} but the close's own surfaces
     * and the windows they own; nothing when already raised. FX thread.
     */
    void raise() {
        if (raised.get()) {
            return;
        }
        for (Window window : List.copyOf(Window.getWindows())) {
            fence(window);
        }
        // Before setRaised: a window a raised-listener shows is fenced too.
        Window.getWindows().addListener(shown);
        setRaised(true);
    }

    /**
     * Lowers the fence from every window it is on, those fenced after the raise included, and stops watching for
     * new windows; nothing when lowered. FX thread.
     */
    void lower() {
        if (!raised.get()) {
            return;
        }
        Window.getWindows().removeListener(shown);
        // Showing or hidden, those fenced after the raise included.
        for (Window window : fenced) {
            window.removeEventFilter(InputEvent.ANY, consume);
        }
        fenced.clear();
        setRaised(false);
    }

    /** Whether the fence is up. FX thread. */
    boolean isRaised() {
        return raised.get();
    }

    /**
     * Whether the fence is up, as a read-only property whose one writer is
     * {@link #setRaised}. FX thread.
     *
     * @return the property
     */
    ReadOnlyBooleanProperty raisedProperty() {
        return raised.getReadOnlyProperty();
    }

    /** The windows the fence is on, those shown after the raise included; empty while it is lowered. FX thread. */
    List<Window> fencedWindows() {
        return List.copyOf(fenced);
    }

    /** Fences each window the list reports showing, unless it is exempt. */
    private void fenceTheShown(ListChangeListener.Change<? extends Window> change) {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (!isExempt(window)) {
                    fence(window);
                }
            }
        }
    }

    /** Idempotent by identity: a fenced window that hides and shows again is not filtered twice. */
    private void fence(Window window) {
        if (fenced.contains(window)) {
            return;
        }
        window.addEventFilter(InputEvent.ANY, consume);
        fenced.add(window);
    }

    /**
     * Checked once, when the window shows: the mark, or a chain of owners that reaches a marked window, each owner on it
     * showing and not fenced. An owner is classified by this same rule, not by whether it is fenced yet: a window shown
     * from its owner's own showing handler is classified before that owner joins {@link Window#getWindows()}.
     */
    private boolean isExempt(Window window) {
        if (isMarked(window)) {
            return true;
        }
        List<Window> seen = new ArrayList<>();
        Optional<Window> owner = ownerOf(window);
        while (owner.isPresent()) {
            Window next = owner.get();
            if (seen.contains(next) || !next.isShowing() || fenced.contains(next)) {
                return false;
            }
            if (isMarked(next)) {
                return true;
            }
            seen.add(next);
            owner = ownerOf(next);
        }
        return false;
    }

    private static boolean isMarked(Window window) {
        return window.hasProperties() && window.getProperties().containsKey(CLOSE_SURFACE);
    }

    private static Optional<Window> ownerOf(Window window) {
        return switch (window) {
            case Stage stage -> Optional.ofNullable(stage.getOwner());
            case PopupWindow popup -> Optional.ofNullable(popup.getOwnerWindow());
            default -> Optional.empty();
        };
    }

    private void setRaised(boolean up) {
        raised.set(up);
    }
}
