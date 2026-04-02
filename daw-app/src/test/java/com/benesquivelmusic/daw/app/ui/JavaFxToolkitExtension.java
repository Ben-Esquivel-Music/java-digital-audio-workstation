package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JUnit 5 extension that initializes the JavaFX toolkit once per JVM.
 *
 * <p>Apply to any test class that requires the JavaFX platform:
 * <pre>{@code
 * @ExtendWith(JavaFxToolkitExtension.class)
 * class MyFxTest { ... }
 * }</pre>
 *
 * <p>If the toolkit cannot be started (e.g. no display and no virtual
 * framebuffer), the test will <em>fail</em> rather than silently skip.
 * CI environments must provide a display — for example via {@code xvfb-run}
 * or by setting {@code DISPLAY} to a running Xvfb instance.
 */
public final class JavaFxToolkitExtension implements BeforeAllCallback {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (initialized.compareAndSet(false, true)) {
            CountDownLatch startupLatch = new CountDownLatch(1);
            try {
                Platform.startup(startupLatch::countDown);
                if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                    initialized.set(false);
                    throw new IllegalStateException(
                            "JavaFX toolkit startup timed out — ensure a display is available "
                                    + "(e.g. run with xvfb-run or set DISPLAY)");
                }
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    throw e;
                }
                // Toolkit already initialized by a previous test class — this is fine.
            }
            // Verify the FX Application Thread is processing events.
            CountDownLatch verifyLatch = new CountDownLatch(1);
            Platform.runLater(verifyLatch::countDown);
            if (!verifyLatch.await(5, TimeUnit.SECONDS)) {
                initialized.set(false);
                throw new IllegalStateException(
                        "JavaFX Application Thread is not responsive — ensure a display is available");
            }
        }
    }
}
