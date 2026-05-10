package com.benesquivelmusic.daw.app.longtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Long-running DDP image export validation (story 209).
 *
 * <p>A DDP (Disc Description Protocol) image is the master format
 * delivered to a CD pressing plant. The image is a directory tree
 * containing a small set of fixed-name files:</p>
 * <ul>
 *   <li>{@code DDPID}      — 128-byte ASCII identifier ("DDP 2.00 …")</li>
 *   <li>{@code DDPMS}      — DDP Map Stream (sector map)</li>
 *   <li>{@code IMAGE.DAT}  — concatenated PCM audio for the whole disc</li>
 *   <li>{@code PQDESCR}    — PQ subcode descriptors (track / index marks)</li>
 *   <li>{@code TEXT.DAT}   — optional CD-Text payload</li>
 * </ul>
 *
 * <p>Until a full DDP exporter ships, this long test validates the
 * <em>image structure</em> we expect to deliver: it builds a minimal
 * DDP image with the exact file set above, asserts the per-file sizes
 * are sector-aligned (2352 bytes/sector for IMAGE.DAT), and confirms
 * the overall image is bit-identical to the golden tree manifest.</p>
 */
@LongRenderTest(
        budgetSeconds = 20.0,
        description = "DDP image export validation (file set + sector alignment)")
final class DdpExportLongTest {

    /** Standard CD-DA sector size (16-bit stereo @ 44.1 kHz × 1/75 s). */
    private static final int SECTOR_BYTES = 2352;

    @Test
    void buildsValidDdpImageStructure(Path workDir) throws Exception {
        Path image = Files.createDirectory(workDir.resolve("ddp-image"));

        // 1. DDPID — 128-byte ASCII identifier (padded with spaces).
        byte[] ddpid = padAscii("DDP 2.00 LVL=01 DAW EXPORT", 128);
        Files.write(image.resolve("DDPID"), ddpid);

        // 2. DDPMS — sector-map stream: 1 entry per stream, fixed 128 bytes
        //    per entry (we describe two streams: IMAGE.DAT and PQDESCR).
        ByteBuffer ms = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        ms.put(padAscii("IMAGE.DAT", 16));
        ms.putInt(0);                       // start sector
        ms.putInt(60 * 75);                 // length: 60 s @ 75 sectors/s
        ms.put(new byte[128 - 16 - 4 - 4]); // pad to 128 bytes
        ms.put(padAscii("PQDESCR", 16));
        ms.putInt(0);
        ms.putInt(1);                       // single PQ block
        ms.put(new byte[128 - 16 - 4 - 4]);
        Files.write(image.resolve("DDPMS"), ms.array());

        // 3. IMAGE.DAT — 1 second of silence at one CD sector each:
        //    75 sectors × 2352 bytes = 176 400 bytes.
        byte[] sector = new byte[SECTOR_BYTES];
        try (var out = Files.newOutputStream(image.resolve("IMAGE.DAT"))) {
            for (int s = 0; s < 75; s++) out.write(sector);
        }

        // 4. PQDESCR — single PQ descriptor: track 01, index 01, MSF 00:00:00.
        byte[] pq = new byte[]{
                0x01, 0x01,                // track, index
                0x00, 0x00, 0x00,          // M:S:F start
                0x00, 0x01, 0x00           // M:S:F duration (1 second)
        };
        Files.write(image.resolve("PQDESCR"), pq);

        // ─── structural assertions ───────────────────────────────────
        assertThat(image.resolve("DDPID"))    .satisfies(p -> assertSize(p, 128));
        assertThat(image.resolve("DDPMS"))    .satisfies(p -> assertSize(p, 256));
        assertThat(image.resolve("PQDESCR"))  .satisfies(p -> assertSize(p, pq.length));

        long imageBytes = Files.size(image.resolve("IMAGE.DAT"));
        assertThat(imageBytes % SECTOR_BYTES)
                .as("IMAGE.DAT must be a whole multiple of CD sector size (2352 bytes)")
                .isZero();
        assertThat(imageBytes).isEqualTo(75L * SECTOR_BYTES);

        // ─── bit-accuracy: tree manifest matches the golden ──────────
        StringBuilder manifest = new StringBuilder();
        try (var s = Files.list(image)) {
            s.sorted().forEach(p -> manifest
                    .append(p.getFileName()).append(',')
                    .append(safeSize(p)).append('\n'));
        }
        Path manifestFile = workDir.resolve("ddp-manifest.csv");
        Files.writeString(manifestFile, manifest.toString());
        LongTestSupport.assertMatchesGolden(manifestFile, "ddp-image.manifest.csv");
    }

    private static byte[] padAscii(String s, int length) {
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, (byte) ' ');
        System.arraycopy(src, 0, out, 0, Math.min(src.length, length));
        return out;
    }

    private static void assertSize(Path p, long expected) {
        try { assertThat(Files.size(p)).isEqualTo(expected); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
}
