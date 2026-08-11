package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to <em>toggle</em> between playing and paused — the Play gesture
 * itself, with the Start-or-Pause decision left to the handler that owns the
 * transport (§2.8 "one path", story 315 review).
 *
 * <p>The gesture layer must not resolve this itself. A control binder decides
 * from {@code TransportVM}, which is an <em>async</em> mirror: every VM write
 * is marshalled through {@code FxDispatcher.onFx}, an unconditional
 * {@code Platform.runLater}, so the mirror lags the authoritative transport by
 * at least one FX turn. In that window a Play click resolved from the VM raises
 * {@link StartTransportCommand} against an already-PLAYING transport, the
 * handler's VALIDATE silently drops it, and the user's click does nothing.
 * Raising this command instead moves the decision to the handler, which reads
 * the authoritative {@code Transport.getState()} — so the toolbar button, the
 * spacebar, and the Performance Stage all behave identically.</p>
 *
 * <p>{@link StartTransportCommand} and {@link PauseTransportCommand} remain
 * valid explicit intents for surfaces that mean one specific transition.</p>
 */
public record TogglePlayPauseCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.togglePlayPause();
    }
}
