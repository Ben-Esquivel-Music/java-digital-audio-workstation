package com.benesquivelmusic.daw.core.audioimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADM probe on WAV files bigger than any Java array: it judges a file by
 * its chunk headers through {@link AdmBwfImporter#isAdmBwf(Path)}, so a file
 * above 2 GiB is answered without being read whole, and a chunk's size is the
 * header's unsigned 32-bit field, so the walk gets past a {@code data} chunk
 * of more than 2 GiB to the {@code axml} chunk after it. The files are sparse,
 * so each test writes a few dozen bytes; each is deleted when its test ends.
 */
class AdmProbeOnFilesAboveTwoGibibytesTest {

    /** Just under 2^31 bytes of audio: the whole file is then above {@link Integer#MAX_VALUE}. */
    private static final long BELOW_TWO_GIBIBYTES = 0x7FFF_FFF0L;
    /** 2.25 GiB of audio: read as a signed 32-bit int, the chunk size would be negative. */
    private static final long ABOVE_TWO_GIBIBYTES = 0x9000_0000L;
    private static final byte[] AXML_BODY = "<ebuCoreMain/>".getBytes(StandardCharsets.US_ASCII);
    private static final int PREAMBLE_AND_FMT_BYTES = 12 + 8 + 16;
    private static final int CHUNK_HEADER_BYTES = 8;

    @TempDir
    Path tempDir;

    @Test
    void aPlainWavAboveTwoGibibytesIsNotAdmAndIsAnsweredWithoutReadingItWhole() throws IOException {
        Path file = tempDir.resolve("huge-plain.wav");
        try {
            sparseWav(file, BELOW_TWO_GIBIBYTES, false);
            assertThat(Files.size(file)).as("fixture: no byte array can hold the file")
                    .isGreaterThan(Integer.MAX_VALUE);

            assertThat(AdmBwfImporter.isAdmBwf(file)).isFalse();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void anAxmlChunkAfterMoreThanTwoGibibytesOfAudioIsFound() throws IOException {
        Path file = tempDir.resolve("huge-adm.wav");
        try {
            sparseWav(file, ABOVE_TWO_GIBIBYTES, true);
            assertThat((int) ABOVE_TWO_GIBIBYTES).as("fixture: the data size does not fit a signed int").isNegative();

            assertThat(AdmBwfImporter.isAdmBwf(file)).as("the walk seeks past the data chunk to axml").isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Writes a sparse 16-bit stereo 48 kHz WAV whose {@code data} chunk
     * declares {@code dataBytes} (an even number) and, when
     * {@code axmlAfterData}, an {@code axml} chunk after it. Only the headers
     * and the last byte are written.
     */
    private static void sparseWav(Path file, long dataBytes, boolean axmlAfterData) throws IOException {
        long dataStart = PREAMBLE_AND_FMT_BYTES + CHUNK_HEADER_BYTES;
        long axmlBytes = axmlAfterData ? CHUNK_HEADER_BYTES + AXML_BODY.length : 0;
        long total = dataStart + dataBytes + axmlBytes;
        ByteBuffer header = ByteBuffer.allocate((int) dataStart).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (total - 8L));
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        header.putShort((short) 1).putShort((short) 2).putInt(48_000).putInt(48_000 * 4)
                .putShort((short) 4).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataBytes);
        header.flip();
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.write(header);
            if (axmlAfterData) {
                ByteBuffer axml = ByteBuffer.allocate((int) axmlBytes).order(ByteOrder.LITTLE_ENDIAN);
                axml.put("axml".getBytes(StandardCharsets.US_ASCII)).putInt(AXML_BODY.length).put(AXML_BODY);
                axml.flip();
                channel.position(dataStart + dataBytes);
                channel.write(axml);
            } else {
                channel.position(total - 1L);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }
        }
    }
}
