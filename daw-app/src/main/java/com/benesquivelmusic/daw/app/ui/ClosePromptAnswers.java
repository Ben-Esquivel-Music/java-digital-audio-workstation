package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ProjectClosePrompts.TakenOverAnswer;
import com.benesquivelmusic.daw.app.ui.ProjectClosePrompts.TakenOverPrompt;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesAnswer;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesPrompt;

/**
 * The answers the close protocol asks the user for (story 333; the studio
 * engineer's rulings R1, R2, R7 and Q6): the take rule's confirmation, the
 * unsaved-changes prompt, the takeover dialog and a Save As target's
 * refusal. Each shows a modal window and waits for its answer in a nested
 * event loop, so each is called on the FX thread, outside a pulse — the
 * close protocol posts every continuation that may ask unkeyed.
 * {@link ProjectClosePrompts} is the production answers; a test answers
 * through it (pressing the real Alert's buttons) or scripts its own (R1:
 * "Seams for the tests: the confirmation answer").
 */
interface ClosePromptAnswers {

    /** The answers of the take rule's confirmation (ruling R1). */
    enum StopTakeAnswer {
        /** "Stop the take and close": the take is stopped, written out, and the close goes on. */
        STOP_THE_TAKE,
        /** "Keep recording" — the default, Escape and the close box: the close ends before any step. */
        KEEP_RECORDING
    }

    /**
     * Asks whether to stop the take being recorded and close (ruling R1).
     *
     * @param header the header, which names what closes
     * @return the answer; the window closed without an answer keeps recording
     */
    StopTakeAnswer confirmStopTake(String header);

    /**
     * Asks whether to save the open project's unsaved changes before it
     * closes (ruling R2).
     *
     * @param prompt the prompt
     * @return the answer; the window closed without an answer is a Cancel
     */
    UnsavedChangesAnswer askBeforeClosing(UnsavedChangesPrompt prompt);

    /**
     * Asks what to do with a project whose lock another session took over
     * (ruling R7).
     *
     * @param prompt the prompt, a save's or a close's
     * @return the answer; the window closed without an answer is a Cancel
     */
    TakenOverAnswer takenOver(TakenOverPrompt prompt);

    /**
     * Refuses a Save As target (ruling Q6).
     *
     * @param message the refusal
     */
    void refuseSaveAsTarget(String message);
}
