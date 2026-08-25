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
import java.util.concurrent.SubmissionPublisher;
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

    /**
     * Hard bound, for every instance the public constructor creates, on how
     * long {@link #releaseInputLine()} waits for the capture thread to exit
     * once its line has been closed (story 316 review). Same bound daw-core's
     * {@code CallbackBackendAdapter} puts on its drain thread at close. A
     * thread that outlives it is logged and dropped, never waited on further:
     * see {@link #joinCaptureThread()} for why that is safe. The
     * package-private constructor lets a test substitute a shorter bound.
     */
    static final long CAPTURE_EXIT_TIMEOUT_MILLIS = 2_000L;

    private final AudioBackendSupport support = new AudioBackendSupport();

    /**
     * The bound {@link #joinCaptureThread()} actually waits under:
     * {@link #CAPTURE_EXIT_TIMEOUT_MILLIS} unless the package-private
     * constructor was given another.
     */
    private final long captureExitTimeoutMillis;

    private SourceDataLine outputLine;
    private TargetDataLine inputLine;

    /**
     * The thread started by {@link #startCapture(AudioFormat, int)} for the
     * stream currently (or most recently) open. Set only there; cleared only
     * by {@link #joinCaptureThread()}, which runs when the capture line has
     * actually been closed — so while a capture line is RETAINED after a
     * failed {@link Line#close()}, its thread stays referenced beside it: it
     * may still be blocked reading that line (a {@link DataLine#stop()} that
     * returned normally has already released the read, in which case it
     * exits on its own), and either way the retry that finally closes the
     * line is the one that joins it.
     */
    private Thread captureThread;

    /**
     * Capture channels the currently open stream really has, or {@code 0} when
     * this open produced none (story 316 review). Read through
     * {@link #openedInputChannels()}.
     *
     * <p>Set only after the {@link TargetDataLine} both OPENED and STARTED and
     * the capture thread was running &mdash; anything short of that publishes
     * no block, and a count that outran the publisher would be the silent take
     * this field exists to make visible. Cleared in
     * {@link #releaseInputLine()}, which is the one place the capture line ever
     * goes away: the {@link #close()} path, the capture rollback, and the
     * retained-line guard at the top of {@code open} all reach it.</p>
     *
     * <p>Volatile because the engine reads it on its lifecycle thread
     * immediately after {@code open} returns, while the capture thread this
     * open started is already running.</p>
     */
    private volatile int openedInputChannels;

    /** Creates a new Java Sound backend. */
    public JavaxSoundBackend() {
        this(CAPTURE_EXIT_TIMEOUT_MILLIS);
    }

    /**
     * Creates a backend whose {@link #releaseInputLine()} waits at most
     * {@code captureExitTimeoutMillis} for the capture thread to exit.
     *
     * <p>Package-private so {@code JavaxSoundBackendTest} can prove the
     * timed-out branch of {@link #joinCaptureThread()} without holding a test
     * for the production bound. Production goes through the public
     * constructor and {@link #CAPTURE_EXIT_TIMEOUT_MILLIS}.</p>
     *
     * @param captureExitTimeoutMillis the join bound, in milliseconds; must be
     *                                 positive — {@link Thread#join(long)}
     *                                 treats {@code 0} as "wait forever", the
     *                                 unbounded stall this bound exists to
     *                                 rule out
     */
    JavaxSoundBackend(long captureExitTimeoutMillis) {
        if (captureExitTimeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "captureExitTimeoutMillis must be positive: " + captureExitTimeoutMillis);
        }
        this.captureExitTimeoutMillis = captureExitTimeoutMillis;
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
     * <p>Equivalent to
     * {@link #open(DeviceId, AudioFormat, int, CaptureRequirement)} with
     * {@link CaptureRequirement#OPTIONAL} — the PLAYBACK contract, and the
     * behaviour this backend has always had. That overload carries the whole
     * body and the whole rationale; read it there.</p>
     */
    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        open(device, format, bufferFrames, CaptureRequirement.OPTIONAL);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The OUTPUT line is mandatory: when it cannot be opened the open is
     * rolled back and an {@link AudioBackendException} propagates — the
     * engine's fallback ladder must see this rung <em>fail</em>, never
     * "succeed" into a silent no-output stream (story 316, honest promises).</p>
     *
     * <p>The INPUT line is where {@code capture} decides the outcome, and the
     * boundary is exact (story 316 review). A capture line that cannot be
     * opened never kills a PLAYBACK open ({@link CaptureRequirement#OPTIONAL}):
     * capture is simply disabled and playback proceeds, because a machine whose
     * microphone is busy must still be able to hear the session. The same
     * failure FAILS a {@link CaptureRequirement#REQUIRED} open, because a
     * RECORDING open that degrades to output-only is a silent take: it returns
     * successfully, the recording pipeline subscribes to
     * {@link #inputBlocks()}, that publisher never emits, and the take is saved
     * empty with nothing anywhere reporting a failure. The capture line's own
     * failure becomes the cause, so the operator reads the real
     * {@code LineUnavailableException} rather than a generic refusal.</p>
     *
     * <p>A REQUIRED refusal deliberately does NOT release the output line it
     * had already opened. Rolling that line back here would duplicate — and
     * race — the engine's own ladder walk, which closes a rung whose
     * {@code open} threw and reads the verdict of that close before advancing;
     * the SDK contract for {@code open} promises no rollback for exactly this
     * reason. {@link #releaseLines(String)} already handles the state the
     * refusal leaves behind (output line open, capture line already rolled
     * back): it attempts BOTH directions unconditionally and a {@code null}
     * field is a no-op, so the engine's {@code close()} — or the retained-line
     * guard at the top of the next open — releases it. What the refusal DOES
     * clear is the open FLAG, through {@code support.markClosed()} inside the
     * rollback, so {@link #isOpen()} never latches {@code true} behind an
     * escaped failure. That is the same "not open, not resumable, not yet
     * released" shape {@link #rollBackFailedOutputOpen(Exception)} and
     * {@link #close()} both document.</p>
     *
     * <p>BOTH rollbacks hand the partially opened line back through the SAME
     * retain-on-failure release the {@link #close()} path uses —
     * {@link #rollBackFailedOutputOpen(Exception)} calls
     * {@link #releaseOutputLine()} and
     * {@link #rollBackFailedCaptureOpen(Exception, CaptureRequirement)} calls
     * {@link #releaseInputLine()} — so a line whose {@link Line#close()}
     * throws STAYS in its field rather than being cleared ahead of a
     * best-effort close nobody checks. Clearing first would drop the only
     * reference to a line the mixer still counts as taken: neither the
     * engine's own retry (it closes a failed hop) nor the retained-line guard
     * described below could ever reach that handle again.</p>
     *
     * <p>The two paths differ only in how a release failure is REPORTED. On
     * the mandatory OUTPUT path it rides as a suppressed exception on the
     * thrown {@link AudioBackendException} — whose cause stays the OPEN
     * failure, the actionable one — and is logged at {@link Level#WARNING}.
     * On the OPTIONAL capture path it is logged and the open still SUCCEEDS:
     * throwing there would kill an open whose mandatory output line had
     * already succeeded. Retention is what lets a later {@link #close()}
     * confirm that capture line's release. On the REQUIRED capture path the
     * open is failing anyway, so the release failure rides along as a
     * suppressed exception exactly as it does on the output path — and for the
     * same reason: the OPEN failure is the actionable one and must stay the
     * cause.</p>
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
     *                               if the output line cannot be opened, or if
     *                               {@code capture} is
     *                               {@link CaptureRequirement#REQUIRED} and the
     *                               capture line could not be opened — in each
     *                               of the latter two cases a release failure
     *                               from the rollback itself rides along as a
     *                               suppressed exception, and the line it
     *                               could not release is retained
     */
    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames,
                     CaptureRequirement capture) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(capture, "capture must not be null");
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
            // Mandatory output line failed: roll the open back — through the
            // same retain-on-failure release close() uses, see
            // rollBackFailedOutputOpen — and fail the rung loudly so the
            // ladder can fall through. RUNTIME failures roll back on this same
            // path (story 316 review): getSourceDataLine / open / start can
            // raise a SecurityException or an
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
            throw rollBackFailedOutputOpen(e);
        }
        try {
            this.inputLine = AudioSystem.getTargetDataLine(jFormat);
            this.inputLine.open(jFormat, lineBufferBytes);
            this.inputLine.start();
            startCapture(format, bufferFrames);
            // Published only once the line is open, started AND the capture
            // thread is feeding support.publishInput: openedInputChannels() is
            // a promise about inputBlocks(), so it may never outrun the thing
            // that does the publishing.
            this.openedInputChannels = format.channels();
        } catch (LineUnavailableException | RuntimeException e) {
            // Optional capture: an input failure never kills a PLAYBACK open
            // (see the REQUIRED case at the end of this comment) — but the
            // partially opened line must still be given back. Java Sound is
            // the ladder's MANDATORY FINAL RUNG (story 316 review): a line
            // leaked on every retry walks the mixer's finite line budget down
            // to zero and turns a transient capture failure into a permanent
            // "no lines available" for the one backend every machine has.
            // Same retain-on-failure roll-back as the mandatory output path
            // above; only the reporting differs, and it differs for the reason
            // spelled out below — see rollBackFailedCaptureOpen.
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
            //
            // ...unless this is a RECORDING open (story 316 review). Under
            // CaptureRequirement.REQUIRED the degradation IS the failure: an
            // open that returns here with no capture line leaves the recording
            // pipeline subscribed to a publisher that never emits, and the take
            // is saved silent. The rollback returns the exception to throw in
            // that case and null in the OPTIONAL case, so the two contracts
            // share one body and cannot drift apart.
            AudioBackendException refusal = rollBackFailedCaptureOpen(e, capture);
            if (refusal != null) {
                throw refusal;
            }
        }
    }

    /**
     * Rolls a failed OUTPUT-line open back, RETAINING a line it could not
     * release, and builds the failure {@link #open(DeviceId, AudioFormat, int)}
     * must throw.
     *
     * <p>Delegates to {@link #releaseOutputLine()} — the release
     * {@link #close()} itself uses — instead of hand-rolling a "clear the
     * field, then close, then swallow" cleanup. That matters because
     * {@code releaseOutputLine} only clears {@code outputLine} when
     * {@link Line#close()} RETURNED: a line the driver refuses to take back
     * stays reachable for the engine's retry (it closes a failed hop) and for
     * the retained-line guard at the top of
     * {@link #open(DeviceId, AudioFormat, int)}, which refuses the next open
     * rather than overwrite the handle. Java Sound is the ladder's MANDATORY
     * FINAL RUNG, so a line leaked here is leaked for the life of the
     * process.</p>
     *
     * <p>{@code support.markClosed()} runs before the release attempt, so
     * {@link #isOpen()} already reports {@code false} while a handle is
     * retained — the same "not open, not resumable, not yet released" shape
     * {@link #close()} documents.</p>
     *
     * <p>The OPEN failure stays the cause: it is what the ladder and the
     * operator must act on. A release failure is the secondary fact, so it is
     * attached as a suppressed exception AND logged, never swallowed.</p>
     *
     * <p>Package-private so {@code JavaxSoundBackendTest} can drive this
     * rollback with a planted line. {@code AudioSystem} is a static JDK
     * factory and this backend takes no injectable mixer, so a line whose
     * {@code close()} refuses cannot be reached through a real
     * {@link #open(DeviceId, AudioFormat, int)}.</p>
     *
     * @param openFailure why the output line could not be opened; becomes the
     *                    returned exception's cause
     * @return the exception the caller must throw, carrying any release
     *         failure as a suppressed exception
     */
    AudioBackendException rollBackFailedOutputOpen(Exception openFailure) {
        support.markClosed();
        RuntimeException releaseFailure = releaseOutputLine();
        AudioBackendException failure = new AudioBackendException(
                "Java Sound output line unavailable: " + openFailure.getMessage(),
                openFailure);
        if (releaseFailure != null) {
            failure.addSuppressed(releaseFailure);
            LOG.log(Level.WARNING,
                    "Java Sound could not release the output line of a failed open;"
                            + " the backend RETAINS that line and a later close()"
                            + " retries the release",
                    releaseFailure);
        }
        return failure;
    }

    /**
     * Rolls a failed CAPTURE-line open back, RETAINING a line it could not
     * release, and REPORTS whether {@code capture} makes that failure fatal to
     * the open (story 316 review).
     *
     * <p>Same retain-on-failure release as
     * {@link #rollBackFailedOutputOpen(Exception)}:
     * {@link #releaseInputLine()} stops the line, closes it regardless of how
     * the stop went, and leaves it in {@code inputLine} when the close throws,
     * so a later {@link #close()} reaches the same handle and can confirm the
     * release. That half is identical for both requirements, which is why they
     * share this one body instead of two that could drift apart.</p>
     *
     * <p>Only the VERDICT differs. Under {@link CaptureRequirement#OPTIONAL}
     * this returns {@code null} and the open stands, because capture is
     * optional-degrade for playback: an exception escaping here would kill an
     * open whose MANDATORY output line had ALREADY succeeded, and the engine —
     * reading the whole rung as failed — would open a second backend in
     * parallel against that still-live output line, two streams on one device.
     * Both facts are logged, each as its own {@link Level#WARNING}: the open
     * failure that disabled capture, then, separately, the release failure that
     * left a handle held.</p>
     *
     * <p>Under {@link CaptureRequirement#REQUIRED} it returns the exception the
     * caller must throw. {@code openFailure} — the capture line's own
     * {@link javax.sound.sampled.LineUnavailableException}, or whatever runtime
     * failure the capture setup raised — becomes its CAUSE, so the operator
     * reads the real reason the device refused rather than a generic refusal.
     * A release failure from the rollback rides along as a suppressed exception
     * and is still logged, exactly as on the output path: the OPEN failure is
     * the actionable one and must stay the cause.</p>
     *
     * <p>{@code support.markClosed()} runs on the REQUIRED path only, and only
     * on the FLAG. The output line is left open on purpose — see
     * {@link #open(DeviceId, AudioFormat, int, CaptureRequirement)} for why the
     * engine's ladder walk, not this method, is what releases it — but
     * {@link #isOpen()} must not latch {@code true} behind a failure that
     * escapes, or the next attempt on this rung would die on
     * {@code markOpen}'s "already has an open stream" instead of on the real
     * device problem. On the OPTIONAL path the flag is deliberately left ALONE:
     * that open is still standing.</p>
     *
     * <p>Package-private for the same testing reason as
     * {@link #rollBackFailedOutputOpen(Exception)}.</p>
     *
     * @param openFailure why the capture line could not be opened
     * @param capture     the requirement the open was made under; must not be
     *                    null
     * @return {@code null} when the open may stand
     *         ({@link CaptureRequirement#OPTIONAL}); otherwise the
     *         {@link AudioBackendException} the caller must throw
     */
    AudioBackendException rollBackFailedCaptureOpen(Exception openFailure,
                                                    CaptureRequirement capture) {
        Objects.requireNonNull(capture, "capture must not be null");
        boolean required = capture == CaptureRequirement.REQUIRED;
        LOG.log(Level.WARNING,
                required
                        ? "Java Sound capture line unavailable and this open REQUIRES"
                                + " capture; failing the open rather than recording a"
                                + " silent take"
                        : "Java Sound capture line unavailable; playback continues"
                                + " without input",
                openFailure);
        if (required) {
            // The flag only — the output line stays open for the caller's
            // close() to release. A refused open must never leave isOpen()
            // reporting true.
            support.markClosed();
        }
        RuntimeException releaseFailure = releaseInputLine();
        if (releaseFailure != null) {
            LOG.log(Level.WARNING,
                    "Java Sound could not release the capture line of that failed open"
                            + " either; the backend RETAINS that line and a later"
                            + " close() retries the release",
                    releaseFailure);
        }
        if (!required) {
            return null;
        }
        AudioBackendException refusal = new AudioBackendException(
                "Java Sound capture line unavailable, and this open requires capture: "
                        + openFailure.getMessage(),
                openFailure);
        if (releaseFailure != null) {
            refusal.addSuppressed(releaseFailure);
        }
        return refusal;
    }

    /**
     * Starts the {@code javax-sound-capture} thread that reads
     * {@code inputLine} and republishes its blocks on {@link #inputBlocks()}.
     *
     * <p>THE PUBLISHER-PINNING RULE (story 316 review): the thread publishes
     * only into the publisher instance {@code support} hands out at the
     * moment this method runs — pinned here as {@code stream}, before the
     * thread starts, and passed to
     * {@link AudioBackendSupport#publishInput(SubmissionPublisher, AudioBlock)}
     * on every block — never into whatever the support's current publisher
     * happens to be when a block arrives. {@link TargetDataLine#read} is not
     * interruptible and can return one last partial block after
     * {@link #close()} has closed the line under it. Without the pin, a
     * close-then-open that raced ahead of that last read would let the old
     * thread publish stale samples into the NEW stream's recording. With it,
     * the old thread can only ever offer into the publisher
     * {@code support.close()} already completed, which the support drops. The
     * loop also stops on its own once that publisher is closed, so a survivor
     * is bounded by its own stream even if the driver's read swallowed the
     * interrupt. {@link #releaseInputLine()} additionally joins the thread
     * once its line is closed; the pin is what makes a join that times out
     * harmless.</p>
     *
     * <p>Package-private for the same testing reason as
     * {@link #rollBackFailedOutputOpen(Exception)}: {@code AudioSystem} is a
     * static JDK factory, so a line whose {@code read} blocks on a
     * test-controlled gate can only be planted, and the thread that reads it
     * can only be started from here.</p>
     *
     * @param format       the opened format, for decoding the line's PCM
     * @param bufferFrames frames per block, sizing the read buffer
     */
    void startCapture(AudioFormat format, int bufferFrames) {
        final TargetDataLine line = this.inputLine;
        final int bytes = bufferFrames * format.bytesPerFrame();
        // Pinned BEFORE the thread starts: this is the stream the thread was
        // started for, and the only one it may ever publish into.
        final SubmissionPublisher<AudioBlock> stream = support.currentInputPublisher();
        Thread t = new Thread(() -> {
            byte[] buf = new byte[bytes];
            while (support.isOpen() && !stream.isClosed()
                    && !Thread.currentThread().isInterrupted()) {
                int read = line.read(buf, 0, buf.length);
                if (read <= 0) {
                    break;
                }
                AudioBlock block = decodePcm16(buf, read, format);
                support.publishInput(stream, block);
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

    /**
     * {@inheritDoc}
     *
     * <p>The opened format's channel count once the {@link TargetDataLine} has
     * been opened, started, and handed to the {@code javax-sound-capture}
     * thread that feeds {@link #inputBlocks()} — the line is opened with that
     * same {@code javax.sound.sampled.AudioFormat}, so the count the mixer gave
     * is the count this backend asked for or the open would have thrown.
     * {@code 0} on every other path: no stream open, a capture line the mixer
     * refused (the optional-degrade case that leaves playback running), or a
     * stream whose lines have since been released.</p>
     */
    @Override
    public int openedInputChannels() {
        return openedInputChannels;
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
     * <p>The interrupt alone does not establish that the capture thread has
     * EXITED (story 316 review): {@link TargetDataLine#read} is not
     * interruptible, so the thread is typically still inside it here (or
     * between reads, in which case it exits on its next loop test), and a
     * blocked read returns — possibly with one last partial block — once the
     * line is stopped or closed below: the JDK contract for {@code read} is
     * that it "no longer blocks" once the line is "closed, stopped, drained,
     * or flushed". Two things cover that. Every block the thread publishes
     * goes into the publisher it pinned at start, which
     * {@code support.close()} has just
     * completed, so a straggler can never reach a stream a later
     * {@link #open(DeviceId, AudioFormat, int)} installs — see
     * {@link #startCapture(AudioFormat, int)}. And {@link #releaseInputLine()}
     * JOINS the thread, under {@link #captureExitTimeoutMillis}
     * ({@link #CAPTURE_EXIT_TIMEOUT_MILLIS} for every production instance), right
     * after the capture line's {@link Line#close()} returns — the later of
     * the two calls ({@link DataLine#stop()}, then {@code close()}) that
     * release the blocked read, and the one whose success is required; a
     * stop failure is only logged — and only then clears
     * {@code captureThread}. When that close throws instead, the thread is
     * NOT joined but stays referenced beside its retained line for the retry.
     * Whether it is still blocked depends on the {@code stop()} issued ahead
     * of the close: one that returned normally has already released the read
     * and the thread exits on its own; one that failed leaves the read
     * blocked on the still-open line, where a join would burn the whole bound
     * for nothing.
     * A join that times out is logged and the thread is dropped; it does not
     * fail this close, because the device is already released and the pin
     * makes the survivor harmless. Throwing would keep the ladder's mandatory
     * final rung in {@code RELEASE_PENDING} over a thread that holds no
     * device.</p>
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
            // Stops the loop if read() returns normally; the field itself is
            // cleared only by the join in releaseInputLine, once the line the
            // thread is reading has really been closed.
            t.interrupt();
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
     * Stops and closes the capture line, each in its own attempt, then joins
     * the thread that was reading it.
     *
     * <p>A {@link DataLine#stop()} that throws is logged and the close is
     * tried anyway — stop only pauses the transfer, {@code close} is what
     * hands the line back to the mixer, so treating a failed stop as a reason
     * to skip the close would leak the very handle this method exists to
     * release.</p>
     *
     * <p>The join comes AFTER a {@link Line#close()} that returned (story 316
     * review). By the JDK's {@link TargetDataLine#read} contract the thread's
     * non-interruptible read returns once the line is stopped or closed, and
     * this method issues both, stop first; the join sits after the close
     * because close is the later of the two and the one whose success is
     * required — a stop failure is only logged. A join placed before either
     * call would wait for a read that nothing has released. It is bounded by
     * {@link #captureExitTimeoutMillis} and never throws — see
     * {@link #joinCaptureThread()} for the timeout policy. When the close
     * THROWS the thread is deliberately not joined and stays in
     * {@code captureThread} beside the retained line, so the retry that
     * finally closes the line is the one that joins it. Whether it is still
     * blocked depends on the {@code stop()} issued first: one that returned
     * normally has already released the read, and the thread exits on its
     * own; one that failed leaves the read blocked on the still-open line,
     * where a join would burn the whole bound for nothing.</p>
     *
     * @return the {@link Line#close()} failure, leaving {@code inputLine} set
     *         for a later retry, or {@code null} when the line was released
     *         (or there was none)
     */
    private RuntimeException releaseInputLine() {
        // Cleared FIRST, and unconditionally: this is the one place the capture
        // line ever goes away, so clearing here covers close(), the capture
        // rollback and the retained-line guard at the top of open() in a single
        // statement. A count that outlived its line would be the silent take
        // openedInputChannels() exists to make visible (story 316 review).
        this.openedInputChannels = 0;
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
            // RETAINED in this.inputLine — a later close retries it. The
            // capture thread stays in its field too, for that retry to join:
            // if the stop() above failed as well, its read is still blocked
            // on this still-open line and a join would only burn the bound;
            // if the stop() returned, the read is already released and the
            // thread exits on its own.
            return e;
        }
        this.inputLine = null;
        joinCaptureThread();
        return null;
    }

    /**
     * Waits — for at most {@link #captureExitTimeoutMillis} — for the capture
     * thread to exit, then drops the reference whether or not it exited; the
     * one early return that keeps it is the guard against being called from
     * the capture thread itself, which nothing in production does.
     *
     * <p>Called only once the capture line's {@link Line#close()} has
     * returned, after a {@link DataLine#stop()} was attempted ahead of it — a
     * stop that returned, or the close that did, each release the thread's
     * blocked read by the JDK's {@link TargetDataLine#read} contract — so
     * this normally returns as soon as the thread
     * has processed that last (possibly partial) block. The timeout is the
     * hard bound the
     * lifecycle thread needs against a driver whose read does not come back
     * (story 316 review).</p>
     *
     * <p>A join that ends with the thread still alive — because it timed out
     * or was interrupted — is logged at {@link Level#WARNING}; the field is
     * cleared anyway, and this never throws.
     * The device is already released (the close returned), and the survivor
     * can only ever publish into the publisher it pinned at start — never
     * into one a later {@link #open(DeviceId, AudioFormat, int)} installs,
     * see {@link #startCapture(AudioFormat, int)}. Failing the close over it would
     * hold the ladder's mandatory final rung in {@code RELEASE_PENDING} for a
     * thread that holds no device. An interrupt is re-asserted for the caller
     * and the wait stops there.</p>
     */
    private void joinCaptureThread() {
        Thread t = this.captureThread;
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        boolean interrupted = false;
        try {
            t.join(captureExitTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted = true;
        }
        if (t.isAlive()) {
            LOG.log(Level.WARNING,
                    (interrupted
                            ? "Java Sound capture thread was still running when the wait"
                                    + " for it was interrupted"
                            : "Java Sound capture thread did not exit within "
                                    + captureExitTimeoutMillis + " ms")
                            + "; its stale blocks are isolated from later streams by"
                            + " the pinned publisher");
        }
        this.captureThread = null;
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
