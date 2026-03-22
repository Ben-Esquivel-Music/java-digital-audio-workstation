package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmBwfExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteValidRiffWaveHeader() throws IOException {
        Path output = tempDir.resolve("test.wav");
        exportSimpleSession(output, 24);

        byte[] data = Files.readAllBytes(output);
        assertThat(new String(data, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(data, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
    }

    @Test
    void shouldWriteCorrectFmtChunk() throws IOException {
        Path output = tempDir.resolve("fmt.wav");
        exportSimpleSession(output, 24);

        byte[] data = Files.readAllBytes(output);
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // fmt chunk at offset 12
        assertThat(new String(data, 12, 4, StandardCharsets.US_ASCII)).isEqualTo("fmt ");
        buf.position(16);
        assertThat(buf.getInt()).isEqualTo(16); // chunk size
        assertThat(buf.getShort()).isEqualTo((short) 1); // PCM
        assertThat(buf.getShort()).isEqualTo((short) 3); // 2 beds + 1 object = 3 channels
        assertThat(buf.getInt()).isEqualTo(48000); // sample rate
    }

    @Test
    void shouldContainAxmlChunk() throws IOException {
        Path output = tempDir.resolve("axml.wav");
        exportSimpleSession(output, 24);

        byte[] data = Files.readAllBytes(output);
        String fileContent = new String(data, StandardCharsets.US_ASCII);

        assertThat(fileContent).contains("axml");
    }

    @Test
    void shouldContainAdmXmlElements() throws IOException {
        Path output = tempDir.resolve("adm.wav");
        exportSimpleSession(output, 24);

        byte[] data = Files.readAllBytes(output);
        String fileContent = new String(data, StandardCharsets.UTF_8);

        assertThat(fileContent).contains("audioFormatExtended");
        assertThat(fileContent).contains("audioProgramme");
        assertThat(fileContent).contains("audioContent");
        assertThat(fileContent).contains("audioObject");
    }

    @Test
    void shouldContainBedChannelSpeakerLabels() throws IOException {
        Path output = tempDir.resolve("beds.wav");
        exportSimpleSession(output, 24);

        byte[] data = Files.readAllBytes(output);
        String fileContent = new String(data, StandardCharsets.UTF_8);

        assertThat(fileContent).contains("M+030"); // L in ADM notation
        assertThat(fileContent).contains("M-030"); // R in ADM notation
    }

    @Test
    void shouldContainObjectPositionMetadata() throws IOException {
        Path output = tempDir.resolve("objpos.wav");
        exportSimpleSession(output, 24);

        byte[] data = Files.readAllBytes(output);
        String fileContent = new String(data, StandardCharsets.UTF_8);

        assertThat(fileContent).contains("coordinate=\"X\"");
        assertThat(fileContent).contains("coordinate=\"Y\"");
        assertThat(fileContent).contains("coordinate=\"Z\"");
    }

    @Test
    void shouldWrite32BitFloatFormat() throws IOException {
        Path output = tempDir.resolve("float32.wav");
        exportSimpleSession(output, 32);

        byte[] data = Files.readAllBytes(output);
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(20);
        assertThat(buf.getShort()).isEqualTo((short) 3); // IEEE float
    }

    @Test
    void shouldWrite16BitPcmFormat() throws IOException {
        Path output = tempDir.resolve("pcm16.wav");
        exportSimpleSession(output, 16);

        byte[] data = Files.readAllBytes(output);
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(20);
        assertThat(buf.getShort()).isEqualTo((short) 1); // PCM
        buf.position(34);
        assertThat(buf.getShort()).isEqualTo((short) 16);
    }

    @Test
    void shouldBuildValidAdmXml() {
        var beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        var objects = List.of(
                new AudioObject("obj-1", new ObjectMetadata(0.5, 0.3, 0.1, 0.2, 0.8)));

        byte[] xml = AdmBwfExporter.buildAdmXml(beds, objects,
                SpeakerLayout.LAYOUT_7_1_4, 48000, 1024);
        String content = new String(xml, StandardCharsets.UTF_8);

        assertThat(content).startsWith("<?xml version=\"1.0\"");
        assertThat(content).contains("ITU-R_BS.2076");
        assertThat(content).contains("AO_1001"); // bed object
        assertThat(content).contains("AO_1002"); // first audio object
        assertThat(content).contains("0.5000"); // x position
        assertThat(content).contains("0.3000"); // y position
        assertThat(content).contains("0.1000"); // z position
    }

    private void exportSimpleSession(Path output, int bitDepth) throws IOException {
        var beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        float[] bedL = new float[1024];
        float[] bedR = new float[1024];
        Arrays.fill(bedL, 0.5f);
        Arrays.fill(bedR, 0.3f);

        var obj = new AudioObject("obj-1", new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9));
        float[] objAudio = new float[1024];
        Arrays.fill(objAudio, 0.1f);

        AdmBwfExporter.export(beds, List.of(bedL, bedR),
                List.of(obj), List.of(objAudio),
                SpeakerLayout.LAYOUT_7_1_4, 48000, bitDepth,
                AudioMetadata.EMPTY, output);
    }
}
