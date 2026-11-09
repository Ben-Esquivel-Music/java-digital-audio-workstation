package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.event.ActionEvent;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tempo editor's commit refuses a tempo that is not finite (story 334,
 * studio ruling E10.5): {@code Double.parseDouble} reads "NaN" and the
 * infinities as numbers, and the transport refuses them as it refuses an
 * out-of-range tempo, so the editor treats them as the invalid input they
 * are — the tempo stays, no history entry is added, the project stays clean,
 * and the status bar and an ERROR notification say the tempo is invalid.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TempoEditControllerInvalidInputTest {

    private static final String INVALID = "Invalid tempo — must be 20–999 BPM";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", " NaN "})
    void aNonFiniteTempoIsRefusedAsInvalidInput(String typed) throws Exception {
        DawProject project = new DawProject("Song", new AudioFormat(48_000, 2, 16, 256));
        project.getTransport().setTempo(97.5);
        project.markClean();
        UndoManager undo = new UndoManager();
        Label tempoLabel = new Label("97.5");
        HBox box = onFx(() -> new HBox(new Label("BPM"), tempoLabel));
        List<String> statuses = new CopyOnWriteArrayList<>();
        NotificationBar notifications = onFx(NotificationBar::new);
        TempoEditController editor = new TempoEditController(tempoLabel, () -> project, () -> undo, () -> { },
                (text, _) -> statuses.add(text), notifications, () -> true);
        runOnFx(editor::install);

        runOnFx(() -> commitTempo(box, tempoLabel, typed));

        assertThat(project.getTransport().getTempo()).as("the tempo stays").isEqualTo(97.5);
        assertThat(undo.undoSize()).as("no history entry").isZero();
        assertThat(project.isDirty()).as("not dirty").isFalse();
        assertThat(statuses).as("the status bar says it is invalid").containsExactly(INVALID);
        assertThat(onFx(notifications::getCurrentLevel)).isEqualTo(NotificationLevel.ERROR);
        assertThat(onFx(notifications::getMessage)).isEqualTo(INVALID);
        assertThat(onFx(() -> box.getChildren().get(1))).as("the label takes the editor's place again")
                .isSameAs(tempoLabel);
    }

    /** Double-clicks the tempo label, types {@code text} into the editor that replaced it, and presses Enter. */
    private static void commitTempo(HBox box, Label tempoLabel, String text) {
        tempoLabel.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2,
                false, false, false, false, false, false, false, false, false, false, null));
        Node shown = box.getChildren().get(1);
        assertThat(shown).as("the double-click opened the editor").isInstanceOf(TextField.class);
        TextField field = (TextField) shown;
        field.executeAccessibleAction(AccessibleAction.SET_TEXT, text);
        field.fireEvent(new ActionEvent());
    }
}
