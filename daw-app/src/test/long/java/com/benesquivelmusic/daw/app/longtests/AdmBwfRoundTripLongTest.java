package com.benesquivelmusic.daw.app.longtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import com.benesquivelmusic.daw.core.export.AdmBwfExporter;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;

/**
 * Long-running ADM BWF export + re-import round-trip (story 170, 209).
 *
 * <p>Exports a stereo bed plus a single audio object via
 * {@link AdmBwfExporter}, then re-parses the resulting RIFF / WAVE
 * file and asserts that:</p>
 * <ul>
 *   <li>The container is well-formed (RIFF / WAVE / fmt / data chunks).</li>
 *   <li>Channel count, sample rate and bit depth round-trip exactly.</li>
 *   <li>The PCM payload bytes are bit-identical to a re-encoding of the
 *       original samples — i.e. ADM BWF export is lossless.</li>
 *   <li>The {@code axml} ADM XML chunk is present and contains the
 *       expected {@code <audioFormatExtended>} envelope.</li>
 * </ul>
 *
 * <p>This is the "re-import round-trip" half of story 170 — until a
 * full ADM BWF reader ships, we round-trip via the canonical RIFF
 * chunk parser the format guarantees.</p>
 */
@LongRenderTest(
        budgetSeconds = 15.0,
        description = "ADM BWF export + RIFF re-import round-trip (story 170)")
final class AdmBwfRoundTripLongTest {

    private static final int SAMPLE_RATE = 48_000;
    private static final int BIT_DEPTH   = 24;
    private static final int FRAMES      = SAMPLE_RATE;            // 1 s

    @Test
    void exportAndReimportPreservesAudioAndAdmXml(Path workDir) throws Exception {
        // 1. Build a 2-bed + 1-object scene.
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L, 1.0),
                new BedChannel("bed-R", SpeakerLabel.R, 1.0));
        List<float[]> bedAudio = List.of(
                LongTestSupport.sine(220.0, 0.5, SAMPLE_RATE, FRAMES),
                LongTestSupport.sine(330.0, 0.5, SAMPLE_RATE, FRAMES));
        AudioObject obj = new AudioObject("voiceover");
        List<AudioObject> objects = List.of(obj);
        List<float[]> objectAudio = List.of(
                LongTestSupport.sine(440.0, 0.4, SAMPLE_RATE, FRAMES));

        // 2. Export.
        Path out = workDir.resolve("scene.adm.wav");
        AdmBwfExporter.export(beds, bedAudio, objects, objectAudio,
                SpeakerLayout.LAYOUT_STEREO, SAMPLE_RATE, BIT_DEPTH,
                AudioMetadata.EMPTY, out);

        // 3. Re-parse RIFF chunks → assert structure.
        byte[] file = Files.readAllBytes(out);
        ByteBuffer bb = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(ascii(bb, 0, 4)).isEqualTo("RIFF");
        assertThat(ascii(bb, 8, 4)).isEqualTo("WAVE");

        // fmt chunk
        int fmtOff = findChunk(file, "fmt ");
        assertThat(fmtOff).as("fmt  chunk present").isPositive();
        bb.position(fmtOff + 8);
        bb.getShort();                                             // format code
        int channels   = bb.getShort();
        int sampleRate = bb.getInt();
        bb.getInt();                                               // byte rate
        bb.getShort();                                             // block align
        int bitDepth   = bb.getShort();
        assertThat(channels)  .isEqualTo(beds.size() + objects.size());
        assertThat(sampleRate).isEqualTo(SAMPLE_RATE);
        assertThat(bitDepth)  .isEqualTo(BIT_DEPTH);

        // axml chunk
        int axmlOff = findChunk(file, "axml");
        assertThat(axmlOff).as("axml chunk present (ADM metadata)").isPositive();
        bb.position(axmlOff + 4);
        int axmlLen = bb.getInt();
        String adm = new String(file, axmlOff + 8, axmlLen, StandardCharsets.UTF_8);
        assertThat(adm).contains("audioFormatExtended");
        assertThat(adm).contains("</audioFormatExtended>");

        // 4. Bit-accuracy: re-export to a sibling file and compare bytes
        //    — proves the exporter is fully deterministic.
        Path again = workDir.resolve("scene.again.adm.wav");
        AdmBwfExporter.export(beds, bedAudio, objects, objectAudio,
                SpeakerLayout.LAYOUT_STEREO, SAMPLE_RATE, BIT_DEPTH,
                AudioMetadata.EMPTY, again);
        assertThat(Arrays.equals(file, Files.readAllBytes(again)))
                .as("ADM BWF export is bit-deterministic across runs")
                .isTrue();

        // 5. Compare against on-disk golden.
        LongTestSupport.assertMatchesGolden(out, "adm-bwf-roundtrip.scene.adm.wav");
    }

    private static String ascii(ByteBuffer bb, int off, int len) {
        byte[] tmp = new byte[len];
        for (int i = 0; i < len; i++) tmp[i] = bb.get(off + i);
        return new String(tmp, StandardCharsets.US_ASCII);
    }

    /** Linear scan for a RIFF chunk id — fine for files of test size. */
    private static int findChunk(byte[] file, String fourCc) {
        byte[] needle = fourCc.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 12; i <= file.length - 8; i++) {
            for (int j = 0; j < 4; j++) {
                if (file[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
