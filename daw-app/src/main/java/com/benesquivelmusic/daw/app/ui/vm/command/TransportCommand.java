package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * A transport intent raised by a control, menu item, or keyboard shortcut — the
 * three of which converge on the <em>same</em> command (§2.8). Commands are thin,
 * immutable wrappers carrying only the intent's data; they perform no mutation
 * themselves but delegate to a {@link TransportIntentHandler} that owns the
 * existing transport mutation path (§2.2 "intent flows up").
 *
 * <p>The hierarchy is {@code sealed} so every transport intent is enumerated at
 * compile time and an exhaustive {@code switch} over the closed set is possible
 * (Control Synchronization Design Book §6.1 transport bar intents).</p>
 */
public sealed interface TransportCommand
        permits StartTransportCommand,
                StopTransportCommand,
                ToggleRecordCommand,
                SetTempoCommand,
                ToggleLoopCommand {

    /**
     * Runs this intent against the given handler.
     *
     * @param handler the handler that owns the mutation path; must not be {@code null}
     */
    void execute(TransportIntentHandler handler);
}
