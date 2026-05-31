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
        if (transport.getState() == TransportState.PLAYING) {
            return; // VALIDATE: already playing — no-op
        }
        transport.play();
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

    /** Converts a beat position to a non-negative sample-frame position at the current tempo. */
    private long beatsToFrames(double beats) {
        double secondsPerBeat = 60.0 / transport.getTempo();
        return Math.max(0L, Math.round(beats * secondsPerBeat * sampleRate));
    }
}
