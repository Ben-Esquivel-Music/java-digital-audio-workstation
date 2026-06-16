package com.benesquivelmusic.daw.core.persistence.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Story 298 — one compact, self-delimiting, checksummed entry in the
 * write-ahead journal.
 *
 * <p>A record is a notification-grade description of a single state
 * mutation: the monotonically increasing {@code sequence} number that
 * fixes global ordering, the wall-clock {@code timestamp}, a compact
 * {@code type} tag (e.g. {@code "MixerEvent.MuteChanged"} or the marker
 * {@link #CHECKPOINT_TYPE}), an optional {@code entityId} (the affected
 * UUID, or {@code null} for events with no single identity), and an
 * opaque {@code payload} of additional bytes (empty for most current
 * events — see {@code JournalReplayer} for the fidelity boundary).</p>
 *
 * <h2>On-disk frame</h2>
 * <p>Each record serialises to a single self-delimiting, CRC-checked
 * frame so the replayer can detect a torn tail (the normal result of a
 * crash mid-append) and stop cleanly rather than throwing:</p>
 * <pre>
 *   [int  frameLength]   bytes that follow, NOT counting this int
 *   [long sequence]
 *   [long epochMillis]
 *   [int  typeLen][type UTF-8 bytes]
 *   [byte hasEntity][16 bytes UUID iff hasEntity == 1]
 *   [int  payloadLen][payload bytes]
 *   [int  crc32]         CRC32 of everything between frameLength and crc32
 * </pre>
 * <p>{@link #readFrom} returns {@link Optional#empty()} on a short read
 * (truncated tail) OR a CRC mismatch (garbled tail) — both mean "stop
 * replaying here", which is the expected crash-truncation behaviour.</p>
 *
 * @param sequence  global monotonic ordering key; must be {@code >= 0}
 * @param timestamp wall-clock instant the mutation occurred
 * @param type      compact, stable tag for the mutation kind
 * @param entityId  the affected entity's UUID, or {@code null} when the
 *                  event carries no single identity (documented nullable —
 *                  matches the surrounding "absent id" style)
 * @param payload   opaque extra bytes (defensively copied; {@code null}
 *                  is normalised to an empty array)
 */
public record JournalRecord(
        long sequence,
        Instant timestamp,
        String type,
        UUID entityId,
        byte[] payload) {

    /** The {@code type} tag of a checkpoint-boundary marker record. */
    public static final String CHECKPOINT_TYPE = "$checkpoint";

    private static final byte[] EMPTY = new byte[0];

    /**
     * Canonical constructor. {@code sequence} must be non-negative;
     * {@code timestamp} and {@code type} are required; {@code entityId}
     * may be {@code null}; {@code payload} is defensively copied and a
     * {@code null} payload is normalised to an empty array.
     */
    public JournalRecord {
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be >= 0: " + sequence);
        }
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(type, "type must not be null");
        payload = (payload == null || payload.length == 0) ? EMPTY : payload.clone();
    }

    /** {@return a defensive copy of the payload bytes} */
    @Override
    public byte[] payload() {
        return payload.length == 0 ? EMPTY : payload.clone();
    }

    /** {@return the entity id if present} */
    public Optional<UUID> entityIdOpt() {
        return Optional.ofNullable(entityId);
    }

    /** {@return {@code true} if this is a checkpoint-boundary marker} */
    public boolean isCheckpointMarker() {
        return CHECKPOINT_TYPE.equals(type);
    }

    /**
     * Factory for a mutation record.
     *
     * @param sequence  global ordering key (&gt;= 0)
     * @param ts        wall-clock instant
     * @param type      compact mutation tag
     * @param entityId  affected entity id, or {@code null}
     * @param payload   opaque extra bytes (may be {@code null}/empty)
     * @return a new mutation record
     */
    public static JournalRecord ofMutation(long sequence, Instant ts, String type,
                                           UUID entityId, byte[] payload) {
        return new JournalRecord(sequence, ts, type, entityId, payload);
    }

    /**
     * Factory for a checkpoint-boundary marker record (no entity, no
     * payload).
     *
     * @param sequence global ordering key (&gt;= 0)
     * @param ts       wall-clock instant the checkpoint succeeded
     * @return a new marker record
     */
    public static JournalRecord checkpointMarker(long sequence, Instant ts) {
        return new JournalRecord(sequence, ts, CHECKPOINT_TYPE, null, EMPTY);
    }

    // ---- byte accounting --------------------------------------------------

    /**
     * {@return the exact number of bytes {@link #writeTo} will emit for
     * this record}, including the leading {@code frameLength} int and the
     * trailing {@code crc32} int. Used by {@code JournalWriter} for the
     * overflow byte cap and segment-rotation accounting.
     */
    public int serializedSize() {
        int typeBytes = type.getBytes(StandardCharsets.UTF_8).length;
        // Leading frameLength int (4) + body + trailing crc int (4).
        // Body = sequence(8) + epochMillis(8) + typeLen(4) + typeBytes
        //        + hasEntity(1) + [16 if present] + payloadLen(4) + payload.
        int body = 8 + 8 + 4 + typeBytes + 1 + (entityId != null ? 16 : 0) + 4 + payload.length;
        return 4 + body + 4;
    }

    // ---- serialisation ----------------------------------------------------

    /**
     * Encodes this record into a freshly-allocated {@link ByteBuffer},
     * flipped and ready to be written.
     *
     * @return a buffer positioned at 0, limited to {@link #serializedSize()}
     */
    public ByteBuffer toBuffer() {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        int hasEntity = entityId != null ? 1 : 0;
        int bodyLen = 8 + 8 + 4 + typeBytes.length + 1 + (hasEntity == 1 ? 16 : 0) + 4 + payload.length;

        ByteBuffer buf = ByteBuffer.allocate(4 + bodyLen + 4);
        buf.putInt(bodyLen + 4);            // frameLength = body + crc int
        int crcStart = buf.position();
        buf.putLong(sequence);
        buf.putLong(timestamp.toEpochMilli());
        buf.putInt(typeBytes.length);
        buf.put(typeBytes);
        buf.put((byte) hasEntity);
        if (hasEntity == 1) {
            buf.putLong(entityId.getMostSignificantBits());
            buf.putLong(entityId.getLeastSignificantBits());
        }
        buf.putInt(payload.length);
        buf.put(payload);

        CRC32 crc = new CRC32();
        crc.update(buf.array(), crcStart, buf.position() - crcStart);
        buf.putInt((int) crc.getValue());

        buf.flip();
        return buf;
    }

    /**
     * Writes this record's frame to {@code ch}. The caller is responsible
     * for any {@code force(true)} fsync (the writer fsyncs once per
     * appended group, not per record).
     *
     * @param ch the channel to append to
     * @throws IOException if the underlying write fails
     */
    public void writeTo(WritableByteChannel ch) throws IOException {
        Objects.requireNonNull(ch, "ch must not be null");
        ByteBuffer buf = toBuffer();
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /**
     * Reads the next record frame from {@code ch}.
     *
     * @param ch the channel to read from
     * @return the decoded record, or {@link Optional#empty()} if the frame
     *         is truncated (short read) or its CRC does not match (torn
     *         tail) — either way, replay must stop here
     * @throws IOException if the underlying read fails for a reason other
     *                     than end-of-stream
     */
    public static Optional<JournalRecord> readFrom(ReadableByteChannel ch) throws IOException {
        Objects.requireNonNull(ch, "ch must not be null");
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        if (!readFully(ch, lenBuf)) {
            return Optional.empty();
        }
        lenBuf.flip();
        int frameLen = lenBuf.getInt();
        if (frameLen <= 4 || frameLen > MAX_FRAME_LEN) {
            return Optional.empty();    // garbage length — treat as torn tail
        }
        ByteBuffer body = ByteBuffer.allocate(frameLen);
        if (!readFully(ch, body)) {
            return Optional.empty();    // truncated tail
        }
        body.flip();
        return decodeBody(body, frameLen);
    }

    /**
     * Decodes a record from {@code buf}, advancing the buffer's position
     * past the consumed frame. Used by the buffered whole-file reader in
     * {@code JournalSegment}.
     *
     * @param buf a buffer positioned at the start of a frame
     * @return the decoded record, or empty on truncation / CRC failure
     */
    public static Optional<JournalRecord> readFrom(ByteBuffer buf) {
        Objects.requireNonNull(buf, "buf must not be null");
        if (buf.remaining() < 4) {
            return Optional.empty();
        }
        int mark = buf.position();
        int frameLen = buf.getInt();
        if (frameLen <= 4 || frameLen > MAX_FRAME_LEN || buf.remaining() < frameLen) {
            buf.position(mark);         // leave buffer untouched on a short tail
            return Optional.empty();
        }
        ByteBuffer body = buf.slice();
        body.limit(frameLen);
        Optional<JournalRecord> rec = decodeBody(body, frameLen);
        if (rec.isPresent()) {
            buf.position(mark + 4 + frameLen);
        } else {
            buf.position(mark);
        }
        return rec;
    }

    /** Hard cap on a single frame so a corrupt length can't OOM the reader. */
    private static final int MAX_FRAME_LEN = 256 * 1024 * 1024;

    private static Optional<JournalRecord> decodeBody(ByteBuffer body, int frameLen) {
        // body holds [sequence..payload][crc]; frameLen = bodyLen + 4.
        int payloadAndMetaLen = frameLen - 4;
        if (body.remaining() < frameLen) {
            return Optional.empty();
        }
        int crcStart = body.position();
        long sequence = body.getLong();
        long epochMillis = body.getLong();
        int typeLen = body.getInt();
        if (typeLen < 0 || typeLen > payloadAndMetaLen) {
            return Optional.empty();
        }
        byte[] typeBytes = new byte[typeLen];
        body.get(typeBytes);
        byte hasEntity = body.get();
        UUID entityId = null;
        if (hasEntity == 1) {
            long msb = body.getLong();
            long lsb = body.getLong();
            entityId = new UUID(msb, lsb);
        } else if (hasEntity != 0) {
            return Optional.empty();    // corrupt flag
        }
        int payloadLen = body.getInt();
        if (payloadLen < 0 || payloadLen > body.remaining() - 4) {
            return Optional.empty();
        }
        byte[] payload = new byte[payloadLen];
        body.get(payload);

        int storedCrc = body.getInt();
        CRC32 crc = new CRC32();
        crc.update(body.array(), body.arrayOffset() + crcStart, payloadAndMetaLen);
        if ((int) crc.getValue() != storedCrc) {
            return Optional.empty();    // garbled tail — stop
        }
        if (sequence < 0L) {
            return Optional.empty();
        }
        return Optional.of(new JournalRecord(
                sequence,
                Instant.ofEpochMilli(epochMillis),
                new String(typeBytes, StandardCharsets.UTF_8),
                entityId,
                payload));
    }

    private static boolean readFully(ReadableByteChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n < 0) {
                return false;           // EOF before the buffer was filled
            }
        }
        return true;
    }
}
