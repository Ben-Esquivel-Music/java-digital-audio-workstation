package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyboard parity (matches the existing arrangement-view shortcut
 * conventions; see {@code KeyBindingManager}):
 *
 * <ul>
 *   <li>{@code M} toggles {@link TrackStrip#mutedProperty()}.</li>
 *   <li>{@code S} toggles {@link TrackStrip#soloedProperty()}.</li>
 *   <li>{@code R} toggles {@link TrackStrip#armedProperty()}.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripKeyboardTest {

    private static TrackStrip attached() {
        return runOnFxThread(() -> {
            TrackStrip s = new TrackStrip();
            s.setTrackName("Bass");
            StackPane root = new StackPane(s);
            new Scene(root, 320, 60);
            root.applyCss();
            root.layout();
            s.requestFocus();
            return s;
        });
    }

    private static void press(TrackStrip s, KeyCode code) {
        runOnFxThread(() -> {
            KeyEvent ev = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                    false, false, false, false);
            Event.fireEvent(s, ev);
            return null;
        });
    }

    @Test
    void pressingMTogglesMuted() {
        TrackStrip s = attached();
        assertThat(s.isMuted()).isFalse();
        press(s, KeyCode.M);
        assertThat(s.isMuted()).as("M toggles mute on").isTrue();
        press(s, KeyCode.M);
        assertThat(s.isMuted()).as("M toggles mute off").isFalse();
    }

    @Test
    void pressingSTogglesSoloed() {
        TrackStrip s = attached();
        assertThat(s.isSoloed()).isFalse();
        press(s, KeyCode.S);
        assertThat(s.isSoloed()).isTrue();
        press(s, KeyCode.S);
        assertThat(s.isSoloed()).isFalse();
    }

    @Test
    void pressingRTogglesArmed() {
        TrackStrip s = attached();
        assertThat(s.isArmed()).isFalse();
        press(s, KeyCode.R);
        assertThat(s.isArmed()).isTrue();
        press(s, KeyCode.R);
        assertThat(s.isArmed()).isFalse();
    }
}
