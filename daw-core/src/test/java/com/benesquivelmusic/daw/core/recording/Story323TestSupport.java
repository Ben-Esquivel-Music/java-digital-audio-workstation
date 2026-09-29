package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared harness for the story 323 contract tests: a mono 48 kHz / 16-bit /
 * 512-frame format driven exactly as {@code RecordingPipelineTest} drives
 * the engine, plus a deterministic ramp whose 16-bit round-trip is exact.
 *
 * <p>Ramp: frame {@code n} carries {@code rampValue(n) = k / 32767f} with
 * {@code k = (n mod 4001) - 2000}. {@code PcmSampleEncoding} quantises with
 * {@code round(v * 32767)}, which recovers {@code k} exactly, and
 * {@code SegmentFile} decodes {@code k / 32768f} — so the expected decoded
 * value of frame {@code n} is {@link #decodedRampValue(long)} to the bit.</p>
 */
final class Story323TestSupport {

    static final double SAMPLE_RATE = 48_000.0;
    static final int BLOCK_FRAMES = 512;
    static final int BYTES_PER_FRAME_MONO_16 = 2;
    static final AudioFormat MONO_16 = new AudioFormat(SAMPLE_RATE, 1, 16, BLOCK_FRAMES);

    /**
     * Bound of {@link #outcomeWithinTheGuard}: far larger than any wait
     * inside the calls it guards (the longest is a 200 ms timeout), so it
     * only runs out when the code under test no longer terminates.
     */
    static final Duration HANG_GUARD = Duration.ofSeconds(10);

    /** A call whose termination is what the test is about; it may throw anything. */
    @FunctionalInterface
    interface GuardedCall {
        void run() throws Exception;
    }

    private Story323TestSupport() {
    }

    /**
     * Runs {@code call} on its own daemon platform thread and waits for it
     * for at most {@link #HANG_GUARD}. A call that is still running then
     * fails the test here instead of hanging the suite: daw-core has no
     * default JUnit timeout, so a loop that stops terminating on the JUnit
     * thread would never go red. The join is the happens-before edge for
     * whatever the caller reads afterwards.
     *
     * @param threadName name of the thread the call runs on (shown in the failure)
     * @param call       the call to guard
     * @return what the call threw, or {@code null} if it returned normally
     */
    static Throwable outcomeWithinTheGuard(String threadName, GuardedCall call) throws InterruptedException {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread running = Thread.ofPlatform().name(threadName).daemon(true).start(() -> {
            try {
                call.run();
            } catch (Throwable thrown) {
                outcome.set(thrown);
            }
        });
        running.join(HANG_GUARD.toMillis());
        assertThat(running.isAlive()).as("%s returned within %s", threadName, HANG_GUARD).isFalse();
        return outcome.get();
    }

    static Track armedMonoTrack(String name) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputRouting(new InputRouting(0, 1));
        return track;
    }

    static float rampValue(long frame) {
        int k = (int) (frame % 4001) - 2000;
        return k / 32767f;
    }

    static float decodedRampValue(long frame) {
        int k = (int) (frame % 4001) - 2000;
        return k / 32768f;
    }

    /** Fills a mono block with the ramp starting at global frame {@code firstFrame}. */
    static float[][] rampBlock(long firstFrame) {
        float[][] block = new float[1][BLOCK_FRAMES];
        for (int i = 0; i < BLOCK_FRAMES; i++) {
            block[0][i] = rampValue(firstFrame + i);
        }
        return block;
    }

    /** Advances the transport by one block, matching what {@code RenderPipeline} does after the callback. */
    static void advanceOneBlock(Transport transport) {
        double samplesPerBeat = SAMPLE_RATE * 60.0 / transport.getTempo();
        transport.advancePosition(BLOCK_FRAMES / samplesPerBeat);
    }

    /**
     * Feeds {@code blocks} ramp blocks through the engine's callback,
     * fencing on the flush thread every {@code fenceEvery} blocks (and at
     * the end) so the ring never overflows.
     *
     * @return the global frame index after the last block
     */
    static long feedRamp(AudioEngine engine, Transport transport, RecordingPipeline pipeline,
                         long firstFrame, int blocks, int fenceEvery) {
        float[][] output = new float[1][BLOCK_FRAMES];
        long frame = firstFrame;
        for (int b = 0; b < blocks; b++) {
            engine.processBlock(rampBlock(frame), output, BLOCK_FRAMES);
            advanceOneBlock(transport);
            frame += BLOCK_FRAMES;
            if ((b + 1) % fenceEvery == 0) {
                pipeline.awaitFlushed();
            }
        }
        pipeline.awaitFlushed();
        return frame;
    }

    /** Reads the RIFF-size (offset 4) and data-size (offset 40) fields of a WAV header. */
    static int[] sizeFields(Path wav) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(SegmentWriter.DATA_OFFSET).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel fc = FileChannel.open(wav, StandardOpenOption.READ)) {
            long position = 0;
            while (header.hasRemaining()) {
                int n = fc.read(header, position);
                if (n < 0) {
                    throw new IOException(wav + " is shorter than the 44-byte header");
                }
                position += n;
            }
        }
        return new int[] {header.getInt(4), header.getInt(40)};
    }

    /** Whether either size field still holds the streaming sentinel — the predicate a sealed {@code .wav} must never satisfy. */
    static boolean hasProvisionalSizes(Path wav) throws IOException {
        int[] fields = sizeFields(wav);
        return fields[0] == SegmentWriter.PROVISIONAL_SIZE || fields[1] == SegmentWriter.PROVISIONAL_SIZE;
    }
}
