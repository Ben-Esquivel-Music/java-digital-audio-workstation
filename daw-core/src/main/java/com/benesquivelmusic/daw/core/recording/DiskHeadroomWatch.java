package com.benesquivelmusic.daw.core.recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cached free-space watch for the capture-flush loop (Recording Reliability
 * book §4.3; story 323).
 *
 * <p><strong>Thread.</strong> {@link #check(long)} is called on the
 * {@code capture-flush} thread before appends; the probe (a
 * {@code getUsableSpace} call in production) only runs when the refresh
 * interval has elapsed, so the per-append cost is a subtraction and a
 * compare. Not thread-safe; single caller by design.</p>
 *
 * <p>States, derived from the cached figure:</p>
 * <ul>
 *   <li>{@link State#OK} — free space at or above the low-water mark.</li>
 *   <li>{@link State#LOW} — below the low-water mark (default
 *       {@link #DEFAULT_LOW_WATER_BYTES}, 1 GiB). The warning sink is told
 *       <em>once per crossing</em>: entering LOW warns, staying LOW is silent,
 *       returning to OK and dropping again warns again.</li>
 *   <li>{@link State#EXHAUSTED} — below the floor (default
 *       {@link #DEFAULT_FLOOR_BYTES}, 64 MiB). The flush service seals the
 *       take cleanly on this state instead of throwing out of a write loop.</li>
 * </ul>
 *
 * <p>A probe failure (the supplier throws a {@link RuntimeException},
 * typically an {@link UncheckedIOException} from the file store) is logged
 * and keeps the previous figure; the state escalates to EXHAUSTED only when
 * {@link #FAILURES_BEFORE_EXHAUSTED} consecutive probes fail — a
 * transiently unreadable file store must not abort a take, but a persistently
 * unreadable one is one the writer cannot trust.</p>
 *
 * <p>Warnings degrade to {@code java.util.logging} WARNING when no sink is
 * injected; story 339 injects the production notification seam. A sink that
 * throws a {@link RuntimeException} is logged at WARNING, with the message
 * it was given, and ignored: {@link #check(long)} returns the state all the
 * same, so a sink can never turn a warning into the end of a take.</p>
 */
public final class DiskHeadroomWatch {

    /** Free-space classification. */
    public enum State { OK, LOW, EXHAUSTED }

    /** Default low-water mark: 1 GiB. */
    public static final long DEFAULT_LOW_WATER_BYTES = 1L << 30;

    /** Default floor below which capture must seal: 64 MiB. */
    public static final long DEFAULT_FLOOR_BYTES = 64L << 20;

    /** Default probe refresh interval (the "slow tick"). */
    public static final Duration DEFAULT_REFRESH = Duration.ofSeconds(2);

    /** Consecutive probe failures that escalate to {@link State#EXHAUSTED}. */
    public static final int FAILURES_BEFORE_EXHAUSTED = 2;

    private static final Logger LOG = Logger.getLogger(DiskHeadroomWatch.class.getName());
    private static final long MIB = 1L << 20;

    private final Path root;
    private final LongSupplier freeBytesProbe;
    private final long lowWaterBytes;
    private final long floorBytes;
    private final long refreshNanos;
    private final Duration refresh;
    private final LongSupplier nanoClock;
    private final Consumer<String> warningSink;

    private boolean probed;
    private boolean hasFigure;
    private long cachedFreeBytes = -1;
    private long lastProbeNanos;
    private int consecutiveProbeFailures;
    private State state = State.OK;

    /**
     * Creates a watch whose warnings go to the class logger.
     *
     * @param root           the directory being written (named in messages)
     * @param freeBytesProbe returns usable bytes at {@code root}; may throw a
     *                       {@link RuntimeException} to signal a probe failure
     * @param lowWaterBytes  warn below this; must be ≥ {@code floorBytes}
     * @param floorBytes     exhausted below this; non-negative
     * @param refresh        minimum interval between probes; non-negative
     * @param nanoClock      monotonic clock the caller passes to {@link #check(long)}
     */
    public DiskHeadroomWatch(Path root, LongSupplier freeBytesProbe, long lowWaterBytes,
                             long floorBytes, Duration refresh, LongSupplier nanoClock) {
        this(root, freeBytesProbe, lowWaterBytes, floorBytes, refresh, nanoClock, null);
    }

    /**
     * Creates a watch with an explicit warning sink.
     *
     * @param warningSink receives one message per LOW/EXHAUSTED crossing, on
     *                    the thread that calls {@link #check(long)};
     *                    {@code null} logs at WARNING instead; a
     *                    {@link RuntimeException} it throws is logged and
     *                    ignored
     * @see #DiskHeadroomWatch(Path, LongSupplier, long, long, Duration, LongSupplier)
     */
    public DiskHeadroomWatch(Path root, LongSupplier freeBytesProbe, long lowWaterBytes,
                             long floorBytes, Duration refresh, LongSupplier nanoClock,
                             Consumer<String> warningSink) {
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.freeBytesProbe = Objects.requireNonNull(freeBytesProbe, "freeBytesProbe must not be null");
        if (floorBytes < 0) {
            throw new IllegalArgumentException("floorBytes must not be negative: " + floorBytes);
        }
        if (lowWaterBytes < floorBytes) {
            throw new IllegalArgumentException("lowWaterBytes (" + lowWaterBytes
                    + ") must be at least floorBytes (" + floorBytes + ")");
        }
        this.refresh = Objects.requireNonNull(refresh, "refresh must not be null");
        if (refresh.isNegative()) {
            throw new IllegalArgumentException("refresh must not be negative: " + refresh);
        }
        this.lowWaterBytes = lowWaterBytes;
        this.floorBytes = floorBytes;
        this.refreshNanos = refresh.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.warningSink = warningSink != null
                ? warningSink
                : message -> LOG.log(Level.WARNING, message);
    }

    /**
     * Production factory: default thresholds and refresh, the real file-store
     * probe for {@code root}, {@code System::nanoTime}.
     *
     * @param root        the take directory (or any path on the target file store)
     * @param warningSink warning sink, or {@code null} to log
     * @return the watch
     */
    public static DiskHeadroomWatch forDirectory(Path root, Consumer<String> warningSink) {
        return new DiskHeadroomWatch(root, usableSpaceProbe(root), DEFAULT_LOW_WATER_BYTES,
                DEFAULT_FLOOR_BYTES, DEFAULT_REFRESH, System::nanoTime, warningSink);
    }

    /**
     * The production probe: {@code Files.getFileStore(root).getUsableSpace()},
     * with the {@link IOException} rethrown as {@link UncheckedIOException}
     * so {@link #check(long)} can count it as a probe failure.
     */
    public static LongSupplier usableSpaceProbe(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        return () -> {
            try {
                return Files.getFileStore(root).getUsableSpace();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read free space under " + root, e);
            }
        };
    }

    /**
     * Classifies free space at {@code nowNanos}, probing only when the
     * refresh interval has elapsed since the last probe (the first call
     * always probes).
     *
     * @param nowNanos the caller's monotonic clock reading
     * @return the current state
     */
    public State check(long nowNanos) {
        if (!probed || nowNanos - lastProbeNanos >= refreshNanos) {
            probe(nowNanos);
        }
        State next = derive();
        if (next != state) {
            State previous = state;
            state = next;
            onCrossing(previous, next);
        }
        return state;
    }

    /** Convenience for callers that own the clock: {@code check(nanoClock.getAsLong())}. */
    public State check() {
        return check(nanoClock.getAsLong());
    }

    private void probe(long nowNanos) {
        probed = true;
        lastProbeNanos = nowNanos;
        try {
            long free = freeBytesProbe.getAsLong();
            if (free < 0) {
                throw new IllegalStateException("probe returned a negative figure: " + free);
            }
            cachedFreeBytes = free;
            hasFigure = true;
            consecutiveProbeFailures = 0;
        } catch (RuntimeException e) {
            consecutiveProbeFailures++;
            LOG.log(Level.WARNING, "Free-space probe failed under " + root + " ("
                    + consecutiveProbeFailures + " consecutive); keeping "
                    + (hasFigure ? cachedFreeBytes / MIB + " MiB" : "no figure"), e);
        }
    }

    private State derive() {
        if (consecutiveProbeFailures >= FAILURES_BEFORE_EXHAUSTED) {
            return State.EXHAUSTED;
        }
        if (!hasFigure) {
            return State.OK;
        }
        if (cachedFreeBytes < floorBytes) {
            return State.EXHAUSTED;
        }
        if (cachedFreeBytes < lowWaterBytes) {
            return State.LOW;
        }
        return State.OK;
    }

    private void onCrossing(State previous, State next) {
        switch (next) {
            case LOW -> warn("Disk headroom low under " + root + ": "
                    + cachedFreeBytes / MIB + " MiB free (low-water " + lowWaterBytes / MIB + " MiB)");
            case EXHAUSTED -> warn(consecutiveProbeFailures >= FAILURES_BEFORE_EXHAUSTED
                    ? "Disk headroom unknown under " + root + ": " + consecutiveProbeFailures
                            + " consecutive free-space probes failed; sealing the take"
                    : "Disk exhausted under " + root + ": " + cachedFreeBytes / MIB
                            + " MiB free (floor " + floorBytes / MIB + " MiB); sealing the take");
            case OK -> LOG.log(Level.INFO, "Disk headroom recovered under " + root + " (was " + previous + ")");
        }
    }

    /**
     * Hands {@code message} to the sink. The sink is foreign code: a
     * {@link RuntimeException} it throws is logged with the message and goes
     * no further, so {@link #check(long)} still returns the state and a
     * warning that could not be delivered never ends a take.
     */
    private void warn(String message) {
        try {
            warningSink.accept(message);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, message + " (warning sink threw)", e);
        }
    }

    /** Returns the state of the last {@link #check(long)} ({@link State#OK} before any). */
    public State state() {
        return state;
    }

    /** Returns the last successfully probed figure, or {@code -1} before any success. */
    public long cachedFreeBytes() {
        return cachedFreeBytes;
    }

    /** Returns how many probes in a row have failed. */
    public int consecutiveProbeFailures() {
        return consecutiveProbeFailures;
    }

    /** Returns the watched root. */
    public Path root() {
        return root;
    }

    /** Returns the low-water mark. */
    public long lowWaterBytes() {
        return lowWaterBytes;
    }

    /** Returns the exhaustion floor. */
    public long floorBytes() {
        return floorBytes;
    }

    /** Returns the probe refresh interval. */
    public Duration refresh() {
        return refresh;
    }

    @Override
    public String toString() {
        return "DiskHeadroomWatch[" + root + ", " + state + ", free="
                + (hasFigure ? cachedFreeBytes / MIB + " MiB" : "?") + "]";
    }
}
