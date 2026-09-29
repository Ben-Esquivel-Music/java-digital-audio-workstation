package com.benesquivelmusic.daw.core.audio.processing;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audioimport.WavFileReader;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class ClipProcessingServiceTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Reverse
    // ------------------------------------------------------------------

    @Test
    void reverse_twice_returnsBitExactOriginalAudio() throws IOException {
        float[][] original = signal(4096);
        Path src = tempDir.resolve("src.wav");
        WavExporter.write(original, 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, src);

        AudioClip clip = new AudioClip("c", 0.0, 4.0, src.toString());
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());

        UndoableAction r1 = service.reverse(clip);
        r1.execute();
        UndoableAction r2 = service.reverse(clip);
        r2.execute();

        float[][] after = WavFileReader.read(Path.of(clip.getSourceFilePath())).audioData();
        assertThat(after).hasSameDimensionsAs(original);
        for (int ch = 0; ch < original.length; ch++) {
            assertThat(after[ch]).containsExactly(original[ch]);
        }
    }

    @Test
    void reverse_undo_restoresOriginalSourceFilePath() throws IOException {
        float[][] original = signal(1024);
        Path src = tempDir.resolve("src.wav");
        WavExporter.write(original, 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, src);

        AudioClip clip = new AudioClip("c", 0.0, 4.0, src.toString());
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());

        UndoManager um = new UndoManager();
        um.execute(service.reverse(clip));
        assertThat(clip.getSourceFilePath()).isNotEqualTo(src.toString());
        assertThat(Path.of(clip.getSourceFilePath())).exists();

        um.undo();
        assertThat(clip.getSourceFilePath()).isEqualTo(src.toString());
    }

    @Test
    void reverse_redo_producesReversedReferenceAgain() throws IOException {
        float[][] original = signal(512);
        Path src = tempDir.resolve("src.wav");
        WavExporter.write(original, 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, src);

        AudioClip clip = new AudioClip("c", 0.0, 4.0, src.toString());
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());

        UndoManager um = new UndoManager();
        um.execute(service.reverse(clip));
        String reversedPath = clip.getSourceFilePath();
        um.undo();
        um.redo();

        assertThat(clip.getSourceFilePath()).isEqualTo(reversedPath);
    }

    // ------------------------------------------------------------------
    // Normalize
    // ------------------------------------------------------------------

    @Test
    void normalize_reachesTargetPeakWithin0_01_dB() throws IOException {
        // Peak sample = 0.5 → −6.02 dBFS, should reach −1 dBFS after normalize.
        float[][] audio = new float[][] {{
                0.0f, 0.25f, 0.5f, -0.5f, 0.3f, -0.1f, 0.4f, -0.25f}};
        Path src = tempDir.resolve("src.wav");
        WavExporter.write(audio, 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, src);

        AudioClip clip = new AudioClip("c", 0.0, 4.0, src.toString());
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());
        service.normalize(clip, -1.0).execute();

        float[][] result = WavFileReader.read(Path.of(clip.getSourceFilePath())).audioData();
        double peak = ClipProcessingService.interSamplePeak4x(result);
        double peakDb = 20.0 * Math.log10(peak);
        assertThat(peakDb).isCloseTo(-1.0, offset(0.01));
    }

    @Test
    void normalize_undo_restoresOriginalSourceFilePath() throws IOException {
        Path src = tempDir.resolve("src.wav");
        WavExporter.write(new float[][] {{0.1f, -0.5f, 0.3f}}, 48_000, 32,
                DitherType.NONE, AudioMetadata.EMPTY, src);

        AudioClip clip = new AudioClip("c", 0.0, 4.0, src.toString());
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());

        UndoManager um = new UndoManager();
        um.execute(service.normalize(clip, -1.0));
        assertThat(clip.getSourceFilePath()).isNotEqualTo(src.toString());

        um.undo();
        assertThat(clip.getSourceFilePath()).isEqualTo(src.toString());
    }

    @Test
    void normalize_silentClip_leavesAudioUnchanged() throws IOException {
        float[][] silent = new float[][] {new float[256]};
        Path src = tempDir.resolve("src.wav");
        WavExporter.write(silent, 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, src);

        AudioClip clip = new AudioClip("c", 0.0, 4.0, src.toString());
        new ClipProcessingService(new ClipAssetHistory()).normalize(clip, -1.0).execute();

        float[][] result = WavFileReader.read(Path.of(clip.getSourceFilePath())).audioData();
        assertThat(result[0]).containsOnly(0.0f);
    }

    // ------------------------------------------------------------------
    // Batch compound
    // ------------------------------------------------------------------

    @Test
    void batchReverse_isUndoneAsSingleStep() throws IOException {
        Path a = tempDir.resolve("a.wav");
        Path b = tempDir.resolve("b.wav");
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, a);
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, b);

        AudioClip c1 = new AudioClip("a", 0.0, 4.0, a.toString());
        AudioClip c2 = new AudioClip("b", 0.0, 4.0, b.toString());
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());

        UndoManager um = new UndoManager();
        um.execute(service.reverse(List.of(c1, c2)));
        assertThat(c1.getSourceFilePath()).isNotEqualTo(a.toString());
        assertThat(c2.getSourceFilePath()).isNotEqualTo(b.toString());

        um.undo();
        assertThat(c1.getSourceFilePath()).isEqualTo(a.toString());
        assertThat(c2.getSourceFilePath()).isEqualTo(b.toString());
    }

    // ------------------------------------------------------------------
    // Recorded takes (story 323): the clip carries a segment list
    // ------------------------------------------------------------------

    @Test
    void oneSegmentRecordedClip_executeCollapsesTheList_undoRestoresIt_redoCollapsesAgain()
            throws IOException {
        Path segment = tempDir.resolve("segment-000.wav");
        WavExporter.write(signal(512), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, segment);

        AudioClip clip = new AudioClip("take", 0.0, 4.0, segment.toString());
        clip.setSourceSegmentPaths(List.of(segment.toString()));
        ClipProcessingService service = new ClipProcessingService(new ClipAssetHistory());

        UndoManager um = new UndoManager();
        um.execute(service.reverse(clip));
        String rendered = clip.getSourceFilePath();
        assertThat(rendered).isNotEqualTo(segment.toString());
        assertThat(Path.of(rendered)).exists();
        assertThat(clip.getSourceSegmentPaths())
                .as("the clip is now a single-file clip backed by the rendered file")
                .isEmpty();

        um.undo();
        assertThat(clip.getSourceSegmentPaths()).containsExactly(segment.toString());
        assertThat(clip.getSourceFilePath()).isEqualTo(segment.toString());

        um.redo();
        assertThat(clip.getSourceSegmentPaths()).isEmpty();
        assertThat(clip.getSourceFilePath()).isEqualTo(rendered);
    }

    @Test
    void multiSegmentRecordedClip_isRefusedBeforeAnyFileIsWrittenOrHistoryRecorded()
            throws IOException {
        Path first = tempDir.resolve("segment-000.wav");
        Path second = tempDir.resolve("segment-001.wav");
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, first);
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, second);

        AudioClip clip = new AudioClip("take", 0.0, 8.0, first.toString());
        clip.setSourceSegmentPaths(List.of(first.toString(), second.toString()));
        ClipAssetHistory history = new ClipAssetHistory();
        ClipProcessingService service = new ClipProcessingService(history);
        UndoManager um = new UndoManager();

        assertThatThrownBy(() -> um.execute(service.normalize(clip, -1.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(clip.getId())
                .hasMessageContaining("multi-segment")
                .hasMessageContaining("2 segments");

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("no rendered file was written next to the segments")
                    .containsExactlyInAnyOrder("segment-000.wav", "segment-001.wav");
        }
        assertThat(history.clipIds()).isEmpty();
        assertThat(history.priorAssets(clip.getId())).isEmpty();
        assertThat(um.canUndo()).isFalse();
        assertThat(clip.getSourceSegmentPaths())
                .containsExactly(first.toString(), second.toString());
        assertThat(clip.getSourceFilePath()).isEqualTo(first.toString());
    }

    @Test
    void batchHoldingAMultiSegmentClip_isRefusedAtTheFactoryBeforeAnyClipIsProcessed()
            throws IOException {
        Path imported = tempDir.resolve("import.wav");
        Path first = tempDir.resolve("segment-000.wav");
        Path second = tempDir.resolve("segment-001.wav");
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, imported);
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, first);
        WavExporter.write(signal(256), 48_000, 32, DitherType.NONE, AudioMetadata.EMPTY, second);

        AudioClip singleFileClip = new AudioClip("import", 0.0, 4.0, imported.toString());
        AudioClip twoSegmentClip = new AudioClip("take", 4.0, 8.0, first.toString());
        twoSegmentClip.setSourceSegmentPaths(List.of(first.toString(), second.toString()));
        List<AudioClip> selection = List.of(singleFileClip, twoSegmentClip);
        ClipAssetHistory history = new ClipAssetHistory();
        ClipProcessingService service = new ClipProcessingService(history);
        UndoManager um = new UndoManager();

        // The gesture as a caller makes it: build the batch, hand it to the
        // undo manager. The clip that CAN be processed comes first.
        assertThatThrownBy(() -> um.execute(service.normalize(selection, -1.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(twoSegmentClip.getId())
                .hasMessageContaining("multi-segment")
                .hasMessageContaining("2 segments");

        assertThat(singleFileClip.getSourceFilePath())
                .as("the clip ahead of the refused one was not processed")
                .isEqualTo(imported.toString());
        assertThat(singleFileClip.getSourceSegmentPaths()).isEmpty();
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("no rendered file was written")
                    .containsExactlyInAnyOrder("import.wav", "segment-000.wav", "segment-001.wav");
        }
        assertThat(history.clipIds()).isEmpty();
        assertThat(um.canUndo()).isFalse();
        assertThat(twoSegmentClip.getSourceSegmentPaths())
                .containsExactly(first.toString(), second.toString());

        // ... and the refusal is the factory's: no action is ever built.
        assertThatThrownBy(() -> service.normalize(selection, -1.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(twoSegmentClip.getId())
                .hasMessageContaining("normalizing it");
        assertThatThrownBy(() -> service.reverse(selection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(twoSegmentClip.getId())
                .hasMessageContaining("reversing it");
    }

    // ------------------------------------------------------------------
    // Inter-sample-peak detector
    // ------------------------------------------------------------------

    @Test
    void interSamplePeak_exceedsSampleDomainPeakForInterSampleOvershoot() {
        // Two adjacent high-valued samples surrounded by zeros produce a
        // cubic-interpolated peak that sits between them, above the
        // sample-domain maximum of 0.9.
        float[][] audio = new float[][] {{0.0f, 0.9f, 0.9f, 0.0f, 0.0f, 0.9f, 0.9f, 0.0f}};

        double isp = ClipProcessingService.interSamplePeak4x(audio);
        assertThat(isp).isGreaterThan(0.9);
    }

    private static float[][] signal(int n) {
        float[][] s = new float[2][n];
        for (int i = 0; i < n; i++) {
            s[0][i] = (float) Math.sin(2 * Math.PI * i / 37.0) * 0.5f;
            s[1][i] = (float) Math.cos(2 * Math.PI * i / 53.0) * 0.4f;
        }
        return s;
    }
}
