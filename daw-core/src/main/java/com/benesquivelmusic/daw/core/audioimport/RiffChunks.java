package com.benesquivelmusic.daw.core.audioimport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A header-only walk of a RIFF/WAVE file's chunk list: it reads the 12-byte
 * {@code RIFF}/{@code WAVE} preamble and then each 8-byte chunk header, and
 * seeks past every chunk body instead of reading it. Asking whether a WAV
 * carries an ADM {@code axml} chunk, or how large its {@code data} chunk is,
 * therefore costs a few bytes per chunk, never a read of the whole file
 * (Persistence Integrity book §4.3: reopening a project probes every WAV it
 * references).
 *
 * <p>The walk follows the rule {@code AdmBwfImporter} has always used:
 * chunk bodies are padded to an even length, and the walk stops at the
 * first chunk header whose declared size is larger than the rest of the
 * file — the chunks before it are still reported. A size is the header's
 * unsigned 32-bit field, so a chunk of 2 GiB or more (a long {@code data}
 * chunk) is walked past like any other.</p>
 *
 * <p>Thread: any non-real-time thread; the channel is the caller's.</p>
 */
final class RiffChunks {

    private static final int PREAMBLE_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;

    private RiffChunks() {
        // utility class
    }

    /**
     * One chunk the walk found.
     *
     * @param id         the four-character chunk id ({@code "fmt "}, {@code "data"}, {@code "axml"}, …)
     * @param bodyOffset the byte offset of the chunk body in the file
     * @param size       the declared body size in bytes, without the pad byte
     */
    record Chunk(String id, long bodyOffset, long size) {
        Chunk {
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * Walks the chunk headers of a RIFF/WAVE file.
     *
     * @param channel the file, positioned anywhere; its position is changed
     * @return the chunks in file order up to the first malformed header, or
     *         an empty list when the file does not start with a
     *         {@code RIFF}/{@code WAVE} preamble
     * @throws IOException if reading a header fails
     */
    static List<Chunk> scan(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel must not be null");
        long length = channel.size();
        if (length < PREAMBLE_BYTES) {
            return List.of();
        }
        ByteBuffer preamble = ByteBuffer.allocate(PREAMBLE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, preamble, 0);
        if (!"RIFF".equals(ascii(preamble, 0)) || !"WAVE".equals(ascii(preamble, 8))) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>();
        ByteBuffer header = ByteBuffer.allocate(CHUNK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        long position = PREAMBLE_BYTES;
        while (length - position >= CHUNK_HEADER_BYTES) {
            header.clear();
            readFully(channel, header, position);
            String id = ascii(header, 0);
            // The size field is unsigned 32-bit: a data chunk of 2 to 4 GiB is legal.
            long size = Integer.toUnsignedLong(header.getInt(4));
            position += CHUNK_HEADER_BYTES;
            long remaining = length - position;
            if (size > remaining) {
                break;
            }
            chunks.add(new Chunk(id, position, size));
            long padded = size + (size % 2);
            position += Math.min(padded, remaining);
        }
        return List.copyOf(chunks);
    }

    /**
     * Returns the first chunk with the given id, walking headers only.
     *
     * @param channel the file
     * @param id      the chunk id
     * @return the chunk, or empty when the walk does not reach one
     * @throws IOException if reading a header fails
     */
    static Optional<Chunk> find(SeekableByteChannel channel, String id) throws IOException {
        Objects.requireNonNull(id, "id must not be null");
        for (Chunk chunk : scan(channel)) {
            if (chunk.id().equals(id)) {
                return Optional.of(chunk);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the first {@code maxBytes} bytes of a chunk body (all of it when
     * the body is shorter).
     *
     * @param channel  the file the chunk was found in
     * @param chunk    the chunk
     * @param maxBytes the most bytes to read
     * @return the body bytes
     * @throws IOException if the body cannot be read
     */
    static byte[] readBody(SeekableByteChannel channel, Chunk chunk, int maxBytes) throws IOException {
        int bytes = (int) Math.min(chunk.size(), maxBytes);
        ByteBuffer body = ByteBuffer.allocate(bytes);
        readFully(channel, body, chunk.bodyOffset());
        return body.array();
    }

    private static String ascii(ByteBuffer buffer, int offset) {
        byte[] tag = new byte[4];
        buffer.get(offset, tag);
        return new String(tag, StandardCharsets.US_ASCII);
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        channel.position(position);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("unexpected end of file at byte " + channel.position());
            }
        }
    }
}
