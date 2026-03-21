package com.benesquivelmusic.daw.app;

import javafx.application.Application;

/**
 * Entry point for the DAW application.
 *
 * <p>This separate launcher class exists because JavaFX requires the
 * {@link Application} subclass to <em>not</em> be the main class when
 * running from a classpath-based (non-module) configuration.</p>
 */
public final class DawLauncher {

    private DawLauncher() {
        // utility class
    }

    public static void main(String[] args) {
        Application.launch(DawApplication.class, args);
    }
}
