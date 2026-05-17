package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.BrowserPanel.BrowserSection;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class BrowserPanelTest {

    private BrowserPanel createOnFxThread() throws Exception {
        AtomicReference<BrowserPanel> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new BrowserPanel());
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
    void shouldHaveBrowserPanelStyleClass() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getStyleClass()).contains("browser-panel");
    }

    @Test
    void shouldHaveSearchField() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getSearchField()).isNotNull();
        // story 275 — persistent search; placeholder comes from the
        // resource bundle (browser.search.placeholder), no longer the
        // hard-coded "Filter files...".
        assertThat(panel.getSearchField().getPromptText())
                .isEqualTo("Search samples and presets…");
        assertThat(panel.getSearchField().getStyleClass()).contains("search-field");
    }

    @Test
    void shouldHaveFourSections() throws Exception {
        // story 275 — the TabPane was replaced by a hand-built tab strip;
        // the four BrowserSections remain (Files/Samples/Presets/Project).
        assertThat(BrowserSection.values()).hasSize(4);
    }

    @Test
    void shouldHaveExpectedSectionNames() throws Exception {
        assertThat(Arrays.stream(BrowserSection.values()).map(Enum::name).toList())
                .containsExactly("FILES", "SAMPLES", "PRESETS", "PROJECT");
    }

    @Test
    void shouldDefaultToFilesSection() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getActiveSection()).isEqualTo(BrowserSection.FILES);
    }

    @Test
    void shouldSwitchActiveSection() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.selectSection(BrowserSection.PRESETS);
            assertThat(panel.getActiveSection()).isEqualTo(BrowserSection.PRESETS);
        });
    }

    @Test
    void shouldHaveFileSystemTree() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getFileSystemTree()).isNotNull();
        assertThat(panel.getFileSystemTree().getRoot()).isNotNull();
        assertThat(panel.getFileSystemTree().getRoot().getValue()).isEqualTo("File System");
    }

    @Test
    void shouldHaveSamplesListView() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getSamplesListView()).isNotNull();
    }

    @Test
    void shouldHavePresetsListView() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPresetsListView()).isNotNull();
    }

    @Test
    void shouldHaveProjectFilesListView() throws Exception {
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getProjectFilesListView()).isNotNull();
    }

    @Test
    void shouldAddAndClearSamples() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.addSamples(List.of("kick.wav", "snare.wav", "hihat.flac"));
            assertThat(panel.getSamplesListView().getItems()).hasSize(3);

            panel.clearSamples();
            assertThat(panel.getSamplesListView().getItems()).isEmpty();
        });
    }

    @Test
    void shouldAddAndClearPresets() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.addPresets(List.of("Init Patch", "Lead Synth"));
            assertThat(panel.getPresetsListView().getItems()).hasSize(2);

            panel.clearPresets();
            assertThat(panel.getPresetsListView().getItems()).isEmpty();
        });
    }

    @Test
    void shouldAddAndClearProjectFiles() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.addProjectFiles(List.of("vocals.wav", "bass.mp3"));
            assertThat(panel.getProjectFilesListView().getItems()).hasSize(2);

            panel.clearProjectFiles();
            assertThat(panel.getProjectFilesListView().getItems()).isEmpty();
        });
    }

    @Test
    void shouldFilterSamplesBySearchText() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.addSamples(List.of("kick.wav", "snare.wav", "hihat.flac", "kick_hard.wav"));
            panel.getSearchField().setText("kick");
            assertThat(panel.getSamplesListView().getItems()).hasSize(2);
            assertThat(panel.getSamplesListView().getItems()).allMatch(item -> item.contains("kick"));
        });
    }

    @Test
    void shouldClearFilterWhenSearchTextCleared() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            panel.addSamples(List.of("kick.wav", "snare.wav", "hihat.flac"));
            panel.getSearchField().setText("kick");
            assertThat(panel.getSamplesListView().getItems()).hasSize(1);

            panel.getSearchField().setText("");
            assertThat(panel.getSamplesListView().getItems()).hasSize(3);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"test.wav", "test.flac", "test.mp3", "test.aiff", "test.ogg"})
    void shouldRecognizeAudioFiles(String fileName) {
        assertThat(BrowserPanel.isAudioFile(fileName)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"test.txt", "test.pdf", "test.java", "test.xml", "test"})
    void shouldRejectNonAudioFiles(String fileName) {
        assertThat(BrowserPanel.isAudioFile(fileName)).isFalse();
    }

    @Test
    void shouldRejectNullFileName() {
        assertThat(BrowserPanel.isAudioFile(null)).isFalse();
    }

    @Test
    void shouldRejectEmptyFileName() {
        assertThat(BrowserPanel.isAudioFile("")).isFalse();
    }

    @Test
    void shouldBeCaseInsensitiveForAudioExtensions() {
        assertThat(BrowserPanel.isAudioFile("KICK.WAV")).isTrue();
        assertThat(BrowserPanel.isAudioFile("Snare.FLAC")).isTrue();
        assertThat(BrowserPanel.isAudioFile("hihat.Mp3")).isTrue();
    }

    @Test
    void shouldContainExpectedAudioExtensions() {
        assertThat(BrowserPanel.AUDIO_EXTENSIONS)
                .containsExactlyInAnyOrder(".wav", ".flac", ".mp3", ".aiff", ".ogg");
    }

    // ── story 275 ──
    // The shared preview control bar (previewPlayButton / StopButton /
    // VolumeSlider / MetadataLabel / ControlBar) was removed: it was an
    // unwired dead stub. Per-row audition replaces it; the surviving
    // intent ("the panel exposes audition") is covered here and in
    // BrowserRowAuditionTest.

    @Test
    void shouldExposeAuditionerSeam() throws Exception {
        BrowserPanel panel = createOnFxThread();
        // No auditioner by default — the per-row buttons render :disabled.
        assertThat(panel.getSampleAuditioner()).isEmpty();
    }

    @Test
    void shouldAcceptInjectedAuditioner() throws Exception {
        BrowserPanel panel = createOnFxThread();
        runOnFxThread(() -> {
            SampleAuditioner fake = new SampleAuditioner() {
                @Override public void play(java.nio.file.Path file) { }
                @Override public void stop() { }
                @Override public boolean isPlaying() { return false; }
                @Override public void setOnPlaybackFinished(Runnable callback) { }
            };
            panel.setSampleAuditioner(fake);
            assertThat(panel.getSampleAuditioner()).containsSame(fake);
        });
    }
}
