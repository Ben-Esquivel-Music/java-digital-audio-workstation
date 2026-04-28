package com.benesquivelmusic.daw.core.export.aaf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for the {@link AafWriter} / {@link AafReader} pair
 * that act as the bundled "AAF verifier" for the rest of the test suite.
 */
class AafWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyCompositionRoundTripsThroughWriterAndReader() throws IOException {
        AafComposition empty = new AafComposition(
                "Empty", 48000, AafFrameRate.FPS_24,
                AafTimecode.zero(AafFrameRate.FPS_24), 0, List.of());
        Path file = tempDir.resolve("empty.aaf");

        new AafWriter().write(empty, file);
        AafReader.AafFile parsed = new AafReader().read(file);

        assertThat(parsed.versionMajor()).isEqualTo((int) AafWriter.VERSION_MAJOR);
        assertThat(parsed.versionMinor()).isEqualTo((int) AafWriter.VERSION_MINOR);
        assertThat(parsed.composition().clips()).isEmpty();
        assertThat(parsed.embeddedMedia()).isEmpty();
    }

    @Test
    void compositionWithOneClipPreservesAllFields() throws IOException {
        UUID mob = UUID.randomUUID();
        AafSourceClip clip = new AafSourceClip(
                mob, "/sources/dialog.wav", "dialog.wav",
                0, "Dialog",
                /* startSample */ 48_000,
                /* lengthSamples */ 96_000,
                /* sourceOffsetSamples */ 1_200,
                /* gainDb */ -3.5,
                /* fadeInSamples */ 4_800,
                AafFadeCurve.EQUAL_POWER,
                /* fadeOutSamples */ 9_600,
                AafFadeCurve.S_CURVE);
        AafComposition comp = new AafComposition(
                "Reel 1", 48000, AafFrameRate.FPS_23_976,
                new AafTimecode(1, 0, 0, 0, AafFrameRate.FPS_23_976),
                144_000,
                List.of(clip));
        Path file = tempDir.resolve("one.aaf");

        new AafWriter().write(comp, file);
        AafReader.AafFile parsed = new AafReader().read(file);

        AafSourceClip back = parsed.composition().clips().get(0);
        assertThat(back.sourceMobId()).isEqualTo(mob);
        assertThat(back.sourceFile()).isEqualTo("/sources/dialog.wav");
        assertThat(back.sourceName()).isEqualTo("dialog.wav");
        assertThat(back.trackIndex()).isZero();
        assertThat(back.trackName()).isEqualTo("Dialog");
        // Sample-quantised fields must match exactly (1-sample precision).
        assertThat(back.startSample()).isEqualTo(48_000);
        assertThat(back.lengthSamples()).isEqualTo(96_000);
        assertThat(back.sourceOffsetSamples()).isEqualTo(1_200);
        assertThat(back.fadeInSamples()).isEqualTo(4_800);
        assertThat(back.fadeOutSamples()).isEqualTo(9_600);
        assertThat(back.fadeInCurve()).isEqualTo(AafFadeCurve.EQUAL_POWER);
        assertThat(back.fadeOutCurve()).isEqualTo(AafFadeCurve.S_CURVE);
        assertThat(back.gainDb()).isEqualTo(-3.5);
        assertThat(parsed.composition().startTimecode())
                .isEqualTo(new AafTimecode(1, 0, 0, 0, AafFrameRate.FPS_23_976));
        assertThat(parsed.composition().frameRate()).isEqualTo(AafFrameRate.FPS_23_976);
    }

    @Test
    void embeddedMediaRoundTripsByteForByte() throws IOException {
        UUID mob = UUID.randomUUID();
        byte[] pcm = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        AafWriter.EmbeddedMedia media = new AafWriter.EmbeddedMedia(
                "embed.wav", 48000, 1, 24, 3, pcm);
        AafSourceClip clip = new AafSourceClip(
                mob, null, "embed.wav", 0, "T1", 0, 1000, 0, 0,
                0, AafFadeCurve.LINEAR, 0, AafFadeCurve.LINEAR);
        AafComposition comp = new AafComposition(
                "Comp", 48000, AafFrameRate.FPS_25,
                AafTimecode.zero(AafFrameRate.FPS_25), 1000, List.of(clip));
        Path file = tempDir.resolve("embed.aaf");

        new AafWriter().write(comp, Map.of(mob, media), file);
        AafReader.AafFile parsed = new AafReader().read(file);

        assertThat(parsed.embeddedMedia()).containsKey(mob);
        AafWriter.EmbeddedMedia back = parsed.embeddedMedia().get(mob);
        assertThat(back.pcmData()).containsExactly(pcm);
        assertThat(back.name()).isEqualTo("embed.wav");
        assertThat(back.channels()).isEqualTo(1);
        assertThat(back.bitsPerSample()).isEqualTo(24);
        assertThat(back.sampleRate()).isEqualTo(48000);
        assertThat(back.frameCount()).isEqualTo(3);
    }

    @Test
    void frameRateConversionPreservesTimelineLength() throws IOException {
        // Same composition expressed at three different frame rates
        // must round-trip with the same totalLengthSamples.
        long total = 48000L * 60L * 5L;  // 5 minutes at 48 kHz
        for (AafFrameRate fr : AafFrameRate.values()) {
            AafComposition c = new AafComposition(
                    "FR", 48000, fr, AafTimecode.zero(fr), total, List.of());
            Path file = tempDir.resolve("fr-" + fr.name() + ".aaf");
            new AafWriter().write(c, file);
            AafReader.AafFile parsed = new AafReader().read(file);
            assertThat(parsed.composition().totalLengthSamples()).isEqualTo(total);
            assertThat(parsed.composition().frameRate()).isEqualTo(fr);
        }
    }
}
