package com.benesquivelmusic.daw.app.ui;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.FailureSpineFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 336, studio-engineer ruling E9: the transient toast never takes
 * keyboard focus. A focus-traversable button takes focus when it is clicked
 * and then consumes the next Space, which is Play/Pause; with an action on
 * every failure toast, a click on it mid-take must leave focus where it was.
 * A history pill's action stays keyboard-reachable — Help ▸ Open Log Folder
 * and the inspector's history are the keyboard routes, a documented trade-off
 * (javafx-application-design "## 14. Accessibility, internationalization, and
 * quality").
 *
 * <p>Real {@link NotificationBar} and {@link NotificationPill} in a scene
 * (headless, never shown), clicked with the mouse events the button's
 * behaviour handles: the toast's action, then the dismiss button of a second
 * toast, each leaving focus on the timeline, and a history pill's action,
 * which takes it.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransientToastTakesNoFocusTest {

    @Test
    void theToastsActionAndDismissButtonsTakeNoFocusWhileAHistoryPillsActionDoes() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        boolean[] results = onFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            bar.show(NotificationLevel.ERROR, "Internal error in the user interface: boom.", "Open Log Folder",
                    opened::incrementAndGet);
            NotificationPill history = new NotificationPill(false);
            history.setEntry(new NotificationEntry(Instant.now(), NotificationLevel.ERROR, "an earlier failure",
                    Optional.of(opened::incrementAndGet), Optional.of("Open Log Folder")));
            TextField timeline = new TextField();
            Scene scene = new Scene(new VBox(timeline, bar, history), 800, 200);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            timeline.requestFocus();

            Button toastAction = bar.getPill().getActionButton();
            Node dismiss = bar.lookup(".notification-dismiss");
            boolean[] answers = new boolean[10];
            answers[0] = toastAction.isFocusTraversable();
            answers[1] = dismiss instanceof Button && dismiss.isFocusTraversable();
            answers[2] = history.getActionButton().isFocusTraversable();
            answers[3] = scene.getFocusOwner() == timeline;
            click(toastAction);
            answers[4] = scene.getFocusOwner() == timeline;
            bar.show(NotificationLevel.ERROR, "Internal error in the user interface: boom again.", "Open Log Folder",
                    opened::incrementAndGet);
            answers[7] = bar.isVisible();
            if (dismiss instanceof Button dismissButton) {
                click(dismissButton);
            }
            answers[8] = scene.getFocusOwner() == timeline;
            answers[9] = !bar.isVisible();
            click(history.getActionButton());
            answers[5] = scene.getFocusOwner() == history.getActionButton();
            answers[6] = dismiss instanceof Button;
            bar.dismiss();
            return answers;
        });

        assertThat(results[6]).as("the transient toast has a dismiss button").isTrue();
        assertThat(results[0]).as("the toast's action is out of the focus order").isFalse();
        assertThat(results[1]).as("so is its dismiss button").isFalse();
        assertThat(results[2]).as("a history pill's action stays keyboard-reachable").isTrue();
        assertThat(results[3]).as("non-vacuity: focus starts on the timeline").isTrue();
        assertThat(results[4]).as("clicking the toast's action leaves focus where it was").isTrue();
        assertThat(results[7]).as("non-vacuity: a second toast is up to dismiss").isTrue();
        assertThat(results[9]).as("non-vacuity: the click dismissed it").isTrue();
        assertThat(results[8]).as("clicking the toast's dismiss button leaves focus where it was").isTrue();
        assertThat(results[5]).as("non-vacuity: clicking a focus-traversable button takes focus").isTrue();
        assertThat(opened).as("the two action clicks ran their action; the dismiss ran none").hasValue(2);
    }

    /** A primary-button press and release, as the button's behaviour receives them. */
    private static void click(Button button) {
        Event.fireEvent(button, mouse(MouseEvent.MOUSE_PRESSED));
        Event.fireEvent(button, mouse(MouseEvent.MOUSE_RELEASED));
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(type, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false,
                true, false, true, null);
    }
}
