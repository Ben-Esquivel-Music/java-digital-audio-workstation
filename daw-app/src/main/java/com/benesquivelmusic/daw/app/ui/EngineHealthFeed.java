package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthText;
import com.benesquivelmusic.daw.app.ui.vm.TransportVM;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.transport.TransportState;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The window's engine-health feed of the xrun log (story 338):
 * one pulse participant on the window's FX dispatcher that moves what the
 * engine controller's {@link XrunJournal} holds into the window's
 * {@link EngineHealthStatus}.
 *
 * <p>Each pulse it
 * <ul>
 *   <li>drains the journal's new entries and appends each as one line of the
 *       log, worded as rulings E20, E38, E42, E72 and E78 word it, stamped
 *       "near m:ss.s" with the transport's position read at the drain, only
 *       while the transport rolls; a shed entry names its track as the
 *       project has it now, or "Track (removed)";</li>
 *   <li>hands each drained resync entry to the current take's clock marks
 *       (ruling E40: nothing is marked unless an audio take records);</li>
 *   <li>sets the xrun figures — the count, how many arrived in the last 30
 *       seconds, the late blocks, when the count restarted and whether the
 *       driver reports underruns — and the take's dropout figures, read from
 *       the current transport's record coordinator (ruling E24).</li>
 * </ul>
 * {@link #clearXrunLog()} is the log's Clear (ruling E22): it forgets the
 * journal's entries and restarts its count, forgets the last finished take's
 * figures — a take still recording keeps its own — and empties the log.</p>
 *
 * <p>FX thread.</p>
 */
final class EngineHealthFeed {

    private static final long RECENT_WINDOW_NANOS = XrunJournal.RECENT_WINDOW_SECONDS * 1_000_000_000L;

    /**
     * Where the feed reads the window's state.
     *
     * @param transport      the window's current transport controller, or a supplier of {@code null} while none
     * @param rollingSeconds the transport's position in seconds while it plays or records, empty otherwise
     * @param trackNames     a track's name by its id, empty when the project has no such track
     */
    record Sources(Supplier<TransportController> transport, Supplier<OptionalDouble> rollingSeconds,
                   Function<String, Optional<String>> trackNames) {

        Sources {
            Objects.requireNonNull(transport, "transport must not be null");
            Objects.requireNonNull(rollingSeconds, "rollingSeconds must not be null");
            Objects.requireNonNull(trackNames, "trackNames must not be null");
        }
    }

    private final EngineHealthStatus status;
    private final XrunJournal journal;
    private final Sources sources;
    private final LongSupplier nanoClock;
    private final ZoneId zone;

    /**
     * A feed on the journal's own clocks, showing times in the system's zone.
     *
     * @param status  the window's engine-health model
     * @param journal the engine controller's xrun journal
     * @param sources where the window's state is read
     */
    EngineHealthFeed(EngineHealthStatus status, XrunJournal journal, Sources sources) {
        this(status, journal, sources, System::nanoTime, ZoneId.systemDefault());
    }

    /**
     * A feed on the given clock and zone.
     *
     * @param status    the window's engine-health model
     * @param journal   the engine controller's xrun journal
     * @param sources   where the window's state is read
     * @param nanoClock the journal's monotonic clock ({@link System#nanoTime()} in production)
     * @param zone      the zone the entries' times are shown in
     */
    EngineHealthFeed(EngineHealthStatus status, XrunJournal journal, Sources sources, LongSupplier nanoClock,
                     ZoneId zone) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * The window's feed: over {@code controller}'s journal, the window's
     * current transport controller and view model, and its project's track
     * names. FX thread.
     *
     * @param status     the window's engine-health model
     * @param controller the window's engine controller
     * @param transport  the window's current transport controller
     * @param transportView the window's current transport view model
     * @param trackNames a track's name by its id
     * @return the feed; its {@link #pulse()} is registered by the caller
     */
    static EngineHealthFeed forWindow(EngineHealthStatus status, DefaultAudioEngineController controller,
                                      Supplier<TransportController> transport, Supplier<TransportVM> transportView,
                                      Function<String, Optional<String>> trackNames) {
        return new EngineHealthFeed(status, controller.xrunJournal(),
                new Sources(transport, () -> rollingSeconds(transportView.get()), trackNames));
    }

    /**
     * The transport's position while it plays or records, as the time display
     * reads it (the playhead at the tempo); empty while it is stopped or
     * paused, or when there is no transport view.
     *
     * @param transportView the window's transport view model, or {@code null}
     * @return the position in seconds, while rolling
     */
    static OptionalDouble rollingSeconds(TransportVM transportView) {
        if (transportView == null || transportView.getTempo() <= 0.0) {
            return OptionalDouble.empty();
        }
        TransportState state = transportView.getState();
        if (state != TransportState.PLAYING && state != TransportState.RECORDING) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(transportView.getPlayhead() * 60.0 / transportView.getTempo());
    }

    /** One pulse: the new entries, the resync marks, the figures. FX thread. */
    void pulse() {
        List<XrunJournal.Entry> fresh = journal.drain();
        if (!fresh.isEmpty()) {
            OptionalDouble position = sources.rollingSeconds().get();
            List<String> lines = new ArrayList<>(fresh.size());
            for (XrunJournal.Entry entry : fresh) {
                lines.add(line(entry, position));
            }
            status.appendXrunLog(lines);
            markResyncs(fresh);
        }
        refreshFigures();
    }

    /** The log's Clear (ruling E22). FX thread. */
    void clearXrunLog() {
        journal.clear();
        TransportController transport = sources.transport().get();
        if (transport != null) {
            transport.recordCoordinator().clearTakeDropouts();
        }
        status.clearXrunLog();
        refreshFigures();
    }

    private void markResyncs(List<XrunJournal.Entry> fresh) {
        TransportController transport = sources.transport().get();
        if (transport == null) {
            return;
        }
        for (XrunJournal.Entry entry : fresh) {
            if (entry.detail() == XrunJournal.Detail.DRIVER_RESYNC) {
                // One mark per resync (ruling E106), all at the entry's arrival: the newest message's.
                for (long resync = 0L; resync < entry.count(); resync++) {
                    transport.recordCoordinator().noteClockEvent(TakeManifest.ClockEvent.RESYNC,
                            entry.arrivedNanos());
                }
            }
        }
    }

    private void refreshFigures() {
        XrunJournal.Counts counts = journal.counts();
        long recent = journal.gapsSince(nanoClock.getAsLong() - RECENT_WINDOW_NANOS);
        status.setXrunFigures(new EngineHealthStatus.XrunFigures(counts.gaps(), recent, counts.lateBlocks(),
                counts.since(), counts.underruns()));
        TransportController transport = sources.transport().get();
        status.setTakeDropouts(transport == null ? Optional.empty() : transport.recordCoordinator().takeDropouts());
    }

    /** "<hh:mm:ss.mmm>  <kind>  <detail>[ ×n]  [near m:ss.s]" (ruling E20). */
    private String line(XrunJournal.Entry entry, OptionalDouble position) {
        StringBuilder line = new StringBuilder(EngineHealthText.toTheMillisecond(entry.arrivedAt(), zone))
                .append("  ").append(EngineHealthText.text("xrunLog.kind." + entry.kind().name()))
                .append("  ").append(detail(entry));
        if (entry.count() > 1L) {
            line.append(" ×").append(entry.count());
        }
        if (position.isPresent()) {
            line.append("  ").append(EngineHealthText.text("xrunLog.near",
                    EngineHealthText.position(position.getAsDouble())));
        }
        return line.toString();
    }

    private String detail(XrunJournal.Entry entry) {
        String key = "xrunLog.detail." + entry.detail().name();
        List<Object> arguments = entry.arguments();
        return switch (entry.detail()) {
            case LATE_BLOCK -> EngineHealthText.text(key,
                    EngineHealthText.milliseconds((Double) arguments.get(0)),
                    EngineHealthText.milliseconds((Double) arguments.get(1)));
            case EXPENSIVE_PROCESSING_BYPASSED, SIMPLER_PROCESSING_SUBSTITUTED -> EngineHealthText.text(key,
                    track((String) arguments.get(0)), String.valueOf(arguments.get(1)));
            case OVERSAMPLING_REDUCED -> EngineHealthText.text(key, track((String) arguments.get(0)),
                    String.valueOf(arguments.get(1)), String.valueOf(arguments.get(2)));
            case DEVICE_STOPPED_RESPONDING -> {
                String name = (String) arguments.get(0);
                yield name.isBlank() ? EngineHealthText.text(key + ".unnamed") : EngineHealthText.text(key, name);
            }
            default -> EngineHealthText.text(key);
        };
    }

    /** "Track 'T'", or "Track (removed)" for a track the project no longer has (ruling E42). */
    private String track(String trackId) {
        return sources.trackNames().apply(trackId)
                .map(name -> EngineHealthText.text("xrunLog.track", name))
                .orElseGet(() -> EngineHealthText.text("xrunLog.track.removed"));
    }
}
