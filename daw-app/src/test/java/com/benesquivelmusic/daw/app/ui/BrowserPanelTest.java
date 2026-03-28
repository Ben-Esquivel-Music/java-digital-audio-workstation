package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserPanelTest {

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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getStyleClass()).contains("browser-panel");
    }

    @Test
    void shouldHaveSearchField() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getSearchField()).isNotNull();
        assertThat(panel.getSearchField().getPromptText()).isEqualTo("Filter files...");
    }

    @Test
    void shouldHaveFourTabs() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getTabPane().getTabs()).hasSize(4);
    }

    @Test
    void shouldHaveExpectedTabNames() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        List<String> tabNames = panel.getTabPane().getTabs().stream()
                .map(Tab::getText)
                .toList();
        assertThat(tabNames).containsExactly("Files", "Samples", "Presets", "Project");
    }

    @Test
    void shouldHaveNonClosableTabs() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        for (Tab tab : panel.getTabPane().getTabs()) {
            assertThat(tab.isClosable()).isFalse();
        }
    }

    @Test
    void shouldHaveFileSystemTree() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getFileSystemTree()).isNotNull();
        assertThat(panel.getFileSystemTree().getRoot()).isNotNull();
        assertThat(panel.getFileSystemTree().getRoot().getValue()).isEqualTo("File System");
    }

    @Test
    void shouldHaveSamplesListView() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getSamplesListView()).isNotNull();
    }

    @Test
    void shouldHavePresetsListView() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPresetsListView()).isNotNull();
    }

    @Test
    void shouldHaveProjectFilesListView() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getProjectFilesListView()).isNotNull();
    }

    @Test
    void shouldAddAndClearSamples() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
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
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
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

    @Test
    void shouldHavePreviewPlayButton() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPreviewPlayButton()).isNotNull();
        assertThat(panel.getPreviewPlayButton().getTooltip()).isNotNull();
    }

    @Test
    void shouldHavePreviewStopButton() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPreviewStopButton()).isNotNull();
        assertThat(panel.getPreviewStopButton().getTooltip()).isNotNull();
    }

    @Test
    void shouldHavePreviewVolumeSlider() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPreviewVolumeSlider()).isNotNull();
        assertThat(panel.getPreviewVolumeSlider().getMin()).isEqualTo(0.0);
        assertThat(panel.getPreviewVolumeSlider().getMax()).isEqualTo(1.0);
        assertThat(panel.getPreviewVolumeSlider().getValue()).isEqualTo(1.0);
    }

    @Test
    void shouldHavePreviewMetadataLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPreviewMetadataLabel()).isNotNull();
    }

    @Test
    void shouldHavePreviewControlBar() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        BrowserPanel panel = createOnFxThread();
        assertThat(panel.getPreviewControlBar()).isNotNull();
        assertThat(panel.getPreviewControlBar().getChildren()).isNotEmpty();
    }
}
