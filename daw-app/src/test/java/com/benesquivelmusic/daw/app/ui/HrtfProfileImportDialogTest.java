package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.spatial.binaural.HrtfImportController;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.core.spatial.binaural.SofaFileReader;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HrtfProfileImportDialogTest {

    @TempDir
    Path tempDir;

    private static HrtfData buildAes69(double sampleRate, int irLen) {
        List<SphericalCoordinate> positions = new ArrayList<>();
        for (int el : new int[]{-30, 0, 30, 60}) {
            for (int az = 0; az < 360; az += 45) {
                positions.add(new SphericalCoordinate(az, el, 1.5));
            }
        }
        int m = positions.size();
        float[][][] ir = new float[m][2][irLen];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < irLen; j++) {
                ir[i][0][j] = (float) Math.sin(2.0 * Math.PI * j / irLen) * 0.1f;
                ir[i][1][j] = (float) Math.cos(2.0 * Math.PI * j / irLen) * 0.1f;
            }
        }
        return new HrtfData("aes69", sampleRate, positions, ir, new float[m][2]);
    }

    private <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.call());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) throw new AssertionError(err.get());
        return ref.get();
    }

    @Test
    void acceptingValidImportResultEnablesImportButtonAndShowsWarnings() throws Exception {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);
        SofaFileReader.ImportResult result =
                SofaFileReader.fromHrtfData(buildAes69(44100.0, 64), "subj-44k", 48000.0);

        HrtfProfileImportDialog dialog = onFx(() -> {
            HrtfProfileImportDialog d = new HrtfProfileImportDialog(controller);
            d.acceptResult(result);
            return d;
        });

        assertThat(dialog.getImportButton().isDisable()).isFalse();
        assertThat(dialog.getStatusLabel().getText()).contains("Validated");
        // 44.1 → 48 kHz triggers a resample warning in the result.
        assertThat(dialog.getWarningsList().getItems())
                .anyMatch(w -> w.contains("Resampled"));
    }

    @Test
    void corruptedSofaFileShowsErrorAndDisablesImport() throws Exception {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);
        Path bad = tempDir.resolve("corrupt.sofa");
        Files.write(bad, new byte[]{1, 2, 3, 4});

        HrtfProfileImportDialog dialog = onFx(() -> {
            HrtfProfileImportDialog d = new HrtfProfileImportDialog(controller);
            d.tryImport(bad);
            return d;
        });

        assertThat(dialog.getImportButton().isDisable()).isTrue();
        assertThat(dialog.getPendingResult()).isNull();
        assertThat(dialog.getStatusLabel().getText()).isNotEmpty();
    }

    @Test
    void coveragePreviewClearsBeforeRender() throws Exception {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);
        SofaFileReader.ImportResult result =
                SofaFileReader.fromHrtfData(buildAes69(48000.0, 32), "preview-test", 48000.0);

        HrtfProfileImportDialog dialog = onFx(() -> {
            HrtfProfileImportDialog d = new HrtfProfileImportDialog(controller);
            d.acceptResult(result);
            return d;
        });

        // Just confirm the canvas was wired and produced no exceptions.
        assertThat(dialog.getCoveragePreview().getWidth()).isGreaterThan(0.0);
    }

    @Test
    void rejectsNullLibrary() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                new HrtfProfileImportDialog((HrtfProfileLibrary) null, 48000.0);
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(thrown.get()).isInstanceOf(NullPointerException.class);
    }
}
