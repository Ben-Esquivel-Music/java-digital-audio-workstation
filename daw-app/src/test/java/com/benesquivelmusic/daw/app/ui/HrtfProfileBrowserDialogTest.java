package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.spatial.binaural.HrtfImportController;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.core.spatial.binaural.SofaFileReader;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HrtfProfileBrowserDialogTest {

    @TempDir
    Path tempDir;

    private static PersonalizedHrtfProfile buildProfile(String name) throws IOException {
        List<SphericalCoordinate> positions = new ArrayList<>();
        for (int el : new int[]{-30, 0, 30, 60}) {
            for (int az = 0; az < 360; az += 45) {
                positions.add(new SphericalCoordinate(az, el, 1.5));
            }
        }
        int m = positions.size();
        float[][][] ir = new float[m][2][16];
        return SofaFileReader.fromHrtfData(
                new HrtfData(name, 48000.0, positions, ir, new float[m][2]), name, 48000.0)
                .profile();
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
    void listsFactoryAndImportedProfilesGrouped() throws Exception {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        library.save(buildProfile("alice"));
        library.save(buildProfile("bob"));
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfProfileBrowserDialog dialog = onFx(() -> new HrtfProfileBrowserDialog(controller));

        assertThat(dialog.getEntryList().getItems())
                .extracting(HrtfProfileLibrary.ProfileEntry::displayName)
                .containsExactly("Small", "Medium", "Large", "alice", "bob");
    }

    @Test
    void deleteButtonOnlyEnabledForImportedProfile() throws Exception {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        library.save(buildProfile("alice"));
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfProfileBrowserDialog dialog = onFx(() -> {
            HrtfProfileBrowserDialog d = new HrtfProfileBrowserDialog(controller);
            d.getEntryList().getSelectionModel().select(0); // Small (factory)
            return d;
        });
        assertThat(dialog.getDeleteButton().isDisable()).isTrue();

        onFx(() -> {
            dialog.getEntryList().getSelectionModel().select(3); // alice
            return null;
        });
        assertThat(dialog.getDeleteButton().isDisable()).isFalse();
    }

    @Test
    void deleteWithoutConfirmRemovesImportedProfile() throws Exception {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        library.save(buildProfile("alice"));
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfProfileBrowserDialog dialog = onFx(() -> new HrtfProfileBrowserDialog(controller));

        Boolean deleted = onFx(() -> dialog.deleteWithoutConfirm("alice"));
        assertThat(deleted).isTrue();
        assertThat(library.listImportedProfileNames()).doesNotContain("alice");
    }
}
