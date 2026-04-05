package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionConfig;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
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

class AtmosExportWorkflowTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldValidateValidConfig() {
        AtmosSessionConfig config = createValidConfig();

        AtmosExportResult result = AtmosExportWorkflow.validate(config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void shouldFailValidationForDuplicateBedSpeaker() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));

        AtmosExportResult result = AtmosExportWorkflow.validate(config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Duplicate"));
    }

    @Test
    void shouldWarnWhenSessionIsEmpty() {
        AtmosSessionConfig config = new AtmosSessionConfig();

        AtmosExportResult result = AtmosExportWorkflow.validate(config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("no bed channels"));
    }

    @Test
    void shouldWarnWhenNotAllBedChannelsAssigned() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.R));

        AtmosExportResult result = AtmosExportWorkflow.validate(config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("2 of 12"));
    }

    @Test
    void shouldExportValidSession() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        Path output = tempDir.resolve("export.wav");

        float[] bedL = new float[1024];
        float[] bedR = new float[1024];
        Arrays.fill(bedL, 0.5f);
        Arrays.fill(bedR, 0.3f);

        float[] objAudio = new float[1024];
        Arrays.fill(objAudio, 0.1f);

        AtmosExportResult result = AtmosExportWorkflow.export(
                config, List.of(bedL, bedR), List.of(objAudio),
                AudioMetadata.EMPTY, output);

        assertThat(result.isSuccess()).isTrue();
        assertThat(output).exists();

        byte[] data = Files.readAllBytes(output);
        assertThat(new String(data, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
    }

    @Test
    void shouldNotExportInvalidSession() throws IOException {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));
        Path output = tempDir.resolve("invalid.wav");

        AtmosExportResult result = AtmosExportWorkflow.export(
                config, List.of(new float[1024], new float[1024]), List.of(),
                AudioMetadata.EMPTY, output);

        assertThat(result.isSuccess()).isFalse();
        assertThat(output).doesNotExist();
    }

    @Test
    void shouldExportWithCorrectChannelCount() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        Path output = tempDir.resolve("channels.wav");

        float[] bedL = new float[512];
        float[] bedR = new float[512];
        float[] objAudio = new float[512];

        AtmosExportResult result = AtmosExportWorkflow.export(
                config, List.of(bedL, bedR), List.of(objAudio),
                AudioMetadata.EMPTY, output);

        assertThat(result.isSuccess()).isTrue();

        byte[] data = Files.readAllBytes(output);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(22);
        assertThat(buf.getShort()).isEqualTo((short) 3); // 2 beds + 1 object
    }

    @Test
    void shouldExportWithAdmXml() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        Path output = tempDir.resolve("adm.wav");

        float[] bedL = new float[256];
        float[] bedR = new float[256];
        float[] objAudio = new float[256];

        AtmosExportWorkflow.export(
                config, List.of(bedL, bedR), List.of(objAudio),
                AudioMetadata.EMPTY, output);

        byte[] data = Files.readAllBytes(output);
        String content = new String(data, StandardCharsets.UTF_8);

        assertThat(content).contains("audioFormatExtended");
        assertThat(content).contains("audioProgramme");
        assertThat(content).contains("coordinate=\"X\"");
    }

    @Test
    void shouldPreserveWarningsOnSuccessfulExport() throws IOException {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        Path output = tempDir.resolve("warnings.wav");

        AtmosExportResult result = AtmosExportWorkflow.export(
                config, List.of(new float[256]), List.of(),
                AudioMetadata.EMPTY, output);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getWarnings()).isNotEmpty();
    }

    private AtmosSessionConfig createValidConfig() {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("bed-L", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("bed-R", SpeakerLabel.R));
        config.addAudioObject(new AudioObject("obj-1",
                new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9)));
        return config;
    }
}
