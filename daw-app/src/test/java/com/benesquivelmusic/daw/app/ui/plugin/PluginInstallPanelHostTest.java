package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/** Story 313 regression coverage for the content-owned install host. */
@ExtendWith(JavaFxToolkitExtension.class)
class PluginInstallPanelHostTest {

    private enum Route {
        CANCEL,
        ESCAPE,
        WINDOW_CLOSE
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void installHostReturnsFromEveryDismissRoute(Route route) {
        PluginRegistry registry = new PluginRegistry();
        try {
            runOnFxThread(() -> {
                DawgDialog<Void> dialog = PluginInstallPanel.createDialog(
                        validInspection(), registry, () -> { });
                Button hiddenCancel = (Button) dialog.getDialogPane()
                        .lookupButton(ButtonType.CANCEL);
                AtomicReference<Throwable> routeFailure = new AtomicReference<>();
                dialog.setOnShown(_ -> Platform.runLater(
                        () -> driveRoute(route, dialog, routeFailure)));

                Optional<Void> result = showWithWatchdog(dialog, routeFailure);

                rethrow(routeFailure.get());
                assertThat(result)
                        .as("Dialog<Void> never leaks its cancel ButtonType as a result")
                        .isEmpty();
                assertThat(dialog.isShowing()).isFalse();
                assertThat(dialog.getDialogPane().getButtonTypes())
                        .containsExactly(ButtonType.CANCEL);
                assertThat(hiddenCancel.isVisible()).isFalse();
                assertThat(hiddenCancel.isManaged()).isFalse();
                return null;
            });
        } finally {
            registry.disposeAll();
        }
    }

    private static void driveRoute(
            Route route, DawgDialog<Void> dialog, AtomicReference<Throwable> failure) {
        try {
            switch (route) {
                case CANCEL -> {
                    Node cancel = dialog.getDialogPane().lookup("#plugin-install-cancel");
                    if (cancel instanceof Button button) {
                        button.fire();
                    } else {
                        throw new AssertionError("plugin install Cancel button was not found");
                    }
                }
                case ESCAPE -> Event.fireEvent(
                        dialog.getDialogPane().getScene().getWindow(), escapeEvent());
                case WINDOW_CLOSE -> {
                    Window window = dialog.getDialogPane().getScene().getWindow();
                    Event.fireEvent(window,
                            new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
                }
            }
        } catch (Throwable thrown) {
            failure.set(thrown);
            dialog.close();
        }
    }

    private static KeyEvent escapeEvent() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "",
                KeyCode.ESCAPE, false, false, false, false);
    }

    private static Optional<Void> showWithWatchdog(
            DawgDialog<Void> dialog, AtomicReference<Throwable> failure) {
        PauseTransition watchdog = new PauseTransition(Duration.seconds(2));
        watchdog.setOnFinished(_ -> {
            failure.compareAndSet(null,
                    new AssertionError("plugin install dismissal route timed out"));
            dialog.close();
        });
        watchdog.play();
        try {
            return dialog.showAndWait();
        } finally {
            watchdog.stop();
        }
    }

    private static JarInspection validInspection() {
        return new JarInspection(
                Path.of("story-313-plugin.jar"), true, true,
                new PluginManifestReader.BundleResult.Valid(List.of(
                        PluginTestJars.manifest(InstallFixturePluginC.class))),
                1, 1, 0);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new AssertionError(failure);
        }
    }
}
