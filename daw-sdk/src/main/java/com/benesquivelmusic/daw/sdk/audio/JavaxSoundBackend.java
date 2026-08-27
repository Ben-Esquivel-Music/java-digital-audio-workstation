package com.benesquivelmusic.daw.sdk.audio;

import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
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
 * <p>Latency is higher than a native driver
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
     * Every packed signed-PCM width understood by {@link #encode(AudioBlock,
     * javax.sound.sampled.AudioFormat)} and {@link #decode(byte[], int,
     * AudioFormat, javax.sound.sampled.AudioFormat)}. The order after a
     * requested width keeps 16 bit as the universal fallback, prefers the
     * higher-fidelity alternatives next, and leaves 8 bit last.
     */
    private static final List<Integer> SIGNED_PCM_WIDTHS =
            List.of(Short.SIZE, 24, Integer.SIZE, Byte.SIZE);

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

    /** Static Java Sound factories, injectable for deterministic contract tests. */
    private final JavaSoundAccess javaSound;

    /**
     * The bound {@link #joinCaptureThread()} actually waits under:
     * {@link #CAPTURE_EXIT_TIMEOUT_MILLIS} unless the package-private
     * constructor was given another.
     */
    private final long captureExitTimeoutMillis;

    /**
     * Output line owned by the lifecycle path. A failed close leaves this
     * reference in place so a later close can retry the same handle.
     */
    private SourceDataLine outputLine;
    private TargetDataLine inputLine;

    /**
     * One safely published, generation-coherent playback snapshot for the
     * render-pump sink path. A sink reads this volatile field exactly once, so
     * it can never combine one stream's line with another stream's actual
     * format across a concurrent close/reopen. It is cleared before teardown;
     * {@link #outputLine} separately retains lifecycle ownership if close
     * fails.
     */
    private volatile OutputGeneration outputGeneration;

    /** Actual capture format, pinned only after the line opens. */
    private javax.sound.sampled.AudioFormat inputLineFormat;

    /** Immutable playback state published once an output line is fully ready. */
    private record OutputGeneration(
            SourceDataLine line,
            javax.sound.sampled.AudioFormat actualFormat,
            AudioFormat busFormat) {

        private OutputGeneration {
            Objects.requireNonNull(line, "line must not be null");
            Objects.requireNonNull(actualFormat, "actualFormat must not be null");
            Objects.requireNonNull(busFormat, "busFormat must not be null");
        }
    }

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
        this(JavaSoundAccess.SYSTEM, CAPTURE_EXIT_TIMEOUT_MILLIS);
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
        this(JavaSoundAccess.SYSTEM, captureExitTimeoutMillis);
    }

    /** Package-private Java Sound seam for headless format/device tests. */
    JavaxSoundBackend(JavaSoundAccess javaSound) {
        this(javaSound, CAPTURE_EXIT_TIMEOUT_MILLIS);
    }

    /** Package-private full test constructor. */
    JavaxSoundBackend(JavaSoundAccess javaSound, long captureExitTimeoutMillis) {
        this.javaSound = Objects.requireNonNull(javaSound, "javaSound must not be null");
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
    public synchronized boolean isAvailable() {
        // A stream line already owned by this backend is the strongest
        // possible availability proof. In particular, do not ask a mixer
        // with a one-line limit for a second SourceDataLine while the first is
        // actively streaming. A retained/partially-opened line has no
        // published generation and blocks a fresh probe until close retries
        // its release.
        if (this.outputLine != null) {
            OutputGeneration current = this.outputGeneration;
            if (current == null) {
                return false;
            }
            try {
                return current.line().isOpen();
            } catch (RuntimeException invalidLine) {
                LOG.log(Level.FINE,
                        "Java Sound open output line could not report its state",
                        invalidLine);
                return false;
            }
        }
        try {
            Mixer.Info[] mixerInfos = javaSound.mixerInfos();
            if (mixerInfos.length == 0) {
                try {
                    return canObtainSourceLine(null);
                } catch (RuntimeException unavailable) {
                    LOG.log(Level.FINE,
                            "Java Sound default-mixer availability probe failed", unavailable);
                    return false;
                }
            }
            for (Mixer.Info mixerInfo : mixerInfos) {
                try {
                    if (canObtainSourceLine(mixerInfo)) {
                        return true;
                    }
                } catch (RuntimeException unavailable) {
                    LOG.log(Level.FINE,
                            () -> "Java Sound availability probe failed for mixer '"
                                    + mixerInfo.getName() + "': " + unavailable);
                }
            }
        } catch (RuntimeException unavailable) {
            LOG.log(Level.FINE, "Java Sound availability probe failed", unavailable);
        }
        return false;
    }

    private boolean canObtainSourceLine(Mixer.Info mixerInfo) {
        for (javax.sound.sampled.AudioFormat format : probeFormats(mixerInfo)) {
            SourceDataLine probe = null;
            try {
                if (!javaSound.supportsSourceLine(mixerInfo, format)) {
                    continue;
                }
                probe = javaSound.sourceLine(mixerInfo, format);
            } catch (LineUnavailableException | RuntimeException unavailable) {
                // This advertised format cannot presently yield a line.
            }
            if (probe == null) {
                continue;
            }
            boolean codecSupported = false;
            try {
                // A provider accepting a wildcard DataLine.Info proves only
                // that it can return some line. The unopened line's default
                // format is the concrete capability that availability may
                // advertise; a wildcard, padded, or otherwise unsupported
                // default would still be rejected by open's format contract.
                codecSupported = isSupportedPcmFormat(probe.getFormat());
            } catch (RuntimeException invalidDefault) {
                LOG.log(Level.FINE,
                        "Java Sound availability probe exposed no usable default format",
                        invalidDefault);
            }
            if (codecSupported) {
                return true;
            }
            // Mixer.getLine returns an unopened line. Java Sound reserves
            // system resources only when Line.open() succeeds, so this query
            // neither owns nor closes the discarded probe object. Treating a
            // provider-specific close failure on an unopened object as device
            // ownership created an unrecoverable retained handle whenever a
            // selector used a temporary backend instance.
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code true} because {@link #sink(AudioBlock)} writes to a real
     * {@link SourceDataLine} and {@link #inputBlocks()} republishes captured
     * audio from a {@link TargetDataLine}. Availability itself is reported
     * separately by {@link #isAvailable()} and is never assumed.</p>
     */
    @Override
    public boolean supportsStreaming() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Preserves byte-aligned 8/16/24/32-bit requests because the signed
     * converter handles every one of those widths; unusual non-byte-aligned
     * requests fall back to 16 bit. The selected mixer's concrete encoding is
     * negotiated independently at open time:
     * 32-bit {@link Encoding#PCM_FLOAT} first, then signed-PCM formats whose
     * sample widths and byte orders {@link #encode(AudioBlock,
     * javax.sound.sampled.AudioFormat)} honours. The requested integer width
     * is tried first, followed by every other supported packed width, so an
     * endpoint accepted by {@link #isAvailable()} is also an open candidate.
     * The SDK bus itself remains normalized float32, so the SDK bit depth never
     * causes float bits to be written into an integer line.</p>
     */
    @Override
    public AudioFormat negotiateFormat(AudioFormat requested) {
        Objects.requireNonNull(requested, "requested must not be null");
        if (isIntegerWidth(requested.bitDepth())) {
            return requested;
        }
        return new AudioFormat(requested.sampleRate(), requested.channels(), 16);
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        Mixer.Info[] infos = javaSound.mixerInfos();
        for (int i = 0; i < infos.length; i++) {
            int maxIn = 0;
            int maxOut = 0;
            for (Line.Info li : javaSound.targetLineInfo(infos[i])) {
                if (li instanceof DataLine.Info dli) {
                    maxIn = mergeChannelCount(maxIn, maxChannels(dli));
                }
            }
            for (Line.Info li : javaSound.sourceLineInfo(infos[i])) {
                if (li instanceof DataLine.Info dli) {
                    maxOut = mergeChannelCount(maxOut, maxChannels(dli));
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
        var formats = info.getFormats();
        if (formats.length == 0) {
            return AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN;
        }
        int max = AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN;
        for (javax.sound.sampled.AudioFormat format : formats) {
            if (format.getChannels() == javax.sound.sampled.AudioSystem.NOT_SPECIFIED) {
                continue;
            }
            if (format.getChannels() > max) {
                max = format.getChannels();
            }
        }
        return max;
    }

    /** Keeps a known positive count over unknown, and unknown over no line. */
    private static int mergeChannelCount(int current, int candidate) {
        if (candidate > 0) {
            return current > 0 ? Math.max(current, candidate) : candidate;
        }
        return current == 0 ? candidate : current;
    }

    /** Formats advertised by one mixer plus conservative standard probes. */
    private List<javax.sound.sampled.AudioFormat> probeFormats(Mixer.Info mixerInfo) {
        List<javax.sound.sampled.AudioFormat> formats = new ArrayList<>();
        for (Line.Info lineInfo : javaSound.sourceLineInfo(mixerInfo)) {
            if (lineInfo instanceof DataLine.Info dataLineInfo) {
                for (javax.sound.sampled.AudioFormat advertised : dataLineInfo.getFormats()) {
                    List<javax.sound.sampled.AudioFormat> concreteFormats =
                            concreteProbeFormats(advertised);
                    for (javax.sound.sampled.AudioFormat concrete : concreteFormats) {
                        addCandidate(formats, concrete);
                    }
                    if (!concreteFormats.isEmpty() && containsWildcard(advertised)) {
                        // A wildcard descriptor may represent a provider whose
                        // only concrete layout is outside our conservative
                        // 48/44.1-kHz mono/stereo probes (for example a
                        // multichannel-only interface). DataLine.Info is
                        // explicitly allowed to carry NOT_SPECIFIED, so ask
                        // the provider for that generic line as the final
                        // capability probe. The non-empty expansion proves
                        // that the descriptor can represent a packed layout
                        // our codec consumes; canObtainSourceLine additionally
                        // requires the returned line to expose such a concrete
                        // default. Actual opened formats remain subject to
                        // requireActualFormat.
                        addCandidate(formats, advertised);
                    }
                }
            }
        }
        formats.addAll(candidateFormats(new AudioFormat(48_000.0, 2, 16)));
        formats.addAll(candidateFormats(AudioFormat.CD_QUALITY));
        return formats;
    }

    /**
     * Expands a Java Sound capability descriptor into packed, concrete PCM
     * layouts the SDK codec can actually consume.
     *
     * <p>{@link javax.sound.sampled.AudioSystem#NOT_SPECIFIED} is a wildcard
     * in {@link DataLine.Info}: providers commonly advertise a fixed native
     * rate with wildcard channels/frame size, or vice versa. Passing that
     * descriptor through {@link #isSupportedPcmFormat} rejects it before the
     * provider can answer. Instead, this method preserves every fixed part
     * and fills only wildcard parts with concrete packed layouts. A fixed
     * padded frame is still refused, so availability never advertises a line
     * that {@link #encode(AudioBlock, javax.sound.sampled.AudioFormat)} cannot
     * write.</p>
     */
    private static List<javax.sound.sampled.AudioFormat> concreteProbeFormats(
            javax.sound.sampled.AudioFormat advertised) {
        if (advertised == null || !isSupportedPcmEncoding(advertised.getEncoding())) {
            return List.of();
        }

        List<Integer> sampleWidths = concreteSampleWidths(advertised);
        List<Float> sampleRates = concreteSampleRates(advertised);
        if (sampleWidths.isEmpty() || sampleRates.isEmpty()) {
            return List.of();
        }

        List<javax.sound.sampled.AudioFormat> concrete = new ArrayList<>();
        for (int bits : sampleWidths) {
            int sampleBytes = bits / Byte.SIZE;
            for (int channels : concreteChannelCounts(advertised, sampleBytes)) {
                int packedFrameSize = Math.multiplyExact(channels, sampleBytes);
                int advertisedFrameSize = advertised.getFrameSize();
                if (advertisedFrameSize != javax.sound.sampled.AudioSystem.NOT_SPECIFIED
                        && advertisedFrameSize != packedFrameSize) {
                    continue;
                }
                for (float sampleRate : sampleRates) {
                    float advertisedFrameRate = advertised.getFrameRate();
                    if (advertisedFrameRate
                                    != javax.sound.sampled.AudioSystem.NOT_SPECIFIED
                            && Float.compare(advertisedFrameRate, sampleRate) != 0) {
                        continue;
                    }
                    addCandidate(concrete, new javax.sound.sampled.AudioFormat(
                            advertised.getEncoding(),
                            sampleRate,
                            bits,
                            channels,
                            packedFrameSize,
                            sampleRate,
                            advertised.isBigEndian()));
                }
            }
        }
        return List.copyOf(concrete);
    }

    private static List<Integer> concreteSampleWidths(
            javax.sound.sampled.AudioFormat advertised) {
        int advertisedBits = advertised.getSampleSizeInBits();
        if (advertisedBits != javax.sound.sampled.AudioSystem.NOT_SPECIFIED) {
            return supportsSampleWidth(advertised.getEncoding(), advertisedBits)
                    ? List.of(advertisedBits) : List.of();
        }
        return Encoding.PCM_FLOAT.equals(advertised.getEncoding())
                ? List.of(Float.SIZE)
                : SIGNED_PCM_WIDTHS;
    }

    private static List<Float> concreteSampleRates(
            javax.sound.sampled.AudioFormat advertised) {
        float sampleRate = advertised.getSampleRate();
        float frameRate = advertised.getFrameRate();
        boolean sampleRateSpecified = sampleRate
                != javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        boolean frameRateSpecified = frameRate
                != javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        if ((sampleRateSpecified && !(sampleRate > 0.0f))
                || (frameRateSpecified && !(frameRate > 0.0f))) {
            return List.of();
        }
        if (sampleRateSpecified && frameRateSpecified
                && Float.compare(sampleRate, frameRate) != 0) {
            return List.of();
        }
        if (sampleRateSpecified) {
            return List.of(sampleRate);
        }
        if (frameRateSpecified) {
            return List.of(frameRate);
        }
        return List.of(48_000.0f, 44_100.0f);
    }

    private static List<Integer> concreteChannelCounts(
            javax.sound.sampled.AudioFormat advertised, int sampleBytes) {
        int channels = advertised.getChannels();
        if (channels != javax.sound.sampled.AudioSystem.NOT_SPECIFIED) {
            return channels > 0 ? List.of(channels) : List.of();
        }
        int frameSize = advertised.getFrameSize();
        if (frameSize == javax.sound.sampled.AudioSystem.NOT_SPECIFIED) {
            return List.of(2, 1);
        }
        if (frameSize <= 0 || frameSize % sampleBytes != 0) {
            return List.of();
        }
        int derivedChannels = frameSize / sampleBytes;
        return derivedChannels > 0 ? List.of(derivedChannels) : List.of();
    }

    private static boolean containsWildcard(javax.sound.sampled.AudioFormat format) {
        return format.getSampleRate() == javax.sound.sampled.AudioSystem.NOT_SPECIFIED
                || format.getSampleSizeInBits()
                        == javax.sound.sampled.AudioSystem.NOT_SPECIFIED
                || format.getChannels() == javax.sound.sampled.AudioSystem.NOT_SPECIFIED
                || format.getFrameSize() == javax.sound.sampled.AudioSystem.NOT_SPECIFIED
                || format.getFrameRate() == javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
    }

    /**
     * Candidate order is deliberate: the engine bus is float32, so a float
     * line is bit-exact and avoids quantisation. Signed PCM is the universal
     * fallback: the requested width is tried first, then every remaining
     * packed width supported by the codec, with 16 bit first among those
     * alternatives. Each width is tried little-endian first (the primary
     * Windows host) and big-endian second so the encoder is always pinned to
     * the exact selected layout. This is the same signed-width set accepted by
     * the availability probe.
     */
    private static List<javax.sound.sampled.AudioFormat> candidateFormats(AudioFormat format) {
        List<javax.sound.sampled.AudioFormat> candidates =
                new ArrayList<>(2 + SIGNED_PCM_WIDTHS.size() * 2);
        addCandidate(candidates, pcmFormat(
                Encoding.PCM_FLOAT, format, Float.SIZE, false));
        addCandidate(candidates, pcmFormat(
                Encoding.PCM_FLOAT, format, Float.SIZE, true));
        if (isIntegerWidth(format.bitDepth())) {
            addSignedPcmCandidates(candidates, format, format.bitDepth());
        }
        for (int bits : SIGNED_PCM_WIDTHS) {
            addSignedPcmCandidates(candidates, format, bits);
        }
        return List.copyOf(candidates);
    }

    private static void addSignedPcmCandidates(
            List<javax.sound.sampled.AudioFormat> candidates,
            AudioFormat format,
            int bits) {
        addCandidate(candidates, pcmFormat(Encoding.PCM_SIGNED, format, bits, false));
        addCandidate(candidates, pcmFormat(Encoding.PCM_SIGNED, format, bits, true));
    }

    private static void addCandidate(
            List<javax.sound.sampled.AudioFormat> candidates,
            javax.sound.sampled.AudioFormat candidate) {
        boolean duplicate = candidates.stream().anyMatch(existing -> sameFormat(existing, candidate));
        if (!duplicate) {
            candidates.add(candidate);
        }
    }

    private static javax.sound.sampled.AudioFormat pcmFormat(
            Encoding encoding, AudioFormat format, int bits, boolean bigEndian) {
        int bytesPerSample = bits / Byte.SIZE;
        return new javax.sound.sampled.AudioFormat(
                encoding,
                (float) format.sampleRate(),
                bits,
                format.channels(),
                format.channels() * bytesPerSample,
                (float) format.sampleRate(),
                bigEndian);
    }

    private static boolean sameFormat(
            javax.sound.sampled.AudioFormat left,
            javax.sound.sampled.AudioFormat right) {
        return left.getEncoding().equals(right.getEncoding())
                && Float.compare(left.getSampleRate(), right.getSampleRate()) == 0
                && left.getSampleSizeInBits() == right.getSampleSizeInBits()
                && left.getChannels() == right.getChannels()
                && left.getFrameSize() == right.getFrameSize()
                && Float.compare(left.getFrameRate(), right.getFrameRate()) == 0
                && left.isBigEndian() == right.isBigEndian();
    }

    private static boolean isIntegerWidth(int bits) {
        return bits >= Byte.SIZE && bits <= Integer.SIZE && bits % Byte.SIZE == 0;
    }

    private static boolean isSupportedPcmEncoding(Encoding encoding) {
        return Encoding.PCM_FLOAT.equals(encoding) || Encoding.PCM_SIGNED.equals(encoding);
    }

    private static boolean supportsSampleWidth(Encoding encoding, int bits) {
        return Encoding.PCM_FLOAT.equals(encoding)
                ? bits == Float.SIZE
                : Encoding.PCM_SIGNED.equals(encoding) && isIntegerWidth(bits);
    }

    private static boolean isSupportedPcmFormat(javax.sound.sampled.AudioFormat format) {
        if (format == null
                || format.getChannels() <= 0
                || !(format.getSampleRate() > 0.0f)) {
            return false;
        }
        int bits = format.getSampleSizeInBits();
        if (!supportsSampleWidth(format.getEncoding(), bits)) {
            return false;
        }
        int packedFrameSize = format.getChannels() * (bits / Byte.SIZE);
        return format.getFrameSize() == packedFrameSize;
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
     * rollback, and the output generation is never published, so neither
     * {@link #isOpen()} nor {@link #sink(AudioBlock)} treats the refused open
     * as live. That is the same "not open, not resumable, not yet released"
     * shape {@link #rollBackFailedOutputOpen(Exception)} and {@link #close()}
     * both document.</p>
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
    public synchronized void open(DeviceId device, AudioFormat format, int bufferFrames,
                                  CaptureRequirement capture) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(capture, "capture must not be null");
        AudioBackendException retained = releaseLines("refusing to open over it");
        if (retained != null) {
            throw retained;
        }
        support.markOpen(format, bufferFrames);
        Mixer.Info mixerInfo;
        OutputGeneration openedOutput;
        try {
            mixerInfo = resolveMixerInfo(device);
            javax.sound.sampled.AudioFormat requestedOutput = selectOutputFormat(
                    mixerInfo, format);
            this.outputLine = javaSound.sourceLine(mixerInfo, requestedOutput);
            // Double-buffer slack (see the method javadoc): line.write
            // blocking is this backend's pacing, so its own buffer is the
            // underrun headroom. Size from the selected concrete Java Sound
            // frame, never the SDK bit depth: PCM_FLOAT and signed PCM differ.
            int outputBufferBytes = Math.multiplyExact(
                    Math.multiplyExact(bufferFrames, requestedOutput.getFrameSize()), 2);
            this.outputLine.open(requestedOutput, outputBufferBytes);
            this.outputLine.start();
            javax.sound.sampled.AudioFormat actualOutput = requireActualFormat(
                    this.outputLine.getFormat(), requestedOutput, "output");
            // Build the line and its actual encoding into one immutable
            // generation, but publish it only after the whole open succeeds
            // below. REQUIRED capture can still refuse this open, so
            // publishing here would let a concurrent sink write a stream whose
            // open ultimately throws.
            openedOutput = new OutputGeneration(this.outputLine, actualOutput, format);
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
            javax.sound.sampled.AudioFormat requestedInput = selectInputFormat(
                    mixerInfo, format);
            this.inputLine = javaSound.targetLine(mixerInfo, requestedInput);
            int inputBufferBytes = Math.multiplyExact(
                    Math.multiplyExact(bufferFrames, requestedInput.getFrameSize()), 2);
            this.inputLine.open(requestedInput, inputBufferBytes);
            this.inputLine.start();
            this.inputLineFormat = requireActualFormat(
                    this.inputLine.getFormat(), requestedInput, "capture");
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
        // The complete stream is now accepted: capture either started or
        // degraded under OPTIONAL. This release-write safely publishes every
        // final field in the immutable generation to the lock-free sink path.
        this.outputGeneration = openedOutput;
    }

    private Mixer.Info resolveMixerInfo(DeviceId device) {
        if (!NAME.equals(device.backend())) {
            throw new IllegalArgumentException(
                    "device selection not supported on this backend: " + NAME
                            + " cannot open a device owned by '" + device.backend() + "'");
        }
        if (device.isDefault()) {
            return null;
        }
        if (!javaSound.supportsDeviceSelection()) {
            throw new UnsupportedOperationException(
                    "device selection not supported on this backend: " + NAME);
        }

        Mixer.Info[] mixers = javaSound.mixerInfos();
        List<Mixer.Info> matches = new ArrayList<>(1);
        // One persisted value can be mixer A's qualified label and mixer B's
        // literal bare name. Decide over the union so neither form silently
        // outranks the other.
        for (Mixer.Info mixer : mixers) {
            if (AudioDeviceInfo.isSelectionFor(
                    device.name(), mixer.getName(), NAME)) {
                matches.add(mixer);
            }
        }
        Mixer.Info match = requireUniqueMixer(device.name(), matches);
        if (match != null) {
            return match;
        }
        throw new IllegalArgumentException(
                "Java Sound device '" + device.name() + "' is not available;"
                        + " reconnect it or choose another device in Audio Settings");
    }

    /** Returns null only when no mixer matches the selection. */
    private static Mixer.Info requireUniqueMixer(
            String selection, List<Mixer.Info> matches) {
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Java Sound device '" + selection + "' is ambiguous: "
                            + matches.size() + " mixers answer to that name;"
                            + " refusing to open an endpoint the user may not have chosen");
        }
        return null;
    }

    private javax.sound.sampled.AudioFormat selectOutputFormat(
            Mixer.Info mixerInfo, AudioFormat format) {
        for (javax.sound.sampled.AudioFormat candidate : candidateFormats(format)) {
            if (javaSound.supportsSourceLine(mixerInfo, candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "selected mixer supports neither PCM_FLOAT nor signed PCM at "
                        + format.sampleRate() + " Hz / " + format.channels() + " channels");
    }

    private javax.sound.sampled.AudioFormat selectInputFormat(
            Mixer.Info mixerInfo, AudioFormat format) {
        for (javax.sound.sampled.AudioFormat candidate : candidateFormats(format)) {
            if (javaSound.supportsTargetLine(mixerInfo, candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "selected mixer exposes no PCM_FLOAT or signed-PCM capture line at "
                        + format.sampleRate() + " Hz / " + format.channels() + " channels");
    }

    private static javax.sound.sampled.AudioFormat requireActualFormat(
            javax.sound.sampled.AudioFormat actual,
            javax.sound.sampled.AudioFormat requested,
            String direction) {
        try {
            requirePackedFormat(actual, requested.getChannels(), direction);
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException(
                    "Java Sound " + direction + " line opened an unsupported actual format: "
                            + actual + " (requested " + requested + ")", unsupported);
        }
        if (Float.compare(actual.getSampleRate(), requested.getSampleRate()) != 0) {
            throw new IllegalStateException(
                    "Java Sound " + direction + " line changed the sample rate to "
                            + actual.getSampleRate() + " Hz (requested "
                            + requested.getSampleRate() + " Hz)");
        }
        return actual;
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
     * rollback directly with a planted line, independently of device and
     * format negotiation through the injectable {@link JavaSoundAccess}
     * boundary.</p>
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
     * <p>{@code support.markClosed()} runs on the REQUIRED path only, and the
     * local output generation is never published. The lifecycle-owned output
     * line is left open on purpose — see
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
            // The lifecycle-owned output line stays open for the caller's
            // close() to release, but it is no longer a writable stream
            // generation. A refused open must never leave isOpen() reporting
            // true or let sink() continue writing to that refused stream.
            this.outputGeneration = null;
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
     * {@link #rollBackFailedOutputOpen(Exception)}: a planted line can hold a
     * {@code read} on a test-controlled gate while this method starts exactly
     * the capture-thread state under test.</p>
     *
     * @param format       the opened format, for decoding the line's PCM
     * @param bufferFrames frames per block, sizing the read buffer
     */
    void startCapture(AudioFormat format, int bufferFrames) {
        final TargetDataLine line = this.inputLine;
        final javax.sound.sampled.AudioFormat lineFormat = this.inputLineFormat != null
                ? this.inputLineFormat
                : pcmFormat(Encoding.PCM_SIGNED, format, Short.SIZE, false);
        final int bytes = Math.multiplyExact(bufferFrames, lineFormat.getFrameSize());
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
                AudioBlock block = decode(buf, read, format, lineFormat);
                support.publishInput(stream, block);
            }
        }, "javax-sound-capture");
        t.setDaemon(true);
        this.captureThread = t;
        t.start();
    }

    static AudioBlock decodePcm16(byte[] pcm, int bytes, AudioFormat format) {
        return decode(pcm, bytes, format,
                pcmFormat(Encoding.PCM_SIGNED, format, Short.SIZE, false));
    }

    static AudioBlock decode(
            byte[] pcm,
            int bytes,
            AudioFormat format,
            javax.sound.sampled.AudioFormat lineFormat) {
        Objects.requireNonNull(pcm, "pcm must not be null");
        Objects.requireNonNull(format, "format must not be null");
        requirePackedFormat(lineFormat, format.channels(), "capture");
        if (bytes < 0 || bytes > pcm.length) {
            throw new IllegalArgumentException(
                    "bytes must be between 0 and the PCM array length: " + bytes);
        }

        int frameSize = lineFormat.getFrameSize();
        int frames = bytes / frameSize;
        int sampleBytes = lineFormat.getSampleSizeInBits() / Byte.SIZE;
        int samplesPerBlock = Math.multiplyExact(frames, format.channels());
        float[] samples = new float[samplesPerBlock];
        boolean bigEndian = lineFormat.isBigEndian();
        boolean floatingPoint = Encoding.PCM_FLOAT.equals(lineFormat.getEncoding());
        int bits = lineFormat.getSampleSizeInBits();
        double signedScale = 1L << (bits - 1);

        for (int sample = 0; sample < samplesPerBlock; sample++) {
            int offset = sample * sampleBytes;
            long raw = readUnsigned(pcm, offset, sampleBytes, bigEndian);
            if (floatingPoint) {
                samples[sample] = Float.intBitsToFloat((int) raw);
                continue;
            }
            long signBit = 1L << (bits - 1);
            long signed = (raw & signBit) == 0L ? raw : raw - (1L << bits);
            samples[sample] = (float) (signed / signedScale);
        }
        return new AudioBlock(format.sampleRate(), format.channels(), frames, samples);
    }

    private static long readUnsigned(
            byte[] source, int offset, int sampleBytes, boolean bigEndian) {
        long value = 0L;
        if (bigEndian) {
            for (int byteIndex = 0; byteIndex < sampleBytes; byteIndex++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(source[offset + byteIndex]);
            }
        } else {
            for (int byteIndex = sampleBytes - 1; byteIndex >= 0; byteIndex--) {
                value = (value << Byte.SIZE) | Byte.toUnsignedLong(source[offset + byteIndex]);
            }
        }
        return value;
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
        Objects.requireNonNull(block, "block must not be null");
        // One acquire-read pins both the line and its actual format to the
        // same open generation. Its SDK bus format supplies validation too,
        // so no separate support-state read can come from another generation.
        // Never re-read lifecycle fields around the blocking write: a
        // lifecycle transition can publish another generation between those
        // reads, and a torn old-line/new-format pair can put raw float bytes
        // into an integer device line. The engine separately quiesces its
        // render pump before release; this carrier guarantees coherence, not
        // a lifetime lease over a concurrently closing line.
        OutputGeneration current = this.outputGeneration;
        if (current == null) {
            return;
        }
        if (block.channels() != current.busFormat().channels()) {
            throw new IllegalArgumentException(
                    "block channels (" + block.channels()
                            + ") does not match opened channels ("
                            + current.busFormat().channels() + ")");
        }
        byte[] pcm = encode(block, current.actualFormat());
        current.line().write(pcm, 0, pcm.length);
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
            throw new IllegalArgumentException(
                    "JavaxSoundBackend only supports 16-bit output, got " + bitDepth);
        }
        AudioFormat sdkFormat = new AudioFormat(
                block.sampleRate(), block.channels(), Short.SIZE);
        return encode(block,
                pcmFormat(Encoding.PCM_SIGNED, sdkFormat, Short.SIZE, false));
    }

    static byte[] encode(
            AudioBlock block, javax.sound.sampled.AudioFormat lineFormat) {
        Objects.requireNonNull(block, "block must not be null");
        requirePackedFormat(lineFormat, block.channels(), "output");
        int sampleBytes = lineFormat.getSampleSizeInBits() / Byte.SIZE;
        float[] samples = block.samples();
        byte[] output = new byte[Math.multiplyExact(samples.length, sampleBytes)];
        boolean bigEndian = lineFormat.isBigEndian();

        if (Encoding.PCM_FLOAT.equals(lineFormat.getEncoding())) {
            for (int sample = 0; sample < samples.length; sample++) {
                writeRaw(output, sample * sampleBytes, sampleBytes, bigEndian,
                        Integer.toUnsignedLong(Float.floatToRawIntBits(samples[sample])));
            }
            return output;
        }

        int bits = lineFormat.getSampleSizeInBits();
        long peak = (1L << (bits - 1)) - 1L;
        for (int sample = 0; sample < samples.length; sample++) {
            float clamped = Math.clamp(samples[sample], -1.0f, 1.0f);
            long quantized = Math.clamp(Math.round(clamped * (double) peak), -peak, peak);
            writeRaw(output, sample * sampleBytes, sampleBytes, bigEndian, quantized);
        }
        return output;
    }

    private static void writeRaw(
            byte[] destination,
            int offset,
            int sampleBytes,
            boolean bigEndian,
            long value) {
        for (int byteIndex = 0; byteIndex < sampleBytes; byteIndex++) {
            int destinationIndex = bigEndian
                    ? offset + sampleBytes - 1 - byteIndex
                    : offset + byteIndex;
            destination[destinationIndex] = (byte) (value >>> (byteIndex * Byte.SIZE));
        }
    }

    private static void requirePackedFormat(
            javax.sound.sampled.AudioFormat lineFormat,
            int expectedChannels,
            String direction) {
        Objects.requireNonNull(lineFormat, "lineFormat must not be null");
        if (!isSupportedPcmFormat(lineFormat)
                || lineFormat.getChannels() != expectedChannels) {
            throw new IllegalArgumentException(
                    "unsupported Java Sound " + direction + " format: " + lineFormat);
        }
        int sampleBytes = lineFormat.getSampleSizeInBits() / Byte.SIZE;
        int packedFrameSize = Math.multiplyExact(expectedChannels, sampleBytes);
        if (lineFormat.getFrameSize() != packedFrameSize) {
            throw new IllegalArgumentException(
                    "Java Sound " + direction + " format uses padded frames: "
                            + lineFormat.getFrameSize() + " bytes, expected " + packedFrameSize);
        }
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
     * <p>The output-generation unpublish, {@code support.close()}, and the
     * capture-thread interrupt run before line release on every path, so
     * {@link #isOpen()} is {@code false} and {@link #sink(AudioBlock)} has no
     * published target even while a lifecycle handle is retained. That is
     * precisely the {@code RELEASE_PENDING} shape: not open, not resumable,
     * not yet released.</p>
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
     * @throws AudioBackendException if any retained capture or output line
     *                               could not be closed — the
     *                               un-released line(s) are named, the first
     *                               failure is the cause, and later failures
     *                               are attached as suppressed exceptions
     */
    @Override
    public synchronized void close() {
        // Unpublish before any teardown so later sinks immediately drop
        // without ever taking this monitor. The engine quiesces its render
        // pump before close; this is publication control, not an in-flight
        // write lease.
        this.outputGeneration = null;
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
     * Attempts to release BOTH stream directions and reports whether the
     * device is still held.
     *
     * <p>Both attempts always run: one handle's failure must never strand
     * another. A line that closed is dropped from its field; a line whose
     * {@link Line#close()} threw stays there, so the next attempt — the next
     * {@link #close()} or the guard in
     * {@link #open(DeviceId, AudioFormat, int)} — reaches the same handle
     * instead of leaking it. Doing nothing when both fields are already
     * null is what keeps {@link #close()} idempotent on a never-opened or
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
        List<String> heldLines = new ArrayList<>(2);
        List<RuntimeException> failures = new ArrayList<>(2);
        addReleaseFailure(heldLines, failures, "capture line", inputFailure);
        addReleaseFailure(heldLines, failures, "output line", outputFailure);
        RuntimeException first = failures.getFirst();
        AudioBackendException failure = new AudioBackendException(
                "Java Sound could not release its " + describeHeldLines(heldLines) + " (" + phase
                        + "); the device is still held", first);
        for (int index = 1; index < failures.size(); index++) {
            RuntimeException secondary = failures.get(index);
            if (secondary != first) {
                failure.addSuppressed(secondary);
            }
        }
        return failure;
    }

    private static void addReleaseFailure(
            List<String> heldLines,
            List<RuntimeException> failures,
            String heldLine,
            RuntimeException failure) {
        if (failure != null) {
            heldLines.add(heldLine);
            failures.add(failure);
        }
    }

    private static String describeHeldLines(List<String> heldLines) {
        return switch (heldLines.size()) {
            case 1 -> heldLines.getFirst();
            case 2 -> heldLines.getFirst() + " and " + heldLines.getLast();
            default -> String.join(", ", heldLines.subList(0, heldLines.size() - 1))
                    + " and " + heldLines.getLast();
        };
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
        this.inputLineFormat = null;
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
        // This volatile write is the sink-path teardown. Lifecycle ownership
        // remains in outputLine until close() actually returns, so a failed
        // release is still retained and honestly reported.
        this.outputGeneration = null;
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
