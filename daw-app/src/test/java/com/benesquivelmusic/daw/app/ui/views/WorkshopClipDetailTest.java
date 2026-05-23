package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 — selecting a clip swaps the clip-detail slot under the
 * plugin pane: a MIDI clip causes the piano-roll component to appear; an
 * audio clip causes the waveform component to appear. The Workshop view
 * is the seam — it routes whatever clip-detail Node the caller pushes
 * (via {@link WorkshopView#setClipDetailContent(javafx.scene.Node)}) into
 * the {@code clipDetailHost} slot under the plugin pane, while reusing
 * {@code AudioEditorView} / {@code MidiEditorView} verbatim per the
 * story's Non-Goal of "Replacing the plugin-parameter renderer — Workshop
 * REUSES it" (the same rule applies to the clip editors).
 *
 * <p>The test uses distinguishable {@link Region} placeholders to stand
 * in for the piano-roll and waveform components — exercising the seam
 * the application controller wires to the real editors. Asserting the
 * Workshop-side slot semantics (right child, parent identity, ordering
 * relative to the plugin pane) is the bit that lives in this view; the
 * editors themselves have their own tests.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopClipDetailTest {

    @Test
    void midiClipSelectionShowsPianoRollAudioClipSelectionShowsWaveform() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            new Scene(view, 1280, 800);

            assertThat(view.clipDetailHost().getChildren())
                    .as("Workshop starts with no clip selected — the clip-detail "
                            + "host is empty")
                    .isEmpty();

            // MIDI clip → caller injects the piano-roll node. Workshop
            // hosts it verbatim under the plugin pane.
            Region pianoRoll = new Region();
            pianoRoll.setId("piano-roll");
            view.setClipDetailContent(pianoRoll);

            assertThat(view.clipDetailHost().getChildren())
                    .as("after a MIDI-clip selection, the clip-detail host "
                            + "contains exactly the piano-roll node")
                    .containsExactly(pianoRoll);
            assertThat(pianoRoll.getParent())
                    .as("the piano-roll node is parented under the clip-detail host")
                    .isSameAs(view.clipDetailHost());

            // Switch to an audio clip → the host swaps to the waveform.
            Region waveform = new Region();
            waveform.setId("waveform");
            view.setClipDetailContent(waveform);

            assertThat(view.clipDetailHost().getChildren())
                    .as("after an audio-clip selection, the clip-detail host "
                            + "contains exactly the waveform node — the piano-roll "
                            + "is detached")
                    .containsExactly(waveform);
            assertThat(pianoRoll.getParent())
                    .as("the previous piano-roll is detached when the slot is swapped")
                    .isNull();

            // The clip-detail host sits BELOW the plugin pane in the right
            // pane VBox — verifies the §4 Concept F mock ordering.
            int pluginIdx = view.rightPane().getChildren().indexOf(view.pluginContainer());
            int clipIdx   = view.rightPane().getChildren().indexOf(view.clipDetailHost());
            assertThat(pluginIdx)
                    .as("the plugin pane is in the right-pane VBox")
                    .isGreaterThanOrEqualTo(0);
            assertThat(clipIdx)
                    .as("the clip-detail host appears below the plugin pane (pluginIdx=%d, clipIdx=%d)",
                            pluginIdx, clipIdx)
                    .isGreaterThan(pluginIdx);
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages);
    }

    private static <T> T onFxThread(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
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
        return ref.get();
    }
}
