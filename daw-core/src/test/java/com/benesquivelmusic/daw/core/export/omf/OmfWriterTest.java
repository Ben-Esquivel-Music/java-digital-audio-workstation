package com.benesquivelmusic.daw.core.export.omf;

import com.benesquivelmusic.daw.core.export.aaf.AafComposition;
import com.benesquivelmusic.daw.core.export.aaf.AafFadeCurve;
import com.benesquivelmusic.daw.core.export.aaf.AafFrameRate;
import com.benesquivelmusic.daw.core.export.aaf.AafReader;
import com.benesquivelmusic.daw.core.export.aaf.AafSourceClip;
import com.benesquivelmusic.daw.core.export.aaf.AafTimecode;
import com.benesquivelmusic.daw.core.export.aaf.AafWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the OMF&nbsp;2.0 fallback writer. The file uses {@code OMF20}
 * magic and {@code OEND} trailer; the data model is otherwise identical
 * to the AAF&nbsp;1.2 output, so the reader (used in package-private
 * mode) verifies it round-trips correctly.
 */
class OmfWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writtenFileHasOmf20MagicAndOendTrailer() throws IOException {
        AafComposition comp = new AafComposition(
                "OMF Test", 48000, AafFrameRate.FPS_24,
                AafTimecode.zero(AafFrameRate.FPS_24), 0, List.of());
        Path file = tempDir.resolve("out.omf");
        new OmfWriter().write(comp, file);

        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).startsWith(OmfWriter.OMF_MAGIC);
        byte[] tail = new byte[]{
                bytes[bytes.length - 4], bytes[bytes.length - 3],
                bytes[bytes.length - 2], bytes[bytes.length - 1]};
        assertThat(tail).containsExactly(OmfWriter.OMF_TRAILER);
    }

    @Test
    void omfRoundTripsCompositionDataIdenticallyToAaf() throws Exception {
        UUID mob = UUID.randomUUID();
        AafSourceClip clip = new AafSourceClip(
                mob, "/x/file.wav", "file.wav", 0, "Track 1",
                48_000, 96_000, 100, -2.0,
                480, AafFadeCurve.EQUAL_POWER, 960, AafFadeCurve.S_CURVE);
        AafComposition comp = new AafComposition(
                "Cmp", 48000, AafFrameRate.FPS_25,
                AafTimecode.zero(AafFrameRate.FPS_25), 96_000, List.of(clip));
        Path file = tempDir.resolve("rt.omf");
        new OmfWriter().write(comp, file);

        // Use the reader's package-private overload to parse with OMF
        // magic + trailer (the public API parses AAF only).
        Method m = AafReader.class.getDeclaredMethod(
                "read", Path.class, byte[].class, byte[].class);
        m.setAccessible(true);
        AafReader.AafFile parsed = (AafReader.AafFile) m.invoke(
                new AafReader(), file, OmfWriter.OMF_MAGIC, OmfWriter.OMF_TRAILER);

        assertThat(parsed.versionMajor()).isEqualTo((int) OmfWriter.VERSION_MAJOR);
        assertThat(parsed.versionMinor()).isEqualTo((int) OmfWriter.VERSION_MINOR);
        AafSourceClip back = parsed.composition().clips().get(0);
        assertThat(back.sourceMobId()).isEqualTo(mob);
        assertThat(back.startSample()).isEqualTo(48_000);
        assertThat(back.lengthSamples()).isEqualTo(96_000);
        assertThat(back.fadeInCurve()).isEqualTo(AafFadeCurve.EQUAL_POWER);
        assertThat(back.fadeOutCurve()).isEqualTo(AafFadeCurve.S_CURVE);
        assertThat(back.gainDb()).isEqualTo(-2.0);
    }

    /** Sanity: verify {@link AafWriter} and {@link OmfWriter} use distinct magic. */
    @Test
    void aafAndOmfMagicAreDistinct() {
        assertThat(AafWriter.AAF_MAGIC).isNotEqualTo(OmfWriter.OMF_MAGIC);
        assertThat(AafWriter.AAF_TRAILER).isNotEqualTo(OmfWriter.OMF_TRAILER);
    }
}
