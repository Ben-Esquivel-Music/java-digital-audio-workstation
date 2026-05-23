package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelection;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 Task 2 — selection-driven push of the clip-detail editor
 * into Workshop's clip-detail slot.
 *
 * <p>Two paths exercise the wiring:</p>
 *
 * <ol>
 *   <li>The standard {@link InspectorSelection.ClipSelection} path with a
 *       real {@link AudioClip} attached to a project track — verifies the
 *       resolver + factory hand-off, identity caching, and the
 *       AudioEditorView class is produced.</li>
 *   <li>The MIDI {@link #setClipDetailFromClip}-direct path — MIDI clips
 *       have no UUID today, so the controller exposes a direct
 *       {@link Clip}-typed seam; verifies a MidiEditorView is produced
 *       for a {@link MidiClip}.</li>
 * </ol>
 *
 * <p>{@link InspectorSelection.ClipSelection} with an unresolvable UUID
 * clears the slot — same defensive contract as the focused-plugin path.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopClipDetailHostWiringTest {

    @Test
    void selectingAnAudioClipPushesAnAudioEditorViewIntoTheClipDetailSlot()
            throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);

            UUID kickId = UUID.fromString(f.kick.getId());
            f.selectionModel.setSelection(new InspectorSelection.ClipSelection(kickId));

            Node detail = f.view.clipDetailContent();
            assertThat(detail)
                    .as("after selecting the kick AudioClip, the clip-detail "
                            + "slot holds an editor node")
                    .isNotNull();
            // AudioEditorView is package-private — assert by simple name.
            assertThat(detail.getClass().getSimpleName())
                    .as("an audio clip selection produces an AudioEditorView")
                    .isEqualTo("AudioEditorView");

            // Identity cache — reselecting the same clip returns the same Node.
            // Round-trip via Empty so the listener actually fires.
            f.selectionModel.setSelection(InspectorSelection.empty());
            assertThat(f.view.clipDetailContent())
                    .as("Empty selection clears the clip-detail slot")
                    .isNull();
            f.selectionModel.setSelection(new InspectorSelection.ClipSelection(kickId));
            assertThat(f.view.clipDetailContent())
                    .as("reselecting the same audio clip returns the cached editor "
                            + "node (identity-stable per clipId)")
                    .isSameAs(detail);

            // Cache holds exactly one entry — the kick editor.
            assertThat(f.controller.clipEditorCacheSize())
                    .as("the clip-editor cache holds exactly one entry")
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void midiClipPushedDirectlyProducesAMidiEditorView() throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);

            // MIDI clips lack a stable UUID — the host controller exposes
            // a direct Clip-typed seam for the call-site that already has
            // the MidiClip in hand (e.g. ClipInteractionController.selectMidiClip).
            MidiClip midiClip = f.midiTrack.getMidiClip();
            f.controller.setClipDetailFromClip(midiClip, f.midiTrack);

            Node detail = f.view.clipDetailContent();
            assertThat(detail)
                    .as("after pushing a MidiClip, the clip-detail slot holds an editor")
                    .isNotNull();
            assertThat(detail.getClass().getSimpleName())
                    .as("a MidiClip produces a MidiEditorView")
                    .isEqualTo("MidiEditorView");

            // Pushing the same clip again returns the same Node (identity cache).
            f.controller.setClipDetailFromClip(null, null);
            assertThat(f.view.clipDetailContent())
                    .as("null clip clears the clip-detail slot")
                    .isNull();
            f.controller.setClipDetailFromClip(midiClip, f.midiTrack);
            assertThat(f.view.clipDetailContent())
                    .as("re-pushing the same MidiClip returns the cached editor")
                    .isSameAs(detail);
            return null;
        });
    }

    @Test
    void unresolvableClipSelectionClearsTheSlot() throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);

            // Seed with a real clip first so the slot has content to clear.
            f.selectionModel.setSelection(new InspectorSelection.ClipSelection(
                    UUID.fromString(f.kick.getId())));
            assertThat(f.view.clipDetailContent()).isNotNull();

            // Fire ClipSelection with an unknown UUID → cleared.
            f.selectionModel.setSelection(
                    new InspectorSelection.ClipSelection(UUID.randomUUID()));
            assertThat(f.view.clipDetailContent())
                    .as("an unresolvable clip selection clears the slot, never crashes")
                    .isNull();
            return null;
        });
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    private static final class Fixture {
        final WorkshopView view;
        final InspectorSelectionModel selectionModel;
        final DawProject project;
        final Track drumsTrack;
        final Track midiTrack;
        final AudioClip kick;
        final WorkshopSelectionHostController controller;

        Fixture() {
            ResourceBundle messages = ResourceBundle.getBundle(
                    "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
            this.selectionModel = new InspectorSelectionModel();
            this.view = new WorkshopView(messages);

            this.project = new DawProject("Test", AudioFormat.CD_QUALITY);
            this.drumsTrack = project.createAudioTrack("Drums");
            this.midiTrack  = project.createMidiTrack("Lead");

            this.kick = new AudioClip("kick.wav", 0.0, 4.0, null);
            drumsTrack.addClip(kick);

            Supplier<DawProject> projectSupplier = () -> project;
            this.controller = new WorkshopSelectionHostController(
                    view, selectionModel, projectSupplier, () -> true, messages);
        }
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
