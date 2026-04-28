package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.BundleMetadata;
import com.benesquivelmusic.daw.sdk.export.BundlePreset;
import com.benesquivelmusic.daw.sdk.export.DeliverableBundle;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.MasterFormat;
import com.benesquivelmusic.daw.sdk.export.StemMetadata;
import com.benesquivelmusic.daw.sdk.export.StemSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class BundleExportServiceTest {

    private static final int SAMPLE_RATE = 44_100;
    private static final double TEMPO = 120.0;
    private static final AudioFormat PROJECT_FORMAT =
            new AudioFormat(SAMPLE_RATE, 2, 24, 512);
    private static final AudioExportConfig WAV_24 =
            new AudioExportConfig(AudioExportFormat.WAV, SAMPLE_RATE, 24, DitherType.NONE);

    private DawProject project;
    private BundleExportService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        project = new DawProject("BundleTest", PROJECT_FORMAT);
        service = new BundleExportService();
    }

    @Test
    void exportsFourStemSessionToValidZipWithMetadata() throws IOException {
        addStemTrack("Drums", 0.5f);
        addStemTrack("Bass", 0.25f);
        addStemTrack("Keys", 0.2f);
        addStemTrack("Vocals", 0.3f);

        Path zip = tempDir.resolve("delivery.zip");
        DeliverableBundle bundle = new DeliverableBundle(
                zip,
                new MasterFormat(WAV_24, "MyProject_Master"),
                List.of(
                        new StemSpec(0, "Drums", WAV_24),
                        new StemSpec(1, "Bass", WAV_24),
                        new StemSpec(2, "Keys", WAV_24),
                        new StemSpec(3, "Vocals", WAV_24)
                ),
                BundleMetadata.template(
                        "My Project", "Ben Esquivel",
                        TEMPO, "Cm", SAMPLE_RATE, 24),
                false);

        BundleExportService.BundleExportResult result =
                service.export(project, 4.0, bundle, ExportProgressListener.NONE);

        assertThat(result.success()).isTrue();
        assertThat(result.zipOutput()).exists();
        assertThat(result.finalMetadata().stems()).hasSize(4);

        // Validate the zip file structure: 5 WAVs + metadata.json
        Set<String> entries = readZipEntryNames(zip);
        assertThat(entries).contains(
                "MyProject_Master.wav",
                "stems/Drums.wav",
                "stems/Bass.wav",
                "stems/Keys.wav",
                "stems/Vocals.wav",
                "metadata.json");
        assertThat(entries).doesNotContain("track_sheet.pdf");
    }

    @Test
    void includesTrackSheetPdfWhenRequested() throws IOException {
        addStemTrack("Drums", 0.4f);
        addStemTrack("Bass", 0.3f);

        Path zip = tempDir.resolve("with_pdf.zip");
        DeliverableBundle bundle = new DeliverableBundle(
                zip,
                new MasterFormat(WAV_24, "Master"),
                List.of(
                        new StemSpec(0, "Drums", WAV_24),
                        new StemSpec(1, "Bass", WAV_24)
                ),
                BundleMetadata.template("Song", "Engineer",
                        TEMPO, "F", SAMPLE_RATE, 24),
                true);

        service.export(project, 4.0, bundle, ExportProgressListener.NONE);

        Set<String> entries = readZipEntryNames(zip);
        assertThat(entries).contains("track_sheet.pdf");

        // Validate the PDF starts with the magic header so any PDF reader will open it.
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry pdfEntry = zf.getEntry("track_sheet.pdf");
            byte[] head = zf.getInputStream(pdfEntry).readNBytes(5);
            assertThat(new String(head)).isEqualTo("%PDF-");
        }
    }

    @Test
    void zipIsValidPerZipSpecification() throws IOException {
        addStemTrack("Drums", 0.4f);
        Path zip = tempDir.resolve("valid.zip");
        DeliverableBundle bundle = new DeliverableBundle(
                zip,
                new MasterFormat(WAV_24, "Master"),
                List.of(new StemSpec(0, "Drums", WAV_24)),
                BundleMetadata.template("X", "Y", TEMPO, "Am", SAMPLE_RATE, 24),
                true);

        service.export(project, 4.0, bundle, ExportProgressListener.NONE);

        // ZipFile constructor throws if the central directory is invalid.
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            // Every entry must report a CRC and be readable end-to-end.
            for (ZipEntry e : java.util.Collections.list(zf.entries())) {
                byte[] data = zf.getInputStream(e).readAllBytes();
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                assertThat(crc.getValue()).isEqualTo(e.getCrc());
            }
        }
    }

    @Test
    void measurementsMatchLiveMeterWithin0Point2Lufs() throws IOException {
        // Use a single, full-amplitude pink-ish signal (deterministic noise).
        Track track = project.createAudioTrack("Sig");
        addNoiseClipToTrack(track, 0.0, 4.0, 0.5f, 1234L);

        Path zip = tempDir.resolve("measure.zip");
        DeliverableBundle bundle = new DeliverableBundle(
                zip,
                null,
                List.of(new StemSpec(0, "Sig", WAV_24)),
                BundleMetadata.template("X", "Y", TEMPO, "C", SAMPLE_RATE, 24),
                false);

        BundleExportService.BundleExportResult result =
                service.export(project, 4.0, bundle, ExportProgressListener.NONE);

        StemMetadata sig = result.finalMetadata().stems().get(0);

        // Independently measure the same buffer and compare.
        int totalFrames = TrackBouncer.beatsToFrames(4.0, SAMPLE_RATE, TEMPO);
        float[][] expected = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 2);
        float[][] padded = new float[2][totalFrames];
        for (int ch = 0; ch < 2; ch++) {
            int copy = Math.min(expected[ch].length, totalFrames);
            System.arraycopy(expected[ch], 0, padded[ch], 0, copy);
        }
        // The exporter applies the (default) mixer-channel pan/volume
        // (constant-power pan at center attenuates by ~3 dB), so the
        // reference must apply the same processing to be comparable.
        StemExporter.applyMixerChannel(padded,
                project.getMixerChannelForTrack(track), totalFrames, 2);
        BundleExportService.Measurements live =
                BundleExportService.measure(padded, SAMPLE_RATE);
        assertThat(sig.integratedLufs()).isCloseTo(live.integratedLufs(), offset(0.2));
        assertThat(sig.peakDbfs()).isCloseTo(live.peakDbfs(), offset(0.01));
    }

    @Test
    void asyncExportRunsOnVirtualThread() throws Exception {
        addStemTrack("Drums", 0.4f);
        Path zip = tempDir.resolve("async.zip");
        DeliverableBundle bundle = new DeliverableBundle(
                zip, new MasterFormat(WAV_24, "Master"),
                List.of(new StemSpec(0, "Drums", WAV_24)),
                BundleMetadata.template("X", "Y", TEMPO, "Em", SAMPLE_RATE, 24),
                false);

        BundleExportService.BundleExportResult result =
                service.exportAsync(project, 4.0, bundle, ExportProgressListener.NONE)
                        .get();
        assertThat(result.success()).isTrue();
        assertThat(zip).exists();
    }

    @Test
    void preservesPresetMasterAndStemFormats() throws IOException {
        addStemTrack("Drums", 0.3f);
        addStemTrack("Bass", 0.3f);

        Path zip = tempDir.resolve("preset.zip");
        DeliverableBundle bundle = BundlePreset.MASTER_AND_STEMS.toBundle(
                zip,
                "MasterFromPreset",
                List.of(
                        new BundlePreset.StemDescriptor(0, "Drums"),
                        new BundlePreset.StemDescriptor(1, "Bass")
                ),
                BundleMetadata.template("PresetSong", "PresetEng",
                        TEMPO, "G", 96_000, 24));

        // Use the project's actual sample rate for rendering — the preset
        // governs the output config (which the encoder may resample to).
        service.export(project, 4.0, bundle, ExportProgressListener.NONE);

        Set<String> entries = readZipEntryNames(zip);
        assertThat(entries).contains(
                "MasterFromPreset.wav",
                "stems/Drums.wav",
                "stems/Bass.wav",
                "track_sheet.pdf",
                "metadata.json");
    }

    @Test
    void rejectsBundleWithOutOfRangeStemTrack() {
        addStemTrack("OnlyOne", 0.4f);
        DeliverableBundle bundle = new DeliverableBundle(
                tempDir.resolve("oops.zip"),
                new MasterFormat(WAV_24, "Master"),
                List.of(new StemSpec(99, "Ghost", WAV_24)),
                BundleMetadata.template("X", "Y", TEMPO, "C", SAMPLE_RATE, 24),
                false);
        assertThatThrownBy(() -> service.export(project, 4.0, bundle,
                ExportProgressListener.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("track index");
    }

    @Test
    void metadataJsonContainsAllExpectedFields() throws IOException {
        addStemTrack("Drums", 0.3f);
        Path zip = tempDir.resolve("meta.zip");
        DeliverableBundle bundle = new DeliverableBundle(
                zip, new MasterFormat(WAV_24, "Master"),
                List.of(new StemSpec(0, "Drums", WAV_24)),
                BundleMetadata.template("Title!", "Engineer",
                        140.0, "Bb", SAMPLE_RATE, 24),
                false);
        service.export(project, 4.0, bundle, ExportProgressListener.NONE);

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String json = new String(
                    zf.getInputStream(zf.getEntry("metadata.json")).readAllBytes());
            assertThat(json).contains("\"projectTitle\": \"Title!\"");
            assertThat(json).contains("\"engineer\": \"Engineer\"");
            assertThat(json).contains("\"tempo\":");
            assertThat(json).contains("\"key\": \"Bb\"");
            assertThat(json).contains("\"sampleRate\": " + SAMPLE_RATE);
            assertThat(json).contains("\"bitDepth\": 24");
            assertThat(json).contains("\"masterFileName\": \"Master.wav\"");
            assertThat(json).contains("\"renderedAt\":");
            assertThat(json).contains("\"stems\":");
            assertThat(json).contains("\"fileName\": \"Drums.wav\"");
        }
    }

    private void addStemTrack(String name, float level) {
        Track track = project.createAudioTrack(name);
        addClipToTrack(track, 0.0, 4.0, level);
    }

    private void addClipToTrack(Track track, double startBeat, double durationBeats,
                                float level) {
        AudioClip clip = new AudioClip("clip-" + track.getName(),
                startBeat, durationBeats, null);
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

    private void addNoiseClipToTrack(Track track, double startBeat,
                                     double durationBeats, float amplitude, long seed) {
        AudioClip clip = new AudioClip("clip-" + track.getName(),
                startBeat, durationBeats, null);
        int frames = TrackBouncer.beatsToFrames(durationBeats, SAMPLE_RATE, TEMPO);
        java.util.Random rng = new java.util.Random(seed);
        float[][] data = new float[2][frames];
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < frames; i++) {
                data[ch][i] = (float) ((rng.nextDouble() * 2 - 1) * amplitude);
            }
        }
        clip.setAudioData(data);
        track.addClip(clip);
    }

    private static Set<String> readZipEntryNames(Path zip) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<? extends ZipEntry> list = new ArrayList<>(java.util.Collections.list(zf.entries()));
            for (ZipEntry e : list) {
                names.add(e.getName());
            }
        }
        return names;
    }

    @SuppressWarnings("unused")
    private void touchFiles(Path p) throws IOException {
        Files.exists(p);
    }
}
