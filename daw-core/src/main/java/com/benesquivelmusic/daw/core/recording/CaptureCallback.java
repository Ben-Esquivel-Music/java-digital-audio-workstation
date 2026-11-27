package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.CountInSchedule;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import java.util.Objects;

/**
 * The recording callback of one take — audio thread (Recording Reliability
 * book §2.2, §5.1). {@link RecordingPipeline#beginCapture()} creates one per
 * take and installs it on the engine; it does exactly one thing per block:
 * hands the block to the take's {@link CaptureRing}.
 *
 * <p>Everything it reads is its own: the ring, the flush service, the
 * instrument-track array, the transport, the engine, the sample rates and the
 * tempo are {@code final} fields set on the caller thread before the
 * callback is installed, so the audio thread reads no field of the pipeline,
 * and a callback left over from an earlier take can only ever reach that
 * take's ring. Per block it enters the ring's producer gate, claims a slot (a
 * {@code null} claim is a counted overflow and an immediate return — the
 * callback never blocks; the claim says what the block carries, delivered
 * input, input-starved silence or no input, with or without a recorded
 * instrument's audio, so a block the full ring drops is counted by kind:
 * story 338, rulings E273, E277, E281 and E287), stamps
 * the header — the transport's beat position,
 * the start frame derived from it, the punch snapshot from one load of the
 * transport's punch region, the loop flag, and for the stream's own input the
 * engine's input-starved flag (the render had no captured block for it), or
 * for a sibling input the output clock drives the transport frame of the
 * render block that clocked it, and on the render thread the transport's
 * lap — copies the device block and each armed graph-instrument buffer (only
 * valid inside this callback), publishes if its final gate read observes
 * open, wakes the flush thread, and leaves the gate. No
 * allocation, no locks, no string, no clock, no collection iteration beyond
 * the preallocated instrument-track array. Routing, gating, wrap detection
 * and every file write happen on the flush thread from the header.</p>
 *
 * <p><strong>Activation.</strong> All pipeline callbacks share a closed start
 * gate until Record and prepared-input activation succeed. Earlier calls
 * leave the producer gate without claiming or advancing their frame cursor.
 * The caller freezes one beat/seek-sequence origin before publishing the
 * volatile activation. Each source starts from it; an explicit subsequent
 * seek supersedes it once the primary clock has committed the target. Sibling
 * inputs re-anchor to that seek's immutable target and then count delivered
 * frames independently, even if the primary clock has already advanced further.
 * Counting frames is sound only because {@code CaptureRoutingPlan} admits a
 * sibling device solely when the backend reports it shares the primary input's
 * hardware clock; unsynchronized devices are refused before the take, since
 * nothing here corrects drift between clock domains.
 * The primary input continues to follow the transport clock.</p>
 *
 * <p><strong>Transport lap.</strong> Only the render thread knows which lap
 * of the loop the transport is in, so the blocks it runs — every block of
 * the stream's own input, and a sibling input's zero-width block the output
 * clock delivers — carry the take's count of the transport's wraps
 * ({@link CaptureRing.Slot#transportLap()}): one take-wide counter, shared
 * through the start gate and written only on that thread, which counts a lap
 * each time the transport's beat goes back while the loop is enabled. While
 * the start gate is still the take's origin, the count starts from the gate's
 * beat, where the rings start, whichever callback runs the take's first such
 * block, so a wrap between the gate's opening and that block — Record pressed
 * while the transport rolls — is counted on it, as the flush thread counts
 * it. It is counted before the
 * ring is asked for a slot, so a wrap in a block the full ring drops is still
 * counted. Of the callbacks one render period runs, the
 * first counts the wrap and the others see the same beat. A block a sibling
 * input delivers on its own publisher's thread leaves the counter alone and
 * carries {@link CaptureRing#NO_TRANSPORT_LAP}. Known limit: a seek backwards
 * while the loop is enabled counts as a wrap, as it does for the flush
 * thread's lanes. Known limit: when a seek is accepted between two callbacks
 * of the take's first render period, the later callback's ring starts at the
 * seek's target while the count started from the start gate's beat, so a wrap
 * between the gate's opening and that period is in every lap stamp of the
 * take but not in that input's own laps (ruling E198).</p>
 *
 * <p><strong>Start frame.</strong> The header's start frame is the beat
 * position converted at the tempo the take was prepared at — the tempo its
 * manifest's anchor frame was computed with — not the transport's tempo of
 * the moment: reading that on the audio thread would read the tempo map's
 * list while another thread may be changing it.</p>
 *
 * <p><strong>Punch frames.</strong> The transport's punch bounds use the
 * project's sample rate. Each block's snapshot converts them to the take's
 * sample rate with a ratio computed before this callback is installed and
 * rounds to the nearest capture frame, matching the anchor conversion.
 * Bounds at equal rates are copied unchanged.</p>
 *
 * <p><strong>Block length.</strong> The engine delivers {@code numFrames}
 * frames per call; the ring's slots hold {@link CaptureRing#slotFrames()}
 * frames, the block size of the format the pipeline was constructed with.
 * Nothing guarantees {@code numFrames <= slotFrames} — the delivered block's
 * size is the engine's, not the pipeline's — so a longer block is not an
 * error here: its first {@code slotFrames} frames are captured, and the
 * excess is stamped on the slot and counted on the ring
 * ({@link CaptureRing#noteTruncatedFrames(int)}) for the flush thread to
 * record in the manifest and report. The ring's count includes the excess
 * of a block the stop then dropped. A shorter block is captured as
 * delivered.</p>
 *
 * <p><strong>Stop.</strong> The thread that ends the take closes the ring's
 * producer gate before it removes this callback and before it moves the
 * transport ({@link RecordingPipeline#requestStop()}). A call that enters
 * after that claims nothing. A call already inside drops its whole block
 * if its final {@link CaptureRing#producerOpen()} read observes the close.
 * If that read observed the gate open, it may publish after the close; its
 * header was stamped before that read, before the stop moved the playhead.
 * The flush thread waits for producer quiescence before its final drain,
 * so a successful wait includes that late publication in the sealed take.
 * See {@link CaptureRing} for the bounded wait and the gate argument. On a
 * device loss the gate is closed earlier, by the engine's
 * {@link AudioEngine#haltRecordingCapture()} on the thread that declares the
 * loss ({@link #onCaptureHalted()}), before the loss is published: for a
 * removal or a stall also before the lost stream is closed and so before any
 * stream can be reopened; for a driver reset whose reopen failed, after the
 * reset had closed it. The pipeline's stop then closes the gate again.</p>
 */
