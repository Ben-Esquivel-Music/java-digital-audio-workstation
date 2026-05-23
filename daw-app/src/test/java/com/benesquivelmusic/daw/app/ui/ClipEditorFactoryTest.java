package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.clip.Clip;
import com.benesquivelmusic.daw.core.midi.MidiClip;

import javafx.application.Platform;
import javafx.scene.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 281 Task 2 — coverage for the {@link ClipEditorFactory} dispatch
 * logic. Pure FX-construction test (needs the toolkit because the editor
 * Nodes extend {@code VBox}); no scene, no controller.
 *
 * <p>Asserts on the class name (the editor types are package-private) so
 * the test stays at arm's length from the editor internals — the only
 * contract is "the right kind of editor gets built".</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ClipEditorFactoryTest {

    @Test
    void audioClipProducesAnAudioEditorView() throws Exception {
        onFxThread(() -> {
            AudioClip clip = new AudioClip("kick.wav", 0.0, 4.0, null);
            Node editor = ClipEditorFactory.buildEditor(clip);
            assertThat(editor)
                    .as("AudioClip → AudioEditorView")
                    .isNotNull();
            assertThat(editor.getClass().getSimpleName())
                    .isEqualTo("AudioEditorView");
            return null;
        });
    }

    @Test
    void midiClipProducesAMidiEditorView() throws Exception {
        onFxThread(() -> {
            MidiClip clip = new MidiClip();
            Node editor = ClipEditorFactory.buildEditor(clip);
            assertThat(editor)
                    .as("MidiClip → MidiEditorView")
                    .isNotNull();
            assertThat(editor.getClass().getSimpleName())
                    .isEqualTo("MidiEditorView");
            return null;
        });
    }

    @Test
    void factoryReturnsFreshInstancesByDesign() throws Exception {
        // The factory itself does NOT cache — the caller (the Workshop
        // selection host controller) caches at its layer. Verifies that
        // contract so a future "optimisation" doesn't silently break the
        // host's identity-cache assumptions.
        onFxThread(() -> {
            AudioClip clip = new AudioClip("kick.wav", 0.0, 4.0, null);
            Node first  = ClipEditorFactory.buildEditor(clip);
            Node second = ClipEditorFactory.buildEditor(clip);
            assertThat(first)
                    .as("the factory builds a fresh node every call — the "
                            + "caller owns the cache")
                    .isNotSameAs(second);
            return null;
        });
    }

    @Test
    void factoryRejectsNullClip() throws Exception {
        onFxThread(() -> {
            assertThatThrownBy(() -> ClipEditorFactory.buildEditor((Clip) null))
                    .isInstanceOf(NullPointerException.class);
            return null;
        });
    }

    // ── Harness ──────────────────────────────────────────────────────────

    private static void onFxThread(java.util.function.Supplier<?> supplier) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                supplier.get();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 15 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
    }
}
