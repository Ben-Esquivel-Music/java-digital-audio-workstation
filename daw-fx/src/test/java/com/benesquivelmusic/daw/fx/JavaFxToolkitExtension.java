package com.benesquivelmusic.daw.fx;

import javafx.application.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JUnit 5 extension that initialises the JavaFX toolkit once per JVM.
 *
 * <p>Apply to any test class that needs the FX Application Thread:
 * <pre>{@code
 * @ExtendWith(JavaFxToolkitExtension.class)
 * class MyFxTest { ... }
 * }</pre>
 */
public final class JavaFxToolkitExtension implements BeforeAllCallback {

    private static final AtomicBoolean INITIALISED = new AtomicBoolean(false);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!INITIALISED.compareAndSet(false, true)) {
            return;
        }
        try {
            CountDownLatch startupLatch = new CountDownLatch(1);
            try {
                Platform.startup(startupLatch::countDown);
                if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "JavaFX toolkit startup timed out — ensure a display is available");
                }
            } catch (IllegalStateException e) {
                String message = e.getMessage();
                if (message == null || !message.contains("Toolkit already initialized")) {
                    throw e;
                }
            }
            CountDownLatch verifyLatch = new CountDownLatch(1);
            Platform.runLater(verifyLatch::countDown);
            if (!verifyLatch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX Application Thread is not responsive");
            }
        } catch (InterruptedException e) {
            INITIALISED.set(false);
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException | Error e) {
            INITIALISED.set(false);
            throw e;
        }
    }

    /** Runs {@code action} on the FX thread and blocks until it completes. */
    public static void runAndWait(Runnable action) throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action did not complete within 5s");
        }
        if (error[0] != null) {
            if (error[0] instanceof RuntimeException re) throw re;
            if (error[0] instanceof Error err) throw err;
            throw new RuntimeException("FX action failed", error[0]);
        }
    }
}
