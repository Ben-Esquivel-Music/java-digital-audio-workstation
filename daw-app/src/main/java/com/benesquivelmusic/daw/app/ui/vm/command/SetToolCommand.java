package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.app.ui.EditTool;

import java.util.Objects;

/**
 * Intent to change the active edit tool (§5.5). Raised by the toolbar tool
 * buttons and the tool keyboard shortcuts. The tool is a continuous UI mode the
 * toolbar / cursor / canvas bind directly (§2.7), so — unlike the selection
 * commands — applying it does not announce a {@code SelectionChanged} bus event.
 *
 * @param tool the edit tool to activate; must not be {@code null}
 */
public record SetToolCommand(EditTool tool) implements SelectionCommand {

    /** @throws NullPointerException if {@code tool} is {@code null} */
    public SetToolCommand {
        Objects.requireNonNull(tool, "tool must not be null");
    }

    @Override
    public void execute(SelectionIntentHandler handler) {
        handler.setTool(tool);
    }
}