final class CaptureCallback implements AudioEngine.RecordingCallback {

    private final CaptureRing ring;
    private final CaptureFlushService flush;
    /** Armed tracks recording their graph instrument; ring source {@code i + 1}. */
    private final Track[] instrumentTracks;
    private final Transport transport;
    private final AudioEngine audioEngine;
    private final double sampleRate;
    private final double punchFrameScale;
    private final double tempoBpm;
    private final StartGate startGate;
    /**
     * The count-in whose record start opens {@link #startGate}, or
     * {@code null} when capture began at once (story 328). Read only by
     * {@link #onCaptureHalted()}, never on the audio thread.
     */
    private final CountInSchedule countIn;
    /** The take's transport laps: the start gate's, or this callback's own when it has no gate and so no sibling. */
    private final TransportLaps transportLaps;
    /** Ring sources {@code 1..instrumentSources} the callback fills: never one the ring's slots do not have. */
    private final int instrumentSources;

    /** Caller initializes the common origin before publishing the volatile activation. */
    static final class StartGate {
        double beat;
        Transport.PositionSeek positionSeek;
        boolean seekPending;
        volatile boolean active;
        /** Shared by the stream's callback and every sibling input's. */
        final TransportLaps transportLaps = new TransportLaps();
    }

    /**
     * The take's count of the transport's wraps and the transport beat it
     * last saw. Read and written only on the render thread, so plain fields.
     */
    static final class TransportLaps {
        /** Whether a block of the take was counted; until then {@link #lastBeat} holds nothing. */
        boolean counting;
        double lastBeat;
        long count;
    }

