package com.benesquivelmusic.daw.app.ui.vm.command;

import java.util.Objects;

/**
 * Intent to select {@code clip} as the focused clip (§5.5). The clip is typed
 * {@code Object} because the clip layer ({@code AudioClip} / {@code MidiClip})
 * has no shared supertype; the clip-editor surface that consumes it migrates in
 * story 294.
 *
 * @param clip the clip to select; must not be {@code null}
 */
public record SelectClipCommand(Object clip) implements SelectionCommand {

    /** @throws NullPointerException if {@code clip} is {@code null} */
    public SelectClipCommand {
        Objects.requireNonNull(clip, "clip must not be null");
    }

    @Override
    public void execute(SelectionIntentHandler handler) {
        handler.selectClip(clip);
    }
}
