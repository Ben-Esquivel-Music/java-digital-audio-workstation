package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Deterministic spy implementing the (unsealed, story 316) SDK
 * {@link AudioBackend} directly: records every {@code open} with its device
 * / format / buffer size, records the open/close order in an optional
 * shared lifecycle log, counts sunk blocks, publishes test-driven capture
 * blocks, and paces the render pump with a short bounded park instead of
 * the default full-timeout wall-clock park — no real hardware is ever
 * touched.
 *
 * <p>Shared story-316 fixture: {@link Story316StreamingSeamTest} (the
 * story's binding acceptance tests 1–4) and
 * {@link DefaultAudioEngineControllerTest}'s driver-initiated
 * format-change reopen test both stream through it.</p>
 */
final class SpyStreamingBackend implements AudioBackend {

    record OpenRecord(
            DeviceId device,
            com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
            int bufferFrames) {
    }

    private final String name;
    private final List<String> lifecycleLog;
    private final List<OpenRecord> opens =
            Collections.synchronizedList(new ArrayList<>());
    private final Semaphore sunkBlocks = new Semaphore(0);
    private final SubmissionPublisher<AudioBlock> inputPublisher =
            new SubmissionPublisher<>();
    private volatile boolean open;
    private volatile RuntimeException openFailure;

    SpyStreamingBackend(String name) {
        this(name, Collections.synchronizedList(new ArrayList<>()));
    }

    SpyStreamingBackend(String name, List<String> lifecycleLog) {
        this.name = name;
        this.lifecycleLog = lifecycleLog;
    }

    void failOpensWith(RuntimeException failure) {
        this.openFailure = failure;
    }

    List<OpenRecord> opens() {
        synchronized (opens) {
            return List.copyOf(opens);
        }
    }

    boolean awaitSunkBlocks(int count, long timeout, TimeUnit unit)
            throws InterruptedException {
        return sunkBlocks.tryAcquire(count, timeout, unit);
    }

    void publishInput(AudioBlock block) {
        inputPublisher.submit(block);
    }

    @Override
    public String name() {
        return name;
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
        RuntimeException failure = this.openFailure;
        if (failure != null) {
            throw failure;
        }
        if (open) {
            throw new IllegalStateException(
                    "A stream is already open on " + name);
        }
        opens.add(new OpenRecord(device, format, bufferFrames));
        open = true;
        lifecycleLog.add("open:" + name);
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return inputPublisher;
    }

    @Override
    public void sink(AudioBlock block) {
        if (open) {
            sunkBlocks.release();
        }
    }

    @Override
    public void awaitSinkCapacity(long timeoutNanos) {
        // Short bounded park: keeps the pump from hot-spinning while
        // letting tests observe several blocks well inside their
        // await budgets (never a full wall-clock block period).
        LockSupport.parkNanos(Math.min(timeoutNanos, 1_000_000L));
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        lifecycleLog.add("close:" + name);
        open = false;
    }
}
