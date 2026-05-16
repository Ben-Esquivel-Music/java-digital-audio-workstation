package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.TrackStripSkin;

import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrackStripSkin#dispose()} removes every listener registered in
 * the skin's constructor. Mirrors {@link FaderDisposeTest}.
 *
 * <p>Verifies the contract two ways:
 *
 * <ol>
 *   <li>The skin's {@code registeredListenerCount()} drops back to zero.</li>
 *   <li>After dispose, mutating {@code mutedProperty},
 *       {@code armedProperty}, {@code selectedProperty} produces no
 *       skin-side reaction (the toggle buttons remain at their pre-dispose
 *       state).</li>
 * </ol>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TrackStripDisposeTest {

    @Test
    void disposeRemovesAllRegisteredListeners() {
        int[] counts = new int[2];
        boolean[] disposed = new boolean[1];
        runOnFxThread(() -> {
            TrackStrip s = new TrackStrip();
            StackPane root = new StackPane(s);
            new Scene(root, 320, 60);
            root.applyCss();
            root.layout();
            TrackStripSkin skin = (TrackStripSkin) s.getSkin();
            counts[0] = skin.registeredListenerCount();
            s.setSkin(null);
            counts[1] = skin.registeredListenerCount();
            disposed[0] = skin.isDisposed();
            return null;
        });
        assertThat(counts[0])
                .as("skin registers listeners on construction")
                .isGreaterThan(0);
        assertThat(counts[1])
                .as("dispose() removes every listener")
                .isEqualTo(0);
        assertThat(disposed[0]).isTrue();
    }

    @Test
    void mutationsAfterDisposeDoNotReachOldSkin() {
        boolean[] result = new boolean[3];
        runOnFxThread(() -> {
            TrackStrip s = new TrackStrip();
            StackPane root = new StackPane(s);
            new Scene(root, 320, 60);
            root.applyCss();
            root.layout();
            TrackStripSkin oldSkin = (TrackStripSkin) s.getSkin();
            ToggleButton oldMute = oldSkin.muteButton();
            ToggleButton oldArm = oldSkin.armButton();

            s.setSkin(null); // dispose the old skin

            // Mutate the control's properties after dispose. The old
            // skin's toggle buttons must NOT track these changes —
            // confirming the skin-side listeners were unregistered.
            s.setMuted(true);
            s.setArmed(true);
            s.setSelected(true);

            result[0] = oldMute.isSelected();
            result[1] = oldArm.isSelected();
            result[2] = oldSkin.isDisposed();
            return null;
        });
        assertThat(result[0])
                .as("old skin's M toggle does NOT react to post-dispose mute change")
                .isFalse();
        assertThat(result[1])
                .as("old skin's R toggle does NOT react to post-dispose arm change")
                .isFalse();
        assertThat(result[2]).isTrue();
    }
}
