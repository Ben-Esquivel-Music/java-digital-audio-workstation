package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.export.AudioExportConfig;
import com.benesquivelmusic.daw.sdk.export.AudioExportFormat;
import com.benesquivelmusic.daw.sdk.export.AudioExporter;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.export.ExportProgressListener;
import com.benesquivelmusic.daw.sdk.export.ExportResult;
import com.benesquivelmusic.daw.sdk.export.StemExportConfig;
import com.benesquivelmusic.daw.sdk.export.StemNamingConvention;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test asserting that the stem-export path produces the same
 * audio as the live render path for the same project state.
 *
 * <p>Story 102 — "Playback-Export Parity: Unified Render Pipeline for Live
 * and Offline Processing" — requires that {@link StemExporter} delegate
 * to {@link com.benesquivelmusic.daw.core.audio.RenderPipeline#renderOffline
 * RenderPipeline.renderOffline}, the same pipeline driving live playback
 * via {@link AudioEngine#processBlock}. This test exercises that contract:
 * for a single-track project, the stem buffer captured by an in-memory
 * {@link AudioExporter} must be bit-identical to what
 * {@code AudioEngine.processBlock} renders for the same project state.</p>
 *
 * <p>This is the export-path complement to
 * {@code RenderPipelineParityTest}, which verifies the pipeline itself.</p>
 */
class StemExporterParityTest {

    private static final double SAMPLE_RATE = 44_100.0;
    private static final int CHANNELS = 2;
    private static final int BUFFER_SIZE = 64;
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;

    private static AudioFormat format() {
        return new AudioFormat(SAMPLE_RATE, CHANNELS, 16, BUFFER_SIZE);
    }

    /**
     * In-memory {@link AudioExporter} that captures the float buffer it
     * receives without writing any file. Lets the test compare per-sample
     * against the live render.
     */
    private static final class CapturingExporter implements AudioExporter {
        float[][] captured;

        @Override
        public ExportResult export(float[][] audioData, int sourceSampleRate,
                                   Path outputDir, String baseName,
                                   AudioExportConfig config) {
            return export(audioData, sourceSampleRate, outputDir, baseName, config,
                    ExportProgressListener.NONE);
        }

        @Override
        public ExportResult export(float[][] audioData, int sourceSampleRate,
                                   Path outputDir, String baseName,
                                   AudioExportConfig config,
                                   ExportProgressListener listener) {
            this.captured = new float[audioData.length][];
            for (int ch = 0; ch < audioData.length; ch++) {
                captured[ch] = audioData[ch].clone();
            }
            // Return a successful "fake" result — outputPath is required by
            // ExportResult, but the test only inspects `captured`.
            Path fakePath = outputDir.resolve(baseName + ".wav");
            return new ExportResult(config, fakePath, true, "ok", 0L);
        }
        @Override
        public java.util.List<ExportResult> exportBatch(float[][] audioData,
                int sourceSampleRate, Path outputDir, String baseName,
                java.util.List<AudioExportConfig> configs) {
            java.util.List<ExportResult> out = new ArrayList<>();
            for (AudioExportConfig c : configs) {
                out.add(export(audioData, sourceSampleRate, outputDir, baseName, c));
            }
            return out;
        }
    }

    @Test
    void stemExporterAndLivePlaybackProduceBitIdenticalMaster(@TempDir Path tempDir)
            throws IOException {
        int totalFrames = BUFFER_SIZE * 100;
        double totalBeats = totalFrames / SAMPLES_PER_BEAT;

        // ── Live render via AudioEngine.processBlock ─────────────────────
        DawProject liveProject = buildProject(totalFrames);
        AudioEngine engine = new AudioEngine(format());
        engine.setTransport(liveProject.getTransport());
        engine.setMixer(liveProject.getMixer());
        engine.setTracks(liveProject.getTracks());
        liveProject.getTransport().play();
        engine.start();

        float[][] liveOut = new float[CHANNELS][totalFrames];
        float[][] blockIn = new float[CHANNELS][BUFFER_SIZE];
        float[][] blockOut = new float[CHANNELS][BUFFER_SIZE];
        int rendered = 0;
        while (rendered < totalFrames) {
            int n = Math.min(BUFFER_SIZE, totalFrames - rendered);
            for (int ch = 0; ch < CHANNELS; ch++) {
                Arrays.fill(blockOut[ch], 0.0f);
            }
            engine.processBlock(blockIn, blockOut, n);
            for (int ch = 0; ch < CHANNELS; ch++) {
                System.arraycopy(blockOut[ch], 0, liveOut[ch], rendered, n);
            }
            rendered += n;
        }

        // ── Offline render via StemExporter ──────────────────────────────
        // Use a fresh project so live render's transport advance is not
        // shared with the export run.
        DawProject offlineProject = buildProject(totalFrames);
        CapturingExporter capturing = new CapturingExporter();
        StemExporter stemExporter = new StemExporter(capturing);

        AudioExportConfig audioConfig = new AudioExportConfig(
                AudioExportFormat.WAV, (int) SAMPLE_RATE, 16, DitherType.NONE);
        StemExportConfig stemConfig = new StemExportConfig(
                List.of(0), audioConfig, StemNamingConvention.TRACK_NAME, "Project");

        stemExporter.exportStems(offlineProject, stemConfig, tempDir, totalBeats,
                ExportProgressListener.NONE);

        assertThat(capturing.captured).isNotNull();
        float[][] stemOut = capturing.captured;

        // ── Bit-identical parity for the master ──────────────────────────
        // Single-track project + empty master chain ⇒ stem == live master.
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < totalFrames; i++) {
                assertThat(stemOut[ch][i])
                        .as("Channel %d sample %d", ch, i)
                        .isEqualTo(liveOut[ch][i]);
            }
        }
    }

    /**
     * Builds a fresh single-track project carrying a deterministic clip.
     */
    private static DawProject buildProject(int totalFrames) {
        DawProject project = new DawProject("ParityProject", format());
        project.getTransport().setTempo(TEMPO);

        Track track = project.createAudioTrack("Lead");
        AudioClip clip = new AudioClip("Lead-Clip", 0.0,
                totalFrames / SAMPLES_PER_BEAT, null);
        float[][] data = new float[CHANNELS][totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE) * 0.7f;
            data[0][i] = v;
            data[1][i] = v * 0.85f;
        }
        clip.setAudioData(data);
        track.addClip(clip);

        // Tweak channel away from defaults so volume/pan are exercised in
        // the comparison.
        MixerChannel channel = project.getMixerChannelForTrack(track);
        channel.setVolume(0.7);
        channel.setPan(-0.25);

        return project;
    }
}
