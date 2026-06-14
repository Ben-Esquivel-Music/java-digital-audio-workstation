package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * A selection intent raised by a control, menu item, or keyboard shortcut — the
 * three of which converge on the <em>same</em> command (§2.8). Commands are thin,
 * immutable wrappers carrying only the intent's data; they perform no mutation
 * themselves but delegate to a {@link SelectionIntentHandler} that owns the
 * {@code SelectionVM} write + the {@code SelectionChanged} announce (§2.2 "intent
 * flows up").
 *
 * <p>The hierarchy is {@code sealed} so every selection intent is enumerated at
 * compile time (Control Synchronization Design Book §5.5 selection &amp;
 * edit-tool intents).</p>
 */
public sealed interface SelectionCommand
        permits SelectTrackCommand,
                SelectClipCommand,
                SelectDeviceCommand,
                SetToolCommand,
                ClearSelectionCommand {

    /**
     * Runs this intent against the given handler.
     *
     * @param handler the handler that owns the selection mutation path; must not be {@code null}
     */
    void execute(SelectionIntentHandler handler);
}
