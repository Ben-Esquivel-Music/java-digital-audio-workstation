package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.StemExportConfig;
import com.benesquivelmusic.daw.sdk.export.StemExportResult;
import com.benesquivelmusic.daw.sdk.export.StemNamingConvention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class StemExporterTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;
    private static final AudioFormat PROJECT_FORMAT =
            new AudioFormat(SAMPLE_RATE, 2, 24, 512);
    private static final AudioExportConfig EXPORT_CONFIG =
            new AudioExportConfig(AudioExportFormat.WAV, SAMPLE_RATE, 24, DitherType.NONE);

    private DawProject project;
    private StemExporter stemExporter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        project = new DawProject("TestProject", PROJECT_FORMAT);
        stemExporter = new StemExporter();
    }

    @Test
    void shouldExportSingleTrackStem() throws IOException {
        Track track = project.createAudioTrack("Vocals");
        addClipToTrack(track, 0.0, 4.0, 0.5f);

        StemExportConfig config = new StemExportConfig(
                List.of(0), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "TestProject");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE);

        assertThat(result.trackResults()).hasSize(1);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.trackResults().get(0).outputPath().getFileName().toString())
                .isEqualTo("Vocals.wav");
    }

    @Test
    void shouldExportMultipleTrackStems() throws IOException {
        project.createAudioTrack("Vocals");
        Track bass = project.createAudioTrack("Bass");
        project.createAudioTrack("Drums");

        addClipToTrack(project.getTracks().get(0), 0.0, 4.0, 0.3f);
        addClipToTrack(bass, 0.0, 4.0, 0.5f);
        addClipToTrack(project.getTracks().get(2), 0.0, 4.0, 0.7f);

        StemExportConfig config = new StemExportConfig(
                List.of(0, 1, 2), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "TestProject");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE);

        assertThat(result.trackResults()).hasSize(3);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.successCount()).isEqualTo(3);
    }

    @Test
    void shouldExportSubsetOfTracks() throws IOException {
        project.createAudioTrack("Vocals");
        project.createAudioTrack("Bass");
        project.createAudioTrack("Drums");

        addClipToTrack(project.getTracks().get(0), 0.0, 4.0, 0.3f);
        addClipToTrack(project.getTracks().get(2), 0.0, 4.0, 0.7f);

        // Export only tracks 0 and 2
        StemExportConfig config = new StemExportConfig(
                List.of(0, 2), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "TestProject");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE);

        assertThat(result.trackResults()).hasSize(2);
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    void shouldUseProjectPrefixNaming() throws IOException {
        project.createAudioTrack("Vocals");
        addClipToTrack(project.getTracks().get(0), 0.0, 2.0, 0.5f);

        StemExportConfig config = new StemExportConfig(
                List.of(0), EXPORT_CONFIG,
                StemNamingConvention.PROJECT_PREFIX, "MyAlbum");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 2.0, ExportProgressListener.NONE);

        assertThat(result.trackResults().get(0).outputPath().getFileName().toString())
                .isEqualTo("MyAlbum_Vocals.wav");
    }

    @Test
    void shouldUseNumberedNaming() throws IOException {
        project.createAudioTrack("Vocals");
        project.createAudioTrack("Bass");
        addClipToTrack(project.getTracks().get(0), 0.0, 2.0, 0.5f);
        addClipToTrack(project.getTracks().get(1), 0.0, 2.0, 0.5f);

        StemExportConfig config = new StemExportConfig(
                List.of(0, 1), EXPORT_CONFIG,
                StemNamingConvention.NUMBERED, "Project");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 2.0, ExportProgressListener.NONE);

        assertThat(result.trackResults().get(0).outputPath().getFileName().toString())
                .isEqualTo("1_Vocals.wav");
        assertThat(result.trackResults().get(1).outputPath().getFileName().toString())
                .isEqualTo("2_Bass.wav");
    }

    @Test
    void shouldPadNumbersForTenOrMoreTracks() throws IOException {
        for (int i = 0; i < 12; i++) {
            Track track = project.createAudioTrack("Track" + (i + 1));
            addClipToTrack(track, 0.0, 1.0, 0.1f);
        }

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            indices.add(i);
        }

        StemExportConfig config = new StemExportConfig(
                indices, EXPORT_CONFIG,
                StemNamingConvention.NUMBERED, "Project");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 1.0, ExportProgressListener.NONE);

        assertThat(result.trackResults().get(0).outputPath().getFileName().toString())
                .isEqualTo("01_Track1.wav");
        assertThat(result.trackResults().get(9).outputPath().getFileName().toString())
                .isEqualTo("10_Track10.wav");
    }

    @Test
    void shouldExportTrackWithNoClipsAsSilence() throws IOException {
        project.createAudioTrack("EmptyTrack");
        // No clips added

        StemExportConfig config = new StemExportConfig(
                List.of(0), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE);

        assertThat(result.trackResults()).hasSize(1);
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    void shouldReportProgressDuringExport() throws IOException {
        project.createAudioTrack("Track1");
        project.createAudioTrack("Track2");
        addClipToTrack(project.getTracks().get(0), 0.0, 1.0, 0.5f);
        addClipToTrack(project.getTracks().get(1), 0.0, 1.0, 0.5f);

        List<Double> progressValues = new ArrayList<>();
        ExportProgressListener listener = (progress, stage) -> progressValues.add(progress);

        StemExportConfig config = new StemExportConfig(
                List.of(0, 1), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        stemExporter.exportStems(project, config, tempDir, 1.0, listener);

        // Should have received progress updates
        assertThat(progressValues).isNotEmpty();
        // First progress should be 0.0
        assertThat(progressValues.get(0)).isCloseTo(0.0, offset(0.001));
        // Last progress should be 1.0
        assertThat(progressValues.get(progressValues.size() - 1)).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void shouldRejectNullProject() {
        StemExportConfig config = new StemExportConfig(
                List.of(0), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> stemExporter.exportStems(
                null, config, tempDir, 4.0, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("project");
    }

    @Test
    void shouldRejectNullConfig() {
        DawProject p = new DawProject("P", PROJECT_FORMAT);

        assertThatThrownBy(() -> stemExporter.exportStems(
                p, null, tempDir, 4.0, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config");
    }

    @Test
    void shouldRejectNullOutputDir() {
        StemExportConfig config = new StemExportConfig(
                List.of(), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> stemExporter.exportStems(
                project, config, null, 4.0, ExportProgressListener.NONE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outputDir");
    }

    @Test
    void shouldRejectNullListener() {
        StemExportConfig config = new StemExportConfig(
                List.of(), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> stemExporter.exportStems(
                project, config, tempDir, 4.0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("listener");
    }

    @Test
    void shouldRejectNonPositiveProjectBeats() {
        StemExportConfig config = new StemExportConfig(
                List.of(), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> stemExporter.exportStems(
                project, config, tempDir, 0.0, ExportProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalProjectBeats");
    }

    @Test
    void shouldRejectOutOfRangeTrackIndex() {
        project.createAudioTrack("Track1");

        StemExportConfig config = new StemExportConfig(
                List.of(5), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("track index");
    }

    @Test
    void shouldRejectNegativeTrackIndex() {
        project.createAudioTrack("Track1");

        StemExportConfig config = new StemExportConfig(
                List.of(-1), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        assertThatThrownBy(() -> stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("track index");
    }

    @Test
    void shouldApplyMixerChannelVolume() {
        float[][] buffer = new float[2][100];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 100; i++) {
                buffer[ch][i] = 1.0f;
            }
        }

        MixerChannel channel = new MixerChannel("Test");
        channel.setVolume(0.5);

        StemExporter.applyMixerChannel(buffer, channel, 100, 2);

        // With pan=0 (center), constant-power pan gives equal L/R gain:
        // angle = (0+1)*0.25*PI = PI/4
        // leftGain = cos(PI/4) * 0.5 ≈ 0.354
        // rightGain = sin(PI/4) * 0.5 ≈ 0.354
        double expectedGain = Math.cos(Math.PI / 4) * 0.5;
        assertThat((double) buffer[0][0]).isCloseTo(expectedGain, offset(0.001));
        assertThat((double) buffer[1][0]).isCloseTo(expectedGain, offset(0.001));
    }

    @Test
    void shouldApplyMixerChannelPan() {
        float[][] buffer = new float[2][100];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 100; i++) {
                buffer[ch][i] = 1.0f;
            }
        }

        MixerChannel channel = new MixerChannel("Test");
        channel.setVolume(1.0);
        channel.setPan(1.0); // full right

        StemExporter.applyMixerChannel(buffer, channel, 100, 2);

        // Full right: angle = (1+1)*0.25*PI = PI/2
        // leftGain = cos(PI/2) ≈ 0.0, rightGain = sin(PI/2) ≈ 1.0
        assertThat((double) buffer[0][0]).isCloseTo(0.0, offset(0.001));
        assertThat((double) buffer[1][0]).isCloseTo(1.0, offset(0.001));
    }

    @Test
    void shouldGenerateFileNameWithTrackNameConvention() {
        String name = StemExporter.generateFileName(
                StemNamingConvention.TRACK_NAME, "Project", "Vocals", 1, 5);
        assertThat(name).isEqualTo("Vocals");
    }

    @Test
    void shouldGenerateFileNameWithProjectPrefixConvention() {
        String name = StemExporter.generateFileName(
                StemNamingConvention.PROJECT_PREFIX, "MyProject", "Bass", 2, 5);
        assertThat(name).isEqualTo("MyProject_Bass");
    }

    @Test
    void shouldGenerateFileNameWithNumberedConvention() {
        String name = StemExporter.generateFileName(
                StemNamingConvention.NUMBERED, "Project", "Drums", 3, 5);
        assertThat(name).isEqualTo("3_Drums");
    }

    @Test
    void shouldPadNumberedFileNames() {
        String name = StemExporter.generateFileName(
                StemNamingConvention.NUMBERED, "Project", "Drums", 3, 15);
        assertThat(name).isEqualTo("03_Drums");
    }

    @Test
    void shouldSanitizeSpecialCharactersInFileNames() {
        String name = StemExporter.generateFileName(
                StemNamingConvention.TRACK_NAME, "Project", "Vocals/Lead:1", 1, 1);
        assertThat(name).doesNotContain("/").doesNotContain(":");
    }

    @Test
    void shouldExportWithEmptyTrackList() throws IOException {
        project.createAudioTrack("Track1");

        StemExportConfig config = new StemExportConfig(
                List.of(), EXPORT_CONFIG,
                StemNamingConvention.TRACK_NAME, "Project");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 4.0, ExportProgressListener.NONE);

        assertThat(result.trackResults()).isEmpty();
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    void shouldExportWithFlacFormat() throws IOException {
        project.createAudioTrack("Vocals");
        addClipToTrack(project.getTracks().get(0), 0.0, 2.0, 0.5f);

        AudioExportConfig flacConfig = new AudioExportConfig(
                AudioExportFormat.FLAC, SAMPLE_RATE, 24, DitherType.NONE);
        StemExportConfig config = new StemExportConfig(
                List.of(0), flacConfig,
                StemNamingConvention.TRACK_NAME, "Project");

        StemExportResult result = stemExporter.exportStems(
                project, config, tempDir, 2.0, ExportProgressListener.NONE);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.trackResults().get(0).outputPath().getFileName().toString())
                .isEqualTo("Vocals.flac");
    }

    /**
     * Helper to create an audio clip with constant-level data and add it to a track.
     */
    private void addClipToTrack(Track track, double startBeat, double durationBeats, float level) {
        AudioClip clip = new AudioClip("clip-" + track.getName(), startBeat, durationBeats, null);
        int frames = TrackBouncer.beatsToFrames(durationBeats, SAMPLE_RATE, TEMPO);
        float[][] data = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < frames; i++) {
                data[ch][i] = level;
            }
        }
        clip.setAudioData(data);
        track.addClip(clip);
    }
}
