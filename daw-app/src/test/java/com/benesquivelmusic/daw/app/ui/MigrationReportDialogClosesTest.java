package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D32 — the migration report dialog with a
 * {@code Roll back…} closes the way a dialog does: OK closes it; a roll-back
 * the user confirms closes it and rolls back once — after it closed
 * (decision D42); a roll-back the user declines leaves it open; Esc and the
 * window's close button close it as OK does — the suppression checkbox
 * honoured, nothing rolled back. The dialog is shown for real and its
 * buttons, keys and window are driven as a user drives them; the roll-back
 * confirmation is answered as a user answers it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MigrationReportDialogClosesTest {

    private static final String MIGRATION_DIALOG_TITLE = "Project Migrated";
    private static final String CONFIRMATION_TITLE = "Roll back migration?";

    @TempDir
    Path projectDirectory;

    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicReference<ButtonType> confirmationAnswer = new AtomicReference<>(ButtonType.OK);
    /** Whether the user presses Roll back… as soon as the migration dialog shows. */
    private final AtomicBoolean pressRollBackWhenShown = new AtomicBoolean();
    private final AtomicReference<Throwable> answerFailure = new AtomicReference<>();
    private final ListChangeListener<Window> answerConfirmations = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && CONFIRMATION_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> press(stage, confirmationAnswer.get()));
                } else if (window instanceof Stage stage && MIGRATION_DIALOG_TITLE.equals(stage.getTitle())
                        && pressRollBackWhenShown.get()) {
                    Platform.runLater(() -> press(stage, MigrationReportDialog.ROLLBACK_BUTTON_TYPE));
                }
            }
        }
    };
    private MigrationReportDialog dialog;

    @BeforeEach
    void answerTheRollBackConfirmation() throws Exception {
        runOnFx(() -> Window.getWindows().addListener(answerConfirmations));
    }

    @AfterEach
    void hideWhatIsLeft() throws Exception {
        runOnFx(() -> {
            Window.getWindows().removeListener(answerConfirmations);
            if (dialog != null && dialog.isShowing()) {
                dialog.getDialogPane().getScene().getWindow().hide();
            }
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage stage && MIGRATION_DIALOG_TITLE.equals(stage.getTitle())) {
                    stage.hide();
                }
            }
        });
        assertThat(answerFailure.get()).as("the confirmation was answered").isNull();
    }

    @Test
    void okClosesTheDialogBesideARollBackAndRollsNothingBack() throws Exception {
        showDialog();

        runOnFx(() -> button(MigrationReportDialog.OK_BUTTON_TYPE).fire());

        assertThat(onFx(dialog::isShowing)).as("OK closed it").isFalse();
        assertThat(rollbacks).hasValue(0);
    }

    @Test
    void aConfirmedRollBackClosesTheDialogAndRollsBackOnce() throws Exception {
        confirmationAnswer.set(ButtonType.OK);
        pressRollBackWhenShown.set(true);
        AtomicReference<Boolean> showingWhenRolledBack = new AtomicReference<>();

        // Bounded at the fixture's guard: a dialog that did not close never lets showUnlessSuppressed return.
        boolean shown = onFx(() -> MigrationReportDialog.showUnlessSuppressed(sampleReport(), projectDirectory, false, () -> {
            rollbacks.incrementAndGet();
            showingWhenRolledBack.set(migrationDialogShowing());
        }));

        assertThat(shown).as("fixture: shown").isTrue();
        assertThat(onFx(MigrationReportDialogClosesTest::migrationDialogShowing))
                .as("the confirmed roll-back closed it").isFalse();
        assertThat(rollbacks).as("rolled back once").hasValue(1);
        assertThat(showingWhenRolledBack.get())
                .as("decision D42: the roll-back ran once the dialog had closed, not under it").isFalse();
    }

    @Test
    void aDeclinedRollBackLeavesTheDialogOpen() throws Exception {
        showDialog();
        confirmationAnswer.set(ButtonType.CANCEL);

        runOnFx(() -> button(MigrationReportDialog.ROLLBACK_BUTTON_TYPE).fire());

        assertThat(onFx(dialog::isShowing)).as("declined: still open").isTrue();
        assertThat(rollbacks).hasValue(0);
        runOnFx(() -> button(MigrationReportDialog.OK_BUTTON_TYPE).fire());
        assertThat(onFx(dialog::isShowing)).as("and OK still closes it").isFalse();
    }

    @Test
    void escapeClosesTheDialogAsOkDoesHonouringTheSuppressionCheckbox() throws Exception {
        showDialog();
        runOnFx(() -> suppressionCheckbox().setSelected(true));

        runOnFx(() -> Event.fireEvent(dialog.getDialogPane(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.ESCAPE, false, false, false, false)));

        assertThat(onFx(dialog::isShowing)).as("Esc closed it").isFalse();
        assertThat(rollbacks).as("Esc rolls nothing back").hasValue(0);
        assertThat(MigrationSuppression.isSuppressed(projectDirectory, sampleReport().toVersion()))
                .as("the checkbox was honoured").isTrue();
    }

    @Test
    void theWindowsCloseButtonClosesTheDialogAsOkDoes() throws Exception {
        showDialog();

        runOnFx(() -> {
            Window window = dialog.getDialogPane().getScene().getWindow();
            window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
        });

        assertThat(onFx(dialog::isShowing)).as("the close button closed it").isFalse();
        assertThat(rollbacks).hasValue(0);
        assertThat(MigrationSuppression.isSuppressed(projectDirectory, sampleReport().toVersion()))
                .as("the checkbox was left clear").isFalse();
    }

    private void showDialog() throws Exception {
        runOnFx(() -> {
            dialog = new MigrationReportDialog(sampleReport(), projectDirectory, rollbacks::incrementAndGet);
            dialog.show();
        });
        assertThat(onFx(dialog::isShowing)).as("fixture: shown").isTrue();
    }

    private Button button(ButtonType type) {
        Node button = dialog.getDialogPane().lookupButton(type);
        assertThat(button).as("the dialog has %s", type.getText()).isInstanceOf(Button.class);
        return (Button) button;
    }

    /** Whether a migration report dialog is showing. FX thread. */
    private static boolean migrationDialogShowing() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && MIGRATION_DIALOG_TITLE.equals(stage.getTitle()) && stage.isShowing()) {
                return true;
            }
        }
        return false;
    }

    private CheckBox suppressionCheckbox() {
        return (CheckBox) ((VBox) dialog.getDialogPane().getContent()).getChildren().get(2);
    }

    /** Presses the button of {@code type} in the dialog shown in {@code stage}. */
    private void press(Stage stage, ButtonType type) {
        try {
            Node root = stage.getScene().getRoot();
            DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
            ((Button) pane.lookupButton(type)).fire();
        } catch (Throwable t) {
            answerFailure.set(t);
            stage.hide();
        }
    }

    private static MigrationReport sampleReport() {
        return new MigrationReport(1, 3, List.of(
                new MigrationReport.AppliedMigration(1, 2, "rename pan-law attribute"),
                new MigrationReport.AppliedMigration(2, 3, "add bed-bus channel-gains")), Instant.now());
    }
}
