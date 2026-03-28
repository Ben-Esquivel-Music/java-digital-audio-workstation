package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mastering.AlbumSequence;
import com.benesquivelmusic.daw.sdk.mastering.AlbumExportType;
import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlbumAssemblyViewTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private AlbumAssemblyView createOnFxThread() throws Exception {
        AtomicReference<AlbumAssemblyView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new AlbumAssemblyView());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private AlbumAssemblyView createOnFxThread(AlbumSequence sequence) throws Exception {
        AtomicReference<AlbumAssemblyView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new AlbumAssemblyView(sequence));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
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
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNullAlbumSequence() {
        assertThatThrownBy(() -> new AlbumAssemblyView(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveContentAreaStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view).isNotNull();
        assertThat(view.getStyleClass()).contains("content-area");
    }

    @Test
    void shouldStartWithEmptyTrackContainer() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getTrackContainer().getChildren()).isEmpty();
    }

    @Test
    void shouldExposeAlbumSequence() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("My Album", "My Artist");
        AlbumAssemblyView view = createOnFxThread(sequence);

        assertThat(view.getAlbumSequence()).isSameAs(sequence);
    }

    @Test
    void shouldExposeStatusLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getStatusLabel()).isNotNull();
        assertThat(view.getStatusLabel().getText()).contains("Add tracks");
    }

    @Test
    void shouldExposeTotalDurationLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getTotalDurationLabel()).isNotNull();
        assertThat(view.getTotalDurationLabel().getText()).contains("Total:");
    }

    @Test
    void shouldExposeExportTypeSelector() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getExportTypeSelector()).isNotNull();
        assertThat(view.getExportTypeSelector().getItems()).hasSize(2);
    }

    @Test
    void shouldDefaultToSingleContinuousExport() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getSelectedExportType()).isEqualTo(AlbumExportType.SINGLE_CONTINUOUS);
    }

    @Test
    void shouldSelectIndividualTracksExport() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        runOnFxThread(() ->
                view.getExportTypeSelector().getSelectionModel().select(1));

        assertThat(view.getSelectedExportType()).isEqualTo(AlbumExportType.INDIVIDUAL_TRACKS);
    }

    @Test
    void shouldExposeAlbumTitleField() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("Test Album", "Test Artist");
        AlbumAssemblyView view = createOnFxThread(sequence);

        assertThat(view.getAlbumTitleField()).isNotNull();
        assertThat(view.getAlbumTitleField().getText()).isEqualTo("Test Album");
    }

    @Test
    void shouldExposeAlbumArtistField() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("Test Album", "Test Artist");
        AlbumAssemblyView view = createOnFxThread(sequence);

        assertThat(view.getAlbumArtistField()).isNotNull();
        assertThat(view.getAlbumArtistField().getText()).isEqualTo("Test Artist");
    }

    @Test
    void shouldRefreshWithTracksInSequence() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("Track 1", 180.0));
        sequence.addTrack(AlbumTrackEntry.of("Track 2", 240.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);

        // 2 track cards + 1 transition indicator = 3 children
        assertThat(view.getTrackContainer().getChildren()).hasSize(3);
    }

    @Test
    void shouldRefreshWithSingleTrack() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("Solo Track", 300.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);

        // 1 track card, no transition indicators
        assertThat(view.getTrackContainer().getChildren()).hasSize(1);
    }

    @Test
    void shouldHandleEmptySequenceRefresh() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        runOnFxThread(view::refresh);

        assertThat(view.getTrackContainer().getChildren()).isEmpty();
    }

    @Test
    void shouldRefreshWithThreeTracks() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("T1", 100.0));
        sequence.addTrack(AlbumTrackEntry.of("T2", 200.0));
        sequence.addTrack(AlbumTrackEntry.of("T3", 300.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);

        // 3 track cards + 2 transition indicators = 5 children
        assertThat(view.getTrackContainer().getChildren()).hasSize(5);
    }

    @Test
    void defaultConstructorShouldCreateDefaultSequence() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getAlbumSequence()).isNotNull();
        assertThat(view.getAlbumSequence().size()).isZero();
        assertThat(view.getAlbumSequence().getAlbumTitle()).isEqualTo("Untitled Album");
    }

    @Test
    void shouldRefreshWithCrossfadeTrack() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("T1", 180.0));
        sequence.addTrack(AlbumTrackEntry.of("T2", 240.0)
                .withCrossfade(3.0, CrossfadeCurve.EQUAL_POWER));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);

        // 2 cards + 1 transition = 3
        assertThat(view.getTrackContainer().getChildren()).hasSize(3);
    }
}
