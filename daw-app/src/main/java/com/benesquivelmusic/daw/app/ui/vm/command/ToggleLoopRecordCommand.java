package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to toggle loop-record (story 328, book §5.6): whether a take
 * recorded over an enabled loop stacks each lap as a take lane. Raised by
 * the Loop Rec toggle and {@code DawAction.TOGGLE_LOOP_RECORD}; the
 * production controller refuses it while a take is in progress.
 */
public record ToggleLoopRecordCommand() implements TransportCommand {
    @Override
    public void execute(TransportIntentHandler handler) {
        handler.toggleLoopRecord();
    }
}
