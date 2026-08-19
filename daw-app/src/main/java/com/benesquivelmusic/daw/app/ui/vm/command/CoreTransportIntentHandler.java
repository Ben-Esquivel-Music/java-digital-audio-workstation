package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * The production {@link TransportIntentHandler}: it wraps the {@link Transport}
 * mutation path and runs the universal cascade for each transport intent
 * (Control Synchronization Design Book §5.1, §5.2).
 *
 * <p>For every intent the handler runs, in order:</p>
 * <ol>
 *   <li><strong>VALIDATE</strong> — reject out-of-range tempo / no-op state
 *       transitions before mutating.</li>
 *   <li><strong>MUTATE</strong> — call the {@code Transport} method. That fires
 *       the toolkit-neutral change signal, so {@code TransportVM} re-reads and
 *       republishes its properties (the REPUBLISH phase happens automatically via
 *       the signal + dispatcher; this handler does not touch any
 *       {@code Property}).</li>
 *   <li><strong>ANNOUNCE</strong> — publish the existing typed
 *       {@link TransportEvent} on the bus via {@link EventBusPublisher} (live
 *       since story 283) so unrelated surfaces react.</li>
 * </ol>
 *
 * <p>The PROJECT phase ({@code ProjectVM.dirty}) is story 292's concern and is
 * skipped here (§5.1 permits skipping a phase). Tempo capture for undo is left to
 * the existing {@code TransportController} path that this handler is wired behind;
 * this class is the neutral seam the commands target.</p>
 */
public final class CoreTransportIntentHandler implements TransportIntentHandler {

    private static final double MIN_TEMPO_BPM = 20.0;
    private static final double MAX_TEMPO_BPM = 999.0;

    private final Transport transport;
    private final double sampleRate;

