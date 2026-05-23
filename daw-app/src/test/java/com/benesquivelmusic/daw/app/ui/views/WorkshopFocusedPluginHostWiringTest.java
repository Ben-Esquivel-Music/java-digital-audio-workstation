package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.PluginParameterEditorPanel;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelection;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
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
 * Story 281 Task 2 — selection-driven push of the
 * {@code PluginParameterEditorPanel} into Workshop's right pane.
 *
 * <p>Verifies the four host-wiring contracts:</p>
 *
 * <ol>
 *   <li>Selecting an insert with a real plugin pushes a
 *       {@link PluginParameterEditorPanel} into the right pane.</li>
 *   <li>Selecting the SAME insert twice returns the same Node instance
 *       (identity cache — {@code isSameAs}).</li>
 *   <li>Selecting a DIFFERENT insert returns a different Node.</li>
 *   <li>Selecting a non-insert (e.g. a track) clears the focused-plugin
 *       slot back to the placeholder.</li>
 * </ol>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopFocusedPluginHostWiringTest {

    @Test
    void selectingAnInsertPushesItsPluginPanelIntoTheWorkshopRightPane()
            throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);

            // Compress is on insert 0; Reverb on insert 1.
            UUID trackId = UUID.fromString(f.drumsTrack.getId());

            // 1) Select compressor insert → panel appears.
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 0));
            Node compressorPanel = f.view.focusedPluginNode();
            assertThat(compressorPanel)
                    .as("after selecting insert 0, the right pane focuses a "
                            + "PluginParameterEditorPanel for the compressor")
                    .isNotNull()
                    .isInstanceOf(PluginParameterEditorPanel.class);
            assertThat(f.view.breadcrumb().getSegments())
                    .as("the breadcrumb reads Track 01 ▸ Insert 1 ▸ Comp")
                    .containsExactly("Track 01", "Insert 1", "Comp");

            // 2) Selecting the same insert again returns the SAME Node.
            // Round-trip through a different selection first to force the
            // listener to fire (setting the same value is a no-op).
            f.selectionModel.setSelection(
                    new InspectorSelection.TrackSelection(trackId));
            assertThat(f.view.focusedPluginNode())
                    .as("track selection clears the focused plugin slot")
                    .isNull();
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 0));
            Node compressorPanelAgain = f.view.focusedPluginNode();
            assertThat(compressorPanelAgain)
                    .as("reselecting the same insert returns the cached "
                            + "panel (identity-stable per (trackId, insertIndex))")
                    .isSameAs(compressorPanel);

            // 3) Selecting a different insert returns a different Node.
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 1));
            Node reverbPanel = f.view.focusedPluginNode();
            assertThat(reverbPanel)
                    .as("selecting insert 1 (Reverb) returns a fresh panel — "
                            + "distinct from the compressor panel")
                    .isNotNull()
                    .isInstanceOf(PluginParameterEditorPanel.class)
                    .isNotSameAs(compressorPanel);

            // 4) Selecting a non-insert (TrackSelection) clears the slot.
            f.selectionModel.setSelection(
                    new InspectorSelection.TrackSelection(trackId));
            assertThat(f.view.focusedPluginNode())
                    .as("a TrackSelection clears the focused plugin slot")
                    .isNull();
            assertThat(f.view.breadcrumb().getSegments())
                    .as("the breadcrumb is also cleared")
                    .isEmpty();

            // Cache stability: exactly two distinct keys built.
            assertThat(f.controller.pluginPanelCacheSize())
                    .as("the plugin-panel cache holds exactly two entries — "
                            + "one per distinct (trackId, insertIndex)")
                    .isEqualTo(2);

            // PluginViewContainer identity is preserved across all the
            // above swaps — extends the WorkshopPluginSwitchTest contract
            // through the host-controller-driven push path.
            assertThat(f.view.pluginContainer().getParent())
                    .as("the PluginViewContainer stays attached to the right pane")
                    .isSameAs(f.view.rightPane());
            return null;
        });
    }

    @Test
    void selectingAnInsertOnAnUnresolvableTrackClearsTheSlot() throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);

            // Seed with a real plugin first so we have something to clear.
            UUID realTrackId = UUID.fromString(f.drumsTrack.getId());
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(realTrackId, 0));
            assertThat(f.view.focusedPluginNode()).isNotNull();

            // Now fire a selection for an unknown track UUID — resolver
            // returns empty → the host clears the focused plugin slot.
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(UUID.randomUUID(), 0));
            assertThat(f.view.focusedPluginNode())
                    .as("an unresolvable insert selection clears the slot, "
                            + "never crashes")
                    .isNull();
            return null;
        });
    }

    // ── Fixture — Workshop + controller wired against a real DawProject ──

    private static final class Fixture {
        final WorkshopView view;
        final InspectorSelectionModel selectionModel;
        final DawProject project;
        final Track drumsTrack;
        final WorkshopSelectionHostController controller;

        Fixture() {
            ResourceBundle messages = ResourceBundle.getBundle(
                    "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
            this.selectionModel = new InspectorSelectionModel();
            this.view = new WorkshopView(messages);

            this.project = new DawProject("Test", AudioFormat.CD_QUALITY);
            this.drumsTrack = project.createAudioTrack("Drums");

            MixerChannel ch = project.getMixerChannelForTrack(drumsTrack);
            InsertSlot comp = new InsertSlot("Comp",
                    InsertEffectFactory.createProcessor(
                            InsertEffectType.COMPRESSOR, 2, 44_100),
                    InsertEffectType.COMPRESSOR);
            InsertSlot reverb = new InsertSlot("Reverb",
                    InsertEffectFactory.createProcessor(
                            InsertEffectType.REVERB, 2, 44_100),
                    InsertEffectType.REVERB);
            ch.addInsert(comp);
            ch.addInsert(reverb);

            // Workshop always active for this test so the controller
            // applies selections immediately (the pending-selection path
            // is covered by WorkshopPendingSelectionTest).
            Supplier<DawProject> projectSupplier = () -> project;
            this.controller = new WorkshopSelectionHostController(
                    view, selectionModel, projectSupplier, () -> true);
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
