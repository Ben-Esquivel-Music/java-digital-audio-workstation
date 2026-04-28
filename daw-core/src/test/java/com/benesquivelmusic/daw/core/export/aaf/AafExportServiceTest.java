package com.benesquivelmusic.daw.core.export.aaf;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AafExportServiceTest {

    private static final int SAMPLE_RATE = 48000;
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = (60.0 / TEMPO) * SAMPLE_RATE;

    private DawProject project;
    private AafExportService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        project = new DawProject("Reel-1",
                new AudioFormat(SAMPLE_RATE, 2, 24, 512));
        project.getTransport().setTempo(TEMPO);
        service = new AafExportService();
    }

    @Test
    void threeTrackSessionRoundTripsThroughAafWithSampleAccuratePositions() throws IOException {
        Track dialog = project.createAudioTrack("Dialog");
        Track music  = project.createAudioTrack("Music");
        Track fx     = project.createAudioTrack("FX");

        AudioClip dialogClip = newClip("VO_01.wav", 0.0, 4.0,
                /* fadeIn */ 0.5, FadeCurveType.LINEAR,
                /* fadeOut */ 0.25, FadeCurveType.S_CURVE,
                -3.0);
        AudioClip musicClip  = newClip("Cue_01.wav", 2.0, 8.0,
                0.0, FadeCurveType.LINEAR,
                1.0, FadeCurveType.EQUAL_POWER,
                -6.5);
        AudioClip fxClip     = newClip("Whoosh.wav", 1.5, 0.75,
                0.1, FadeCurveType.EQUAL_POWER,
                0.1, FadeCurveType.EQUAL_POWER,
                0.0);

        dialog.addClip(dialogClip);
        music.addClip(musicClip);
        fx.addClip(fxClip);

        AafExportConfig cfg = new AafExportConfig(
                AafFrameRate.FPS_24,
                new AafTimecode(1, 0, 0, 0, AafFrameRate.FPS_24),
                /* embedMedia */ false, List.of(),
                "Reel-1");
        Path out = tempDir.resolve("reel1.aaf");

        service.export(project, cfg, out);
        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.size(out)).isGreaterThan(0);

        AafReader.AafFile parsed = new AafReader().read(out);
        AafComposition comp = parsed.composition();

        assertThat(comp.frameRate()).isEqualTo(AafFrameRate.FPS_24);
        assertThat(comp.startTimecode().hours()).isEqualTo(1);
        assertThat(comp.clips()).hasSize(3);

        // Clips are sorted by track index then start position.
        AafSourceClip readDialog = comp.clips().get(0);
        AafSourceClip readMusic  = comp.clips().get(1);
        AafSourceClip readFx     = comp.clips().get(2);

        // Position / length precision: 1 sample.
        assertWithinSample(readDialog.startSample(),  beatsToSamples(0.0));
        assertWithinSample(readDialog.lengthSamples(), beatsToSamples(4.0));
        assertWithinSample(readDialog.fadeInSamples(),  beatsToSamples(0.5));
        assertWithinSample(readDialog.fadeOutSamples(), beatsToSamples(0.25));

        assertWithinSample(readMusic.startSample(),   beatsToSamples(2.0));
        assertWithinSample(readMusic.lengthSamples(), beatsToSamples(8.0));
        assertWithinSample(readMusic.fadeOutSamples(), beatsToSamples(1.0));

        assertWithinSample(readFx.startSample(),   beatsToSamples(1.5));
        assertWithinSample(readFx.lengthSamples(), beatsToSamples(0.75));

        // Curve types preserved.
        assertThat(readDialog.fadeInCurve()).isEqualTo(AafFadeCurve.LINEAR);
        assertThat(readDialog.fadeOutCurve()).isEqualTo(AafFadeCurve.S_CURVE);
        assertThat(readMusic.fadeOutCurve()).isEqualTo(AafFadeCurve.EQUAL_POWER);
        assertThat(readFx.fadeInCurve()).isEqualTo(AafFadeCurve.EQUAL_POWER);

        // Per-clip gain preserved.
        assertThat(readDialog.gainDb()).isEqualTo(-3.0);
        assertThat(readMusic.gainDb()).isEqualTo(-6.5);
        assertThat(readFx.gainDb()).isEqualTo(0.0);

        // Track names preserved.
        assertThat(readDialog.trackName()).isEqualTo("Dialog");
        assertThat(readMusic.trackName()).isEqualTo("Music");
        assertThat(readFx.trackName()).isEqualTo("FX");
    }

    @Test
    void embedMediaProducesSelfContainedFile() throws IOException {
        Track dialog = project.createAudioTrack("Dialog");
        AudioClip clip = newClip("VO.wav", 0.0, 1.0,
                0.0, FadeCurveType.LINEAR, 0.0, FadeCurveType.LINEAR, 0.0);
        // Provide some in-memory audio so embedding has data to write.
        float[][] audio = new float[2][1024];
        for (int ch = 0; ch < 2; ch++)
            for (int i = 0; i < 1024; i++)
                audio[ch][i] = (i % 100) / 100f - 0.5f;
        clip.setAudioData(audio);
        dialog.addClip(clip);

        AafExportConfig cfg = new AafExportConfig(
                AafFrameRate.FPS_25,
                AafTimecode.zero(AafFrameRate.FPS_25),
                /* embedMedia */ true, List.of(),
                "Embedded");
        Path out = tempDir.resolve("embed.aaf");
        service.export(project, cfg, out);

        AafReader.AafFile parsed = new AafReader().read(out);
        assertThat(parsed.embeddedMedia()).hasSize(1);
        AafWriter.EmbeddedMedia media = parsed.embeddedMedia().values().iterator().next();
        assertThat(media.channels()).isEqualTo(2);
        assertThat(media.frameCount()).isEqualTo(1024);
        assertThat(media.bitsPerSample()).isEqualTo(24);
        assertThat(media.pcmData()).hasSize(1024 * 2 * 3);  // 24-bit interleaved
    }

    @Test
    void includedTrackIndicesFiltersOutOtherTracks() throws IOException {
        Track dialog = project.createAudioTrack("Dialog");
        project.createAudioTrack("Music");
        Track fx     = project.createAudioTrack("FX");

        dialog.addClip(newClip("VO.wav", 0.0, 4.0, 0, FadeCurveType.LINEAR, 0, FadeCurveType.LINEAR, 0));
        // Music clip will be excluded by the inclusion list below.
        project.getTracks().get(1).addClip(
                newClip("M.wav", 0.0, 4.0, 0, FadeCurveType.LINEAR, 0, FadeCurveType.LINEAR, 0));
        fx.addClip(newClip("FX.wav", 0.0, 4.0, 0, FadeCurveType.LINEAR, 0, FadeCurveType.LINEAR, 0));

        AafExportConfig cfg = new AafExportConfig(
                AafFrameRate.FPS_24,
                AafTimecode.zero(AafFrameRate.FPS_24),
                false, List.of(0, 2), "Subset");
        Path out = tempDir.resolve("subset.aaf");
        service.export(project, cfg, out);
        AafReader.AafFile parsed = new AafReader().read(out);

        assertThat(parsed.composition().clips()).hasSize(2);
        assertThat(parsed.composition().clips()).extracting(AafSourceClip::trackName)
                .containsExactlyInAnyOrder("Dialog", "FX");
    }

    @Test
    void frameRateConversionPreservesTimelineLength() throws IOException {
        Track t = project.createAudioTrack("T");
        // 8 beats at 120 BPM = 4 seconds = 192000 samples
        t.addClip(newClip("c.wav", 0.0, 8.0, 0, FadeCurveType.LINEAR, 0, FadeCurveType.LINEAR, 0));

        long lengthAt24 = exportAndGetTotalLength(AafFrameRate.FPS_24, "c24.aaf");
        long lengthAt25 = exportAndGetTotalLength(AafFrameRate.FPS_25, "c25.aaf");
        long lengthAt30 = exportAndGetTotalLength(AafFrameRate.FPS_30, "c30.aaf");

        // The timeline length in samples must not depend on the
        // labelled frame rate.
        assertThat(lengthAt25).isEqualTo(lengthAt24);
        assertThat(lengthAt30).isEqualTo(lengthAt24);
    }

    private long exportAndGetTotalLength(AafFrameRate fr, String fileName) throws IOException {
        AafExportConfig cfg = new AafExportConfig(
                fr, AafTimecode.zero(fr), false, List.of(), "L");
        Path out = tempDir.resolve(fileName);
        service.export(project, cfg, out);
        return new AafReader().read(out).composition().totalLengthSamples();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static AudioClip newClip(String name, double startBeat, double durationBeats,
                                     double fadeInBeats, FadeCurveType fiCurve,
                                     double fadeOutBeats, FadeCurveType foCurve,
                                     double gainDb) {
        AudioClip c = new AudioClip(name, startBeat, durationBeats, "/sources/" + name);
        c.setFadeInBeats(fadeInBeats);
        c.setFadeInCurveType(fiCurve);
        c.setFadeOutBeats(fadeOutBeats);
        c.setFadeOutCurveType(foCurve);
        c.setGainDb(gainDb);
        return c;
    }

    private static long beatsToSamples(double beats) {
        return Math.round(beats * SAMPLES_PER_BEAT);
    }

    private static void assertWithinSample(long actual, long expected) {
        assertThat(Math.abs(actual - expected))
                .as("actual=%d expected=%d", actual, expected)
                .isLessThanOrEqualTo(1L);
    }
}
