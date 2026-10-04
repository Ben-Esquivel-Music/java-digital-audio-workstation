package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.recording.CapturePeakSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The live peaks of one audio take while it is being captured (Recording
 * Reliability book §4.5, §6.1): one {@link FxDispatcher} continuous channel
 * per armed audio track, and the latest {@link CapturePeakSnapshot} each
 * channel delivered.
 *
 * <p>The take's {@code capture-flush} thread hands every snapshot to
 * {@link #publish(CapturePeakSnapshot)}, which only puts it in the channel of
 * the snapshot's track — a depth-1, latest-wins mailbox. The dispatcher's
 * pulse then delivers the newest snapshot of each track on the FX thread,
 * where it replaces the one held before; nothing is delivered between
 * pulses. A snapshot carries its lane index, so a reader tells the laps of a
 * loop-record take apart: the snapshot held for a track is always the newest
 * one its channel delivered, whichever lane that is.</p>
 *
 * <p>This holder draws nothing. It is the feed a live capture waveform reads
 * ({@link #latest(String)}) on the FX thread.</p>
 *
 * <p>{@link #close()} closes every channel — an open channel is drained on
 * every pulse of the dispatcher for as long as the dispatcher lives — and
 * forgets every snapshot, so the peaks of a take that has ended are never
 * read as live.</p>
 */
public final class LiveCapturePeaks {

    /** The channel of each armed audio track, by track id. Never changed after construction. */
    private final Map<String, FxDispatcher.ContinuousChannel<CapturePeakSnapshot>> channels;

    /** The newest snapshot each channel delivered, by track id. FX thread. */
    private final Map<String, CapturePeakSnapshot> latest = new HashMap<>();

    /** Set by {@link #close()}. FX thread. */
    private boolean closed;

    private LiveCapturePeaks(FxDispatcher dispatcher, Collection<String> trackIds) {
        Map<String, FxDispatcher.ContinuousChannel<CapturePeakSnapshot>> opened = new LinkedHashMap<>();
        for (String trackId : trackIds) {
            opened.computeIfAbsent(trackId, id -> dispatcher.openContinuous(this::hold));
        }
        this.channels = Map.copyOf(opened);
    }

    /**
     * Opens one continuous channel on {@code dispatcher} for each distinct
     * id of {@code trackIds}. Every id is checked before the first channel
     * is opened, so a refused call leaves no channel open. FX thread.
     *
     * @param dispatcher the dispatcher whose pulse delivers the snapshots
     * @param trackIds   the ids of the take's armed audio tracks
     * @return the holder, whose channels stay open until {@link #close()}
     * @throws NullPointerException if an argument or one of the ids is {@code null}
     */
    public static LiveCapturePeaks open(FxDispatcher dispatcher, Collection<String> trackIds) {
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        Objects.requireNonNull(trackIds, "trackIds must not be null");
        List<String> ids = new ArrayList<>(trackIds);
        for (String trackId : ids) {
            Objects.requireNonNull(trackId, "trackId must not be null");
        }
        return new LiveCapturePeaks(dispatcher, ids);
    }

    /**
     * Puts {@code snapshot} in the channel of its track; a snapshot of a
     * track that has no channel is dropped. Called on the take's
     * {@code capture-flush} thread: it publishes and does nothing else.
     *
     * @param snapshot the peaks of one lane of one armed track
     */
    public void publish(CapturePeakSnapshot snapshot) {
        FxDispatcher.ContinuousChannel<CapturePeakSnapshot> channel = channels.get(snapshot.trackId());
        if (channel != null) {
            channel.publish(snapshot);
        }
    }

    /** The consumer of every channel: runs inside the dispatcher's pulse, on the FX thread. */
    private void hold(CapturePeakSnapshot snapshot) {
        if (!closed) {
            latest.put(snapshot.trackId(), snapshot);
        }
    }

    /**
     * The newest snapshot delivered for {@code trackId}. FX thread.
     *
     * @param trackId the id of an armed audio track
     * @return the snapshot, or empty before the first one was delivered,
     *         for a track that has no channel, and once {@link #close()} has run
     */
    public Optional<CapturePeakSnapshot> latest(String trackId) {
        return Optional.ofNullable(latest.get(trackId));
    }

    /**
     * Closes every channel and forgets every snapshot. A snapshot published
     * afterwards is never delivered. Idempotent. FX thread.
     */
    public void close() {
        closed = true;
        channels.values().forEach(FxDispatcher.ContinuousChannel::close);
        latest.clear();
    }
}
