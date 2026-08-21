package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Headless {@link AudioBackend} for deterministic, offline tests (story 316:
 * the harness backend now implements the SDK interface the engine actually
 * streams through — the interface was unsealed for exactly this kind of
 * out-of-module implementor).
 *
 * <p>This backend touches no device. {@link #sink(AudioBlock)} captures every
 * interleaved block into an in-memory buffer tests can assert on, and
 * {@link #awaitSinkCapacity(long)} is a no-op so a pump-driven test never
 * waits on a wall clock. Captured "input" is produced by the test itself via
 * {@link #publishInput(AudioBlock)}.</p>
 *
 * <p>The block-by-block render <em>drive</em> that used to live here
 * ({@code drive(int)}) moved to {@link HeadlessAudioHarness}, which calls
 * {@code engine.processBlock} directly — that is what keeps golden-file
 * renders byte-reproducible (no pump thread, no scheduling jitter).</p>
 *
 * <p>Thread-safe only to the extent the engine's pump needs: {@code sink}
 * may run on the pump thread while a test thread reads the captured
 * output.</p>
 */
public final class HeadlessAudioBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "Headless";

    /** Default headless device — no physical device is required. */
    public static final AudioDeviceInfo HEADLESS_DEVICE = new AudioDeviceInfo(
            0,
            "Headless",
            "Headless",
            2,
            2,
            44_100.0,
            List.of(SampleRate.HZ_44100, SampleRate.HZ_48000, SampleRate.HZ_96000),
            0.0,
            0.0
    );

    private volatile boolean open;
    private DeviceId openedDevice;
    private AudioFormat openedFormat;
    private int openedBufferFrames;

    // Captured output: one interleaved snapshot per sink() call.
    private final List<float[]> sunkBlocks = new ArrayList<>();

    private volatile SubmissionPublisher<AudioBlock> inputPublisher =
            new SubmissionPublisher<>();

    @Override
    public String name() {
        return NAME;
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
        return List.of(HEADLESS_DEVICE);
    }

    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(format, "format");
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
        }
        if (open) {
            throw new IllegalStateException("A stream is already open; close it first");
        }
        this.openedDevice = device;
        this.openedFormat = format;
        this.openedBufferFrames = bufferFrames;
        this.open = true;
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return inputPublisher;
    }

    @Override
    public void sink(AudioBlock block) {
        Objects.requireNonNull(block, "block");
        if (!open) {
            return; // silently dropped per the interface contract
        }
        synchronized (sunkBlocks) {
            sunkBlocks.add(block.samples().clone());
        }
    }

    /** No-op: a headless "device" always has capacity — never wall-clock pace. */
    @Override
    public void awaitSinkCapacity(long timeoutNanos) {
        // no-op
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        SubmissionPublisher<AudioBlock> outgoing = this.inputPublisher;
        this.inputPublisher = new SubmissionPublisher<>();
        outgoing.close();
    }

    // ── Test-driven interaction ──────────────────────────────────────────────

    /**
     * Publishes one captured-input block to {@link #inputBlocks()}
     * subscribers, standing in for the hardware capture side.
     *
     * @param block the block to publish (must not be null)
     */
    public void publishInput(AudioBlock block) {
        Objects.requireNonNull(block, "block");
        inputPublisher.submit(block);
    }

    /** Returns copies of every interleaved block passed to {@link #sink}. */
    public List<float[]> sunkBlocks() {
        synchronized (sunkBlocks) {
            List<float[]> copy = new ArrayList<>(sunkBlocks.size());
            for (float[] block : sunkBlocks) {
                copy.add(block.clone());
            }
            return copy;
        }
    }

    /** Returns the number of blocks passed to {@link #sink} while open. */
    public int sunkBlockCount() {
        synchronized (sunkBlocks) {
            return sunkBlocks.size();
        }
    }

    /** Clears the captured output without touching stream state. */
    public void clearSunkBlocks() {
        synchronized (sunkBlocks) {
            sunkBlocks.clear();
        }
    }

    /** Returns the device the current/most recent open resolved. */
    public DeviceId openedDevice() {
        return openedDevice;
    }

    /** Returns the format the current/most recent open was given. */
    public AudioFormat openedFormat() {
        return openedFormat;
    }

    /** Returns the buffer size the current/most recent open was given. */
    public int openedBufferFrames() {
        return openedBufferFrames;
    }
}