    private boolean independentFrameCursor;
    private long nextInputFrame;
    private boolean frameCursorInitialized;
    private Transport.PositionSeek observedPositionSeek;
    void useIndependentFrameCursor() {
        independentFrameCursor = true;
    }

    /**
     * Creates the callback of one take. Caller thread, before the callback
     * is installed.
     *
     * @param ring             the take's capture ring
     * @param flush            the take's flush service, woken after each publish
     * @param instrumentTracks the armed tracks recording their graph instrument, in ring-source order
     * @param transport        the transport whose position, punch region and loop flag are stamped
     * @param audioEngine      the engine the graph-instrument buffers are looked up on
     * @param sampleRate       the take's sample rate; positive
     * @param projectSampleRate the rate of the transport's punch frames; finite and positive
     * @param tempoBpm         the tempo the take was prepared at; positive
     */
    CaptureCallback(CaptureRing ring, CaptureFlushService flush, Track[] instrumentTracks,
                    Transport transport, AudioEngine audioEngine, double sampleRate,
                    double projectSampleRate, double tempoBpm) {
        this(ring, flush, instrumentTracks, transport, audioEngine, sampleRate,
                projectSampleRate, tempoBpm, null);
    }

    CaptureCallback(CaptureRing ring, CaptureFlushService flush, Track[] instrumentTracks,
                    Transport transport, AudioEngine audioEngine, double sampleRate,
                    double projectSampleRate, double tempoBpm, StartGate startGate) {
        this(ring, flush, instrumentTracks, transport, audioEngine, sampleRate,
                projectSampleRate, tempoBpm, startGate, null);
    }

