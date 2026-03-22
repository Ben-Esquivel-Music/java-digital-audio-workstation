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
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

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
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(20);
        assertThat(buf.getShort()).isEqualTo((short) 3); // IEEE float
    }

    @Test
    void shouldWrite16BitPcmFormat() throws IOException {
        Path output = tempDir.resolve("pcm16.wav");
        exportSimpleSession(output, 16);

        byte[] data = Files.readAllBytes(output);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.position(20);
        assertThat(buf.getShort()).isEqualTo((short) 1); // PCM
        buf.position(34);
        assertThat(buf.getShort()).isEqualTo((short) 16);
    }

    @Test
    void shouldBuildValidAdmXml() {
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        List<AudioObject> objects = List.of(
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

    @Test
    void shouldHandleMultipleChunks() throws IOException {
        // 10000 frames > CHUNK_FRAMES (8192) — verifies chunked MemorySegment writing
        Path output = tempDir.resolve("multi_chunk.wav");
        int numSamples = 10000;
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        float[] bedL = new float[numSamples];
        float[] bedR = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            bedL[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000));
            bedR[i] = (float) (0.3 * Math.cos(2.0 * Math.PI * 440.0 * i / 48000));
        }

        AudioObject obj = new AudioObject("obj-1", new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9));
        float[] objAudio = new float[numSamples];
        Arrays.fill(objAudio, 0.1f);

        AdmBwfExporter.export(beds, List.of(bedL, bedR),
                List.of(obj), List.of(objAudio),
                SpeakerLayout.LAYOUT_7_1_4, 48000, 24,
                AudioMetadata.EMPTY, output);

        assertThat(output).exists();
        byte[] data = Files.readAllBytes(output);
        assertThat(new String(data, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // Verify data chunk size: 10000 samples * 3 channels * 3 bytes
        int expectedDataSize = numSamples * 3 * 3;
        buf.position(40);
        assertThat(buf.getInt()).isEqualTo(expectedDataSize);
    }

    @Test
    void shouldHandleExactChunkBoundary() throws IOException {
        // Exactly 8192 frames = one full chunk
        Path output = tempDir.resolve("exact_chunk.wav");
        int numSamples = 8192;
        List<BedChannel> beds = List.of(new BedChannel("bed-L", SpeakerLabel.L));
        float[] bedL = new float[numSamples];
        Arrays.fill(bedL, 0.4f);

        AdmBwfExporter.export(beds, List.of(bedL),
                List.of(), List.of(),
                SpeakerLayout.LAYOUT_7_1_4, 48000, 16,
                AudioMetadata.EMPTY, output);

        byte[] data = Files.readAllBytes(output);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int expectedDataSize = numSamples * 1 * 2;
        buf.position(40);
        assertThat(buf.getInt()).isEqualTo(expectedDataSize);
    }

    private void exportSimpleSession(Path output, int bitDepth) throws IOException {
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        float[] bedL = new float[1024];
        float[] bedR = new float[1024];
        Arrays.fill(bedL, 0.5f);
        Arrays.fill(bedR, 0.3f);

        AudioObject obj = new AudioObject("obj-1", new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9));
        float[] objAudio = new float[1024];
        Arrays.fill(objAudio, 0.1f);

        AdmBwfExporter.export(beds, List.of(bedL, bedR),
                List.of(obj), List.of(objAudio),
                SpeakerLayout.LAYOUT_7_1_4, 48000, bitDepth,
                AudioMetadata.EMPTY, output);
    }
}
