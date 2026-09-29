package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CaptureFlushService} driven directly through its ring (story 323;
 * context D9): the cases the engine cannot produce on demand — a source
 * that delivers fewer channels than the routed width, and a take whose very
 * first manifest write is refused.
 */
class CaptureFlushServiceTest {

    private static final int SLOT_FRAMES = 8;
    private static final AudioFormat STEREO_16 = new AudioFormat(48_000.0, 2, 16, SLOT_FRAMES);
    private static final long GIB = 1L << 30;
    private static final Duration GUARD = Duration.ofSeconds(10);

    @TempDir
    Path takeDir;

    private final AtomicLong clock = new AtomicLong();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private CaptureFlushService service;

    @AfterEach
    void stopTheFlushThread() {
        if (service != null) {
            service.close();
        }
    }

    /** One armed graph-instrument track (routing NONE) recording ring source 1. */
    private TrackCapture instrumentCapture(Track track) {
        return new TrackCapture(track, InputRouting.NONE, 1, STEREO_16.channels(), SLOT_FRAMES, 0L, 0.0,
                STEREO_16.sampleRate(), 120.0, takeDir.resolve(track.getId()),
                (t, dir) -> new RecordingSession(STEREO_16, dir));
    }

    private CaptureFlushService newService(CaptureRing ring, TrackCapture capture) {
        CaptureFlushService.TakeConfig config = new CaptureFlushService.TakeConfig(takeDir, STEREO_16, 120.0,
                0.0, 0L, null, false, Duration.ofSeconds(5), Instant.parse("2026-09-29T10:00:00Z"));
        DiskHeadroomWatch watch = new DiskHeadroomWatch(takeDir, () -> 10 * GIB, GIB, 64L << 20,
                Duration.ZERO, clock::get, warnings::add);
        return new CaptureFlushService(ring, config, List.of(capture), watch, warnings::add, clock::get);
    }

    private static float[][] block(int channels, float value) {
        float[][] block = new float[channels][SLOT_FRAMES];
        for (float[] row : block) {
            Arrays.fill(row, value);
        }
        return block;
    }

    private void publish(CaptureRing ring, int blockIndex, float[][] instrument, int instrumentChannels) {
        CaptureRing.Slot slot = ring.claim();
        assertThat(slot).as("fixture: the ring has room").isNotNull();
        slot.setNumFrames(SLOT_FRAMES);
        slot.setStartFrame((long) blockIndex * SLOT_FRAMES);
        slot.setBeatPosition(blockIndex * 0.001);
        slot.copySource(0, block(2, 0f), 2, SLOT_FRAMES);
        slot.copySource(1, instrument, instrumentChannels, SLOT_FRAMES);
        ring.publish();
        service.signal();
    }

    @Test
    void anInstrumentSourceNarrowerThanTheRoutedWidthLeavesNoStaleSamples() {
        Track synth = new Track("Synth", TrackType.AUDIO);
        TrackCapture capture = instrumentCapture(synth);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, capture);
        service.start();

        publish(ring, 0, block(2, 0.5f), 2);  // both instrument channels present
        publish(ring, 1, block(2, 0.25f), 1); // this block delivers ONE channel only
        service.awaitFlushed(GUARD);

        assertThat(ring.peek()).isNull();
        float[][] captured = capture.session().getCapturedAudio();
        assertThat(captured).hasNumberOfRows(2);
        assertThat(captured[0]).hasSize(2 * SLOT_FRAMES);
        assertThat(Arrays.copyOfRange(captured[0], 0, SLOT_FRAMES)).containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(captured[0], SLOT_FRAMES, 2 * SLOT_FRAMES)).containsOnly(0.25f);
        assertThat(Arrays.copyOfRange(captured[1], 0, SLOT_FRAMES))
                .as("fixture: the first block really wrote the second row").containsOnly(0.5f);
        assertThat(Arrays.copyOfRange(captured[1], SLOT_FRAMES, 2 * SLOT_FRAMES))
                .as("the row the second block did not deliver is silent, not the first block's samples")
                .containsOnly(0f);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aRefusedInitialManifestWriteFailsTheStartAtOnceAndLeavesNothing() throws IOException {
        Track synth = new Track("Synth", TrackType.AUDIO);
        TrackCapture capture = instrumentCapture(synth);
        CaptureRing ring = new CaptureRing(SLOT_FRAMES, 2, 2, 8);
        service = newService(ring, capture);

        // One refused attempt: a retry would get through, so a start that
        // fails proves the caller thread does not sit in a retry loop.
        service.failNextManifestWrites(1);

        assertThatThrownBy(service::start).isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("injected manifest write failure (test seam) under " + takeDir);
        assertThat(service.isRunning()).isFalse();
        assertThat(service.thread().isAlive()).as("the flush thread was never started").isFalse();
        assertThat(service.manifestWrites()).isZero();
        try (Stream<Path> entries = Files.list(takeDir)) {
            assertThat(entries).as("the failed start left the take directory empty").isEmpty();
        }
    }
}
