package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.BLOCK_FRAMES;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.MONO_16;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.feedRamp;
import static com.benesquivelmusic.daw.core.recording.Story323TestSupport.hasProvisionalSizes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 proof (4) — seal atomicity (book §2.5, §5.3): while the pipeline
 * seals well over a hundred tiny segments, a concurrent scanner that reads
 * the size fields of every {@code .wav} it can find never observes one with
 * the provisional streaming sentinel — a segment is either a {@code .part}
 * or fully sealed.
 */
class Story323AtomicSealContractTest {

    private static final long BLOCK_BYTES = (long) BLOCK_FRAMES * Story323TestSupport.BYTES_PER_FRAME_MONO_16;
    private static final int BLOCKS = 240; // 2 blocks per segment → 120 sealed segments
    private static final Duration GUARD = Duration.ofSeconds(20);

    @TempDir
    Path takeDir;

    @TempDir
    Path fixtureDir;

    @Test
    void aScannerNeverObservesAWavWithProvisionalSizes() throws Exception {
        AudioEngine engine = new AudioEngine(MONO_16);
        Transport transport = new Transport();
        Track track = Story323TestSupport.armedMonoTrack("Drums");
        RecordingPipeline pipeline = new RecordingPipeline(engine, transport, MONO_16, takeDir, List.of(track));
        pipeline.setSegmentLimits(Duration.ofHours(1), 2 * BLOCK_BYTES);
        pipeline.start();
        Path trackDir = takeDir.resolve(track.getId());

        AtomicBoolean scanning = new AtomicBoolean(true);
        AtomicLong wavReads = new AtomicLong();
        AtomicLong provisionalWavs = new AtomicLong();
        AtomicLong partsSeen = new AtomicLong();
        AtomicLong passes = new AtomicLong();
        List<Throwable> scannerFailures = new ArrayList<>();
        Thread scanner = Thread.ofPlatform().name("story323-scanner").daemon(true).start(() -> {
            while (scanning.get()) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(trackDir)) {
                    for (Path entry : entries) {
                        String name = entry.getFileName().toString();
                        if (name.endsWith(".wav.part")) {
                            partsSeen.incrementAndGet();
                        } else if (name.endsWith(".wav")) {
                            if (hasProvisionalSizes(entry)) {
                                provisionalWavs.incrementAndGet();
                            }
                            wavReads.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    synchronized (scannerFailures) {
                        scannerFailures.add(e);
                    }
                }
                passes.incrementAndGet();
            }
        });

        try {
            feedRamp(engine, transport, pipeline, 0, BLOCKS, 1);
            // Feeding is paused and every block is applied: one streaming
            // .part is on disk and cannot rotate. Wait for a full scanner pass
            // that starts after this point, so seeing it is deterministic.
            long partsBefore = partsSeen.get();
            long passesBefore = passes.get();
            long deadline = System.nanoTime() + GUARD.toNanos();
            while (passes.get() < passesBefore + 2) {
                assertThat(System.nanoTime() - deadline < 0).as("scanner keeps passing").isTrue();
                LockSupport.parkNanos(1_000_000L);
            }
            assertThat(partsSeen.get()).as("the streaming tail was observed").isGreaterThan(partsBefore);
        } finally {
            scanning.set(false);
            scanner.join(GUARD.toMillis());
            pipeline.stop();
        }

        assertThat(scanner.isAlive()).isFalse();
        assertThat(scannerFailures).isEmpty();
        assertThat(pipeline.getSession(track).getSegments()).hasSize(BLOCKS / 2);
        assertThat(wavReads.get()).as("the scanner read sealed segments").isGreaterThanOrEqualTo(100);
        assertThat(provisionalWavs.get()).as("no .wav ever carried the streaming sentinel").isZero();
    }

    @Test
    void theScannersPredicateWouldFireOnAProvisionalWav() throws IOException {
        Path fake = fixtureDir.resolve("segment-000.wav");
        ByteBuffer header = ByteBuffer.allocate(SegmentWriter.DATA_OFFSET).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(SegmentWriter.PROVISIONAL_SIZE);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(48_000);
        header.putInt(96_000);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(SegmentWriter.PROVISIONAL_SIZE);
        Files.write(fake, header.array());

        assertThat(hasProvisionalSizes(fake)).isTrue();

        // And a genuinely sealed segment does not trip it.
        SegmentWriter writer = SegmentWriter.open(fixtureDir.resolve("segment-001.wav.part"), 48_000.0, 1, 16,
                Duration.ofSeconds(5), System::nanoTime);
        writer.append(new float[1][10], 1, 10);
        Path sealed = writer.seal();
        assertThat(hasProvisionalSizes(sealed)).isFalse();
    }
}
