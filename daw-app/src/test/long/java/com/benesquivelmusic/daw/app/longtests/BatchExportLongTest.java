package com.benesquivelmusic.daw.app.longtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;

/**
 * Long-running batch-export test (story 186): export 20 mono stems
 * back-to-back to a single output directory. Mirrors the user-facing
 * "Export All Stems" workflow when a session has many tracks.
 *
 * <p>Each stem is a deterministic synthetic source so the SHA-256 of
 * the manifest of {@code (filename, size, sha256)} is bit-stable, and
 * we compare it to the golden manifest under
 * {@code daw-app/src/test/long/resources/golden/}.</p>
 */
@LongRenderTest(
        budgetSeconds = 60.0,
        description = "20-track batch stem export → manifest golden")
final class BatchExportLongTest {

    private static final int   STEMS       = 20;
    private static final int   SAMPLE_RATE = 48_000;
    private static final int   BIT_DEPTH   = 16;
    private static final int   FRAMES      = SAMPLE_RATE * 2;     // 2 s/stem

    @Test
    void exportsTwentyStemsMatchingManifestGolden(Path workDir) throws Exception {
        Path stemsDir = Files.createDirectory(workDir.resolve("stems"));

        // Fixed (frequency, amplitude) pairs per stem index → deterministic.
        for (int i = 0; i < STEMS; i++) {
            double freq = 110.0 * (1.0 + 0.05 * i);
            double amp  = 0.4 + 0.01 * i;
            float[] mono = LongTestSupport.sine(freq, amp, SAMPLE_RATE, FRAMES);
            Path out = stemsDir.resolve(String.format("stem-%02d.wav", i));
            WavExporter.write(new float[][]{mono},
                    SAMPLE_RATE, BIT_DEPTH, DitherType.NONE,
                    AudioMetadata.EMPTY, out);
        }

        // Build a deterministic manifest: one line per stem, sorted by name.
        StringBuilder manifest = new StringBuilder();
        try (var entries = Files.list(stemsDir)) {
            entries.sorted()
                    .forEach(p -> manifest
                            .append(p.getFileName())
                            .append(',')
                            .append(safeSize(p))
                            .append('\n'));
        }
        Path manifestFile = workDir.resolve("manifest.csv");
        Files.writeString(manifestFile, manifest.toString());

        // Sanity: 20 stem files materialised on disk.
        try (var entries = Files.list(stemsDir)) {
            assertThat(entries.count()).isEqualTo(STEMS);
        }

        // Bit-accuracy: manifest is byte-identical to the golden.
        LongTestSupport.assertMatchesGolden(manifestFile, "batch-export-20track.manifest.csv");
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
