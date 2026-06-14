package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * A track or channel intent raised by a control, menu item, or keyboard
 * shortcut — the three of which converge on the <em>same</em> command (§2.8).
 * Commands are thin, immutable wrappers carrying only the intent's target and
 * data; they perform no mutation themselves but delegate to a
 * {@link TrackIntentHandler} that owns the existing mutation path (§2.2 "intent
 * flows up"), story 291.
 *
 * <p>The hierarchy is {@code sealed} so every track/channel intent is enumerated
 * at compile time and an exhaustive {@code switch} over the closed set is
 * possible (Control Synchronization Design Book §6).</p>
 */
public sealed interface TrackCommand
        permits ToggleMuteCommand,
                ToggleSoloCommand,
                ToggleArmCommand,
                SetChannelVolumeCommand,
                SetChannelPanCommand {

    /**
     * Runs this intent against the given handler.
     *
     * @param handler the handler that owns the mutation path; must not be {@code null}
     */
    void execute(TrackIntentHandler handler);
}
