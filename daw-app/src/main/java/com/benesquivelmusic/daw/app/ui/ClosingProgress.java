package com.benesquivelmusic.daw.app.ui;

import javafx.stage.Window;

/**
 * The close protocol's progress surface (story 333; the studio engineer's
 * rulings R1, R8, Q4 and ruling 2 OQ-3): one modal surface, owned by the
 * main window, that the close opens once it has waited on disk work for
 * half a second — its title, the step it waits on and a keep button
 * ({@code Don't quit} for an exit, {@code Keep project open} for Close
 * Project) — and that, after ten seconds, shows a note and, for an exit,
 * {@code Quit anyway}. {@link ClosingProgressDialog} is the production
 * surface; the close protocol's tests also use a recording one.
 *
 * <p>Every method runs on the FX thread. The keep button and the offer run
 * their single-slot action once each time they are pressed while shown and
 * enabled; Escape and the window's close box run the keep action only
 * while the keep button is shown and enabled, and otherwise do nothing.
 * {@link #closeByProtocol()} runs neither.</p>
 */
interface ClosingProgress {

    /** The second button the note offers, besides the keep button. */
    enum Offer {
        /** None: the note only, or no note. */
        NONE,
        /** {@code Quit anyway}: an exit's way out of a hung disk (ruling R8), or past a seal that failed. */
        QUIT_ANYWAY,
        /** {@code Close anyway}: Close Project's way past a seal that failed (ruling Q4.6). */
        CLOSE_ANYWAY
    }

    /**
     * Shows the surface, if it is not showing.
     *
     * @param title     its title, {@code Closing “<project>”}
     * @param step      the step it waits on
     * @param keepLabel the keep button's label
     */
    void open(String title, String step, String keepLabel);

    /** Sets the step line. */
    void step(String step);

    /**
     * Shows {@code note} with {@code offer}, or takes the note away when it
     * is empty.
     *
     * @param note  the note, or empty for none
     * @param offer the button beside the keep button
     */
    void note(String note, Offer offer);

    /** Relabels the keep button. */
    void keepLabel(String label);

    /** Enables or disables the keep button; disabled, Escape and the close box do nothing. */
    void keepEnabled(boolean enabled);

    /** Shows or hides the keep button; hidden, Escape and the close box do nothing. */
    void keepShown(boolean shown);

    /** What the keep button, Escape and the close box run. */
    void onKeep(Runnable action);

    /** What the offer — {@code Quit anyway} or {@code Close anyway} — runs. */
    void onOffer(Runnable action);

    /** Closes the surface for the protocol: runs neither action, and is never vetoed. */
    void closeByProtocol();

    /** Whether the surface is showing. */
    boolean isOpen();

    /** Whether {@code window} is this surface's own window, which a window's disposal keeps until HIDE. */
    boolean owns(Window window);
}
