package com.benesquivelmusic.daw.app.ui;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.input.InputEvent;
import javafx.stage.Window;

import java.util.List;

/**
 * The input-inert window of the close protocol (story 333, design §1.5; the
 * studio engineer's rulings R2 and R12 #4): from the moment the close starts
 * waiting on disk work until it ends or the window hides, every input event
 * to every window that was showing then is consumed — the main window's
 * menu bar, accelerators and controls, the floating dock panels, the insert
 * rack's and plug-ins' editor stages, the Welcome and Hub windows, popups
 * and the palette — so nothing is edited, played or journaled between the
 * close's decision and its seal. One event filter on each such
 * {@link Window} does it: a window's filter sees an event before its scene
 * does. Nothing greys: the window does not flash on a fast close, and the
 * root pane's disable state stays the installation fence's alone.
 *
 * <p>Windows shown after {@link #raise()} — the close's own prompts, its
 * progress surface, the native choosers — are not fenced. A window event is
 * not an input event, so a second close request still reaches the close.
 * {@link #lower()} removes the filter from exactly the windows it was added
 * to (the remembered-subject rule of the javafx skill §4). FX-confined.</p>
 */
final class WindowInputFence {

    private final ReadOnlyBooleanWrapper raised = new ReadOnlyBooleanWrapper(this, "raised");
    private final EventHandler<InputEvent> consume = Event::consume;
    private List<Window> fenced = List.of();

    /** Fences every window showing now; nothing when already raised. FX thread. */
    void raise() {
        if (raised.get()) {
            return;
        }
        fenced = List.copyOf(Window.getWindows());
        for (Window window : fenced) {
            window.addEventFilter(InputEvent.ANY, consume);
        }
        setRaised(true);
    }

    /** Lowers the fence from the windows it was raised on; nothing when lowered. FX thread. */
    void lower() {
        if (!raised.get()) {
            return;
        }
        for (Window window : fenced) {
            window.removeEventFilter(InputEvent.ANY, consume);
        }
        fenced = List.of();
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

    /** The windows the fence is on; empty while it is lowered. FX thread. */
    List<Window> fencedWindows() {
        return fenced;
    }

    private void setRaised(boolean up) {
        raised.set(up);
    }
}
