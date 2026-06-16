package com.benesquivelmusic.daw.core.persistence.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Story 298 — one append-only journal segment file ({@code segment-NNN.bin}),
 * owned by a single writer.
 *
 * <p>The backing {@link FileChannel} is opened {@code CREATE, WRITE, APPEND}
 * so writes are pure appends; durability is achieved by calling
 * {@link FileChannel#force(boolean) force(true)} (fsync) once per appended
 * group — not per record, which would be needlessly slow, and not never,
 * which would defeat write-ahead durability. The tmp+atomic-move pattern
 * used elsewhere for whole-file head writes is deliberately <em>not</em>
 * used here: a segment grows by appending, and a crash mid-append leaves a
 * torn tail that {@link #readAll} detects and stops at via the per-record
 * CRC.</p>
 *
 * <p>This class is not thread-safe; the {@link JournalWriter} confines all
 * appends to its single writer virtual thread.</p>
 */
final class JournalSegment implements AutoCloseable {

    /**
     * Pluggable fsync seam so tests can count {@code force(true)} calls
     * without touching production durability. The default delegates to a
     * real {@link FileChannel#force(boolean) force(true)}.
     */
    @FunctionalInterface
    interface Syncer {
        /** Flushes {@code ch}'s data (and metadata) to the storage device. */
        void force(FileChannel ch) throws IOException;

        /** The real, durable default: {@code ch.force(true)}. */
        Syncer REAL = ch -> ch.force(true);
    }

    private final Path path;
    private final FileChannel channel;
    private final Syncer syncer;
    private long sizeBytes;
    private boolean closed;

    private JournalSegment(Path path, FileChannel channel, Syncer syncer, long initialSize) {
        this.path = path;
        this.channel = channel;
        this.syncer = syncer;
        this.sizeBytes = initialSize;
    }

    /**
     * Opens (creating if necessary) the segment at {@code path} for
     * appending, using the real fsync.
     *
     * @param path the segment file path
     * @return an open segment
     * @throws IOException if the channel cannot be opened
     */
    static JournalSegment open(Path path) throws IOException {
        return open(path, Syncer.REAL);
    }

    /**
     * Opens (creating if necessary) the segment at {@code path} for
     * appending, using the given fsync seam.
     *
     * @param path   the segment file path
     * @param syncer the fsync implementation (real by default)
     * @return an open segment
     * @throws IOException if the channel cannot be opened
     */
    static JournalSegment open(Path path, Syncer syncer) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(syncer, "syncer must not be null");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        long initialSize = ch.size();
        return new JournalSegment(path, ch, syncer, initialSize);
    }

    /**
     * Appends every record in {@code group} as a frame, then fsyncs the
     * channel exactly once for the whole group (write-ahead durability).
     *
     * @param group the records to append (in order)
     * @return the new total size of the segment in bytes
     * @throws IOException if a write or fsync fails
     */
    long appendGroup(List<JournalRecord> group) throws IOException {
        Objects.requireNonNull(group, "group must not be null");
        if (closed) {
            throw new IOException("segment already closed: " + path);
        }
        if (group.isEmpty()) {
            return sizeBytes;
        }
        // sizeBytes is the durable (fsynced) file length on entry. If any write
        // or the fsync fails part-way through the group, roll the file back to
        // that length so the writer's whole-batch retry re-appends cleanly
        // instead of leaving a partial frame that the retry would then turn into
        // a duplicated record on disk.
        long appended = 0L;
        try {
            for (JournalRecord record : group) {
                ByteBuffer buf = record.toBuffer();
                int frameSize = buf.remaining();
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                appended += frameSize;
            }
            // Single fsync per group — the durability point.
            syncer.force(channel);
        } catch (IOException e) {
            rollBackTo(sizeBytes);
            throw e;
        }
        sizeBytes += appended;
        return sizeBytes;
    }

    /**
     * Truncates the channel back to {@code length} after a failed append so the
     * writer's batch retry does not double-write the records that were already
     * appended before the failure. Best-effort: if the truncate itself fails the
     * channel is almost certainly dead and the retry will fail too.
     */
    private void rollBackTo(long length) {
        try {
            if (channel.isOpen() && channel.size() > length) {
                channel.truncate(length);
            }
        } catch (IOException ignored) {
            // best-effort rollback
        }
    }

    /** {@return the current size of the segment in bytes} */
    long sizeBytes() {
        return sizeBytes;
    }

    /** {@return the segment's plain file name, e.g. {@code segment-000.bin}} */
    String fileName() {
        return path.getFileName().toString();
    }

    /** {@return the segment's path} */
    Path path() {
        return path;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException ignored) {
            // best-effort close; data was already fsynced per group
        }
    }

    // ---- whole-file reads (recovery) --------------------------------------

    /**
     * Reads every well-formed record from {@code file}, stopping cleanly at
     * the first torn / short / CRC-failed frame (the normal result of a
     * crash mid-append). A non-existent file yields an empty list.
     *
     * @param file the segment file to read
     * @return the records that were durably persisted, in file order
     * @throws IOException if the file exists but cannot be read
     */
    static List<JournalRecord> readAll(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<JournalRecord> records = new ArrayList<>();
        while (buf.remaining() >= 4) {
            Optional<JournalRecord> rec = JournalRecord.readFrom(buf);
            if (rec.isEmpty()) {
                break;          // torn tail — stop cleanly
            }
            records.add(rec.get());
        }
        return records;
    }

    /**
     * Like {@link #readAll(Path)} but distinguishes a benign torn tail (a crash
     * mid-append left an incomplete final frame) from genuine mid-file
     * corruption (a <em>complete</em> frame whose CRC or fields fail to decode
     * with more bytes following). The former stops cleanly; the latter throws so
     * the structured recovery rolls back rather than silently dropping every
     * record after the corrupt frame.
     *
     * @param file the segment file to read
     * @return the durably-persisted records up to a clean torn tail
     * @throws IOException if the file cannot be read, or if a complete frame
     *                     fails to decode (mid-file corruption)
     */
    static List<JournalRecord> readAllStrict(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.exists(file)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<JournalRecord> records = new ArrayList<>();
        while (buf.remaining() >= 4) {
            int offset = buf.position();
            int frameLen = buf.getInt(offset);          // peek without consuming
            Optional<JournalRecord> rec = JournalRecord.readFrom(buf);
            if (rec.isPresent()) {
                records.add(rec.get());
                continue;
            }
            // readFrom rejected the frame without advancing the buffer. If the
            // declared frame fits entirely within the bytes that remain, it is a
            // complete-but-corrupt frame (mid-file corruption); if it runs past
            // the end it is the normal torn tail of a crash mid-append.
            boolean completeFramePresent =
                    frameLen > 4 && (long) buf.remaining() >= 4L + (long) frameLen;
            if (completeFramePresent) {
                throw new IOException("corrupt journal frame at offset " + offset + ": " + file);
            }
            break;          // clean torn tail — stop
        }
        return records;
    }
}
