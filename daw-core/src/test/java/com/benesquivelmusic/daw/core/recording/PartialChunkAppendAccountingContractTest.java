package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Probe (story 323 review, verification round 2): what a block written in
 * more than one chunk counts when a later chunk's write fails.
 *
 * <p>{@link SegmentWriter#append(float[][], int, int, int)} writes a block in
 * chunks of at most 8192 frames and documents that {@code frameCount()} and
 * {@code dataBytes()} advance chunk by chunk, "each chunk once its bytes are
 * written, whether or not the append then throws: ... a write that fails
 * leaves counted only the chunks written in full before it".
 * {@link RecordingSession#recordAudioData} (the fix round's "Disk first")
 * advances its counters "by exactly the frames it added to the writer's
 * count". Two failures are pinned already: a refusal before any write
 * ({@code RecordingSessionTest.aFailedAppendLeavesMirrorAndCountsMatchingTheDisk},
 * nothing counted) and a failed cadence force after a one-chunk block
 * ({@code SegmentForceCadenceContractTest.anAppendWhoseCadenceForceFailsEndsTheTakeWithSessionClipSegmentAndManifestAgreeing},
 * the whole block counted). In both, what the writer counted is nothing or
 * the whole block, so a writer that counts only once the whole block is
 * written, or a session that counts the whole block once anything was
 * written, passes both. This probe fails the second chunk's write of a
 * two-chunk block, where the two differ: the first chunk (8192 frames) is on
 * disk and counted, the rest is not.</p>
 *
 * <p>The fault is a delegating channel that refuses the one positional write
 * at the second chunk's file offset, before it reaches the file. No thread,
 * no wait: both tests are synchronous.</p>
 */
class PartialChunkAppendAccountingContractTest {

    /** The writer's chunk size, as its Javadoc states it ("chunks of at most 8192 frames"). */
    private static final int CHUNK_FRAMES = 8192;
    /** Frames of the block beyond its first chunk: the part whose write fails. */
    private static final int TAIL_FRAMES = 100;
    private static final int CHANNELS = 2;
    private static final int BIT_DEPTH = 16;
    private static final int BYTES_PER_FRAME = CHANNELS * BIT_DEPTH / 8;
    /** Where the second chunk's bytes start in the file. */
    private static final long SECOND_CHUNK_OFFSET = SegmentWriter.DATA_OFFSET + (long) CHUNK_FRAMES * BYTES_PER_FRAME;
    /** Exact in 16-bit PCM: round(0.25 x 32767) / 32768 == 0.25. */
    private static final float FIRST_CHUNK_SAMPLE = 0.25f;
    private static final float TAIL_SAMPLE = -0.5f;

    @TempDir
    Path tempDir;

    @Test
    void aWriteThatFailsOnTheSecondChunkLeavesTheFirstChunkWrittenCountedAndSealed() throws IOException {
        FailOneWriteAt fault = new FailOneWriteAt(SECOND_CHUNK_OFFSET);
        SegmentWriter writer = SegmentWriter.open(tempDir.resolve("segment-000.wav.part"), 44_100.0, CHANNELS,
                BIT_DEPTH, Duration.ofSeconds(1), () -> 0L, fault::wrap);

        assertThatThrownBy(() -> writer.append(twoChunkBlock(), CHANNELS, CHUNK_FRAMES + TAIL_FRAMES))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(FailOneWriteAt.MESSAGE);
        assertThat(fault.fired()).as("non-vacuity: the second chunk's write was the one refused").isTrue();

        assertThat(writer.frameCount()).as("the chunk written in full before the failure is counted")
                .isEqualTo(CHUNK_FRAMES);
        assertThat(writer.dataBytes()).isEqualTo((long) CHUNK_FRAMES * BYTES_PER_FRAME);

        Path sealed = writer.seal();
        float[][] disk = SegmentFile.readFrames(sealed);
        assertThat(disk[0]).as("the sealed segment holds that chunk and nothing of the failed one")
                .hasSize(CHUNK_FRAMES)
                .containsOnly(FIRST_CHUNK_SAMPLE);
    }

    @Test
    void aSessionWhoseBlockFailsOnItsSecondChunkCountsExactlyTheChunkTheSegmentHolds() throws IOException {
        Path trackDir = tempDir.resolve("track");
        FailOneWriteAt fault = new FailOneWriteAt(SECOND_CHUNK_OFFSET);
        RecordingSession session = new RecordingSession(AudioFormat.CD_QUALITY, trackDir);
        session.setChannelOpener(fault::wrap);
        session.start();

        assertThatThrownBy(() -> session.recordAudioData(twoChunkBlock(), CHUNK_FRAMES + TAIL_FRAMES))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage(FailOneWriteAt.MESSAGE);
        assertThat(fault.fired()).as("non-vacuity: the second chunk's write was the one refused").isTrue();

        assertThat(session.getCurrentWriter().frameCount()).as("the writer counts the first chunk")
                .isEqualTo(CHUNK_FRAMES);
        assertThat(session.getCapturedSampleCount())
                .as("the session counts what the writer counted, not the whole block").isEqualTo(CHUNK_FRAMES);
        assertThat(session.getTotalSamplesRecorded()).isEqualTo(CHUNK_FRAMES);
        assertThat(session.getCapturedAudio()[0]).as("the RAM mirror holds the same frames")
                .hasSize(CHUNK_FRAMES)
                .containsOnly(FIRST_CHUNK_SAMPLE);

        session.stop();

        assertThat(session.getSegments()).singleElement()
                .satisfies(segment -> assertThat(segment.sampleCount()).isEqualTo(CHUNK_FRAMES));
        float[][] disk = SegmentFile.readFrames(trackDir.resolve("segment-000.wav"));
        assertThat(disk[0]).as("the sealed segment holds exactly what the session reports")
                .hasSize(CHUNK_FRAMES)
                .containsOnly(FIRST_CHUNK_SAMPLE);
    }

    /** One block of two chunks: the first all {@link #FIRST_CHUNK_SAMPLE}, the tail all {@link #TAIL_SAMPLE}. */
    private static float[][] twoChunkBlock() {
        float[][] block = new float[CHANNELS][CHUNK_FRAMES + TAIL_FRAMES];
        for (int ch = 0; ch < CHANNELS; ch++) {
            for (int i = 0; i < block[ch].length; i++) {
                block[ch][i] = i < CHUNK_FRAMES ? FIRST_CHUNK_SAMPLE : TAIL_SAMPLE;
            }
        }
        return block;
    }

    /**
     * A {@link SegmentWriter.ChannelOpener} whose channels delegate to the
     * production channel and refuse, once, the positional write at
     * {@code failAt}, before it reaches the file.
     */
    private static final class FailOneWriteAt {

        static final String MESSAGE = "injected write failure at the second chunk (probe)";

        private final long failAt;
        private final AtomicBoolean fired = new AtomicBoolean();

        FailOneWriteAt(long failAt) {
            this.failAt = failAt;
        }

        FileChannel wrap(Path path) throws IOException {
            return new Delegating(SegmentWriter.CREATE_NEW_CHANNEL.open(path));
        }

        boolean fired() {
            return fired.get();
        }

        private final class Delegating extends FileChannel {

            private final FileChannel delegate;

            Delegating(FileChannel delegate) {
                this.delegate = delegate;
            }

            @Override
            public int write(ByteBuffer src, long position) throws IOException {
                if (position == failAt && fired.compareAndSet(false, true)) {
                    throw new IOException(MESSAGE);
                }
                return delegate.write(src, position);
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
            public int write(ByteBuffer src) throws IOException {
                return delegate.write(src);
            }

            @Override
            public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
                return delegate.write(srcs, offset, length);
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
            public void force(boolean metaData) throws IOException {
                delegate.force(metaData);
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
            public int read(ByteBuffer dst, long position) throws IOException {
                return delegate.read(dst, position);
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

            @Override
            protected void implCloseChannel() throws IOException {
                delegate.close();
            }
        }
    }
}
