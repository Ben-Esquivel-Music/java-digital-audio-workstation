package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.export.AdmBwfExporter;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmBwfImporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectAdmBwfFile() throws IOException {
        Path admFile = exportSimpleAdm("adm.wav");
        assertThat(AdmBwfImporter.isAdmBwf(admFile)).isTrue();
    }

    @Test
    void shouldNotDetectPlainWavAsAdm() throws IOException {
        // A plain WAV (no axml) — written by a different exporter. Easy to fake:
        // produce a minimal RIFF/WAVE with only a fmt + data chunk.
        Path plain = tempDir.resolve("plain.wav");
        byte[] header = new byte[]{
                'R','I','F','F', 36, 0, 0, 0, 'W','A','V','E',
                'f','m','t',' ', 16, 0, 0, 0,
                1, 0, 1, 0,                       // PCM, 1 channel
                (byte)0x44, (byte)0xAC, 0, 0,     // 44100 Hz
                (byte)0x88, (byte)0x58, 1, 0,     // byte rate
                2, 0, 16, 0,                      // block align, 16 bit
                'd','a','t','a', 0, 0, 0, 0
        };
        Files.write(plain, header);
        assertThat(AdmBwfImporter.isAdmBwf(plain)).isFalse();
    }

    @Test
    void shouldParseBedChannelsAndAudioObjects() throws IOException {
        Path admFile = exportSimpleAdm("parse.wav");
        AdmImportResult result = AdmBwfImporter.parse(admFile);

        assertThat(result.bedChannels()).hasSize(2);
        assertThat(result.bedChannels().get(0).speakerLabel()).isEqualTo(SpeakerLabel.L);
        assertThat(result.bedChannels().get(1).speakerLabel()).isEqualTo(SpeakerLabel.R);
        assertThat(result.audioObjects()).hasSize(1);
        assertThat(result.objectAudio()).hasSize(1);
        assertThat(result.bedAudio()).hasSize(2);
        assertThat(result.sampleRate()).isEqualTo(48000);
    }

    @Test
    void shouldPreserveObjectPositionMetadata() throws IOException {
        Path admFile = exportSimpleAdm("pos.wav");
        AdmImportResult result = AdmBwfImporter.parse(admFile);

        AudioObject obj = result.audioObjects().get(0);
        ObjectMetadata meta = obj.getMetadata();
        assertThat(meta.x()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
        assertThat(meta.y()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(meta.z()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(meta.size()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.001));
        assertThat(meta.gain()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void shouldProduceOneAutomationPointPerObjectBlock() throws IOException {
        Path admFile = exportSimpleAdm("auto.wav");
        AdmImportResult result = AdmBwfImporter.parse(admFile);

        // Exporter writes one audioBlockFormat per object → one automation point each
        assertThat(result.totalAutomationPointCount()).isEqualTo(result.audioObjects().size());
        for (AudioObject obj : result.audioObjects()) {
            assertThat(result.objectAutomation()).containsKey(obj.getTrackId());
            assertThat(result.objectAutomation().get(obj.getTrackId())).hasSize(1);
        }
    }

    @Test
    void shouldRoundTripObjectAndBedCounts() throws IOException {
        // Export a richer session, import it back, re-export, re-import, compare counts.
        Path first = tempDir.resolve("first.wav");
        Path second = tempDir.resolve("second.wav");

        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R),
                new BedChannel("bed-C", SpeakerLabel.C),
                new BedChannel("bed-LFE", SpeakerLabel.LFE),
                new BedChannel("bed-LS", SpeakerLabel.LS),
                new BedChannel("bed-RS", SpeakerLabel.RS));
        List<float[]> bedAudio = List.of(
                buffer(512, 0.1f), buffer(512, 0.2f), buffer(512, 0.3f),
                buffer(512, 0.0f), buffer(512, 0.4f), buffer(512, 0.5f));

        List<AudioObject> objects = List.of(
                new AudioObject("o1", new ObjectMetadata(0.5, 0.5, 0.5, 0.1, 0.9)),
                new AudioObject("o2", new ObjectMetadata(-0.5, 0.0, 0.2, 0.2, 0.8)),
                new AudioObject("o3", new ObjectMetadata(0.0, -0.5, -0.3, 0.3, 0.7)));
        List<float[]> objectAudio = List.of(buffer(512, 0.7f), buffer(512, 0.6f), buffer(512, 0.5f));

        AdmBwfExporter.export(beds, bedAudio, objects, objectAudio,
                SpeakerLayout.LAYOUT_5_1, 48000, 24, AudioMetadata.EMPTY, first);

        AdmImportResult firstResult = AdmBwfImporter.parse(first);

        // Re-export from the imported session
        AdmBwfExporter.export(firstResult.bedChannels(), firstResult.bedAudio(),
                firstResult.audioObjects(), firstResult.objectAudio(),
                firstResult.bedLayout(), 48000, 24, AudioMetadata.EMPTY, second);

        AdmImportResult secondResult = AdmBwfImporter.parse(second);

        assertThat(secondResult.bedChannels()).hasSameSizeAs(firstResult.bedChannels());
        assertThat(secondResult.audioObjects()).hasSameSizeAs(firstResult.audioObjects());
        assertThat(secondResult.totalAutomationPointCount())
                .isEqualTo(firstResult.totalAutomationPointCount());
        assertThat(secondResult.bedLayout()).isEqualTo(firstResult.bedLayout());
    }

    @Test
    void shouldRouteAdmBwfFileThroughAudioFileImporter() throws IOException {
        Path admFile = exportSimpleAdm("routed.wav");
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        AudioFileImporter importer = new AudioFileImporter(project);

        AudioImportResult result = importer.importFile(admFile, 0.0);

        // 1 bed multi-channel track + 1 object track = 2 tracks
        assertThat(project.getTracks()).hasSize(2);
        assertThat(result.track().getName()).startsWith("Bed (");
        assertThat(result.clip().getAudioData().length).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldExposeDetailedAdmResult() throws IOException {
        Path admFile = exportSimpleAdm("detailed.wav");
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        AudioFileImporter importer = new AudioFileImporter(project);

        AdmImportResult result = importer.importAdmBwfDetailed(admFile, 0.0);

        assertThat(result.bedChannels()).isNotEmpty();
        assertThat(result.audioObjects()).isNotEmpty();
        assertThat(result.bedLayout()).isNotNull();
        // Detailed parse must not modify the project
        assertThat(project.getTracks()).isEmpty();
    }

    @Test
    void shouldRejectNonAdmFileFromParse() {
        Path missing = tempDir.resolve("nope.wav");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AdmBwfImporter.parse(missing))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldInferLayoutFromBedChannels() {
        SpeakerLayout layout = AdmBwfImporter.inferLayout(List.of(SpeakerLabel.L, SpeakerLabel.R));
        assertThat(layout).isEqualTo(SpeakerLayout.LAYOUT_STEREO);

        SpeakerLayout custom = AdmBwfImporter.inferLayout(List.of(SpeakerLabel.L));
        assertThat(custom.name()).isEqualTo("Custom");
        assertThat(custom.channelCount()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Path exportSimpleAdm(String filename) throws IOException {
        Path output = tempDir.resolve(filename);
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        float[] bedL = buffer(1024, 0.5f);
        float[] bedR = buffer(1024, 0.3f);

        AudioObject obj = new AudioObject("obj-1",
                new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9));
        float[] objAudio = buffer(1024, 0.1f);

        AdmBwfExporter.export(beds, List.of(bedL, bedR),
                List.of(obj), List.of(objAudio),
                SpeakerLayout.LAYOUT_7_1_4, 48000, 24,
                AudioMetadata.EMPTY, output);
        return output;
    }

    private static float[] buffer(int size, float value) {
        float[] buf = new float[size];
        Arrays.fill(buf, value);
        return buf;
    }
}
