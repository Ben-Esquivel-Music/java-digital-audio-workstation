package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
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
 * callback never blocks), stamps the header — the transport's beat position,
 * the start frame derived from it, the punch snapshot from one load of the
 * transport's punch region, the loop flag — copies the device block and each
 * armed graph-instrument buffer (only valid inside this callback), publishes
 * if its final gate read observes open, wakes the flush thread, and leaves
 * the gate. No
 * allocation, no locks, no string, no clock, no collection iteration beyond
 * the preallocated instrument-track array. Routing, gating, wrap detection
 * and every file write happen on the flush thread from the header.</p>
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
 * See {@link CaptureRing} for the bounded wait and the gate argument.</p>
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
        CaptureRing.Slot slot = ring.claim();
        if (slot == null) {
            return false;
        }

        // The recording callback fires *before* advancePosition(), so
        // getPositionInBeats() still reflects this block's start.
        double beat = transport.getPositionInBeats();
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
        slot.setLoopEnabled(transport.isLoopEnabled());

        slot.setNumFrames(numFrames);
        int excess = numFrames - slot.slotFrames();
        if (excess > 0) {
            slot.setTruncatedFrames(excess);
            ring.noteTruncatedFrames(excess);
        }
        slot.copySource(0, inputBuffer, inputBuffer.length, numFrames);
        // Never address a ring source the slot does not have.
        int instrumentSources = Math.min(instrumentTracks.length, slot.sourceCount() - 1);
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

    private long startFrameOf(double beat) {
        double seconds = beat * 60.0 / tempoBpm;
        return Math.round(seconds * sampleRate);
    }

    private long captureFrameOf(long projectFrame) {
        return punchFrameScale == 1.0 ? projectFrame : Math.round(projectFrame * punchFrameScale);
    }
}
