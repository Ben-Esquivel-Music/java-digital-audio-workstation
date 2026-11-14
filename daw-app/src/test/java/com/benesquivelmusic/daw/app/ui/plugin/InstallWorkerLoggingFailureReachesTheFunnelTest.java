package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.FailureSpineFixture;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.JarOnlyExceptions;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.awaitOnFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 336 and the Note from Story 334: what a log handler throws while the
 * install panel's install worker logs a plugin's failure reaches the top of
 * that worker, {@code plugin-install-load}, and so the failure funnel through
 * the default handler — once, as that very error — while the installation
 * still completes and the install button is enabled again.
 *
 * <p>The plugin's failure is an exception only its closed JAR can format
 * ({@link JarOnlyExceptions}; dawg-annotations-reflection, "### Other
 * reflection / {@code MethodHandles} uses (NOT annotation-driven)"): the
 * handler formats it as the JDK's console
 * handler does, which throws {@link NoClassDefFoundError}. The funnel is the
 * production default wiring, {@link FailureSpineFixture#launch}
 * ({@code DawLauncher.prepareLaunch}), with the {@code MainController}
 * substitute of {@link FailureSpineFixture#armOnFx()}: the
 * {@code FxDispatcher}, {@code NotificationBar} and
 * {@code NotificationHistoryService} built as
 * {@code MainController.initializeNotificationBar} and
 * {@code DawApplication.start} build them, and the same production units
 * called. The test lives in this package because the panel's loader seam and
 * {@code installationForTest()} are package-private here.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InstallWorkerLoggingFailureReachesTheFunnelTest {

    private static final String DIALOG_TITLE = "Install plugin";
    private static final String WORKER = "plugin-install-load";

    @Test
    void anInstallWorkerWhoseLogHandlerThrowsEndsIntoTheFunnelAndTheInstallationCompletes(@TempDir Path directory)
            throws Exception {
        RuntimeException jarOnly = JarOnlyExceptions.loadThenClose(directory.resolve("exceptions"));
        assertThat(catchThrowable(jarOnly::getMessage))
                .as("non-vacuity: only its closed JAR can give the exception's message")
                .isInstanceOf(NoClassDefFoundError.class);
        JarInspection inspection = new JarInspection(Path.of("licensed.jar"), true, true,
                new PluginManifestReader.BundleResult.Valid(List.of(new PluginManifest("com.example.Licensed",
                        new PluginDescriptor("com.example.licensed", "Licensed", "1.0", "Example Audio",
                                PluginType.EFFECT)))), 1, 0, 0);
        PluginRegistry registry = new PluginRegistry();
        List<Throwable> formattingFailures = new CopyOnWriteArrayList<>();
        Handler formatting = formattingThenRethrowing(formattingFailures);
        Logger panelLog = Logger.getLogger(PluginInstallPanel.class.getName());
        List<String> errors = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> dismissErrors = dismissingErrors(errors);
        try (FailureSpineFixture spine = FailureSpineFixture.launch(directory.resolve("settings"))) {
            spine.armOnFx();
            panelLog.addHandler(formatting);
            runOnFx(() -> Window.getWindows().addListener(dismissErrors));
            PluginInstallPanel panel = onFx(() -> new PluginInstallPanel(inspection, registry, () -> { },
                    entry -> {
                        throw jarOnly;
                    }));

            runOnFx(() -> panel.primaryButtonForTest().fire());
            panel.installationForTest().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
            awaitOnFx("the install worker's end in the funnel", () -> !severeRecordsOnTheWorker(spine).isEmpty());

            assertThat(formattingFailures).as("the handler's formatter could not read the plugin's exception")
                    .singleElement().isInstanceOf(NoClassDefFoundError.class);
            Throwable handlerThrew = formattingFailures.getFirst();
            assertThat(severeRecordsOnTheWorker(spine)).as("one record, of what the handler threw").singleElement()
                    .satisfies(record -> {
                        assertThat(record.getMessage())
                                .startsWith("Failure thread.uncaught (ERROR) on thread \"" + WORKER + "\"");
                        assertThat(record.getThrown() == handlerThrew).as("that very error").isTrue();
                    });
            awaitOnFx("the worker's toast", () -> spine.bar().getMessage()
                    .startsWith("Internal error in the background task “plugin install load”: "));
            assertThat(errors).as("the installation completed, naming the failure by its class")
                    .containsExactly("Some plugins could not be installed:\ncom.example.Licensed — LicenceException");
            assertThat(onFx(() -> panel.primaryButtonForTest().isDisabled()))
                    .as("the engineer can install again").isFalse();
            assertThat(onFx(registry::getEntries)).as("nothing is installed").isEmpty();
        } finally {
            panelLog.removeHandler(formatting);
            FailureSpineFixture.runOnFx(() -> {
                Window.getWindows().removeListener(dismissErrors);
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                        window.hide();
                    }
                }
            });
            registry.disposeAll();
        }
    }

    /** The funnel's SEVERE records about a failure on the install worker. */
    private static List<LogRecord> severeRecordsOnTheWorker(FailureSpineFixture spine) {
        return spine.funnelRecords().stream()
                .filter(record -> record.getLevel() == Level.SEVERE)
                .filter(record -> record.getMessage().contains("on thread \"" + WORKER + "\""))
                .toList();
    }

    /**
     * A handler that formats each WARNING record as the JDK's console handler
     * does, which runs what the record's exception runs to give its message,
     * and records and rethrows what that throws.
     */
    private static Handler formattingThenRethrowing(List<Throwable> thrown) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() != Level.WARNING) {
                    return;
                }
                try {
                    new SimpleFormatter().format(record);
                } catch (RuntimeException | Error formatting) {
                    thrown.add(formatting);
                    throw formatting;
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    /** Records the message of each {@value #DIALOG_TITLE} error dialog, then hides it on a later FX turn. */
    private static ListChangeListener<Window> dismissingErrors(List<String> errors) {
        return change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                        Platform.runLater(() -> {
                            try {
                                DialogPane pane = (DialogPane) window.getScene().getRoot();
                                Node message = pane.getContent().lookup(".dawg-dialog-message");
                                errors.add(message instanceof Label label ? label.getText()
                                        : "<not an error dialog: " + pane.getContent() + ">");
                            } finally {
                                window.hide();
                            }
                        });
                    }
                }
            }
        };
    }
}
