package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Unit contract of the engine-owned render pump (story 316): device-clock
 * pacing via {@code awaitSinkCapacity}, capture flow from
 * {@code inputBlocks()} into {@code processBlock}'s input planes, sink-fault
 * resilience, a bounded stop-join, and a quiet exit when the engine stops
 * racing the pump.
 *
 * <p>Async assertions use one generous guard budget
 * ({@value #GUARD_BUDGET_MILLIS}&nbsp;ms) and await conditions, never
 * sleeps.</p>
 */
class EngineStreamPumpTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 128);
    private static final com.benesquivelmusic.daw.sdk.audio.AudioFormat SDK_FORMAT =
            new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 2, 24);

    /** Guard budget — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private AudioEngine engine;
    private EngineStreamPump pump;

    @AfterEach
    void tearDown() {
        if (pump != null) {
            pump.stop();
        }
        if (engine != null) {
            engine.stop();
        }
    }

    private EngineStreamPump startPump(RecordingBackend backend) {
        engine = new AudioEngine(FORMAT);
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();
        return pump;
    }

    @Test
    void pumpPacesProductionThroughAwaitSinkCapacity() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);

        awaitCondition(() -> backend.sinkCalls.get() >= 3, "the pump sinks blocks");
        awaitCondition(() -> backend.awaitCalls.get() >= 2,
                "awaitSinkCapacity is called between blocks");
        assertThat(backend.lastAwaitTimeoutNanos.get())
                .as("the pacing timeout is one block period: 128 frames at 48 kHz")
                .isEqualTo(128L * 1_000_000_000L / 48_000L);
    }

    @Test
    void pumpReusesOneBlockInstanceAcrossSinkCalls() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);

        awaitCondition(() -> backend.sunkInstances.size() >= 3, "several blocks sunk");
        assertThat(backend.sunkInstances.stream().distinct().count())
                .as("the sink contract permits one reusable block — the pump is allocation-free")
                .isEqualTo(1);
        AudioBlock block = backend.sunkInstances.get(0);
        assertThat(block.channels()).isEqualTo(2);
        assertThat(block.frames()).isEqualTo(128);
    }

    @Test
    void publishedInputBlocksReachProcessBlockInputPlanes() {
        RecordingBackend backend = new RecordingBackend();
        AtomicBoolean sawInput = new AtomicBoolean();
        engine = new AudioEngine(FORMAT);
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            for (float[] channel : inputBuffer) {
                for (int i = 0; i < numFrames; i++) {
                    if (channel[i] == 0.5f) {
                        sawInput.set(true);
                        return;
                    }
                }
            }
        });
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        float[] samples = new float[2 * 128];
        java.util.Arrays.fill(samples, 0.5f);
        backend.inputPublisher.submit(new AudioBlock(48_000.0, 2, 128, samples));

        awaitCondition(sawInput::get,
                "a published capture block is de-interleaved into the input planes");
    }

    @Test
    void anOpenedFormatDisagreeingOnShapeIsRejectedAtConstruction() {
        // Story 316 review (F6), defence in depth: the planes, the interleave
        // buffer and the reusable block are built from the ENGINE format while
        // the block is stamped with the OPENED sample rate. A wider channel
        // count would make every sink reject the block; a different rate would
        // merely relabel un-resampled audio. The ladder already enforces this
        // when it picks a rung — asserting it here means no caller can build a
        // mislabelling pump silently.
        RecordingBackend backend = new RecordingBackend();
        engine = new AudioEngine(FORMAT);
        engine.start();

        assertThatThrownBy(() -> new EngineStreamPump(backend, engine, FORMAT,
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 4, 24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels and sample rate");
        assertThatThrownBy(() -> new EngineStreamPump(backend, engine, FORMAT,
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(44_100.0, 2, 24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels and sample rate");
        assertThat(new EngineStreamPump(backend, engine, FORMAT,
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 2, 16)))
                .as("a bit-depth-only renegotiation is still accepted")
                .isNotNull();
    }

    @Test
    void sinkFailuresAreSurvivedAndCounted() {
        RecordingBackend backend = new RecordingBackend();
        backend.failSink = true;
        startPump(backend);

        awaitCondition(() -> pump.sinkFailures() >= 3,
                "a throwing sink does not kill the pump — it keeps looping");
        assertThat(pump.isRunning()).isTrue();
    }

    @Test
    void processBlockFaultRendersSilenceAndLaterBlocksStillReachSink() {
        // Story 316 review (F2): a plugin NPE inside processBlock must not
        // kill the render drive — the faulted block becomes silence, the
        // fault is counted, and later blocks keep reaching the sink.
        RecordingBackend backend = new RecordingBackend();
        AtomicInteger processBlockCalls = new AtomicInteger();
        engine = new AudioEngine(FORMAT);
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (processBlockCalls.incrementAndGet() == 1) {
                throw new NullPointerException("plugin NPE on the first block");
            }
        });
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        awaitCondition(() -> pump.renderFaults() >= 1,
                "the processBlock fault is counted, not fatal");
        int sunkAtFault = backend.sinkCalls.get();
        awaitCondition(() -> backend.sinkCalls.get() > sunkAtFault + 2,
                "blocks rendered AFTER the fault still reach the sink");
        assertThat(pump.isRunning()).isTrue();
        assertThat(pump.renderFaults()).isEqualTo(1);
    }

    @Test
    void collaboratorIllegalStateWhileTheEngineRunsIsAnOrdinaryRenderFault() {
        // Story 316 review: the quiet exit is decided by STATE, not by the
        // exception TYPE. An insert plugin asserting "not prepared" after a
        // mid-session swap — or the mixer, the MIDI renderer, the graph
        // scheduler — throws IllegalStateException while the engine is
        // perfectly healthy. Matching on the type alone made every one of
        // them silently end audio for the rest of the session.
        RecordingBackend backend = new RecordingBackend();
        AtomicInteger processBlockCalls = new AtomicInteger();
        engine = new AudioEngine(FORMAT);
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            if (processBlockCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("insert not prepared");
            }
        });
        engine.start();
        pump = new EngineStreamPump(backend, engine, FORMAT, SDK_FORMAT);
        pump.start();

        awaitCondition(() -> pump.renderFaults() >= 1,
                "a collaborator's IllegalStateException is counted, not fatal");
        int sunkAtFault = backend.sinkCalls.get();
        awaitCondition(() -> backend.sinkCalls.get() > sunkAtFault + 2,
                "blocks rendered AFTER the fault still reach the sink");
        assertThat(pump.isRunning())
                .as("only a stopped engine (or a stopped pump) ends the render loop")
                .isTrue();
        assertThat(pump.renderFaults()).isEqualTo(1);
    }

    @Test
    void awaitSinkCapacityFaultIsSurvivedAndThePumpKeepsSinking() {
        // Story 316 review (F2): a throwing pacing seam must not kill the
        // render drive — the loop logs once and continues un-paced.
        RecordingBackend backend = new RecordingBackend();
        backend.failAwait = true;
        startPump(backend);

        awaitCondition(() -> backend.sinkCalls.get() >= 5,
                "the pump keeps sinking blocks despite the pacing fault");
        assertThat(backend.awaitCalls.get())
                .as("the pacing seam really was exercised and threw")
                .isGreaterThanOrEqualTo(1);
        assertThat(pump.isRunning()).isTrue();
    }

    @Test
    void stopJoinsThePumpThread() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);
        awaitCondition(() -> backend.sinkCalls.get() >= 1, "the pump is running");

        pump.stop();

        assertThat(pump.isRunning()).isFalse();
        int sunkAtStop = backend.sinkCalls.get();
        // A stopped pump renders nothing more — poll briefly within the guard
        // budget and require the counter to stay put.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertThat(backend.sinkCalls.get()).isEqualTo(sunkAtStop);
    }

    @Test
    void engineStoppingMidStreamExitsThePumpQuietly() {
        RecordingBackend backend = new RecordingBackend();
        startPump(backend);
        awaitCondition(() -> backend.sinkCalls.get() >= 1, "the pump is running");

        engine.stop(); // processBlock now throws IllegalStateException

        awaitCondition(() -> !pump.isRunning(),
                "the pump exits quietly when the engine stops racing it");
    }

    @Test
    void inputQueueOverflowDropsAndCounts() {
        RecordingBackend backend = new RecordingBackend();
        // Do NOT start the pump thread: subscribe-only via start would race.
        // Instead start it against a stalling engine? Simpler: start the pump
        // and flood far beyond the 32-slot queue faster than the paced loop
        // can drain — drops must be counted, and the pump must survive.
        startPump(backend);
        float[] samples = new float[2 * 128];
        for (int i = 0; i < 500; i++) {
            backend.inputPublisher.submit(new AudioBlock(48_000.0, 2, 128, samples));
        }

        awaitCondition(() -> pump.droppedInputBlocks() > 0,
                "input blocks beyond the bounded queue are dropped and counted");
        assertThat(pump.isRunning()).isTrue();
    }

    // ── Await support ────────────────────────────────────────────────────

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    // ── Scripted backend ─────────────────────────────────────────────────

    /** Fake backend recording sink/pacing traffic; fast 1 ms pacing park. */
    private static final class RecordingBackend implements AudioBackend {

        final AtomicInteger sinkCalls = new AtomicInteger();
        final AtomicInteger awaitCalls = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong lastAwaitTimeoutNanos =
                new java.util.concurrent.atomic.AtomicLong();
        final List<AudioBlock> sunkInstances = new CopyOnWriteArrayList<>();
        final SubmissionPublisher<AudioBlock> inputPublisher = new SubmissionPublisher<>();
        volatile boolean failSink;
        volatile boolean failAwait;

        @Override
        public String name() {
            return "Recording";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return List.of();
        }

        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            // Not exercised: the pump receives an already-open backend.
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return inputPublisher;
        }

        @Override
        public void sink(AudioBlock block) {
            sinkCalls.incrementAndGet();
            if (sunkInstances.size() < 16) {
                sunkInstances.add(block);
            }
            if (failSink) {
                throw new IllegalArgumentException("shape mismatch after driver reset");
            }
        }

        @Override
        public void awaitSinkCapacity(long timeoutNanos) {
            awaitCalls.incrementAndGet();
            lastAwaitTimeoutNanos.set(timeoutNanos);
            if (failAwait) {
                throw new IllegalStateException("pacing seam failure");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            inputPublisher.close();
        }
    }
}