    /**
     * The callback of a take whose capture start gate a count-in's record
     * start opens (story 328): the engine's capture halt reaches the
     * schedule through {@link #onCaptureHalted()}, so a halt between the
     * schedule's installation and its firing cancels it and the record start
     * never runs on a halted take.
     *
     * @param countIn the take's count-in, or {@code null} when capture begins at once
     */
    CaptureCallback(CaptureRing ring, CaptureFlushService flush, Track[] instrumentTracks,
                    Transport transport, AudioEngine audioEngine, double sampleRate,
                    double projectSampleRate, double tempoBpm, StartGate startGate, CountInSchedule countIn) {
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
        this.flush = Objects.requireNonNull(flush, "flush must not be null");
        this.instrumentTracks = Objects.requireNonNull(instrumentTracks, "instrumentTracks must not be null").clone();
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        if (!(sampleRate > 0)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (!(tempoBpm > 0)) {
            throw new IllegalArgumentException("tempoBpm must be positive: " + tempoBpm);
        }
        if (!(projectSampleRate > 0) || !Double.isFinite(projectSampleRate)) {
            throw new IllegalArgumentException("projectSampleRate must be finite and positive: " + projectSampleRate);
        }
        this.sampleRate = sampleRate;
        this.punchFrameScale = sampleRate / projectSampleRate;
        this.tempoBpm = tempoBpm;
        this.startGate = startGate;
        this.countIn = countIn;
        this.transportLaps = startGate != null ? startGate.transportLaps : new TransportLaps();
        this.instrumentSources = Math.min(this.instrumentTracks.length, ring.sourceCount() - 1);
    }

    /**
     * Hands one delivered block to the ring: {@link #fillBlock} then
     * {@link #publishAndLeave}, which runs whatever the fill did, so the
     * producer gate is left on every path.
     */
    @RealTimeSafe
    @Override
    public void onAudioCaptured(float[][] inputBuffer, int numFrames) {
        boolean filled = false;
        try {
            filled = fillBlock(inputBuffer, numFrames);
        } finally {
            publishAndLeave(filled);
        }
    }

    /**
     * The engine removed this callback through
     * {@link AudioEngine#haltRecordingCapture()} — the device-loss path, on
     * the thread that declares the loss (story 327, PR #982 review): closes
     * the ring's producer gate, one volatile store, so a later call of this
     * callback — a pump a reopened stream starts before the pipeline's own
     * stop has run on the FX thread — claims nothing, and a call already
     * inside drops its block if its final gate read observes the close. Not
     * the audio thread's, and not reachable from {@link #onAudioCaptured}: it
     * carries no {@code @RealTimeSafe}. The pipeline's stop closes the same
     * gate again, harmlessly.
     *
     * <p>A take still counting in (story 328) has its count-in cancelled
     * first, so the record start never runs on the halted take — unless the
     * render thread had already passed the schedule's cancel check, in which
     * case the transport goes to recording over a closed producer gate and
     * the loss's own handling stops the take through the ordinary path.</p>
     */
    @Override
    public void onCaptureHalted() {
        CountInSchedule schedule = countIn;
        if (schedule != null) {
            schedule.cancel();
        }
        ring.closeProducer();
    }

    /**
     * The first half of a callback: enters the producer gate and, if it is
     * open and the ring has a free slot, stamps the slot's header and copies
     * the block into it. The header is stamped before the copies, so it
     * carries the transport as it was when the block began. Every call is
     * followed by one {@link #publishAndLeave(boolean)}.
     *
     * @return whether a slot was claimed and filled
     */
    @RealTimeSafe
    boolean fillBlock(float[][] inputBuffer, int numFrames) {
        if (!ring.enterProducer()) {
            return false;
        }
        if (startGate != null && !startGate.active) {
            return false;
        }
        Transport.PositionSeek positionSeek = transport.getPositionSeek();
        boolean seekPending = independentFrameCursor && transport.hasPendingPositionSeek();
        boolean firstBlock = !frameCursorInitialized;
        boolean gateIsTheOrigin = startGate != null
                && !startGate.seekPending
                && startGate.positionSeek == positionSeek;
        boolean useStartOrigin = firstBlock && gateIsTheOrigin;
        if (firstBlock) {
            nextInputFrame = startFrameOf(useStartOrigin ? startGate.beat : transport.getPositionInBeats());
            frameCursorInitialized = true;
            if (!seekPending && (useStartOrigin || startGate == null)) {
                observedPositionSeek = positionSeek;
            }
        }
        if (independentFrameCursor && !seekPending && observedPositionSeek != positionSeek) {
            nextInputFrame = startFrameOf(positionSeek.targetInBeats());
            observedPositionSeek = positionSeek;
        }
        Transport.LoopWindow loop = transport.getLoopWindow();
        boolean onRenderThread = !independentFrameCursor || inputBuffer.length == 0;
        // The recording callback fires *before* advancePosition(), so
        // getPositionInBeats() still reflects this block's start.
        double transportBeat = onRenderThread ? transport.getPositionInBeats() : 0.0;
        long transportLap = onRenderThread ? transportLapAt(transportBeat, loop.enabled(), gateIsTheOrigin)
                : CaptureRing.NO_TRANSPORT_LAP;
        // The engine sets the flag around the block this callback runs inside, on the render thread; an input
        // beside the stream delivers on its own publisher's thread, which never reads it.
        boolean starved = !independentFrameCursor && audioEngine.isCapturedInputStarved();
        int carried = (inputBuffer.length == 0 ? 0 : starved ? CaptureRing.STARVED : CaptureRing.DELIVERED)
                | (instrumentAudioPresent() ? CaptureRing.INSTRUMENT : 0);
        CaptureRing.Slot slot = ring.claim(carried);
        if (slot == null) {
            if (independentFrameCursor) nextInputFrame += numFrames;
            return false;
        }

        double beat = independentFrameCursor ? nextInputFrame / sampleRate * tempoBpm / 60.0
                : useStartOrigin ? startGate.beat : transportBeat;
        if (independentFrameCursor) nextInputFrame += numFrames;
        if (independentFrameCursor && loop.enabled() && beat >= loop.endInBeats()) {
            double length = loop.endInBeats() - loop.startInBeats();
            beat = loop.startInBeats() + (beat - loop.startInBeats()) % length;
        }
        slot.setBeatPosition(beat);
        slot.setStartFrame(startFrameOf(beat));
        // One load: the enabled flag and the frames come from the same region.
        PunchRegion punch = transport.getPunchRegion();
        if (punch != null && punch.enabled()) {
            slot.setPunchEnabled(true);
            slot.setPunchStartFrames(captureFrameOf(punch.startFrames()));
            slot.setPunchEndFrames(captureFrameOf(punch.endFrames()));
        } else {
            slot.setPunchEnabled(false);
        }
        slot.setLoopEnabled(loop.enabled());
        if (loop.enabled() && loop.endInBeats() > loop.startInBeats()) {
            slot.setLoopStartFrame(startFrameOf(loop.startInBeats()));
            slot.setLoopEndFrame(startFrameOf(loop.endInBeats()));
        }
        if (!independentFrameCursor) {
            slot.setInputStarved(starved);
        } else if (inputBuffer.length == 0) {
            // Only the output clock delivers a zero-width sibling block, on
            // the render thread, before the transport advances past it.
            slot.setOutputClockFrame(startFrameOf(transportBeat));
        }
        slot.setTransportLap(transportLap);

        slot.setNumFrames(numFrames);
        int excess = numFrames - slot.slotFrames();
        if (excess > 0) {
            slot.setTruncatedFrames(excess);
            ring.noteTruncatedFrames(excess);
        }
        slot.copySource(0, inputBuffer, inputBuffer.length, numFrames);
        for (int i = 0; i < instrumentSources; i++) {
            float[][] instrument = audioEngine.graphInstrumentRecordingBuffer(instrumentTracks[i]);
            if (instrument == null) {
                slot.clearSource(i + 1);
            } else {
                slot.copySource(i + 1, instrument, instrument.length, numFrames);
            }
        }
        return true;
    }

    /**
     * The second half of a callback: publishes the filled slot and wakes the
     * flush thread if its final gate read observes open, then leaves
     * the gate. A slot filled by a callback that finds the gate closed here
     * stays claimed and unpublished.
     *
     * @param filled what {@link #fillBlock} returned
     */
    @RealTimeSafe
    void publishAndLeave(boolean filled) {
        if (filled && ring.producerOpen()) {
            ring.publish();
            flush.signal();
        }
        ring.exitProducer();
    }

    /**
     * Whether a recorded instrument of this ring renders audio in the block the render thread runs now: what
     * makes a block with no channel of input more than the silence after its input's declared stop (ruling E277).
     */
    @RealTimeSafe
    private boolean instrumentAudioPresent() {
        for (int i = 0; i < instrumentSources; i++) {
            float[][] instrument = audioEngine.graphInstrumentRecordingBuffer(instrumentTracks[i]);
            if (instrument != null && instrument.length > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The transport's lap at a block the render thread runs: one lap more
     * than at the take's previous such block when the transport's beat went
     * back while the loop is enabled (see the class note). The take's first
     * such block is compared with the start gate's beat while no seek has
     * superseded that origin, whichever callback runs it. Render thread.
     */
    private long transportLapAt(double transportBeat, boolean loopEnabled, boolean fromTheStartOrigin) {
        TransportLaps laps = transportLaps;
        if (!laps.counting) {
            // The rings start at the gate's beat, so the flush thread counts a wrap between it and this block.
            laps.lastBeat = fromTheStartOrigin ? startGate.beat : transportBeat;
            laps.counting = true;
        }
        if (loopEnabled && transportBeat < laps.lastBeat) {
            laps.count = laps.count + 1;
        }
        laps.lastBeat = transportBeat;
        return laps.count;
    }

    private long startFrameOf(double beat) {
        double seconds = beat * 60.0 / tempoBpm;
        return Math.round(seconds * sampleRate);
    }

    private long captureFrameOf(long projectFrame) {
        return punchFrameScale == 1.0 ? projectFrame : Math.round(projectFrame * punchFrameScale);
    }
}
