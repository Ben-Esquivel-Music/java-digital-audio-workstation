package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/** Story 313 host-level dismissal, ordering, and retry regressions. */
@ExtendWith(JavaFxToolkitExtension.class)
class FirstRunWizardHostTest {

    private enum Route {
        FINISH,
        SKIP,
        ESCAPE,
        WINDOW_CLOSE,
        HEADER_GLYPH
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void everyHostRouteReturnsFromShowAndWait(Route route) {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            FirstRunWizard wizard = newWizard(model);
            AtomicInteger applies = new AtomicInteger();
            FirstRunWizardHost host = new FirstRunWizardHost(
                    wizard,
                    _ -> {
                        applies.incrementAndGet();
                        return Optional.empty();
                    },
                    _ -> { });
            DawgDialog<Void> dialog = host.dialogForTest();
            AtomicReference<Throwable> routeFailure = new AtomicReference<>();
            dialog.setOnShown(_ -> Platform.runLater(
                    () -> driveRoute(route, dialog, routeFailure)));

            Optional<Void> result = showWithWatchdog(host, routeFailure);

            rethrow(routeFailure.get());
            assertThat(result).isEmpty();
            assertThat(dialog.isShowing()).isFalse();
            assertThat(model.isFirstRunWizardCompleted()).isTrue();
            assertThat(wizard.hasOutcome()).isTrue();
            assertThat(wizard.wasFinished()).isEqualTo(route == Route.FINISH);
            assertThat(applies.get()).isEqualTo(route == Route.FINISH ? 1 : 0);
            return null;
        });
    }

    @Test
    void hiddenCancelKeepsClosePermissionWithoutRemovingTheHeaderGlyph() {
        runOnFxThread(() -> {
            FirstRunWizardHost host = new FirstRunWizardHost(
                    newWizard(newModel()), _ -> Optional.empty(), _ -> { });
            DawgDialog<Void> dialog = host.dialogForTest();
            Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);

            assertThat(dialog.getDialogPane().getButtonTypes())
                    .containsExactly(ButtonType.CANCEL);
            assertThat(cancel.isVisible()).isFalse();
            assertThat(cancel.isManaged()).isFalse();
            assertThat(dialog.getGraphic()).isNotNull();
            assertThat(dialog.getGraphic().getStyleClass()).contains("dawg-dialog-close");
            assertThat(dialog.getGraphic().getOnMouseClicked()).isNotNull();
            return null;
        });
    }

    @Test
    void failedApplyStaysOpenAndASecondFinishSucceeds() {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            FirstRunWizard wizard = newWizard(model);
            AtomicInteger attempts = new AtomicInteger();
            FirstRunWizardHost host = new FirstRunWizardHost(
                    wizard,
                    _ -> {
                        if (attempts.incrementAndGet() == 1) {
                            throw new IllegalStateException("selected ASIO device is unavailable");
                        }
                        return Optional.empty();
                    },
                    _ -> { });
            DawgDialog<Void> dialog = host.dialogForTest();
            AtomicReference<Throwable> routeFailure = new AtomicReference<>();
            AtomicBoolean stayedOpen = new AtomicBoolean();
            AtomicBoolean flagStayedUnset = new AtomicBoolean();
            AtomicBoolean errorWasVisible = new AtomicBoolean();
            dialog.setOnShown(_ -> Platform.runLater(() -> {
                try {
                    Button next = nextButton(dialog);
                    advanceToDone(next);
                    next.fire();
                    stayedOpen.set(dialog.isShowing());
                    flagStayedUnset.set(!model.isFirstRunWizardCompleted()
                            && !wizard.hasOutcome());
                    errorWasVisible.set(wizard.isOutcomeErrorVisible()
                            && wizard.outcomeErrorText().contains(
                                    "selected ASIO device is unavailable"));
                    next.fire();
                } catch (Throwable failure) {
                    routeFailure.set(failure);
                    dialog.close();
                }
            }));

            Optional<Void> result = showWithWatchdog(host, routeFailure);

            rethrow(routeFailure.get());
            assertThat(result).isEmpty();
            assertThat(stayedOpen).isTrue();
            assertThat(flagStayedUnset).isTrue();
            assertThat(errorWasVisible).isTrue();
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(model.isFirstRunWizardCompleted()).isTrue();
            assertThat(wizard.wasFinished()).isTrue();
            assertThat(dialog.isShowing()).isFalse();
            return null;
        });
    }

    @Test
    void restartNoticeIsPublishedOnlyAfterSuccessfulFinish() {
        runOnFxThread(() -> {
            AtomicReference<String> notice = new AtomicReference<>();
            FirstRunWizardHost host = new FirstRunWizardHost(
                    newWizard(newModel()),
                    _ -> Optional.of("Restart required for: Audio backend. Changes apply when you relaunch."),
                    notice::set);
            DawgDialog<Void> dialog = host.dialogForTest();
            dialog.setOnShown(_ -> Platform.runLater(() -> {
                Button next = nextButton(dialog);
                advanceToDone(next);
                next.fire();
            }));

            AtomicReference<Throwable> noticeFailure = new AtomicReference<>();
            showWithWatchdog(host, noticeFailure);
            rethrow(noticeFailure.get());

            assertThat(notice.get()).contains("Restart required").contains("relaunch");

            AtomicBoolean cleanNotice = new AtomicBoolean();
            FirstRunWizardHost cleanHost = new FirstRunWizardHost(
                    newWizard(newModel()), _ -> Optional.empty(),
                    _ -> cleanNotice.set(true));
            DawgDialog<Void> cleanDialog = cleanHost.dialogForTest();
            cleanDialog.setOnShown(_ -> Platform.runLater(() -> {
                Button next = nextButton(cleanDialog);
                advanceToDone(next);
                next.fire();
            }));
            AtomicReference<Throwable> cleanFailure = new AtomicReference<>();
            showWithWatchdog(cleanHost, cleanFailure);
            rethrow(cleanFailure.get());

            assertThat(cleanNotice).isFalse();
            return null;
        });
    }

    private static void driveRoute(
            Route route, DawgDialog<Void> dialog, AtomicReference<Throwable> failure) {
        try {
            switch (route) {
                case FINISH -> {
                    Button next = nextButton(dialog);
                    advanceToDone(next);
                    next.fire();
                }
                case SKIP -> ((Button) dialog.getDialogPane()
                        .lookup("#first-run-wizard-skip")).fire();
                case ESCAPE -> Event.fireEvent(
                        dialog.getDialogPane().getScene().getWindow(), escapeEvent());
                case WINDOW_CLOSE -> {
                    Window window = dialog.getDialogPane().getScene().getWindow();
                    Event.fireEvent(window,
                            new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
                }
                case HEADER_GLYPH -> {
                    Node glyph = dialog.getGraphic();
                    if (glyph == null) {
                        throw new AssertionError("shown wizard host has no header close glyph");
                    }
                    Event.fireEvent(glyph, mouseClick());
                }
            }
        } catch (Throwable thrown) {
            failure.set(thrown);
            dialog.close();
        }
    }

    private static Button nextButton(DawgDialog<Void> dialog) {
        Node button = dialog.getDialogPane().lookup("#first-run-wizard-next");
        if (button instanceof Button next) {
            return next;
        }
        throw new AssertionError("wizard Next/Finish button was not found");
    }

    private static void advanceToDone(Button next) {
        next.fire();
        next.fire();
        next.fire();
    }

    private static KeyEvent escapeEvent() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "",
                KeyCode.ESCAPE, false, false, false, false);
    }

    private static MouseEvent mouseClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, true, false, true, null);
    }

    private static Optional<Void> showWithWatchdog(
            FirstRunWizardHost host, AtomicReference<Throwable> failure) {
        DawgDialog<Void> dialog = host.dialogForTest();
        PauseTransition watchdog = new PauseTransition(Duration.seconds(2));
        watchdog.setOnFinished(_ -> {
            failure.compareAndSet(null,
                    new AssertionError("first-run wizard dismissal route timed out"));
            dialog.close();
        });
        watchdog.play();
        try {
            return host.showAndWait();
        } finally {
            watchdog.stop();
        }
    }

    private static FirstRunWizard newWizard(SettingsModel model) {
        return new FirstRunWizard(SettingsCatalogue.create(), model, null);
    }

    private static SettingsModel newModel() {
        return new SettingsModel(Preferences.userRoot()
                .node("firstRunWizardHostTest_" + System.nanoTime()));
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
