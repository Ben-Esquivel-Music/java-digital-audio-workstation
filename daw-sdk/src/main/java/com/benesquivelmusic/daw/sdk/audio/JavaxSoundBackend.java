package com.benesquivelmusic.daw.sdk.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cross-platform {@link AudioBackend} built on the JDK's
 * {@code javax.sound.sampled} API.
 *
 * <p>Always available. Latency is higher than a native driver
 * (typically 30–50&nbsp;ms on Windows) which is why this backend is the
 * final rung of the engine's {@code StreamingProvision} fallback ladder,
 * walked when {@link AsioBackend}, {@link CoreAudioBackend},
 * {@link WasapiBackend}, or {@link JackBackend} cannot open a stream on the
 * current host (story 316).</p>
 */
public final class JavaxSoundBackend implements AudioBackend {

    private static final Logger LOG = Logger.getLogger(JavaxSoundBackend.class.getName());

    /** Backend name. */
    public static final String NAME = "Java Sound";

    /**
     * Hard bound on the stop drain — never the JDK's unbounded
     * {@link SourceDataLine#drain()}, which would stall the lifecycle thread
     * for as long as the device takes to play its whole buffer out (story 316
     * review).
     */
    private static final long CLOSE_DRAIN_TIMEOUT_MILLIS = 200L;

    /** Poll interval of the bounded stop drain. */
    private static final long CLOSE_DRAIN_POLL_NANOS = 2_000_000L; // 2 ms

    private final AudioBackendSupport support = new AudioBackendSupport();
    private SourceDataLine outputLine;
    private TargetDataLine inputLine;
    private Thread captureThread;

