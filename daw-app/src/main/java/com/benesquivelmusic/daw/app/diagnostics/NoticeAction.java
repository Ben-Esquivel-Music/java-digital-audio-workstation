package com.benesquivelmusic.daw.app.diagnostics;

import java.util.Objects;

/**
 * The action a failure notice carries, supplied by the caller (story 337,
 * ruling E43): the funnel's Open Log Folder ({@link FailureFunnel#openLogFolderNotice()})
 * or Restart Audio Engine for the audio categories a stream restart can cure
 * (story 337, ruling E13). The toast shows it, and its history entry keeps it
 * re-triggerable.
 *
 * @param label  the action button's label, from the bundle
 * @param action what the button runs, on the FX thread; it must not block it
 */
public record NoticeAction(String label, Runnable action) {

    public NoticeAction {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }
}
