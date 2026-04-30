package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.export.AtmosExportResult;
import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionConfig;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmBwfExportControllerTest {

    @TempDir
    Path tempDir;

    private AdmBwfExportController controller;

    @BeforeEach
    void setUp() {
        controller = new AdmBwfExportController();
    }

    @Test
    void shouldFailExportWithNoAudioProvider() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        controller.setOutputPath(tempDir.resolve("test.wav"));

        AtmosExportResult result = controller.performExport(config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("No audio data provider"));
    }

    @Test
    void shouldFailExportWithNoOutputPath() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        controller.setAudioDataProvider(trackId -> new float[1024]);

        AtmosExportResult result = controller.performExport(config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("No output path"));
    }

    @Test
    void shouldFailExportWithInvalidConfig() throws IOException {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));

        controller.setAudioDataProvider(trackId -> new float[1024]);
        controller.setOutputPath(tempDir.resolve("test.wav"));

        AtmosExportResult result = controller.performExport(config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Duplicate"));
    }

    @Test
    void shouldExportSuccessfully() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        Path output = tempDir.resolve("success.wav");

        controller.setAudioDataProvider(trackId -> new float[1024]);
        controller.setOutputPath(output);
        controller.setMetadata(AudioMetadata.EMPTY);

        AtmosExportResult result = controller.performExport(config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(output).exists();

        byte[] data = Files.readAllBytes(output);
        assertThat(new String(data, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
    }

    @Test
    void shouldNotifyResultListener() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        Path output = tempDir.resolve("listener.wav");

        controller.setAudioDataProvider(trackId -> new float[1024]);
        controller.setOutputPath(output);

        AtomicReference<AtmosExportResult> received = new AtomicReference<>();
        controller.setResultListener(received::set);

        controller.performExport(config);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().isSuccess()).isTrue();
    }

    @Test
    void shouldNotifyResultListenerOnFailure() throws IOException {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));

        controller.setAudioDataProvider(trackId -> new float[1024]);
        controller.setOutputPath(tempDir.resolve("fail.wav"));

        AtomicReference<AtmosExportResult> received = new AtomicReference<>();
        controller.setResultListener(received::set);

        controller.performExport(config);

        assertThat(received.get()).isNotNull();
        assertThat(received.get().isSuccess()).isFalse();
    }

    @Test
    void shouldSetAndGetMetadata() {
        AudioMetadata metadata = new AudioMetadata("Title", "Artist", "Album", null);
        controller.setMetadata(metadata);

        assertThat(controller.getMetadata()).isSameAs(metadata);
    }

    @Test
    void shouldSetAndGetOutputPath() {
        Path path = tempDir.resolve("output.wav");
        controller.setOutputPath(path);

        assertThat(controller.getOutputPath()).isEqualTo(path);
    }

    @Test
    void shouldRejectNullConfig() {
        assertThatThrownBy(() -> controller.performExport(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullAudioProvider() {
        assertThatThrownBy(() -> controller.setAudioDataProvider(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMetadata() {
        assertThatThrownBy(() -> controller.setMetadata(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOutputPath() {
        assertThatThrownBy(() -> controller.setOutputPath(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldExportWithObjectPositionMetadata() throws IOException {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("bed-L", SpeakerLabel.L));
        config.addAudioObject(new AudioObject("obj-1",
                new ObjectMetadata(0.75, -0.5, 0.3, 0.2, 0.85)));

        Path output = tempDir.resolve("positions.wav");
        controller.setAudioDataProvider(trackId -> new float[512]);
        controller.setOutputPath(output);

        AtmosExportResult result = controller.performExport(config);

        assertThat(result.isSuccess()).isTrue();

        byte[] data = Files.readAllBytes(output);
        String content = new String(data, StandardCharsets.UTF_8);
        assertThat(content).contains("coordinate=\"X\"");
        assertThat(content).contains("coordinate=\"Y\"");
        assertThat(content).contains("coordinate=\"Z\"");
        assertThat(content).contains("0.7500");
    }

    @Test
    void shouldDefaultMetadataToEmpty() {
        assertThat(controller.getMetadata()).isEqualTo(AudioMetadata.EMPTY);
    }

    @Test
    void shouldExportTimeStampedPositionsFromAutomationLanes() throws IOException {
        // Build a session with one object track and an X-automation lane on it.
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("bed-L", SpeakerLabel.L));
        config.addAudioObject(new AudioObject("obj-1",
                new ObjectMetadata(0.0, 0.0, 0.0, 0.0, 1.0)));

        com.benesquivelmusic.daw.core.automation.AutomationData automationData =
                new com.benesquivelmusic.daw.core.automation.AutomationData();
        com.benesquivelmusic.daw.core.automation.AutomationLane xLane =
                automationData.getOrCreateObjectLane(
                        new com.benesquivelmusic.daw.core.automation.ObjectParameterTarget(
                                "obj-1",
                                com.benesquivelmusic.daw.sdk.spatial.ObjectParameter.X));
        // 120 BPM => 0.5 sec/beat — points at beats 0, 2 → seconds 0, 1.
        xLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(0.0, -0.8));
        xLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(2.0, 0.6));

        Path output = tempDir.resolve("trajectory.wav");
        controller.setAudioDataProvider(trackId -> new float[48_000 * 2]); // 2 sec at 48 kHz
        controller.setOutputPath(output);
        controller.setObjectAutomationProvider(trackId ->
                "obj-1".equals(trackId) ? automationData : null);
        controller.setTempoBpm(120.0);

        AtmosExportResult result = controller.performExport(config);
        assertThat(result.isSuccess()).isTrue();

        byte[] data = Files.readAllBytes(output);
        String content = new String(data, StandardCharsets.UTF_8);
        // Two breakpoints → two audioBlockFormat entries with rtime/duration.
        assertThat(content).contains("rtime=\"00:00:00.00000\"");
        assertThat(content).contains("rtime=\"00:00:01.00000\"");
        assertThat(content).contains("duration=");
        // The X values from the lane appear in the exported XML.
        assertThat(content).contains("-0.8000");
        assertThat(content).contains("0.6000");
    }

    @Test
    void shouldFallBackToStaticBlockFormatWhenNoAutomationProvider() throws IOException {
        AtmosSessionConfig config = createValidConfig();
        Path output = tempDir.resolve("static.wav");

        controller.setAudioDataProvider(trackId -> new float[1024]);
        controller.setOutputPath(output);

        AtmosExportResult result = controller.performExport(config);
        assertThat(result.isSuccess()).isTrue();

        byte[] data = Files.readAllBytes(output);
        String content = new String(data, StandardCharsets.UTF_8);
        // No rtime attribute when no automation provider is set.
        assertThat(content).doesNotContain("rtime=");
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
