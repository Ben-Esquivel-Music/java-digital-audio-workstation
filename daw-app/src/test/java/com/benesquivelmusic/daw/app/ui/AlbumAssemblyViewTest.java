package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mastering.AlbumSequence;
import com.benesquivelmusic.daw.sdk.mastering.AlbumExportType;
import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumTrackMetadata;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(JavaFxToolkitExtension.class)
class AlbumAssemblyViewTest {

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
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view).isNotNull();
        assertThat(view.getStyleClass()).contains("content-area");
    }

    @Test
    void shouldStartWithEmptyTrackContainer() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getTrackContainer().getChildren()).isEmpty();
    }

    @Test
    void shouldExposeAlbumSequence() throws Exception {
        AlbumSequence sequence = new AlbumSequence("My Album", "My Artist");
        AlbumAssemblyView view = createOnFxThread(sequence);

        assertThat(view.getAlbumSequence()).isSameAs(sequence);
    }

    @Test
    void shouldExposeStatusLabel() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getStatusLabel()).isNotNull();
        assertThat(view.getStatusLabel().getText()).contains("Add tracks");
    }

    @Test
    void shouldExposeTotalDurationLabel() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getTotalDurationLabel()).isNotNull();
        assertThat(view.getTotalDurationLabel().getText()).contains("Total:");
    }

    @Test
    void shouldExposeExportTypeSelector() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getExportTypeSelector()).isNotNull();
        assertThat(view.getExportTypeSelector().getItems()).hasSize(2);
    }

    @Test
    void shouldDefaultToSingleContinuousExport() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getSelectedExportType()).isEqualTo(AlbumExportType.SINGLE_CONTINUOUS);
    }

    @Test
    void shouldSelectIndividualTracksExport() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        runOnFxThread(() ->
                view.getExportTypeSelector().getSelectionModel().select(1));

        assertThat(view.getSelectedExportType()).isEqualTo(AlbumExportType.INDIVIDUAL_TRACKS);
    }

    @Test
    void shouldExposeAlbumTitleField() throws Exception {
        AlbumSequence sequence = new AlbumSequence("Test Album", "Test Artist");
        AlbumAssemblyView view = createOnFxThread(sequence);

        assertThat(view.getAlbumTitleField()).isNotNull();
        assertThat(view.getAlbumTitleField().getText()).isEqualTo("Test Album");
    }

    @Test
    void shouldExposeAlbumArtistField() throws Exception {
        AlbumSequence sequence = new AlbumSequence("Test Album", "Test Artist");
        AlbumAssemblyView view = createOnFxThread(sequence);

        assertThat(view.getAlbumArtistField()).isNotNull();
        assertThat(view.getAlbumArtistField().getText()).isEqualTo("Test Artist");
    }

    @Test
    void shouldRefreshWithTracksInSequence() throws Exception {
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
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("Solo Track", 300.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);

        // 1 track card, no transition indicators
        assertThat(view.getTrackContainer().getChildren()).hasSize(1);
    }

    @Test
    void shouldHandleEmptySequenceRefresh() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        runOnFxThread(view::refresh);

        assertThat(view.getTrackContainer().getChildren()).isEmpty();
    }

    @Test
    void shouldRefreshWithThreeTracks() throws Exception {
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
        AlbumAssemblyView view = createOnFxThread();

        assertThat(view.getAlbumSequence()).isNotNull();
        assertThat(view.getAlbumSequence().size()).isZero();
        assertThat(view.getAlbumSequence().getAlbumTitle()).isEqualTo("Untitled Album");
    }

    @Test
    void shouldRefreshWithCrossfadeTrack() throws Exception {
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("T1", 180.0));
        sequence.addTrack(AlbumTrackEntry.of("T2", 240.0)
                .withCrossfade(3.0, CrossfadeCurve.EQUAL_POWER));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);

        // 2 cards + 1 transition = 3
        assertThat(view.getTrackContainer().getChildren()).hasSize(3);
    }

    @Test
    void shouldExposeAlbumLevelMetadataFields() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        // The constructor wires these up unconditionally — they must be live
        // even before any tracks are added.
        assertThat(view.getAlbumYearSpinner()).isNotNull();
        assertThat(view.getAlbumGenreField()).isNotNull();
        assertThat(view.getAlbumUpcEanField()).isNotNull();
        assertThat(view.getAlbumReleaseDatePicker()).isNotNull();
        assertThat(view.getPropagateArtistButton()).isNotNull();
        assertThat(view.getAutoIsrcButton()).isNotNull();
        assertThat(view.getFirstIsrcField()).isNotNull();
    }

    @Test
    void editingGenreShouldUpdateAlbumMetadata() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        runOnFxThread(() -> view.getAlbumGenreField().setText("Indie"));

        assertThat(view.getAlbumSequence().getAlbumMetadata().genre())
                .isEqualTo("Indie");
    }

    @Test
    void propagateArtistButtonShouldOverwriteEveryTrack() throws Exception {
        AlbumSequence sequence = new AlbumSequence("Album", "New Album Artist");
        sequence.addTrack(AlbumTrackEntry.of("T1", 100.0));
        sequence.addTrack(AlbumTrackEntry.of("T2", 200.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);
        runOnFxThread(() -> view.getPropagateArtistButton().fire());

        assertThat(view.getAlbumSequence().getTracks())
                .extracting(AlbumTrackEntry::artist)
                .containsExactly("New Album Artist", "New Album Artist");
        // Per-track metadata mirrors the change.
        AlbumTrackMetadata m0 = view.getAlbumSequence().getTrackMetadata(0).orElseThrow();
        assertThat(m0.artist()).isEqualTo("New Album Artist");
    }

    @Test
    void autoIsrcButtonShouldSequenceAcrossTracks() throws Exception {
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("T1", 100.0));
        sequence.addTrack(AlbumTrackEntry.of("T2", 200.0));
        sequence.addTrack(AlbumTrackEntry.of("T3", 300.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);
        runOnFxThread(() -> {
            view.getFirstIsrcField().setText("US-RC1-26-00100");
            view.getAutoIsrcButton().fire();
        });

        assertThat(view.getAlbumSequence().getTracks())
                .extracting(AlbumTrackEntry::isrc)
                .containsExactly("US-RC1-26-00100", "US-RC1-26-00101", "US-RC1-26-00102");
        assertThat(view.getAlbumSequence().getTrackMetadata(0).orElseThrow().isrc())
                .isEqualTo("US-RC1-26-00100");
    }

    @Test
    void autoIsrcButtonShouldRejectInvalidFirstIsrc() throws Exception {
        AlbumSequence sequence = new AlbumSequence("Album", "Artist");
        sequence.addTrack(AlbumTrackEntry.of("T1", 100.0));
        AlbumAssemblyView view = createOnFxThread(sequence);

        runOnFxThread(view::refresh);
        runOnFxThread(() -> {
            view.getFirstIsrcField().setText("BAD");
            view.getAutoIsrcButton().fire();
        });

        // Track ISRCs left unchanged.
        assertThat(view.getAlbumSequence().getTracks().get(0).isrc()).isNull();
        assertThat(view.getStatusLabel().getText()).contains("valid first ISRC");
    }

    @Test
    void firstIsrcFieldShouldAutoHyphenate() throws Exception {
        AlbumAssemblyView view = createOnFxThread();

        runOnFxThread(() -> view.getFirstIsrcField().setText("USRC12600042"));

        assertThat(view.getFirstIsrcField().getText()).isEqualTo("US-RC1-26-00042");
    }
}
