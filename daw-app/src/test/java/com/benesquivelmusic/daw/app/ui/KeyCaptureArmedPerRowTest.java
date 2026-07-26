package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.ControlKind;
import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 306 — per-row armed key capture (Settings View Design Book §6.5,
 * rejection #7 / §1.9). A {@code KEY_CAPTURE} {@link SettingRow} captures a
 * keystroke <em>only</em> while explicitly armed: disarmed, its field-local
 * filter consumes nothing, so ordinary keys keep flowing (nothing is globally
 * swallowed); armed, the next non-modifier keystroke becomes the row value,
 * is consumed, and disarms the row; ESC disarms without clearing the binding.
 *
 * <p>Propagation is asserted through a parent-level bubbling
 * {@code addEventHandler} on the event PAYLOAD (the {@link KeyCode}), never
 * {@code Event.getSource()} identity — JavaFX rewrites the source per node on
 * bubble ({@code feedback_javafx_bubbling_event_test_pitfall}). A consumed
 * event never reaches the parent's bubbling handler; an unconsumed one
 * always does.</p>
 *
 * <h2>Why the disarmed Up/Down check reads the caret, not the parent</h2>
 *
 * <p>JavaFX itself maps UP → {@code home()} and DOWN → {@code end()} on every
 * text input control and auto-consumes them at the target (JavaFX 26
 * {@code TextInputControlBehavior} constructor key mappings; its catch-all
 * additionally consumes most unmodified {@code KEY_PRESSED} events). So
 * Up/Down fired <em>at the field</em> can never reach a parent bubbling
 * handler on any JavaFX {@code TextField} — armed or not, story or no story.
 * What the story's rejection-#7 guard actually requires is that the
 * <em>capture filter</em> passes them through while disarmed, and that is
 * observable: the filter runs in the capture phase <em>before</em> the
 * field's own behavior, so the caret only moves if the filter did not
 * consume. The direct parent-propagation assertion uses an alt-modified
 * key, which the field's behavior genuinely leaves alone.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KeyCaptureArmedPerRowTest {

    private <T> T runOnFxThread(Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                // Capture AssertionError too — an FX-thread assertion must
                // fail the test, not vanish into the FX exception handler.
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX task completed within the timeout")
                .isTrue();
        Throwable thrown = error.get();
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown instanceof Exception e) {
            throw e;
        }
        return ref.get();
    }

    /**
     * A realized KEY_CAPTURE row under a parent whose bubbling handler
     * records every {@code KEY_PRESSED} payload code that reaches it.
     */
    private record Fixture(SettingRow row, TextField field, List<KeyCode> reachedParent) {}

    /**
     * Builds a row for a <em>real</em> catalogue KEY_CAPTURE descriptor
     * (any keybinding row — the capture machinery is descriptor-agnostic),
     * realizes its skin in a Scene, and hooks the parent payload recorder.
     */
    private static Fixture buildFixture(Object initialValue) {
        SettingDescriptor<?> descriptor = SettingsCatalogue.create().categories().stream()
                .flatMap(category -> category.groups().stream())
                .flatMap(group -> group.settings().stream())
                .filter(d -> d.controlKind() == ControlKind.KEY_CAPTURE)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the catalogue carries no KEY_CAPTURE descriptor"));
        SettingRow row = new SettingRow(descriptor, initialValue, SettingRow.ControlHints.none());
        StackPane root = new StackPane(row);
        new Scene(root, 800, 240);
        root.applyCss();
        root.layout();
        TextField field = (TextField) row.lookup(".key-capture");
        assertThat(field)
                .as("the KEY_CAPTURE row's editor is the .key-capture field")
                .isNotNull();
        List<KeyCode> reachedParent = new ArrayList<>();
        root.addEventHandler(KeyEvent.KEY_PRESSED, event -> reachedParent.add(event.getCode()));
        return new Fixture(row, field, reachedParent);
    }

    private static void press(TextField field, KeyCode code, boolean alt) {
        Event.fireEvent(field, new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false));
    }

    @Test
    void disarmedRowConsumesNothing() throws Exception {
        runOnFxThread(() -> {
            KeyCombination binding = new KeyCodeCombination(KeyCode.J, KeyCombination.ALT_DOWN);
            Fixture fx = buildFixture(binding);

            // A key the field's own behavior leaves alone bubbles all the
            // way to the parent — the disarmed filter consumed nothing.
            press(fx.field(), KeyCode.K, true);
            assertThat(fx.reachedParent())
                    .as("disarmed: an alt-modified key must bubble to the parent")
                    .contains(KeyCode.K);

            // Up/Down pass through the disarmed filter into the field's own
            // caret handling (see class Javadoc — the toolkit consumes them
            // at the target, so caret motion is the propagation proof).
            assertThat(fx.field().getText())
                    .as("the seeded binding renders display text (caret proof needs it)")
                    .isNotEmpty();
            fx.field().positionCaret(0);
            press(fx.field(), KeyCode.DOWN, false);
            assertThat(fx.field().getCaretPosition())
                    .as("disarmed: DOWN must reach the field's own end() handling")
                    .isEqualTo(fx.field().getText().length());
            press(fx.field(), KeyCode.UP, false);
            assertThat(fx.field().getCaretPosition())
                    .as("disarmed: UP must reach the field's own home() handling")
                    .isZero();

            // The disarmed filter neither armed nor captured anything.
            assertThat(fx.row().armedProperty().get()).isFalse();
            assertThat(fx.row().getValue()).isEqualTo(binding);
            return null;
        });
    }

    @Test
    void enterArmsThenNextKeystrokeIsCapturedConsumedAndDisarms() throws Exception {
        runOnFxThread(() -> {
            Fixture fx = buildFixture(null);

            // ENTER at the disarmed field arms — and is consumed only
            // because it arms (no double toggling, §6.5).
            press(fx.field(), KeyCode.ENTER, false);
            assertThat(fx.row().armedProperty().get()).isTrue();
            assertThat(fx.reachedParent())
                    .as("the arming ENTER is consumed by the field's filter")
                    .doesNotContain(KeyCode.ENTER);

            // Armed: the keystroke becomes the binding, is consumed, and the
            // row disarms (one combination per arming).
            press(fx.field(), KeyCode.K, true);
            assertThat(fx.row().getValue())
                    .isEqualTo(new KeyCodeCombination(KeyCode.K, KeyCombination.ALT_DOWN));
            assertThat(fx.row().armedProperty().get()).isFalse();
            assertThat(fx.reachedParent())
                    .as("a captured keystroke never reaches the parent")
                    .doesNotContain(KeyCode.K);

            // Disarmed again: the very same keystroke propagates and the
            // captured binding stays untouched.
            press(fx.field(), KeyCode.B, true);
            assertThat(fx.reachedParent())
                    .as("after capture the row stops swallowing keys")
                    .contains(KeyCode.B);
            assertThat(fx.row().getValue())
                    .isEqualTo(new KeyCodeCombination(KeyCode.K, KeyCombination.ALT_DOWN));
            return null;
        });
    }

    @Test
    void escapeDisarmsWithoutClearingAndKeysPropagateAgain() throws Exception {
        runOnFxThread(() -> {
            KeyCombination binding = new KeyCodeCombination(KeyCode.J, KeyCombination.ALT_DOWN);
            Fixture fx = buildFixture(binding);

            fx.row().armedProperty().set(true);
            press(fx.field(), KeyCode.ESCAPE, false);
            assertThat(fx.row().armedProperty().get())
                    .as("ESC disarms the row")
                    .isFalse();
            assertThat(fx.row().getValue())
                    .as("ESC keeps the existing binding")
                    .isEqualTo(binding);
            assertThat(fx.reachedParent())
                    .as("the disarming ESC is consumed (it must not also close the dialog)")
                    .doesNotContain(KeyCode.ESCAPE);

            // Post-disarm, keys flow again (§6.5 — nothing stays swallowed).
            press(fx.field(), KeyCode.K, true);
            assertThat(fx.reachedParent()).contains(KeyCode.K);
            assertThat(fx.row().getValue()).isEqualTo(binding);
            return null;
        });
    }
}
