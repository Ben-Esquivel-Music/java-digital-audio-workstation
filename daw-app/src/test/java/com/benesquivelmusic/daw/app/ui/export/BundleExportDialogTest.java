package com.benesquivelmusic.daw.app.ui.export;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.export.BundlePreset;
import com.benesquivelmusic.daw.sdk.export.DeliverableBundle;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class BundleExportDialogTest {

    private BundleExportDialog createDialog() throws Exception {
        AtomicReference<BundleExportDialog> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new BundleExportDialog(
                List.of(
                        new BundleExportDialog.TrackOption(0, "Drums"),
                        new BundleExportDialog.TrackOption(1, "Bass"),
                        new BundleExportDialog.TrackOption(2, "Vocals")
                ),
                "MyProject",
                48_000, 24,
                Paths.get(System.getProperty("java.io.tmpdir")))));
        return ref.get();
    }

    private static void runOnFx(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("FX action timed out");
        }
    }

    @Test
    void buildsBundleFromDefaults() throws Exception {
        BundleExportDialog dialog = createDialog();
        AtomicReference<DeliverableBundle> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(dialog.buildBundle(48_000, 24)));
        DeliverableBundle b = ref.get();

        assertThat(b).isNotNull();
        assertThat(b.master()).isNotNull();
        assertThat(b.stems()).hasSize(3);
        assertThat(b.includeTrackSheet()).isTrue();
        assertThat(b.metadata().projectTitle()).isEqualTo("MyProject");
        assertThat(b.metadata().sampleRate()).isEqualTo(48_000);
    }

    @Test
    void respectsMasterOnlyPreset() throws Exception {
        BundleExportDialog dialog = createDialog();
        AtomicReference<DeliverableBundle> ref = new AtomicReference<>();
        runOnFx(() -> {
            dialog.getPresetCombo().setValue(BundlePreset.MASTER_ONLY);
            ref.set(dialog.buildBundle(48_000, 24));
        });
        DeliverableBundle b = ref.get();
        assertThat(b.master()).isNotNull();
        assertThat(b.stems()).isEmpty();
    }

    @Test
    void unselectingStemRemovesItFromBundle() throws Exception {
        BundleExportDialog dialog = createDialog();
        AtomicReference<DeliverableBundle> ref = new AtomicReference<>();
        runOnFx(() -> {
            dialog.getStemCheckBoxes().get(1).setSelected(false);
            ref.set(dialog.buildBundle(48_000, 24));
        });
        DeliverableBundle b = ref.get();
        assertThat(b.stems()).hasSize(2);
        assertThat(b.stems().stream().map(s -> s.stemName())).containsExactly(
                "Drums", "Vocals");
    }

    @Test
    void progressUpdatesPropagateToBarAndLabel() throws Exception {
        BundleExportDialog dialog = createDialog();
        runOnFx(() -> dialog.updateProgress(0.42, "Rendering"));
        runOnFx(() -> {
            assertThat(dialog.getProgressBar().getProgress()).isEqualTo(0.42);
            assertThat(dialog.getProgressLabel().getText()).isEqualTo("Rendering");
        });
    }
}
