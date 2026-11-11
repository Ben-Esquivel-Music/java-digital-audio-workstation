package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.UndeclaredThrowFixture;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 334 (studio-engineer ruling E20) — a plugin's identity is its manifest
 * id, so the install panel refuses a plugin whose loaded descriptor disagrees
 * with its manifest, and a bundle that declares one id for more than one
 * class, each with the ruled words; and a manifest whose id is installed
 * already says, before the engineer confirms, which entry the install
 * replaces — which the install then does, the replaced catalogue instance
 * released off the JavaFX application thread (critique J9).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ManifestIdentityInstallTest {

    private static final String DIALOG_TITLE = "Install plugin";

    private final List<String> errorMessages = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> dialogFailure = new AtomicReference<>();
    private final ListChangeListener<Window> dismissErrors = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> {
                        try {
                            DialogPane pane = (DialogPane) window.getScene().getRoot();
                            Node message = pane.getContent().lookup(".dawg-dialog-message");
                            errorMessages.add(message instanceof Label label ? label.getText()
                                    : "<not an error dialog: " + pane.getContent() + ">");
                        } catch (Throwable failure) {
                            dialogFailure.compareAndSet(null, failure);
                        } finally {
                            window.hide();
                        }
                    });
                }
            }
        }
    };

    private PluginRegistry registry;

    @BeforeEach
    void watchForErrorDialogs() {
        registry = new PluginRegistry();
        runOnFxThread(() -> {
            Window.getWindows().addListener(dismissErrors);
            return null;
        });
    }

    @AfterEach
    void stopWatching() {
        runOnFxThread(() -> {
            Window.getWindows().removeListener(dismissErrors);
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                    window.hide();
                }
            }
            return null;
        });
        registry.disposeAll();
    }

    @Test
    void aMismatchedManifestIsRefusedWithTheExactText() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")));
        TrackingPlugin loaded = new TrackingPlugin(descriptor("com.example.comp2", "Comp X", "1.2.0"));
        TrackingClassLoader loader = new TrackingClassLoader();
        AtomicInteger loads = new AtomicInteger();

        PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                entry -> {
                    loads.incrementAndGet();
                    return new ExternalPluginLoader.LoadResult(loaded, loader);
                }));
        runOnFxThread(() -> {
            panel.primaryButtonForTest().fire();
            return null;
        });
        panel.installationForTest().get(10, TimeUnit.SECONDS);

        assertThat(dialogFailure).hasNullValue();
        assertThat(loads).as("the plugin was loaded, so its descriptor could be checked").hasValue(1);
        assertThat(errorMessages).singleElement().isEqualTo("Some plugins could not be installed:\n"
                + "comp-x.jar was not installed: CompX reports the id com.example.comp2 and version 1.2.0, but its"
                + " manifest declares com.example.comp 1.2.0. A plugin's manifest and its descriptor must agree.");
        assertThat(runOnFxThread(() -> registry.getEntries())).as("nothing is installed").isEmpty();
        assertThat(loaded.disposals).as("the refused plugin is released").hasValue(1);
        assertThat(loaded.disposedOnFx).isFalse();
        assertThat(loader.closes).as("then its loader closes").hasValue(1);
        assertThat(loader.closedOnFx).isFalse();
    }

    /**
     * Story 334 (the final review's NIT-1, swept to the install panel's
     * release loop): refused plugins whose {@code dispose()} all throw one
     * shared exception instance — as a library's stackless exception is —
     * are every one released with its loader closed, and the installation
     * completes with that exception, logged once at WARNING. {@link Throwable}
     * refuses to suppress an exception into itself, so collecting the second
     * throw into the first would throw out of the loop: the third plugin would
     * stay unreleased and the installation would never complete, so the panel
     * could not install again.
     */
    @Test
    void refusedPluginsThatThrowOneSharedExceptionAsTheyAreReleasedAreAllReleased() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")),
                new PluginManifest("com.example.Limiter", descriptor("com.example.limiter", "Limiter", "1.0")));
        IllegalStateException shared = new IllegalStateException("the plugin library is already closed");
        Map<String, TrackingPlugin> refused = Map.of(
                "com.example.CompX", refusedThrowing(shared, "com.example.comp2"),
                "com.example.Gate", refusedThrowing(shared, "com.example.gate2"),
                "com.example.Limiter", refusedThrowing(shared, "com.example.limiter2"));
        List<TrackingClassLoader> loaders = new CopyOnWriteArrayList<>();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> {
                        TrackingClassLoader loader = new TrackingClassLoader();
                        loaders.add(loader);
                        return new ExternalPluginLoader.LoadResult(refused.get(entry.className()), loader);
                    }));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            Throwable completion = catchThrowable(() -> panel.installationForTest().get(10, TimeUnit.SECONDS));

            assertThat(refused.values()).as("every refused plugin is released once")
                    .allSatisfy(plugin -> assertThat(plugin.disposals).hasValue(1));
            assertThat(loaders).as("then every loader closes").hasSize(3)
                    .allSatisfy(loader -> assertThat(loader.closes).hasValue(1));
            assertThat(completion).as("the installation completes with the plugins' exception")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(shared);
            assertThat(shared.getSuppressed()).as("never suppressed into itself").isEmpty();
            assertThat(warnings).as("logged once for the installation, not once per plugin").singleElement()
                    .extracting(LogRecord::getThrown).isSameAs(shared);
            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).as("the one refusal dialog").hasSize(1);
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * Story 334 (PR #990 review, ruling R-C3.8): refused plugins whose
     * {@code dispose()} throw two different exceptions are logged once at
     * WARNING, with the first to fail, the second suppressed into it, and the
     * installation completes with the first.
     */
    @Test
    void refusedPluginsThatThrowDifferentExceptionsAsTheyAreReleasedAreLoggedOnce() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        IllegalStateException first = new IllegalStateException("the comp's temp folder could not be deleted");
        IllegalStateException second = new IllegalStateException("the gate's native library is already unloaded");
        Map<String, TrackingPlugin> refused = Map.of(
                "com.example.CompX", refusedThrowing(first, "com.example.comp2"),
                "com.example.Gate", refusedThrowing(second, "com.example.gate2"));
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> new ExternalPluginLoader.LoadResult(refused.get(entry.className()),
                            new TrackingClassLoader())));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            Throwable completion = catchThrowable(() -> panel.installationForTest().get(10, TimeUnit.SECONDS));

            assertThat(refused.values()).as("every refused plugin is released once")
                    .allSatisfy(plugin -> assertThat(plugin.disposals).hasValue(1));
            assertThat(warnings).as("logged once for the installation, not once per plugin").singleElement()
                    .satisfies(record -> {
                        assertThat(record.getMessage())
                                .isEqualTo("A plugin the installation did not keep failed while it was disposed");
                        assertThat(record.getThrown()).as("the first to fail, released in manifest order")
                                .isSameAs(first);
                        assertThat(record.getThrown().getSuppressed()).as("the second suppressed into it")
                                .containsExactly(second);
                    });
            assertThat(completion).as("the installation completes with the first")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(first);
            assertThat(dialogFailure).hasNullValue();
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * Story 334 (PR #990 review, rulings R-C3.7 and R2-2): a plugin whose descriptor check throws a
     * checked exception it does not declare, with no message, is refused with one failure line
     * naming the exception's class, logged once at WARNING with that exception, and released with
     * the attempt's other leftovers, while the JAR's plugin that agrees with its manifest is
     * installed; the installation completes and the install button can be pressed again.
     */
    @Test
    void aPluginWhoseDescriptorCheckThrowsAnUndeclaredCheckedExceptionIsRefusedAndTheRestInstall()
            throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        IOException descriptorFailure = new IOException();
        TrackingPlugin thrower = new TrackingPlugin(() -> {
            UndeclaredThrowFixture.throwUndeclared(descriptorFailure);
            return null;
        }, () -> { });
        TrackingPlugin gate = new TrackingPlugin(descriptor("com.example.gate", "Gate", "1.0"));
        TrackingClassLoader throwerLoader = new TrackingClassLoader();
        TrackingClassLoader gateLoader = new TrackingClassLoader();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> entry.className().equals("com.example.Gate")
                            ? new ExternalPluginLoader.LoadResult(gate, gateLoader)
                            : new ExternalPluginLoader.LoadResult(thrower, throwerLoader)));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            panel.installationForTest().get(10, TimeUnit.SECONDS);

            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).as("one failure line, naming the exception's class: it has no message")
                    .singleElement().isEqualTo("Some plugins could not be installed:\ncom.example.CompX — IOException");
            assertThat(warnings).as("logged once, with what was thrown").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not install plugin com.example.CompX");
                assertThat(record.getThrown()).isSameAs(descriptorFailure);
            });
            assertThat(runOnFxThread(() -> registry.getEntries())).as("the plugin that agrees is installed")
                    .singleElement().satisfies(entry -> assertThat(entry.className()).isEqualTo("com.example.Gate"));
            assertThat(thrower.disposals).as("the refused plugin is released once").hasValue(1);
            assertThat(throwerLoader.closes).as("then its loader closes once").hasValue(1);
            assertThat(gate.disposals).as("the installed one is kept").hasValue(0);
            assertThat(gateLoader.closes).hasValue(0);
            assertThat(runOnFxThread(() -> panel.primaryButtonForTest().isDisabled()))
                    .as("the engineer can install again").isFalse();
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * Story 334 (PR #990 review, rulings R-C3.4 and R2-2): the failure line of a plugin that could
     * not be loaded names an exception with no message by its class, and the failure is logged once
     * at WARNING with that exception.
     */
    @Test
    void aLoadFailureWithNoMessageIsNamedByItsExceptionsClass() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")));
        IllegalStateException loadFailure = new IllegalStateException();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> {
                        throw loadFailure;
                    }));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            panel.installationForTest().get(10, TimeUnit.SECONDS);

            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).singleElement()
                    .isEqualTo("Some plugins could not be installed:\ncom.example.CompX — IllegalStateException");
            assertThat(warnings).as("logged once, with what was thrown").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not install plugin com.example.CompX");
                assertThat(record.getThrown()).isSameAs(loadFailure);
            });
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * Story 334 (PR #990 review, ruling R4-1): a plugin whose loading throws a checked exception it
     * does not declare gets one failure line naming it and one WARNING record, and the installation
     * completes with the install button enabled again.
     */
    @Test
    void aLoadThatThrowsAnUndeclaredCheckedExceptionIsAFailureLineAndTheInstallationCompletes()
            throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")));
        GeneralSecurityException loadFailure =
                new GeneralSecurityException("the plugin's licence could not be verified");
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = capturingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> {
                        UndeclaredThrowFixture.throwUndeclared(loadFailure);
                        throw new AssertionError();
                    }));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            panel.installationForTest().get(10, TimeUnit.SECONDS);

            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).as("one failure line, naming the exception by its message").singleElement()
                    .isEqualTo("Some plugins could not be installed:\n"
                            + "com.example.CompX — the plugin's licence could not be verified");
            assertThat(warnings).as("logged once, with what was thrown").singleElement().satisfies(record -> {
                assertThat(record.getMessage()).isEqualTo("Could not install plugin com.example.CompX");
                assertThat(record.getThrown()).isSameAs(loadFailure);
            });
            assertThat(runOnFxThread(() -> panel.primaryButtonForTest().isDisabled()))
                    .as("the engineer can install again").isFalse();
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * PR #990 review, ruling R5-3: plugins whose failures a log handler cannot format — reading
     * their exceptions' messages throws, as a plugin's that needs its closed JAR does — each get a
     * failure line naming the exception by its class: one whose loading throws, and one whose
     * descriptor check throws, which is refused and released. Every failure is handed to the
     * handlers once, whatever an earlier one threw, and the installation completes with the
     * install button enabled again.
     */
    @Test
    void failuresALogHandlerCannotFormatAreFailureLinesAndTheInstallationCompletes() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        UndeclaredThrowFixture.UnformattableFailure loadFailure = new UndeclaredThrowFixture.UnformattableFailure();
        UndeclaredThrowFixture.UnformattableFailure descriptorFailure =
                new UndeclaredThrowFixture.UnformattableFailure();
        TrackingPlugin gate = new TrackingPlugin(() -> {
            throw descriptorFailure;
        }, () -> { });
        TrackingClassLoader gateLoader = new TrackingClassLoader();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = formattingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> {
                        if (entry.className().equals("com.example.Gate")) {
                            return new ExternalPluginLoader.LoadResult(gate, gateLoader);
                        }
                        throw loadFailure;
                    }));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            panel.installationForTest().get(10, TimeUnit.SECONDS);

            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).as("one failure line each, naming the exception by its class")
                    .singleElement().isEqualTo("Some plugins could not be installed:\n"
                            + "com.example.CompX — UnformattableFailure\n"
                            + "com.example.Gate — UnformattableFailure");
            assertThat(warnings).as("each failure handed to the handlers once, whatever an earlier one threw")
                    .extracting(LogRecord::getThrown).containsExactly(loadFailure, descriptorFailure);
            assertThat(gate.disposals).as("the refused plugin is released once").hasValue(1);
            assertThat(gateLoader.closes).as("then its loader closes once").hasValue(1);
            assertThat(runOnFxThread(() -> registry.getEntries())).as("nothing is installed").isEmpty();
            assertThat(runOnFxThread(() -> panel.primaryButtonForTest().isDisabled()))
                    .as("the engineer can install again").isFalse();
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * PR #990 review, ruling R6-2: an installation cancelled while one plugin loads, after another
     * failed with an exception a log handler cannot format, still releases the plugin it loaded —
     * disposed once, its loader closed once — and completes, whatever the logging throws; the
     * failure is handed to the handlers once.
     */
    @Test
    void aCancelledInstallationReleasesWhatItLoadedWhateverItsLoggingThrows() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        UndeclaredThrowFixture.UnformattableFailure loadFailure = new UndeclaredThrowFixture.UnformattableFailure();
        TrackingPlugin gate = new TrackingPlugin(descriptor("com.example.gate", "Gate", "1.0"));
        TrackingClassLoader gateLoader = new TrackingClassLoader();
        CountDownLatch gateLoading = new CountDownLatch(1);
        CountDownLatch gateLoads = new CountDownLatch(1);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = formattingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> {
                        if (!entry.className().equals("com.example.Gate")) {
                            throw loadFailure;
                        }
                        gateLoading.countDown();
                        try {
                            if (!gateLoads.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("the test did not let the load finish");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return new ExternalPluginLoader.LoadResult(gate, gateLoader);
                    }));
            CompletableFuture<Void> installation = runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return panel.installationForTest();
            });
            assertThat(gateLoading.await(10, TimeUnit.SECONDS)).as("the second plugin is loading").isTrue();
            runOnFxThread(() -> {
                if (panel.lookup("#plugin-install-cancel") instanceof Button cancel) {
                    cancel.fire();
                } else {
                    throw new AssertionError("the install panel's Cancel button was not found");
                }
                return null;
            });
            gateLoads.countDown();
            installation.get(10, TimeUnit.SECONDS);

            assertThat(gate.disposals).as("the plugin it loaded is released once").hasValue(1);
            assertThat(gateLoader.closes).as("then its loader closes once").hasValue(1);
            assertThat(warnings).as("the failure is handed to the handlers once").singleElement()
                    .extracting(LogRecord::getThrown).isSameAs(loadFailure);
            assertThat(runOnFxThread(() -> registry.getEntries())).as("nothing is installed").isEmpty();
        } finally {
            gateLoads.countDown();
            panelLog.removeHandler(capture);
        }
    }

    /** A handler that adds each WARNING record it is given to {@code warnings}. */
    private static Handler capturingWarnings(List<LogRecord> warnings) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }

    /**
     * A handler that adds each WARNING record it is given to {@code warnings}, then formats it as the
     * JDK's console handler does, which runs what the record's exception runs to give its message.
     */
    private static Handler formattingWarnings(List<LogRecord> warnings) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record);
                    new SimpleFormatter().format(record);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }

    /**
     * Story 334 (PR #990 review, ruling R-C3.8): a refused plugin whose {@code dispose()} throws a
     * checked exception it does not declare does not end the release loop: the refused plugin
     * after it is released too, every loader closes once, the failure is logged once at WARNING,
     * and then the installation completes with it.
     */
    @Test
    void aReleaseThatThrowsAnUndeclaredCheckedExceptionLetsEveryOtherReleaseRunAndIsLoggedOnce()
            throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        IOException disposalFailure = new IOException("the plugin's temp folder could not be deleted");
        TrackingPlugin throwing = new TrackingPlugin(descriptor("com.example.comp2", "Mismatched", "1.0"),
                () -> UndeclaredThrowFixture.throwUndeclared(disposalFailure));
        TrackingPlugin after = new TrackingPlugin(descriptor("com.example.gate2", "Mismatched", "1.0"));
        TrackingClassLoader throwingLoader = new TrackingClassLoader();
        TrackingClassLoader afterLoader = new TrackingClassLoader();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> entry.className().equals("com.example.Gate")
                            ? new ExternalPluginLoader.LoadResult(after, afterLoader)
                            : new ExternalPluginLoader.LoadResult(throwing, throwingLoader)));
            AtomicReference<List<LogRecord>> atCompletion = new AtomicReference<>();
            // Registered in the firing call, after fire(), so on the attempt fire() started: the panel's
            // earlier installation is already complete. Nothing else waits on this one yet, so the
            // thread that completes it runs this callback as it completes it.
            CompletableFuture<Void> observed = runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return panel.installationForTest()
                        .whenComplete((_, _) -> atCompletion.set(List.copyOf(warnings)));
            });
            catchThrowable(() -> observed.get(10, TimeUnit.SECONDS)); // waits, bounded, for the callback
            Throwable completion = catchThrowable(() -> panel.installationForTest().get(10, TimeUnit.SECONDS));

            assertThat(atCompletion.get()).as("logged before the installation completed: its waiters find the record")
                    .singleElement().extracting(LogRecord::getThrown).isSameAs(disposalFailure);
            assertThat(List.of(throwing, after)).as("every refused plugin is released once")
                    .allSatisfy(plugin -> assertThat(plugin.disposals).hasValue(1));
            assertThat(List.of(throwingLoader, afterLoader)).as("then every loader closes once")
                    .allSatisfy(loader -> assertThat(loader.closes).hasValue(1));
            assertThat(completion).as("the installation completes with the plugin's exception")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(disposalFailure);
            assertThat(warnings).as("logged once").singleElement().satisfies(record -> {
                assertThat(record.getMessage())
                        .isEqualTo("A plugin the installation did not keep failed while it was disposed");
                assertThat(record.getThrown()).isSameAs(disposalFailure);
            });
            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).as("the one refusal dialog").hasSize(1);
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    /**
     * PR #990 review, ruling R5-4: a refused plugin whose {@code dispose()} throws an exception a
     * log handler cannot format — reading its message throws, as a plugin's exception that needs
     * its closed JAR does — is released once, its loader closed once, and the installation
     * completes with that exception, whatever the logging throws.
     */
    @Test
    void aReleaseFailureALogHandlerCannotFormatStillCompletesTheInstallation() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")));
        UndeclaredThrowFixture.UnformattableFailure disposalFailure = new UndeclaredThrowFixture.UnformattableFailure();
        TrackingPlugin throwing = new TrackingPlugin(descriptor("com.example.comp2", "Mismatched", "1.0"), () -> {
            throw disposalFailure;
        });
        TrackingClassLoader throwingLoader = new TrackingClassLoader();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = formattingWarnings(warnings);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        panelLog.addHandler(capture);
        try {
            PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> new ExternalPluginLoader.LoadResult(throwing, throwingLoader)));
            runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return null;
            });
            Throwable completion = catchThrowable(() -> panel.installationForTest().get(10, TimeUnit.SECONDS));

            assertThat(completion).as("the installation completes with the plugin's exception")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(disposalFailure);
            assertThat(throwing.disposals).as("the refused plugin is released once").hasValue(1);
            assertThat(throwingLoader.closes).as("then its loader closes once").hasValue(1);
            assertThat(warnings).as("the record is handed to the handlers once").singleElement()
                    .extracting(LogRecord::getThrown).isSameAs(disposalFailure);
            assertThat(dialogFailure).hasNullValue();
            assertThat(errorMessages).as("the one refusal dialog").hasSize(1);
        } finally {
            panelLog.removeHandler(capture);
        }
    }

    private static TrackingPlugin refusedThrowing(IllegalStateException disposalFailure, String reportedId) {
        return new TrackingPlugin(descriptor(reportedId, "Mismatched", "1.0"), () -> {
            throw disposalFailure;
        });
    }

    @Test
    void aDuplicateIdIsRefusedWithTheExactText() throws Exception {
        JarInspection twoClasses = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.CompY", descriptor("com.example.comp", "Comp Y", "1.2.0")));
        AtomicInteger installed = new AtomicInteger();

        runOnFxThread(() -> {
            PluginInstallPanel.showDialog(twoClasses, registry, installed::incrementAndGet);
            return null;
        });

        assertThat(dialogFailure).hasNullValue();
        assertThat(errorMessages).as("the one error dialog, and no install panel").singleElement()
                .isEqualTo("comp-x.jar was not installed: its manifest declares the id com.example.comp for two"
                        + " plugins (CompX, CompY). Each plugin needs its own id.");
        assertThat(installed).hasValue(0);
        assertThat(runOnFxThread(() -> registry.getEntries())).isEmpty();

        // R11 (d): more than two classes, and one sentence per repeated id, in manifest order.
        JarInspection twoIds = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")),
                new PluginManifest("com.example.CompY", descriptor("com.example.comp", "Comp Y", "1.2.0")),
                new PluginManifest("com.example.Outer$GateTwo", descriptor("com.example.gate", "Gate 2", "1.0")),
                new PluginManifest("com.example.CompZ", descriptor("com.example.comp", "Comp Z", "1.2.0")));
        assertThat(PluginInstallPanel.duplicateIdMessage(twoIds)).contains(
                "comp-x.jar was not installed: its manifest declares the id com.example.comp for 3 plugins"
                        + " (CompX, CompY, CompZ). Each plugin needs its own id.\n"
                        + "comp-x.jar was not installed: its manifest declares the id com.example.gate for two"
                        + " plugins (Gate, GateTwo). Each plugin needs its own id.");
        assertThat(PluginInstallPanel.duplicateIdMessage(inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")))))
                .as("one id per class: nothing to refuse").isEmpty();
    }

    @Test
    void anInstalledIdShowsTheReplacesRow() throws Exception {
        TrackingPlugin installedCatalogue = new TrackingPlugin(descriptor("com.example.comp", "Comp X", "1.1.0"));
        TrackingClassLoader installedLoader = new TrackingClassLoader();
        registry.install(new InstalledPlugin(new ExternalPluginEntry(Path.of("comp-x-1.1.jar"), "com.example.CompX"),
                        new PluginManifest("com.example.CompX", installedCatalogue.getDescriptor()), "comp-x-1.1.jar"),
                new ExternalPluginLoader.LoadResult(installedCatalogue, installedLoader));
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        TrackingPlugin newCatalogue = new TrackingPlugin(descriptor("com.example.comp", "Comp X", "1.2.0"));
        TrackingPlugin gate = new TrackingPlugin(descriptor("com.example.gate", "Gate", "1.0"));

        PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                entry -> new ExternalPluginLoader.LoadResult(
                        entry.className().equals("com.example.Gate") ? gate : newCatalogue,
                        new TrackingClassLoader())));

        assertThat(runOnFxThread(() -> panel.lookupAll(".plugin-install-replaces").stream()
                .map(node -> ((Label) node).getText()).toList()))
                .as("only the row of the installed id says what it replaces")
                .containsExactly("Replaces Comp X 1.1.0");

        runOnFxThread(() -> {
            panel.primaryButtonForTest().fire();
            return null;
        });
        panel.installationForTest().get(10, TimeUnit.SECONDS);

        assertThat(errorMessages).isEmpty();
        assertThat(runOnFxThread(() -> registry.installedJar("com.example.comp").map(InstalledPlugin::version)))
                .as("one entry per id: the install replaced it").contains("1.2.0");
        assertThat(runOnFxThread(() -> registry.getEntries())).hasSize(2);
        assertThat(installedCatalogue.disposals).as("the replaced catalogue instance is released").hasValue(1);
        assertThat(installedCatalogue.disposedOnFx).isFalse();
        assertThat(installedLoader.closes).hasValue(1);
        assertThat(installedLoader.closedOnFx).isFalse();
        assertThat(newCatalogue.disposals).as("the installed one is kept").hasValue(0);
    }

    private static JarInspection inspection(String jarName, PluginManifest... manifests) {
        return new JarInspection(Path.of(jarName), true, true,
                new PluginManifestReader.BundleResult.Valid(List.of(manifests)), manifests.length, 0, 0);
    }

    private static PluginDescriptor descriptor(String id, String name, String version) {
        return new PluginDescriptor(id, name, version, "Example Audio", PluginType.EFFECT);
    }

    private static final class TrackingPlugin implements DawPlugin {
        private final Supplier<PluginDescriptor> descriptor;
        private final Runnable lastStepOfDispose;
        final AtomicInteger disposals = new AtomicInteger();
        volatile boolean disposedOnFx;

        TrackingPlugin(PluginDescriptor descriptor) {
            this(descriptor, () -> { });
        }

        /** A plugin whose {@code dispose()} runs {@code lastStepOfDispose} once it has counted the disposal. */
        TrackingPlugin(PluginDescriptor descriptor, Runnable lastStepOfDispose) {
            this(() -> descriptor, lastStepOfDispose);
        }

        /** As above, its {@code getDescriptor()} returning what {@code descriptor} gives — or throwing what it throws. */
        TrackingPlugin(Supplier<PluginDescriptor> descriptor, Runnable lastStepOfDispose) {
            this.descriptor = descriptor;
            this.lastStepOfDispose = lastStepOfDispose;
        }

        @Override public PluginDescriptor getDescriptor() { return descriptor.get(); }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() {
            disposedOnFx = Platform.isFxApplicationThread();
            disposals.incrementAndGet();
            lastStepOfDispose.run();
        }
    }

    private static final class TrackingClassLoader extends URLClassLoader {
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean closedOnFx;

        TrackingClassLoader() {
            super(new URL[0]);
        }

        @Override public void close() throws IOException {
            closedOnFx = Platform.isFxApplicationThread();
            closes.incrementAndGet();
            super.close();
        }
    }
}
