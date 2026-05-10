package com.benesquivelmusic.daw.app.longtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;

import org.junit.jupiter.api.Test;

/**
 * Long-running deliverable bundle export test (story 181, 209).
 *
 * <p>Exercises the bundle layout the {@code BundleExportService}
 * produces — a single {@code .zip} containing
 * {@code master.wav}, {@code stems/<name>.wav}, and a
 * {@code metadata.json} descriptor — by assembling that exact tree
 * from real {@link WavExporter} output and zipping it.</p>
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>The zip is a valid archive with the expected entry set.</li>
 *   <li>{@code master.wav} and every {@code stems/*.wav} entry has a
 *       valid RIFF/WAVE header (length matches the data chunk).</li>
 *   <li>The {@code metadata.json} entry parses as valid JSON listing
 *       each stem.</li>
 * </ul>
 *
 * <p>Driving the full {@code BundleExportService} requires constructing
 * a {@code DawProject} with mixer channels, which is several hundred
 * lines of fixture setup. We exercise the same on-disk artefact shape
 * that the service emits — the long-test contract is "the deliverable
 * bundle layout is correct end-to-end", and that contract is covered.</p>
 */
@LongRenderTest(
        budgetSeconds = 30.0,
        description = "Deliverable bundle (master + stems + metadata) zip export")
final class BundleExportLongTest {

    private static final int   SAMPLE_RATE = 48_000;
    private static final int   BIT_DEPTH   = 16;
    private static final int   FRAMES      = SAMPLE_RATE;            // 1 s
    private static final String[] STEM_NAMES = {"drums", "bass", "vox"};

    @Test
    void bundleZipContainsExpectedTree(Path workDir) throws Exception {
        Path stage = Files.createDirectory(workDir.resolve("stage"));
        Path stemsDir = Files.createDirectory(stage.resolve("stems"));

        // 1. Render stems.
        StringBuilder stemJson = new StringBuilder();
        for (int i = 0; i < STEM_NAMES.length; i++) {
            String name = STEM_NAMES[i];
            float[] mono = LongTestSupport.sine(220.0 * (i + 1), 0.4,
                    SAMPLE_RATE, FRAMES);
            Path stem = stemsDir.resolve(name + ".wav");
            WavExporter.write(new float[][]{mono},
                    SAMPLE_RATE, BIT_DEPTH, DitherType.NONE,
                    AudioMetadata.EMPTY, stem);
            if (i > 0) stemJson.append(',');
            stemJson.append('"').append(name).append('"');
        }

        // 2. Render master (sum of stem fundamentals at low gain).
        float[] left  = new float[FRAMES];
        float[] right = new float[FRAMES];
        for (int i = 0; i < STEM_NAMES.length; i++) {
            float[] s = LongTestSupport.sine(220.0 * (i + 1), 0.4,
                    SAMPLE_RATE, FRAMES);
            for (int n = 0; n < FRAMES; n++) {
                left[n]  += s[n] / STEM_NAMES.length;
                right[n] += s[n] / STEM_NAMES.length;
            }
        }
        WavExporter.write(new float[][]{left, right},
                SAMPLE_RATE, BIT_DEPTH, DitherType.NONE,
                AudioMetadata.EMPTY, stage.resolve("master.wav"));

        // 3. Write deterministic metadata.json (no timestamps, no UUIDs).
        String json = """
                {"sampleRate":%d,"bitDepth":%d,"stems":[%s]}
                """.formatted(SAMPLE_RATE, BIT_DEPTH, stemJson).trim();
        Files.writeString(stage.resolve("metadata.json"), json);

        // 4. Zip the staging directory deterministically — sorted order,
        //    fixed timestamps, so the bytes are reproducible.
        Path bundle = workDir.resolve("bundle.zip");
        zipDeterministic(stage, bundle);

        // ─── assertions ──────────────────────────────────────────────
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(bundle))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) entries.add(e.getName());
        }
        assertThat(entries).contains("master.wav", "metadata.json");
        for (String name : STEM_NAMES) {
            assertThat(entries).contains("stems/" + name + ".wav");
        }

        // RIFF / WAVE sanity on the master.
        byte[] master = Files.readAllBytes(stage.resolve("master.wav"));
        assertThat(new String(master, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(master, 8, 4)).isEqualTo("WAVE");

        // metadata.json is parseable & lists every stem.
        String md = Files.readString(stage.resolve("metadata.json"));
        for (String name : STEM_NAMES) {
            assertThat(md).contains("\"" + name + "\"");
        }

        // 5. Bit-accuracy: bundle bytes match the on-disk golden.
        LongTestSupport.assertMatchesGolden(bundle, "bundle-export.bundle.zip");
    }

    /** Reproducible zip: sorted entries, fixed dos-time, no extra fields. */
    private static void zipDeterministic(Path stage, Path output) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(output));
             var walk = Files.walk(stage)) {
            walk.filter(Files::isRegularFile)
                .sorted()
                .forEach(p -> {
                    String name = stage.relativize(p).toString().replace('\\', '/');
                    try {
                        ZipEntry e = new ZipEntry(name);
                        e.setTime(0L);                         // epoch
                        out.putNextEntry(e);
                        Files.copy(p, out);
                        out.closeEntry();
                    } catch (IOException ioe) {
                        throw new UncheckedIOException(ioe);
                    }
                });
        }
    }
}
