package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.Cursor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class AudioEditorViewTest {

    private AudioEditorView createOnFxThread() throws Exception {
        AtomicReference<AudioEditorView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new AudioEditorView());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread timed out creating AudioEditorView")
                .isTrue();
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread timed out running action")
                .isTrue();
    }

    // ── Component tests ─────────────────────────────────────────────────────

    @Test
    void shouldHaveWaveformDisplay() throws Exception {
        AudioEditorView view = createOnFxThread();

        assertThat(view.getWaveformDisplay()).isNotNull();
        assertThat(view.getWaveformDisplay()).isInstanceOf(WaveformDisplay.class);
    }

    @Test
    void shouldHaveTrimButton() throws Exception {
        AudioEditorView view = createOnFxThread();

        assertThat(view.getTrimButton()).isNotNull();
    }

    @Test
    void shouldHaveFadeInButton() throws Exception {
        AudioEditorView view = createOnFxThread();

        assertThat(view.getFadeInButton()).isNotNull();
    }

    @Test
    void shouldHaveFadeOutButton() throws Exception {
        AudioEditorView view = createOnFxThread();

        assertThat(view.getFadeOutButton()).isNotNull();
    }

    // ── Tool cursor tests ───────────────────────────────────────────────────

    @Test
    void shouldSetCursorForPencilTool() throws Exception {
        AudioEditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));

        assertThat(view.getWaveformDisplay().getCursor()).isEqualTo(Cursor.CROSSHAIR);
    }

    @Test
    void shouldSetCursorForEraserTool() throws Exception {
        AudioEditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.ERASER));

        assertThat(view.getWaveformDisplay().getCursor()).isEqualTo(Cursor.HAND);
    }

    @Test
    void shouldSetCursorForPointerTool() throws Exception {
        AudioEditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));
        runOnFxThread(() -> view.setActiveEditTool(EditTool.POINTER));

        assertThat(view.getWaveformDisplay().getCursor()).isEqualTo(Cursor.DEFAULT);
    }

    // ── Audio handle button state tests ─────────────────────────────────────

    @Test
    void audioHandleButtonsShouldBeDisabledByDefault() throws Exception {
        AudioEditorView view = createOnFxThread();

        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldBeDisabledForMidiTrack() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track midiTrack = new Track("Keys", TrackType.MIDI);

        runOnFxThread(() -> view.setSelectedTrack(midiTrack));

        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldBeDisabledForEmptyAudioTrack() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);

        runOnFxThread(() -> view.setSelectedTrack(audioTrack));

        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldBeEnabledForAudioTrackWithClip() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, "/audio/take1.wav"));

        runOnFxThread(() -> view.setSelectedTrack(audioTrack));

        assertThat(view.getTrimButton().isDisabled()).isFalse();
        assertThat(view.getFadeInButton().isDisabled()).isFalse();
        assertThat(view.getFadeOutButton().isDisabled()).isFalse();
    }

    @Test
    void audioHandleButtonsShouldDisableWhenTrackCleared() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, null));

        runOnFxThread(() -> view.setSelectedTrack(audioTrack));
        assertThat(view.getTrimButton().isDisabled()).isFalse();

        runOnFxThread(() -> view.setSelectedTrack(null));

        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    // ── Callback tests ──────────────────────────────────────────────────────

    @Test
    void trimButtonShouldFireCallback() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, null));
        AtomicReference<Boolean> trimFired = new AtomicReference<>(false);

        runOnFxThread(() -> view.setSelectedTrack(audioTrack));
        runOnFxThread(() -> {
            view.setOnTrimAction(() -> trimFired.set(true));
            view.getTrimButton().fire();
        });

        assertThat(trimFired.get()).isTrue();
    }

    @Test
    void fadeInButtonShouldFireCallback() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, null));
        AtomicReference<Boolean> fadeInFired = new AtomicReference<>(false);

        runOnFxThread(() -> view.setSelectedTrack(audioTrack));
        runOnFxThread(() -> {
            view.setOnFadeInAction(() -> fadeInFired.set(true));
            view.getFadeInButton().fire();
        });

        assertThat(fadeInFired.get()).isTrue();
    }

    @Test
    void fadeOutButtonShouldFireCallback() throws Exception {
        AudioEditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, null));
        AtomicReference<Boolean> fadeOutFired = new AtomicReference<>(false);

        runOnFxThread(() -> view.setSelectedTrack(audioTrack));
        runOnFxThread(() -> {
            view.setOnFadeOutAction(() -> fadeOutFired.set(true));
            view.getFadeOutButton().fire();
        });

        assertThat(fadeOutFired.get()).isTrue();
    }
}
