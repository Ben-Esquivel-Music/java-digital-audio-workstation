package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * An undoable action that sets a plugin parameter value on an insert effect slot.
 *
 * <p>Executing this action applies the new parameter value via the slot's
 * parameter handler and publishes a {@link PluginEvent.ParameterChanged} event.
 * Undoing it restores the previous value.</p>
 */
public final class SetPluginParameterAction implements UndoableAction {

    private final InsertSlot slot;
    private final int parameterId;
    private final double newValue;
    private final BiConsumer<Integer, Double> parameterHandler;
    private double previousValue;

    /**
     * Creates a new set-plugin-parameter action.
     *
     * @param slot             the insert slot whose parameter is being changed
     * @param parameterId      the parameter identifier
     * @param newValue         the new parameter value
     * @param previousValue    the current parameter value (for undo)
     * @param parameterHandler the handler that applies value changes to the processor
     */
    public SetPluginParameterAction(InsertSlot slot, int parameterId,
                                    double newValue, double previousValue,
                                    BiConsumer<Integer, Double> parameterHandler) {
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
        this.parameterId = parameterId;
        this.newValue = newValue;
        this.previousValue = previousValue;
        this.parameterHandler = Objects.requireNonNull(parameterHandler,
                "parameterHandler must not be null");
    }

    @Override
    public String description() {
        return "Set Plugin Parameter";
    }

    @Override
    public void execute() {
        parameterHandler.accept(parameterId, newValue);
        EventBusPublisher.publish(new PluginEvent.ParameterChanged(
                slot.getPluginInstanceId(),
                String.valueOf(parameterId),
                Instant.now()));
    }

    @Override
    public void undo() {
        parameterHandler.accept(parameterId, previousValue);
        EventBusPublisher.publish(new PluginEvent.ParameterChanged(
                slot.getPluginInstanceId(),
                String.valueOf(parameterId),
                Instant.now()));
    }
}
