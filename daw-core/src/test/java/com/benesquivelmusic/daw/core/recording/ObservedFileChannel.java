package com.benesquivelmusic.daw.core.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for story 323: a {@link FileChannel} that delegates every call
 * to a real channel and journals the ones the durability contracts are
 * about — {@code force(false)}, {@code force(true)}, positional writes and
 * {@code close} — so a test observes the syscalls themselves rather than the
 * writer's bookkeeping. It can also fail the next writes outright, the shape
 * of a disk that refuses the bytes, or the next forces, the shape of a flush
 * to storage that fails.
 *
 * <p>Thread-safe enough for the tests: the journal is copy-on-write and the
 * fault counters atomic, so a test thread can read while the
 * {@code capture-flush} thread writes.</p>
 */
final class ObservedFileChannel extends FileChannel {

    /** What every channel opened through {@link #opener} did, in call order. */
    static final class Journal {

        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger writesToFail = new AtomicInteger();
        private final AtomicInteger forcesToFail = new AtomicInteger();
        private volatile Runnable beforeForce;

        /** Returns an opener that wraps whatever {@code real} opens. */
        SegmentWriter.ChannelOpener opener(SegmentWriter.ChannelOpener real) {
            Objects.requireNonNull(real, "real must not be null");
            return path -> new ObservedFileChannel(real.open(path), this);
        }

        /** The next {@code count} writes throw an {@link IOException} before reaching the file. */
        void failNextWrites(int count) {
            writesToFail.set(count);
        }

        /**
         * The next {@code count} {@code force} calls throw an {@link IOException}
         * before reaching the file; a refused force is not journalled and does
         * not run the {@link #beforeForce} hook.
         */
        void failNextForces(int count) {
            forcesToFail.set(count);
        }

        /** Runs {@code hook} on the forcing thread immediately before every delegated {@code force}. */
        void beforeForce(Runnable hook) {
            beforeForce = hook;
        }

        /** Every journalled call: {@code force(false)}, {@code force(true)}, {@code write@<position>}, {@code write}, {@code close}. */
        List<String> events() {
            return List.copyOf(events);
        }

        /** The journal without the data appends — forces, header patches (positions below 44) and closes. */
        List<String> eventsWithoutDataWrites() {
            return events.stream()
                    .filter(e -> !e.startsWith("write") || isHeaderWrite(e))
                    .toList();
        }

        /** How many {@code force(metaData)} calls reached a channel. */
        long forces(boolean metaData) {
            String wanted = "force(" + metaData + ")";
            return events.stream().filter(wanted::equals).count();
        }

        private static boolean isHeaderWrite(String event) {
            int at = event.indexOf('@');
            return at >= 0 && Long.parseLong(event.substring(at + 1)) < SegmentWriter.DATA_OFFSET;
        }
    }

    private final FileChannel delegate;
    private final Journal journal;

    private ObservedFileChannel(FileChannel delegate, Journal journal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.journal = journal;
    }

    private void failIfAsked() throws IOException {
        if (claimFault(journal.writesToFail)) {
            throw new IOException("injected write failure (test double)");
        }
    }

    private static boolean claimFault(AtomicInteger faults) {
        while (true) {
            int remaining = faults.get();
            if (remaining <= 0) {
                return false;
            }
            if (faults.compareAndSet(remaining, remaining - 1)) {
                return true;
            }
        }
    }

    @Override
    public void force(boolean metaData) throws IOException {
        if (claimFault(journal.forcesToFail)) {
            throw new IOException("injected force(" + metaData + ") failure (test double)");
        }
        Runnable hook = journal.beforeForce;
        if (hook != null) {
            hook.run();
        }
        delegate.force(metaData);
        journal.events.add("force(" + metaData + ")");
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        failIfAsked();
        int written = delegate.write(src, position);
        journal.events.add("write@" + position);
        return written;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        failIfAsked();
        int written = delegate.write(src);
        journal.events.add("write");
        return written;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        failIfAsked();
        long written = delegate.write(srcs, offset, length);
        journal.events.add("write");
        return written;
    }

    @Override
    protected void implCloseChannel() throws IOException {
        delegate.close();
        journal.events.add("close");
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return delegate.read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return delegate.read(dsts, offset, length);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return delegate.read(dst, position);
    }

    @Override
    public long position() throws IOException {
        return delegate.position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        delegate.position(newPosition);
        return this;
    }

    @Override
    public long size() throws IOException {
        return delegate.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        delegate.truncate(size);
        return this;
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return delegate.transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        return delegate.transferFrom(src, position, count);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return delegate.map(mode, position, size);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return delegate.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return delegate.tryLock(position, size, shared);
    }
}
