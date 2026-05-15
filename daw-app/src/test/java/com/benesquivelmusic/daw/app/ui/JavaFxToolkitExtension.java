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
            try {
                CountDownLatch startupLatch = new CountDownLatch(1);
                try {
                    Platform.startup(startupLatch::countDown);
                    if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "JavaFX toolkit startup timed out — ensure a display is available "
                                        + "(e.g. run with xvfb-run or set DISPLAY)");
                    }
                    // Disable implicit exit BEFORE any test shows a Stage.
                    //
                    // Surefire reuses ONE forked JVM (and ONE JavaFX toolkit)
                    // for all ~78 FX test classes. With the default
                    // implicit-exit behaviour, when a test hides/closes its
                    // last visible Stage (e.g. ButtonAlignmentTest's
                    // stage.close()) JavaFX begins platform shutdown. On the
                    // JavaFX 26 built-in Headless Glass platform
                    // (-Dglass.platform=Headless) that shutdown tears down the
                    // Headless backend's NestedRunnableProcessor and wedges
                    // the FX Application Thread: every later test class's
                    // Platform.runLater callbacks then never execute, hanging
                    // the build. (Verified by isolating show vs. close: show()
                    // stays responsive; close()/hide() of the last window with
                    // implicit-exit ON wedges; with it OFF every show/hide
                    // cycle stays responsive.)
                    //
                    // Keeping the toolkit alive for the whole fork is exactly
                    // what we want for a shared-toolkit test suite, so this is
                    // the correct fix, not a workaround. It is harmless on a
                    // real desktop platform too.
                    Platform.setImplicitExit(false);
                } catch (IllegalStateException e) {
                    String message = e.getMessage();
                    if (message != null && message.contains("Toolkit already initialized")) {
                        // Toolkit already initialized by a previous test class — this is fine.
                    } else {
                        throw e;
                    }
                }
            } catch (InterruptedException e) {
                initialized.set(false);
                Thread.currentThread().interrupt();
                throw e;
            } catch (RuntimeException | Error e) {
                initialized.set(false);
                throw e;
            }
        }
        // Verify the FX Application Thread is processing events on EVERY test
        // class — not just the first. Surefire reuses one forked JVM (and one
        // FX toolkit) for all ~78 FX test classes; an earlier class that wedges
        // the Glass toolkit (e.g. a Stage shown on a non-headless Windows
        // desktop) would otherwise leave every later class's Platform.runLater
        // callbacks unexecuted, hanging the build with no fork timeout. Failing
        // fast here turns an unbounded Windows hang into a clear, actionable
        // error instead.
        verifyFxThreadResponsive();
    }

    private static void verifyFxThreadResponsive() throws InterruptedException {
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Platform.runLater(verifyLatch::countDown);
        if (!verifyLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "JavaFX Application Thread is not responsive — the toolkit was likely "
                            + "wedged by an earlier test class (e.g. a Stage closed while "
                            + "implicit-exit was enabled, triggering platform shutdown on the "
                            + "shared fork). Ensure FX tests run on the built-in headless Glass "
                            + "platform (-Dglass.platform=Headless, JavaFX 26+) with "
                            + "Platform.setImplicitExit(false) — both are configured by the "
                            + "daw-app Surefire setup and JavaFxToolkitExtension.");
        }
    }
}
