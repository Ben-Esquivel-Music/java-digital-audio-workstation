package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.core.export.FlacExporter;
import com.benesquivelmusic.daw.core.export.WavExporter;

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

class AudioFileImporterTest {

    @TempDir
    Path tempDir;

    private DawProject project;
    private AudioFileImporter importer;

    @BeforeEach
    void setUp() {
        project = new DawProject("Test Project", AudioFormat.CD_QUALITY);
        importer = new AudioFileImporter(project);
    }

    // ── Basic import tests ──────────────────────────────────────────────────

    @Test
    void shouldImportWavFileToNewTrack() throws IOException {
        Path wavFile = createTestWav("test.wav", 44100, 1.0);

        AudioImportResult result = importer.importFile(wavFile, 0.0);

        assertThat(result.track()).isNotNull();
        assertThat(result.clip()).isNotNull();
        assertThat(result.sourceFile()).isEqualTo(wavFile);
        assertThat(result.wasConverted()).isFalse();
        assertThat(result.track().getName()).isEqualTo("test");
        assertThat(result.clip().getName()).isEqualTo("test");
        assertThat(result.clip().getStartBeat()).isEqualTo(0.0);
        assertThat(result.clip().getSourceFilePath()).isEqualTo(wavFile.toString());
        assertThat(result.clip().getAudioData()).isNotNull();
    }