    /**
     * Creates a handler bound to {@code transport}.
     *
     * @param transport  the authoritative transport to mutate; must not be {@code null}
     * @param sampleRate the sample rate in Hz used to convert beat positions to
     *                   the sample-frame positions carried by {@link TransportEvent}
     *                   (must be positive)
     * @throws NullPointerException     if {@code transport} is {@code null}
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     */
    public CoreTransportIntentHandler(Transport transport, double sampleRate) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        if (sampleRate <= 0.0 || !Double.isFinite(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be positive and finite: " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    @Override
    public void start() {
        TransportState state = transport.getState();
        // VALIDATE: no-op when already playing, and also when RECORDING — Stop is
        // the only way out of record (mirrors TransportController.onPlay). Without
        // the RECORDING guard, transport.play() (which unconditionally sets PLAYING)
        // would silently drop out of record and still announce Started.
        if (state == TransportState.PLAYING || state == TransportState.RECORDING) {
            return;
        }
        transport.play();
        EventBusPublisher.publish(new TransportEvent.Started(positionFrames(), Instant.now()));
    }

    @Override
    public void pause() {
        TransportState state = transport.getState();
        // VALIDATE: pausing is only meaningful from PLAYING or RECORDING — a
        // stale intent (the binder reads the async VM mirror, which can lag the
        // authoritative state by one frame) is absorbed here.
        if (state != TransportState.PLAYING && state != TransportState.RECORDING) {
            return;
        }
        transport.pause();
        // ANNOUNCE skipped: the story-283 TransportEvent vocabulary has no
        // Paused event, and §5.1 permits skipping a phase. Do not invent one —
        // pause is a UI-local fact until a consumer needs it on the bus.
    }

    /**
     * Resolves the Play gesture from the AUTHORITATIVE transport state (story
     * 315 review): PLAYING pauses, anything else starts. RECORDING therefore
     * maps to {@link #start()}, whose VALIDATE rejects it — Stop stays the only
     * way out of record. Resolving here rather than at the gesture layer is the
     * whole point of {@link TogglePlayPauseCommand}: the binder's
     * {@code TransportVM} mirror is a frame behind this read.
     */
    @Override
    public void togglePlayPause() {
        if (transport.getState() == TransportState.PLAYING) {
            pause();
        } else {
            start();
        }
    }

    /**
     * Starts playback with the configured pre-roll applied (Story 134; story
     * 315 review). No VALIDATE gate: {@code Transport.playWithPreRoll()} always
     * transitions to PLAYING — re-anchoring and rewinding when a pre-roll is
     * configured — so the announce is unconditional and carries the
     * <em>post-rewind</em> position, i.e. where playback actually begins
     * (parity with the production controller's path).
     */
    @Override
    public void playWithPreRoll() {
        transport.playWithPreRoll();
        EventBusPublisher.publish(new TransportEvent.Started(positionFrames(), Instant.now()));
    }

    @Override
    public void stop() {
        TransportState before = transport.getState();
        if (before == TransportState.STOPPED) {
            return; // VALIDATE: already stopped — no-op
        }
        long stoppedAt = positionFrames();
        transport.stop();
        EventBusPublisher.publish(new TransportEvent.Stopped(stoppedAt, Instant.now()));
    }

    @Override
    public void skipBack() {
        // Absolute seek to zero: queued while the RT clock owns the transport,
        // immediate otherwise (story 315). No seek base is read at all.
        transport.setPositionInBeats(0.0);
        // ANNOUNCE skipped: TransportEvent.Seeked exists in the story-283
        // vocabulary but has NO production publisher today, and §5.1 permits
        // skipping a phase. Do not invent bus traffic no consumer reads —
        // starting to publish Seeked is a deliberate decision for the story
        // that gives it a consumer.
    }

    /**
     * Skips forward by four bars. Snap-to-grid is deliberately absent here:
     * snap is a UI-layer dependency (grid resolution, snap toggle) owned by the
     * production controller's override — this neutral cascade knows nothing of
     * the view-navigation state.
     */
    @Override
    public void skipForward() {
        // RELATIVE seek — compose against the pending seek target, not the
        // committed position (story 315 review). While the RT clock owns the
        // transport a seek sits in a single-slot, last-writer-wins queue and
        // the committed position does not move until the next block boundary;
        // getSeekTargetInBeats() returns the queued target when one is pending,
        // so successive relative seeks accumulate instead of collapsing onto
        // the same base. ANNOUNCE skipped for the same reason as skipBack().
        transport.setPositionInBeats(
                transport.getSeekTargetInBeats() + 4.0 * transport.getTimeSignatureNumerator());
    }

    @Override
    public void toggleRecord() {
        if (transport.getState() == TransportState.RECORDING) {
            long stoppedAt = positionFrames();
            transport.stop();
            EventBusPublisher.publish(new TransportEvent.Stopped(stoppedAt, Instant.now()));
        } else {
            transport.record();
            EventBusPublisher.publish(new TransportEvent.Started(positionFrames(), Instant.now()));
        }
    }

    @Override
    public void setTempo(double bpm) {
        if (Double.isNaN(bpm) || bpm < MIN_TEMPO_BPM || bpm > MAX_TEMPO_BPM) {
            throw new IllegalArgumentException(
                    "tempo must be between " + MIN_TEMPO_BPM + " and " + MAX_TEMPO_BPM + " BPM: " + bpm);
        }
        double previousBpm = transport.getTempo();
        transport.setTempo(bpm);
        EventBusPublisher.publish(new TransportEvent.TempoChanged(previousBpm, bpm, Instant.now()));
    }

    @Override
    public void toggleLoop() {
        boolean nowEnabled = !transport.isLoopEnabled();
        transport.setLoopEnabled(nowEnabled);
        EventBusPublisher.publish(new TransportEvent.LoopChanged(
                nowEnabled,
                beatsToFrames(transport.getLoopStartInBeats()),
                beatsToFrames(transport.getLoopEndInBeats()),
                Instant.now()));
    }

    private long positionFrames() {
        return beatsToFrames(transport.getPositionInBeats());
    }

    /**
     * Converts a beat position to a non-negative sample-frame position at this
     * handler's sample rate and the transport's current tempo — the <em>single</em>
     * conversion behind every {@link TransportEvent} frame field (story 315
     * review; {@code TransportController} held a verbatim copy plus its own
     * {@code sampleRate} field, so a future correction — e.g. honouring the
     * {@code TempoMap} instead of the initial tempo, which this still does not —
     * would have had to be made twice).
     *
     * @param beats the beat position to convert
     * @return the equivalent sample-frame position, never negative
     */
    public long beatsToFrames(double beats) {
        double secondsPerBeat = 60.0 / transport.getTempo();
        return Math.max(0L, Math.round(beats * secondsPerBeat * sampleRate));
    }
}
