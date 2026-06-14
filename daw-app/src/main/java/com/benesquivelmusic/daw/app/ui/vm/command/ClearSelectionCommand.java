package com.benesquivelmusic.daw.app.ui.vm.command;

/**
 * Intent to clear the current selection — track, clip, and device (§5.5). The
 * edit tool is unaffected. Raised by an Escape / "deselect" gesture or by the
 * cascade when clamping a selection that no longer fits the loaded project.
 */
public record ClearSelectionCommand() implements SelectionCommand {

    @Override
    public void execute(SelectionIntentHandler handler) {
        handler.clear();
    }
}