    @Test
    void shouldAddTrackToProject() throws IOException {
        Path wavFile = createTestWav("vocals.wav", 44100, 0.5);

        importer.importFile(wavFile, 0.0);

        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getTracks().get(0).getName()).isEqualTo("vocals");
        assertThat(project.getMixer().getChannelCount()).isEqualTo(1);
    }

    @Test
    void shouldAddClipToTrack() throws IOException {
        Path wavFile = createTestWav("drums.wav", 44100, 0.5);

        AudioImportResult result = importer.importFile(wavFile, 0.0);

        assertThat(result.track().getClips()).hasSize(1);
        assertThat(result.track().getClips().get(0)).isEqualTo(result.clip());
    }

    @Test
    void shouldPlaceClipAtSpecifiedBeat() throws IOException {
        Path wavFile = createTestWav("lead.wav", 44100, 0.5);

        AudioImportResult result = importer.importFile(wavFile, 8.0);

        assertThat(result.clip().getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldCalculateClipDurationInBeats() throws IOException {
        // 1 second at 120 BPM = 2 beats
        Path wavFile = createTestWav("one_second.wav", 44100, 1.0);

        AudioImportResult result = importer.importFile(wavFile, 0.0);

        assertThat(result.clip().getDurationBeats()).isCloseTo(2.0, offset(0.1));
    }

    // ── Import to existing track ────────────────────────────────────────────

    @Test
    void shouldImportToExistingTrack() throws IOException {
        Track existingTrack = project.createAudioTrack("My Track");
        Path wavFile = createTestWav("sample.wav", 44100, 0.5);

        AudioImportResult result = importer.importFile(wavFile, 4.0, existingTrack);

        assertThat(result.track()).isSameAs(existingTrack);
        assertThat(project.getTracks()).hasSize(1);
        assertThat(existingTrack.getClips()).hasSize(1);
    }

    @Test
    void shouldCreateNewTrackWhenTargetIsNull() throws IOException {
        Path wavFile = createTestWav("sample.wav", 44100, 0.5);

        AudioImportResult result = importer.importFile(wavFile, 0.0, null);

        assertThat(project.getTracks()).hasSize(1);
        assertThat(result.track().getName()).isEqualTo("sample");
    }

    // ── Sample rate conversion ──────────────────────────────────────────────

    @Test
    void shouldConvertSampleRateWhenDifferent() throws IOException {
        // Project is 44100 Hz (CD_QUALITY), file is 48000 Hz
        Path wavFile = createTestWav("hires.wav", 48000, 0.5);

        AudioImportResult result = importer.importFile(wavFile, 0.0);

        assertThat(result.wasConverted()).isTrue();
        assertThat(result.clip().getAudioData()).isNotNull();
        // After conversion, the number of frames should match project sample rate
        int expectedFrames = (int) Math.round(0.5 * 44100);
        int actualFrames = result.clip().getAudioData()[0].length;
        assertThat(Math.abs(actualFrames - expectedFrames)).isLessThan(100);
    }

    @Test
    void shouldNotConvertWhenSampleRateMatches() throws IOException {
        Path wavFile = createTestWav("matching.wav", 44100, 0.5);

        AudioImportResult result = importer.importFile(wavFile, 0.0);

        assertThat(result.wasConverted()).isFalse();
    }

    // ── Multiple file import ────────────────────────────────────────────────

    @Test
    void shouldImportMultipleFiles() throws IOException {
        Path wav1 = createTestWav("drums.wav", 44100, 0.5);
        Path wav2 = createTestWav("bass.wav", 44100, 0.5);
        Path wav3 = createTestWav("vocals.wav", 44100, 0.5);

        List<AudioImportResult> results = importer.importFiles(
                List.of(wav1, wav2, wav3), 0.0);

        assertThat(results).hasSize(3);
        assertThat(project.getTracks()).hasSize(3);
        assertThat(results.get(0).track().getName()).isEqualTo("drums");
        assertThat(results.get(1).track().getName()).isEqualTo("bass");
        assertThat(results.get(2).track().getName()).isEqualTo("vocals");
    }

    @Test
    void shouldCreateSeparateTracksForEachFile() throws IOException {
        Path wav1 = createTestWav("guitar.wav", 44100, 0.5);
        Path wav2 = createTestWav("keys.wav", 44100, 0.5);

        List<AudioImportResult> results = importer.importFiles(
                List.of(wav1, wav2), 4.0);

        assertThat(results.get(0).track()).isNotSameAs(results.get(1).track());
        assertThat(project.getMixer().getChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectEmptyFileList() {
        assertThatThrownBy(() -> importer.importFiles(List.of(), 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    // ── Import at playhead ──────────────────────────────────────────────────

    @Test
    void shouldImportAtPlayheadPosition() throws IOException {
        project.getTransport().setPositionInBeats(16.0);
        Path wavFile = createTestWav("sample.wav", 44100, 0.5);

        AudioImportResult result = importer.importAtPlayhead(wavFile);

        assertThat(result.clip().getStartBeat()).isEqualTo(16.0);
    }

    @Test
    void shouldImportAtZeroWhenPlayheadAtStart() throws IOException {
        Path wavFile = createTestWav("sample.wav", 44100, 0.5);

        AudioImportResult result = importer.importAtPlayhead(wavFile);

        assertThat(result.clip().getStartBeat()).isEqualTo(0.0);
    }

    // ── Progress listener ───────────────────────────────────────────────────

    @Test
    void shouldNotifyProgressListener() throws IOException {
        Path wavFile = createTestWav("progress.wav", 44100, 0.5);
        List<Double> reportedProgress = new ArrayList<>();

        ImportProgressListener listener = new ImportProgressListener() {
            @Override
            public void onFileStarted(Path file, int fileIndex, int totalFiles) {
            }

            @Override
            public void onProgress(Path file, double progress) {
                reportedProgress.add(progress);
            }

            @Override
            public void onFileCompleted(Path file, AudioImportResult result) {
            }

            @Override
            public void onFileError(Path file, Exception error) {
            }
        };

        importer.importFile(wavFile, 0.0, null, listener);

        assertThat(reportedProgress).isNotEmpty();
        assertThat(reportedProgress.get(0)).isEqualTo(0.0);
        assertThat(reportedProgress.get(reportedProgress.size() - 1)).isEqualTo(1.0);
    }

    @Test
    void shouldNotifyMultiFileProgressListener() throws IOException {
        Path wav1 = createTestWav("a.wav", 44100, 0.5);
        Path wav2 = createTestWav("b.wav", 44100, 0.5);
        List<Path> startedFiles = new ArrayList<>();
        List<Path> completedFiles = new ArrayList<>();

        ImportProgressListener listener = new ImportProgressListener() {
            @Override
            public void onFileStarted(Path file, int fileIndex, int totalFiles) {
                startedFiles.add(file);
            }

            @Override
            public void onProgress(Path file, double progress) {
            }

            @Override
            public void onFileCompleted(Path file, AudioImportResult result) {
                completedFiles.add(file);
            }

            @Override
            public void onFileError(Path file, Exception error) {
            }
        };

        importer.importFiles(List.of(wav1, wav2), 0.0, listener);

        assertThat(startedFiles).containsExactly(wav1, wav2);
        assertThat(completedFiles).containsExactly(wav1, wav2);
    }

    // ── Validation tests ────────────────────────────────────────────────────

    @Test
    void shouldRejectNullFile() {
        assertThatThrownBy(() -> importer.importFile(null, 0.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeStartBeat() throws IOException {
        Path wavFile = createTestWav("test.wav", 44100, 0.5);

        assertThatThrownBy(() -> importer.importFile(wavFile, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startBeat");
    }

    @Test
    void shouldRejectUnsupportedFormat() {
        Path pdfFile = tempDir.resolve("document.pdf");
        try {
            java.nio.file.Files.writeString(pdfFile, "not audio");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        assertThatThrownBy(() -> importer.importFile(pdfFile, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void shouldAttemptToImportMp3File() throws IOException {
        Path mp3File = tempDir.resolve("song.mp3");
        java.nio.file.Files.writeString(mp3File, "fake mp3 data");

        // MP3 import is now supported — the importer should attempt to read the file
        // and fail due to invalid data or missing SPI, not because of format rejection
        assertThatThrownBy(() -> importer.importFile(mp3File, 0.0))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldRejectNullProject() {
        assertThatThrownBy(() -> new AudioFileImporter(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private Path createTestWav(String fileName, int sampleRate, double durationSeconds) throws IOException {
        int numSamples = (int) (sampleRate * durationSeconds);
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[0][i] = value;
            audio[1][i] = value;
        }
        Path wavFile = tempDir.resolve(fileName);
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, wavFile);
        return wavFile;
    }

    private Path createTestFlac(String fileName, int sampleRate, double durationSeconds) throws IOException {
        int numSamples = (int) (sampleRate * durationSeconds);
        float[][] audio = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            float value = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate));
            audio[0][i] = value;
            audio[1][i] = value;
        }
        Path flacFile = tempDir.resolve(fileName);
        FlacExporter.write(audio, sampleRate, 16, DitherType.NONE, flacFile);
        return flacFile;
    }

    // ── FLAC import tests ───────────────────────────────────────────────────

    @Test
    void shouldImportFlacFile() throws IOException {
        Path flacFile = createTestFlac("track.flac", 44100, 1.0);

        AudioImportResult result = importer.importFile(flacFile, 0.0);

        assertThat(result.track()).isNotNull();
        assertThat(result.clip()).isNotNull();
        assertThat(result.sourceFile()).isEqualTo(flacFile);
        assertThat(result.wasConverted()).isFalse();
        assertThat(result.track().getName()).isEqualTo("track");
        assertThat(result.clip().getAudioData()).isNotNull();
    }

    @Test
    void shouldConvertFlacSampleRate() throws IOException {
        // Project is 44100 Hz (CD_QUALITY), file is 48000 Hz
        Path flacFile = createTestFlac("hires.flac", 48000, 0.5);

        AudioImportResult result = importer.importFile(flacFile, 0.0);

        assertThat(result.wasConverted()).isTrue();
        assertThat(result.clip().getAudioData()).isNotNull();
    }
}