    /** Creates a new Java Sound backend. */
    public JavaxSoundBackend() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: {@link #sink(AudioBlock)} writes to a real
     * {@link SourceDataLine} and {@link #inputBlocks()} republishes captured
     * audio from a {@link TargetDataLine}. Java Sound is the always-available
     * final rung of the story-316 fallback ladder.</p>
     */
    @Override
    public boolean supportsStreaming() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clamps the bit depth to 16 while preserving the requested sample
     * rate and channel count, because this backend's output path
     * ({@link #encodePcm16}) only encodes 16-bit little-endian PCM. Without
     * this negotiation a 24-bit engine format would open a 24-bit line and
     * then throw on every {@link #sink(AudioBlock)} — the Java Sound rung
     * must actually produce sound (story 316; story 317 owns the fuller
     * line-format negotiation).</p>
     */
    @Override
    public AudioFormat negotiateFormat(AudioFormat requested) {
        Objects.requireNonNull(requested, "requested must not be null");
        if (requested.bitDepth() == 16) {
            return requested;
        }
        return new AudioFormat(requested.sampleRate(), requested.channels(), 16);
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        for (int i = 0; i < infos.length; i++) {
            Mixer mixer = AudioSystem.getMixer(infos[i]);
            int maxIn = 0;
            int maxOut = 0;
            for (Line.Info li : mixer.getTargetLineInfo()) {
                if (li instanceof DataLine.Info dli) {
                    maxIn = Math.max(maxIn, maxChannels(dli));
                }
            }
            for (Line.Info li : mixer.getSourceLineInfo()) {
                if (li instanceof DataLine.Info dli) {
                    maxOut = Math.max(maxOut, maxChannels(dli));
                }
            }
            devices.add(new AudioDeviceInfo(
                    i,
                    infos[i].getName(),
                    NAME,
                    maxIn,
                    maxOut,
                    44_100.0,
                    List.of(SampleRate.HZ_44100, SampleRate.HZ_48000),
                    20.0,
                    20.0));
        }
        return Collections.unmodifiableList(devices);
    }

    private static int maxChannels(DataLine.Info info) {
        int max = 0;
        for (javax.sound.sampled.AudioFormat f : info.getFormats()) {
            if (f.getChannels() > max) {
                max = f.getChannels();
            }
        }
        return Math.max(max, 2);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The OUTPUT line is mandatory: when it cannot be opened, any
     * partially opened line is closed, the open is rolled back, and an
     * {@link AudioBackendException} propagates — the engine's fallback
     * ladder must see this rung <em>fail</em>, never "succeed" into a
     * silent no-output stream (story 316, honest promises). The INPUT line
     * remains optional-degrade: a capture line that cannot be opened only
     * disables capture; playback proceeds.</p>
     *
     * <p>Both lines are opened with two hardware blocks of buffer
     * ({@code bufferFrames * bytesPerFrame * 2}): {@link #awaitSinkCapacity}
     * is a no-op on this backend — the blocking {@link SourceDataLine#write}
     * is the pacing — so the line's own buffer is the only underrun
     * headroom; one block would drain to empty the instant a write
     * returned.</p>
     *
     * <p>A line RETAINED by a previous {@link #close()} — one whose
     * {@link Line#close()} threw, so the backend kept the handle rather than
     * report a release it never made — is retried HERE, before anything is
     * opened, and this open is REFUSED while it still cannot be released:
     * {@code this.outputLine} is assigned unconditionally below, so opening
     * over a retained line would drop the only reference to it and leak it
     * for the life of the process. The guard runs before
     * {@code support.markOpen} for exactly the reason the rollbacks below
     * restore it — a refused open must never leave {@link #isOpen()}
     * reporting {@code true}.</p>
     *
     * @throws AudioBackendException if a line retained by a failed
     *                               {@link #close()} still cannot be released,
     *                               or if the output line cannot be opened
     */
    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        AudioBackendException retained = releaseLines("refusing to open over it");
        if (retained != null) {
            throw retained;
        }
        support.markOpen(format, bufferFrames);
        javax.sound.sampled.AudioFormat jFormat = new javax.sound.sampled.AudioFormat(
                (float) format.sampleRate(),
                format.bitDepth(),
                format.channels(),
                true,
                false);
        // Double-buffer slack (see the method javadoc): line.write blocking
        // is this backend's pacing, so the line buffer is the only underrun
        // headroom — match the retired daw-core backend's 2x sizing.
        int lineBufferBytes = bufferFrames * format.bytesPerFrame() * 2;
        try {
            this.outputLine = AudioSystem.getSourceDataLine(jFormat);
            this.outputLine.open(jFormat, lineBufferBytes);
            this.outputLine.start();
        } catch (LineUnavailableException | RuntimeException e) {
            // Mandatory output line failed: roll the open back and fail the
            // rung loudly so the ladder can fall through. RUNTIME failures
            // roll back on this same path (story 316 review): getSourceDataLine
            // / open / start can raise a SecurityException or an
            // IllegalStateException just as readily as the two originally
            // listed cases, and one escaping past support.markOpen would leave
            // isOpen() LYING to the engine while a half-opened SourceDataLine
            // leaks — the engine, seeing the rung fail, walks on and opens
            // another backend beside that leaked line. Java Sound is the
            // ladder's MANDATORY FINAL RUNG: a leaked line walks the mixer's
            // finite line budget to zero. IllegalArgumentException is a
            // RuntimeException so it is covered rather than listed (javac
            // rejects multicatch alternatives related by subclassing); Error
            // still propagates untouched.
            SourceDataLine partial = this.outputLine;
            this.outputLine = null;
            if (partial != null) {
                try {
                    partial.close();
                } catch (RuntimeException ignored) {
                    // best-effort cleanup of a line that never started
                }
            }
            support.markClosed();
            throw new AudioBackendException(
                    "Java Sound output line unavailable: " + e.getMessage(), e);
        }
        try {
            this.inputLine = AudioSystem.getTargetDataLine(jFormat);
            this.inputLine.open(jFormat, lineBufferBytes);
            this.inputLine.start();
            startCapture(format, bufferFrames);
        } catch (LineUnavailableException | RuntimeException e) {
            // Optional capture: input failure never kills playback — but the
            // partially opened line must still be given back. Java Sound is
            // the ladder's MANDATORY FINAL RUNG (story 316 review): a line
            // leaked on every retry walks the mixer's finite line budget down
            // to zero and turns a transient capture failure into a permanent
            // "no lines available" for the one backend every machine has.
            // Same roll-back shape as the mandatory output path above.
            // RUNTIME failures degrade through here too (story 316 review):
            // the capture setup can raise a security denial, or fail to start
            // the capture thread, as easily as it can raise the two originally
            // listed cases. One escaping would kill an open whose MANDATORY
            // output line had ALREADY succeeded — the engine would read the
            // whole rung as failed and open a second backend in parallel
            // against that still-live output line, two streams on one device.
            // IllegalArgumentException is a RuntimeException so it is covered
            // rather than listed (javac rejects multicatch alternatives
            // related by subclassing); Error still propagates untouched.
            TargetDataLine partialCapture = this.inputLine;
            this.inputLine = null;
            if (partialCapture != null) {
                try {
                    partialCapture.stop();
                    partialCapture.close();
                } catch (RuntimeException ignored) {
                    // best-effort cleanup of a line that never captured
                }
            }
            LOG.log(Level.WARNING,
                    "Java Sound capture line unavailable; playback continues without input",
                    e);
        }
    }

    private void startCapture(AudioFormat format, int bufferFrames) {
        final TargetDataLine line = this.inputLine;
        final int bytes = bufferFrames * format.bytesPerFrame();
        Thread t = new Thread(() -> {
            byte[] buf = new byte[bytes];
            while (support.isOpen() && !Thread.currentThread().isInterrupted()) {
                int read = line.read(buf, 0, buf.length);
                if (read <= 0) {
                    break;
                }
                AudioBlock block = decodePcm16(buf, read, format);
                support.publishInput(block);
            }
        }, "javax-sound-capture");
        t.setDaemon(true);
        this.captureThread = t;
        t.start();
    }

    static AudioBlock decodePcm16(byte[] pcm, int bytes, AudioFormat format) {
        ShortBuffer sb = ByteBuffer.wrap(pcm, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int shorts = sb.remaining();
        float[] samples = new float[shorts];
        for (int i = 0; i < shorts; i++) {
            samples[i] = sb.get(i) / 32768.0f;
        }
        int frames = shorts / format.channels();
        return new AudioBlock(format.sampleRate(), format.channels(), frames, samples);
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    @Override
    public void sink(AudioBlock block) {
        support.validateOutgoing(block);
        SourceDataLine line = this.outputLine;
        if (!support.isOpen() || line == null) {
            return;
        }
        AudioFormat fmt = support.format();
        if (fmt == null) {
            return;
        }
        byte[] pcm = encodePcm16(block, fmt.bitDepth());
        line.write(pcm, 0, pcm.length);
    }

    /**
     * {@inheritDoc}
     *
     * <p>No-op: {@link #sink(AudioBlock)} already blocks on
     * {@link SourceDataLine#write}, which <em>is</em> this backend's device
     * pacing — the line's internal buffer only accepts bytes at the device's
     * consumption rate. Parking here as well would halve the achievable
     * throughput.</p>
     */
    @Override
    public void awaitSinkCapacity(long timeoutNanos) {
        // Intentionally empty — SourceDataLine.write provides the pacing.
    }

    static byte[] encodePcm16(AudioBlock block, int bitDepth) {
        if (bitDepth != 16) {
            // Down-convert to 16-bit LE for simplicity; the SDK allows any bit
            // depth but javax.sound.sampled output is configured for 16-bit here.
            // Higher bit depths are the domain of native backends.
            throw new IllegalArgumentException(
                    "JavaxSoundBackend only supports 16-bit output, got " + bitDepth);
        }
        float[] s = block.samples();
        byte[] out = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int v = Math.round(Math.max(-1.0f, Math.min(1.0f, s[i])) * 32767.0f);
            out[2 * i]     = (byte) (v & 0xFF);
            out[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    @Override
    public boolean isOpen() {
        return support.isOpen();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The output line's tail is drained under a hard
     * {@value #CLOSE_DRAIN_TIMEOUT_MILLIS}&nbsp;ms deadline before the line
     * is stopped, so a Stop does not truncate the mix mid-block (story 316
     * review). {@link #open(DeviceId, AudioFormat, int)} sizes the line at
     * two hardware blocks and {@link #awaitSinkCapacity(long)} is a
     * deliberate no-op, so that line buffer is the ONLY place rendered audio
     * can still be sitting when close arrives — discarding it clicked on
     * every single Stop, on the one backend every machine has.</p>
     *
     * <p>The DEADLINE is what keeps the lifecycle thread safe, not the
     * absence of a drain: {@link #drainBounded(SourceDataLine)} polls the
     * line's own occupancy and returns unconditionally once the deadline
     * passes, whereas the JDK's {@link SourceDataLine#drain()} is unbounded
     * and could stall this thread for a full line buffer on a wedged device.
     * (The previous "no drain at all" rationale cited the retired daw-core
     * Java Sound backend, which was never the engine's mandatory final
     * fallback rung.)</p>
     *
     * <p>A failed {@link Line#close()} PROPAGATES instead of being swallowed,
     * because the engine treats a throwing {@code close()} as a first-class
     * outcome (story 316 review): the stream becomes {@code RELEASE_PENDING}
     * rather than {@code CLOSED}, is deliberately not reported open, and the
     * engine retries the release itself — on the next {@code stopAudioOutput}
     * or before the next open. Reporting a successful close while Java Sound
     * still holds the device would instead let the engine open another rung
     * BESIDE the leaked line, and this is the ladder's MANDATORY FINAL RUNG:
     * a line leaked on every stop walks the mixer's finite line budget to
     * zero.</p>
     *
     * <p>The line whose close threw is therefore RETAINED in its field, so a
     * later {@code close()} — or the guard at the top of
     * {@link #open(DeviceId, AudioFormat, int)} — reaches that same handle
     * again; only a close that returned normally drops its field. The drain
     * and {@link DataLine#stop()} ahead of it are attempted independently and
     * merely logged when they fail: {@code close()} is the call that actually
     * gives the device back, so a failed drain or stop must never skip it.
     * The two directions are released independently too — a capture line that
     * cannot be closed must not strand the output line.</p>
     *
     * <p>{@code support.close()} and the capture-thread interrupt run first on
     * every path, so {@link #isOpen()} is {@code false} even while a handle is
     * retained. That is precisely the {@code RELEASE_PENDING} shape: not open,
     * not resumable, not yet released.</p>
     *
     * @throws AudioBackendException if either line could not be closed — the
     *                               un-released line(s) are named, the first
     *                               failure is the cause, and a second is
     *                               attached as a suppressed exception
     */
    @Override
    public void close() {
        support.close();
        Thread t = this.captureThread;
        if (t != null) {
            t.interrupt();
            this.captureThread = null;
        }
        AudioBackendException failure = releaseLines("on close");
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Attempts to release BOTH directions and reports whether the device is
     * still held.
     *
     * <p>Both attempts always run: one direction's failure must never strand
     * the other direction's line. A line that closed is dropped from its
     * field; a line whose {@link Line#close()} threw stays there, so the next
     * attempt — the next {@link #close()}, or the guard in
     * {@link #open(DeviceId, AudioFormat, int)} — reaches the same handle
     * instead of leaking it. Doing nothing when both fields are already null
     * is what keeps {@link #close()} idempotent on a never-opened or
     * already-closed backend.</p>
     *
     * @param phase what this attempt is, named in the failure message so a
     *              stop that could not release reads differently from an open
     *              refused over a retained line
     * @return {@code null} when nothing is held any more; otherwise the
     *         exception the caller must throw, naming the un-released
     *         line(s), carrying the first failure as its cause and any second
     *         one as a suppressed exception
     */
    private AudioBackendException releaseLines(String phase) {
        RuntimeException inputFailure = releaseInputLine();
        RuntimeException outputFailure = releaseOutputLine();
        if (inputFailure == null && outputFailure == null) {
            return null;
        }
        String held;
        if (inputFailure != null && outputFailure != null) {
            held = "capture line and output line";
        } else if (inputFailure != null) {
            held = "capture line";
        } else {
            held = "output line";
        }
        RuntimeException first = inputFailure != null ? inputFailure : outputFailure;
        AudioBackendException failure = new AudioBackendException(
                "Java Sound could not release its " + held + " (" + phase
                        + "); the device is still held", first);
        if (inputFailure != null && outputFailure != null) {
            failure.addSuppressed(outputFailure);
        }
        return failure;
    }

    /**
     * Stops and closes the capture line, each in its own attempt.
     *
     * <p>A {@link DataLine#stop()} that throws is logged and the close is
     * tried anyway — stop only pauses the transfer, {@code close} is what
     * hands the line back to the mixer, so treating a failed stop as a reason
     * to skip the close would leak the very handle this method exists to
     * release.</p>
     *
     * @return the {@link Line#close()} failure, leaving {@code inputLine} set
     *         for a later retry, or {@code null} when the line was released
     *         (or there was none)
     */
    private RuntimeException releaseInputLine() {
        TargetDataLine line = this.inputLine;
        if (line == null) {
            return null;
        }
        try {
            line.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Java Sound capture line could not be stopped; closing it anyway", e);
        }
        try {
            line.close();
        } catch (RuntimeException e) {
            return e; // RETAINED in this.inputLine — a later close retries it
        }
        this.inputLine = null;
        return null;
    }

    /**
     * Drains, stops and closes the output line, each in its own attempt.
     *
     * <p>Same independence as {@link #releaseInputLine()}, with the bounded
     * drain ahead of the stop: {@link #drainBounded(SourceDataLine)} reads the
     * line's own occupancy, so a line the driver has already invalidated can
     * throw from there — and a tail that cannot be played out is no reason to
     * keep the device.</p>
     *
     * @return the {@link Line#close()} failure, leaving {@code outputLine} set
     *         for a later retry, or {@code null} when the line was released
     *         (or there was none)
     */
    private RuntimeException releaseOutputLine() {
        SourceDataLine line = this.outputLine;
        if (line == null) {
            return null;
        }
        try {
            drainBounded(line);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Java Sound output line could not be drained; closing it anyway", e);
        }
        try {
            line.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Java Sound output line could not be stopped; closing it anyway", e);
        }
        try {
            line.close();
        } catch (RuntimeException e) {
            return e; // RETAINED in this.outputLine — a later close retries it
        }
        this.outputLine = null;
        return null;
    }

    /**
     * Waits — for at most {@value #CLOSE_DRAIN_TIMEOUT_MILLIS}&nbsp;ms — for
     * the device to play out whatever is still buffered in {@code line}. A
     * fully played-out line reports its WHOLE buffer as
     * {@link SourceDataLine#available() available}, so occupancy is simply
     * {@code available() < getBufferSize()}.
     *
     * <p>Deliberately not {@link SourceDataLine#drain()}: that call is
     * unbounded and this runs on the lifecycle thread (story 316 review).
     * Returns unconditionally once the deadline passes, drained or not.</p>
     *
     * @param line the output line to drain; must not be null
     */
    private static void drainBounded(SourceDataLine line) {
        if (!line.isOpen()) {
            return;
        }
        long deadline = System.nanoTime()
                + CLOSE_DRAIN_TIMEOUT_MILLIS * 1_000_000L;
        while (line.available() < line.getBufferSize()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return; // hard bound: never stall the lifecycle thread
            }
            LockSupport.parkNanos(Math.min(CLOSE_DRAIN_POLL_NANOS, remaining));
        }
    }
}
