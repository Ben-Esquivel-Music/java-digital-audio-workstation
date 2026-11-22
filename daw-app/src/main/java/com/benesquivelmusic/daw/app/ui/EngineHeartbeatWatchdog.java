package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.CaptureInputSnapshot;
import com.benesquivelmusic.daw.core.audio.InputStallKind;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The engine's heartbeat watchdog (story 338, rulings E1, E2, E12, E15 and E16): pure decision
 * logic, ticked by the engine controller's health monitor, that decides when
 * an open stream's device stopped responding and when a capture input stopped
 * delivering. It owns no thread and touches the engine only through its
 * {@link Probe}; its verdicts go to {@link Reactions}, which may refuse one.
 *
 * <h2>The stream</h2>
 * <p>The watched heartbeat is the device-callback period count where the
 * open stream's backend reports one, and the render drive's beats otherwise
 * (ruling E1). Each tick samples, in this order, the render-drive epoch, the
 * drive's liveness, the counters, the liveness again and the epoch again; the
 * sample counts only when both liveness reads were true and the epoch did not
 * move, so a stop, a pause, a halt or a reopen that lands inside the sample
 * is never read as a frozen heartbeat. The watchdog re-baselines — forgets
 * how long the heartbeat has been still and starts measuring afresh — when
 * the epoch changes, when the drive is not alive, when the watched kind
 * changes (device periods present or not), when a tick arrives later than
 * twice the tick interval (a process pause must never look like a stall,
 * ruling E1), and when {@link Reactions#streamStalled} refuses a verdict.</p>
 *
 * <p>The verdict comes when the watched heartbeat has not advanced for the
 * window: {@code max(2 s, 8 buffer periods)} of the engine's format, read at
 * each re-baseline (ruling E1). Until the heartbeat first advances in an
 * epoch, the window is at least five seconds (ruling E2): an interface that
 * locks to an external clock can take seconds to deliver its first period.
 * A deliberate clock-source switch on the open stream's device gets the same
 * allowance again, counted from the switch, until the heartbeat's first
 * advance after it ({@link #allowForAClockSourceSwitch()}, ruling E107).
 * One verdict per epoch: the epoch is latched only when the verdict is
 * accepted.</p>
 *
 * <h2>Capture inputs</h2>
 * <p>Each input the probe lists is judged by its own delivery count, keyed by
 * its handle (rulings E12, E15, E16). Time counts towards an input's silence
 * only over ticks in which the stream's heartbeat advanced — the render beats
 * and, where the backend reports them, the device periods — so a dead device
 * is the stream verdict's, never an input stall. An input that has delivered
 * and then delivers nothing for the window ({@code max(2 s, 8 buffer
 * periods)}) has {@link InputStallKind#STOPPED}; an input that has delivered
 * nothing for the start-up allowance while a take's capture is active has
 * {@link InputStallKind#NEVER_DELIVERED}. Each handle is declared once.</p>
 *
 * <p>Not thread-safe: one thread ticks it, the health monitor's or a test's.</p>
 */
final class EngineHeartbeatWatchdog {

    /** The floor of every window (ruling E1). */
    static final long MIN_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2);
    /** How many buffer periods of silence a window spans at least (ruling E1). */
    static final int WINDOW_PERIODS = 8;
    /** The floor of the start-up allowance (ruling E2). */
    static final long MIN_STARTUP_ALLOWANCE_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** Which heartbeat a stream verdict was decided on. */
    enum HeartbeatKind {
        /** The open stream's backend's device-callback periods. */
        DEVICE_PERIODS,
        /** The render drive's beats: the backend reports no device periods. */
        RENDER_BEATS
    }

    /** What the watchdog reads; every method is called on the ticking thread and must not block. */
    interface Probe {
        /** {@code AudioHealthRecord.renderDriveEpoch()}. */
        long renderDriveEpoch();

        /** {@code AudioEngine.isRenderDriveAlive()}. */
        boolean isRenderDriveAlive();

        /** {@code AudioHealthRecord.renderDriveBeats()}. */
        long renderDriveBeats();

        /** {@code AudioHealthRecord.deviceCallbackPeriods()}. */
        OptionalLong deviceCallbackPeriods();

        /** The engine format's buffer period in nanoseconds; positive. */
        long bufferPeriodNanos();

        /** {@code AudioEngine.captureInputs()}. */
        List<CaptureInputSnapshot> captureInputs();

        /** {@code AudioEngine.isRecordingCaptureActive()}. */
        boolean isRecordingCaptureActive();
    }

    /**
     * A stream verdict.
     *
     * @param epoch       the render-drive epoch it was decided in
     * @param kind        the heartbeat that stood still
     * @param count       that heartbeat's count, unchanged for the window
     * @param silentNanos how long it stood still
     */
    record StreamStall(long epoch, HeartbeatKind kind, long count, long silentNanos) {
    }

    /** Where the verdicts go; called on the ticking thread. */
    interface Reactions {
        /**
         * The stream's heartbeat stood still for its window.
         *
         * @return {@code true} when the verdict was acted on; {@code false}
         *         re-baselines and may decide again a window later
         */
        boolean streamStalled(StreamStall stall);

        /** A capture input stalled; called once per handle. */
        void inputStalled(CaptureInputSnapshot input, InputStallKind kind);
    }

    private static final long NO_TICK = Long.MIN_VALUE;

    private final Probe probe;
    private final Reactions reactions;
    private final LongSupplier clock;
    private final long tickIntervalNanos;
    private final Map<Object, InputWatch> inputs = new IdentityHashMap<>();

    private long previousTickNanos = NO_TICK;
    private boolean baselined;
    private long epoch = -1L;
    private HeartbeatKind kind;
    private long watchedCount;
    private long beats;
    private long periods;
    private long stillSinceNanos;
    private long windowNanos;
    private long advancedInEpoch = -1L;
    private long latchedEpoch = -1L;
    /** Bumped by {@link #allowForAClockSourceSwitch()}, from any thread; one writer at a time. */
    private volatile long switchRequests;
    /** When the newest switch was sent, read on {@link #clock}; written before {@link #switchRequests}. */
    private volatile long switchRequestedNanos;
    private long switchRequestsSeen;
    /** The start-up allowance applies again until the heartbeat's first advance after a switch (ruling E107). */
    private boolean switchAllowance;

    /**
     * @param probe             what the watchdog reads
     * @param reactions         where its verdicts go
     * @param clock             a monotonic nanosecond clock
     * @param tickIntervalNanos the interval the watchdog is ticked at; a tick
     *                          later than twice this re-baselines
     */
    EngineHeartbeatWatchdog(Probe probe, Reactions reactions, LongSupplier clock, long tickIntervalNanos) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        this.reactions = Objects.requireNonNull(reactions, "reactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (tickIntervalNanos <= 0) {
            throw new IllegalArgumentException("tickIntervalNanos must be positive: " + tickIntervalNanos);
        }
        this.tickIntervalNanos = tickIntervalNanos;
    }

    /** The window for a buffer period: {@code max(2 s, 8 periods)} (ruling E1). */
    static long windowFor(long bufferPeriodNanos) {
        return Math.max(MIN_WINDOW_NANOS, saturatedTimes(bufferPeriodNanos, WINDOW_PERIODS));
    }

    /** The start-up allowance for a window: {@code max(5 s, window)} (ruling E2). */
    static long startupAllowanceFor(long windowNanos) {
        return Math.max(MIN_STARTUP_ALLOWANCE_NANOS, windowNanos);
    }

    /**
     * A deliberate clock-source switch on the open stream's device is being
     * sent to the driver (ruling E107): from now, the stall verdict waits for
     * the start-up allowance, {@code max(5 s, window)}, with no advance, and
     * the normal window applies again at the heartbeat's first advance after
     * this call. Any thread; the caller serializes its calls. Never re-arms
     * anything but the stall verdict.
     */
    void allowForAClockSourceSwitch() {
        switchRequestedNanos = clock.getAsLong();
        switchRequests = switchRequests + 1;
    }

    /** One look at the engine; decides at most one stream verdict and declares stalled inputs. */
    void tick() {
        long requests = switchRequests;
        long now = clock.getAsLong();
        boolean late = previousTickNanos != NO_TICK && now - previousTickNanos > 2 * tickIntervalNanos;
        long elapsed = previousTickNanos == NO_TICK ? 0L : now - previousTickNanos;
        previousTickNanos = now;
        Sample sample = sample();
        if (sample == null) {
            baselined = false;
            return;
        }
        if (!baselined || sample.epoch() != epoch || sample.kind() != kind || late) {
            rebaseline(sample, now);
            if (requests != switchRequestsSeen) {
                switchRequestsSeen = requests;
                switchAllowance = true;
            }
            return;
        }
        if (requests != switchRequestsSeen) {
            switchRequestsSeen = requests;
            switchAllowance = true;
            stillSinceNanos = switchRequestedNanos;
            watchedCount = sample.watchedCount();
        }
        boolean beatsAdvanced = sample.beats() > beats;
        boolean periodsAdvanced = sample.periods() > periods;
        boolean heartbeatAdvanced = beatsAdvanced && (kind == HeartbeatKind.RENDER_BEATS || periodsAdvanced);
        beats = sample.beats();
        periods = sample.periods();
        judgeStream(sample, now);
        judgeInputs(sample, heartbeatAdvanced ? elapsed : 0L);
    }

    private void judgeStream(Sample sample, long now) {
        if (sample.watchedCount() > watchedCount) {
            watchedCount = sample.watchedCount();
            stillSinceNanos = now;
            advancedInEpoch = epoch;
            switchAllowance = false;
            return;
        }
        if (latchedEpoch == epoch) {
            return;
        }
        long still = now - stillSinceNanos;
        long window = advancedInEpoch == epoch && !switchAllowance ? windowNanos : startupAllowanceFor(windowNanos);
        if (still < window) {
            return;
        }
        if (reactions.streamStalled(new StreamStall(epoch, kind, watchedCount, still))) {
            latchedEpoch = epoch;
        } else {
            stillSinceNanos = now;
        }
    }

    private void judgeInputs(Sample sample, long heartbeatNanos) {
        long allowance = startupAllowanceFor(windowNanos);
        boolean captureActive = false;
        boolean captureRead = false;
        Map<Object, InputWatch> present = new IdentityHashMap<>();
        for (CaptureInputSnapshot input : sample.inputs()) {
            InputWatch watch = inputs.get(input.handle());
            if (watch == null) {
                present.put(input.handle(), new InputWatch(input.deliveredBlocks()));
                continue;
            }
            present.put(input.handle(), watch);
            if (watch.declared) {
                continue;
            }
            if (input.deliveredBlocks() > watch.delivered) {
                watch.delivered = input.deliveredBlocks();
                watch.quietNanos = 0L;
                continue;
            }
            watch.quietNanos += heartbeatNanos;
            if (watch.delivered > 0 && watch.quietNanos >= windowNanos) {
                watch.declared = true;
                reactions.inputStalled(input, InputStallKind.STOPPED);
            } else if (watch.delivered == 0 && watch.quietNanos >= allowance) {
                if (!captureRead) {
                    captureActive = probe.isRecordingCaptureActive();
                    captureRead = true;
                }
                if (captureActive) {
                    watch.declared = true;
                    reactions.inputStalled(input, InputStallKind.NEVER_DELIVERED);
                }
            }
        }
        inputs.clear();
        inputs.putAll(present);
    }

    private void rebaseline(Sample sample, long now) {
        if (sample.epoch() != epoch) {
            inputs.clear();
        }
        for (CaptureInputSnapshot input : sample.inputs()) {
            InputWatch watch = inputs.get(input.handle());
            if (watch == null) {
                inputs.put(input.handle(), new InputWatch(input.deliveredBlocks()));
            } else {
                watch.delivered = Math.max(watch.delivered, input.deliveredBlocks());
                watch.quietNanos = 0L;
            }
        }
        epoch = sample.epoch();
        kind = sample.kind();
        watchedCount = sample.watchedCount();
        beats = sample.beats();
        periods = sample.periods();
        stillSinceNanos = now;
        windowNanos = windowFor(probe.bufferPeriodNanos());
        baselined = true;
    }

    /**
     * Samples in the order epoch, alive, counters, alive, epoch; {@code null}
     * when the drive was not alive at either read or the epoch moved.
     */
    private Sample sample() {
        long firstEpoch = probe.renderDriveEpoch();
        if (!probe.isRenderDriveAlive()) {
            return null;
        }
        long renderBeats = probe.renderDriveBeats();
        OptionalLong devicePeriods = probe.deviceCallbackPeriods();
        List<CaptureInputSnapshot> captureInputs = probe.captureInputs();
        if (!probe.isRenderDriveAlive() || probe.renderDriveEpoch() != firstEpoch) {
            return null;
        }
        HeartbeatKind sampledKind = devicePeriods.isPresent() ? HeartbeatKind.DEVICE_PERIODS : HeartbeatKind.RENDER_BEATS;
        long devicePeriodCount = devicePeriods.orElse(0L);
        long watched = sampledKind == HeartbeatKind.DEVICE_PERIODS ? devicePeriodCount : renderBeats;
        return new Sample(firstEpoch, sampledKind, watched, renderBeats, devicePeriodCount, captureInputs);
    }

    private static long saturatedTimes(long value, int factor) {
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private record Sample(long epoch, HeartbeatKind kind, long watchedCount, long beats, long periods,
                          List<CaptureInputSnapshot> inputs) {
    }

    /** One capture input's watch: what it had delivered, and how long the stream beat without it delivering. */
    private static final class InputWatch {
        private long delivered;
        private long quietNanos;
        private boolean declared;

        private InputWatch(long delivered) {
            this.delivered = delivered;
        }
    }
}
