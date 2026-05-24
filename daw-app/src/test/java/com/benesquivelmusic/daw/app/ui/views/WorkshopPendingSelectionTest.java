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
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 Task 2 — view-activation gating contract.
 *
 * <p>While Workshop is <em>inactive</em> (e.g. the user is in Arrangement
 * or Mixer view), inspector selections are <strong>not</strong> applied
 * to the Workshop right pane — building a plugin GUI for a user who will
 * never enter Workshop wastes work and (in story 282) opens a real
 * floating window. The controller records the latest such selection as
 * <em>pending</em>; it is applied the next time Workshop becomes active
 * via {@link WorkshopSelectionHostController#applyPendingOnWorkshopActivation}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopPendingSelectionTest {

    @Test
    void selectionWhileWorkshopInactiveIsAppliedOnActivation() throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);

            // Workshop is INACTIVE for now.
            f.workshopActive.set(false);

            UUID trackId = UUID.fromString(f.drumsTrack.getId());
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 0));

            // While inactive, the right pane stays empty — no plugin was
            // built (the controller deferred the work).
            assertThat(f.view.focusedPluginNode())
                    .as("while Workshop is inactive, the right pane is NOT updated — "
                            + "the selection is recorded as pending")
                    .isNull();
            assertThat(f.controller.pluginPanelCacheSize())
                    .as("no plugin panel was built — the cache is still empty")
                    .isZero();

            // Now the user switches to Workshop — the navigation controller
            // flips the active flag and calls applyPendingOnWorkshopActivation.
            f.workshopActive.set(true);
            f.controller.applyPendingOnWorkshopActivation();

            assertThat(f.view.focusedPluginNode())
                    .as("on Workshop activation, the pending selection materialises "
                            + "into a focused plugin panel")
                    .isNotNull()
                    .isInstanceOf(PluginParameterEditorPanel.class);
            assertThat(f.view.breadcrumb().getSegments())
                    .as("the breadcrumb reflects the resolved insert")
                    .containsExactly("Track 01", "Insert 1", "Comp");
            assertThat(f.controller.pluginPanelCacheSize())
                    .as("the cache now holds exactly one entry — the materialised panel")
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void onlyTheLatestPendingSelectionIsApplied() throws Exception {
        onFxThread(() -> {
            Fixture f = new Fixture();
            new Scene(f.view, 1280, 800);
            f.workshopActive.set(false);

            UUID trackId = UUID.fromString(f.drumsTrack.getId());
            // User clicks through five inserts while in Arrangement —
            // only the FIFTH should materialise on activation (and only
            // one cache entry should be built — not five).
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 0));
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 1));
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 0));
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 1));
            f.selectionModel.setSelection(
                    new InspectorSelection.InsertSelection(trackId, 0));

            assertThat(f.controller.pluginPanelCacheSize())
                    .as("no panels built while inactive — even after five selection events")
                    .isZero();

            f.workshopActive.set(true);
            f.controller.applyPendingOnWorkshopActivation();

            assertThat(f.view.focusedPluginNode())
                    .as("on activation, the LAST pending selection materialises")
                    .isNotNull();
            assertThat(f.view.breadcrumb().getSegments())
                    .as("the last selection was insert 0 (Comp) — that's what appears")
                    .containsExactly("Track 01", "Insert 1", "Comp");
            assertThat(f.controller.pluginPanelCacheSize())
                    .as("exactly ONE panel was built — only the last pending "
                            + "selection paid the build cost (not all five)")
                    .isEqualTo(1);
            return null;
        });
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    private static final class Fixture {
        final WorkshopView view;
        final InspectorSelectionModel selectionModel;
        final DawProject project;
        final Track drumsTrack;
        final AtomicBoolean workshopActive = new AtomicBoolean(false);
        final WorkshopSelectionHostController controller;

        Fixture() {
            ResourceBundle messages = ResourceBundle.getBundle(
                    "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
            this.selectionModel = new InspectorSelectionModel();
            this.view = new WorkshopView(messages);

            this.project = new DawProject("Test", AudioFormat.CD_QUALITY);
            this.drumsTrack = project.createAudioTrack("Drums");

            MixerChannel ch = project.getMixerChannelForTrack(drumsTrack);
            ch.addInsert(new InsertSlot("Comp",
                    InsertEffectFactory.createProcessor(
                            InsertEffectType.COMPRESSOR, 2, 44_100),
                    InsertEffectType.COMPRESSOR));
            ch.addInsert(new InsertSlot("Reverb",
                    InsertEffectFactory.createProcessor(
                            InsertEffectType.REVERB, 2, 44_100),
                    InsertEffectType.REVERB));

            Supplier<DawProject> projectSupplier = () -> project;
            this.controller = new WorkshopSelectionHostController(
                    view, selectionModel, projectSupplier, workshopActive::get, messages,
                    null /* eventBus — not under test here */);
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
