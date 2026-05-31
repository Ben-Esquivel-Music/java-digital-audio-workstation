package com.benesquivelmusic.daw.app.ui.vm;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the JavaFX toolkit once per JVM for the transport view-model tests.
 *
 * <p>Mirrors {@code com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension} but
 * lives in <em>this</em> package so it can be invoked from a plain
 * {@code @BeforeAll} static call rather than via {@code @ExtendWith}. When the
 * targeted Maven verify command selects only the {@code vm} tests, surefire opens
 * only the {@code vm} packages to the test engine; a cross-package extension in
 * {@code ...ui} would then be inaccessible by reflection. A same-package static
 * call sidesteps that entirely.</p>
 */
final class FxTestSupport {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private FxTestSupport() {
    }

    /** Idempotently starts the headless FX toolkit and keeps it alive for the fork. */
    static void startToolkit() throws InterruptedException {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            CountDownLatch startup = new CountDownLatch(1);
            Platform.startup(startup::countDown);
            if (!startup.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "JavaFX toolkit startup timed out — ensure a headless display is available");
            }
            // Keep the toolkit alive across the shared fork (see JavaFxToolkitExtension).
            Platform.setImplicitExit(false);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("Toolkit already initialized")) {
                throw e;
            }
            // Already started by an earlier test class — fine.
        }
    }
}
