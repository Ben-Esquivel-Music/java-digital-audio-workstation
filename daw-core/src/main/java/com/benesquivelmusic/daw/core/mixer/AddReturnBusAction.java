package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds a new return bus to the mixer.
 *
 * <p>Executing this action creates and adds a return bus with the given name.
 * Undoing it removes the return bus (and any sends targeting it).</p>
 */
public final class AddReturnBusAction implements UndoableAction {

    private final Mixer mixer;
    private final String name;
    private MixerChannel returnBus;

    /**
     * Creates a new add-return-bus action.
     *
     * @param mixer the mixer to add the return bus to
     * @param name  the display name for the new return bus
     */
    public AddReturnBusAction(Mixer mixer, String name) {
        this.mixer = Objects.requireNonNull(mixer, "mixer must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    @Override
    public String description() {
        return "Add Return Bus";
    }

    @Override
    public void execute() {
        if (returnBus == null) {
            returnBus = mixer.addReturnBus(name);
        } else {
            mixer.addReturnBus(returnBus);
        }
    }

    @Override
    public void undo() {
        if (returnBus != null) {
            mixer.removeReturnBus(returnBus);
        }
    }

    /**
     * Returns the return bus created by this action, or {@code null} if the
     * action has not yet been executed.
     *
     * @return the return bus, or {@code null}
     */
    public MixerChannel getReturnBus() {
        return returnBus;
    }
}
