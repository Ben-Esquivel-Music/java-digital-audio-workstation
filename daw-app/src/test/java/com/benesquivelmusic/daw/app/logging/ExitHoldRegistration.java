package com.benesquivelmusic.daw.app.logging;

import java.util.Optional;

/**
 * Test-side access to {@link ExitHoldingLogManager}'s test-only seam that
 * puts the exit-wait registration back (story 336), for a test outside this
 * package that registers an exit wait in the shared surefire JVM: it takes
 * {@link ExitHoldingLogManager#registeredHold()} before, and restores exactly
 * that afterwards — none included — so that no hold on a hook the test
 * removed is left behind. The application never calls it.
 */
public final class ExitHoldRegistration {

    private ExitHoldRegistration() {
    }

    /**
     * Puts the registration back as {@code before} had it.
     *
     * @param before what {@link ExitHoldingLogManager#registeredHold()} returned before the test, none included
     */
    public static void restore(Optional<ExitHoldingLogManager.Hold> before) {
        ExitHoldingLogManager.restoreRegisteredHold(before);
    }
}
