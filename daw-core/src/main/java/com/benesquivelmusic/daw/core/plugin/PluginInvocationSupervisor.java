package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps each plugin invocation in a try/catch so that a misbehaving plugin
 * cannot take down the audio session.
 *
 * <p>The supervisor returns an {@link AudioProcessor} wrapper for each
 * {@link InsertSlot}. On any {@link Throwable} thrown by the delegate, the
 * wrapper zeroes the slot's output buffer, flips the slot's bypass flag, and
 * enqueues a lightweight fault marker for a dedicated daemon thread to
 * publish and persist asynchronously — keeping the real-time path
 * allocation-free.</p>
 *
 * <p>After more than {@value #QUARANTINE_THRESHOLD} faults for the same
 * plugin id within a session the plugin is marked {@link PluginFault#quarantined()
 * quarantined}; callers can {@link #clearQuarantine(String)} to reset the
 * counter and {@link #reenable(InsertSlot)} to un-bypass the slot.</p>
 *
 * <p><strong>Limitations:</strong> JVM-level failures such as
 * {@link OutOfMemoryError} (on large allocations) or
 * {@link StackOverflowError} on the audio thread leave the JVM in an
 * undefined state and cannot be reliably recovered by a try/catch. Native
 * segmentation faults from FFM downcalls into third-party native code take
 * down the whole JVM process and are unreachable from Java-level exception
 * handling entirely. True isolation against these failure modes requires
 * hosting third-party plugin code in a separate process — tracked as a
 * future story.</p>
 */
public final class PluginInvocationSupervisor {

    private static final Logger LOG = Logger.getLogger(PluginInvocationSupervisor.class.getName());

    /** After this many faults for a single plugin id, the slot stays bypassed. */
    public static final int QUARANTINE_THRESHOLD = 3;

    /** Sentinel pushed into the work queue to signal shutdown to the drain thread. */
    private static final PendingFault SHUTDOWN = new PendingFault(null, null, null);

    private final SubmissionPublisher<PluginFault> publisher = new SubmissionPublisher<>();
    private final Map<String, AtomicInteger> faultCounts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> quarantined = new ConcurrentHashMap<>();
    // Latest InsertSlot registered for each pluginId. Uses WeakReference so
    // that removed slots can be garbage-collected rather than being retained
    // indefinitely over long sessions with many add/remove cycles.
    private final Map<String, WeakReference<InsertSlot>> slotsByPluginId = new ConcurrentHashMap<>();

    // Unbounded queue so the RT catch block never blocks or allocates beyond
    // a single LinkedBlockingQueue Node per event; drain thread does all I/O.
    private final LinkedBlockingQueue<PendingFault> faultQueue = new LinkedBlockingQueue<>();

    private final Path faultLogPath;
    private final Thread drainThread;
    private volatile boolean running = true;

    /** Creates a supervisor that persists faults to {@code ~/.daw/plugin-faults.log}. */
    public PluginInvocationSupervisor() {
        this(defaultFaultLogPath());
    }

    /**
     * Creates a supervisor that persists faults to the given path. Test code
     * should inject a {@code @TempDir} path.
     *
     * @param faultLogPath destination log file (parent directory is created if missing)
     */
    public PluginInvocationSupervisor(Path faultLogPath) {
        this.faultLogPath = Objects.requireNonNull(faultLogPath, "faultLogPath must not be null");
        this.drainThread = new Thread(this::drainLoop, "plugin-fault-drain");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    private static Path defaultFaultLogPath() {
        return Path.of(System.getProperty("user.home"), ".daw", "plugin-faults.log");
    }

    /**
     * Returns the {@link Flow.Publisher} that emits one {@link PluginFault}
     * event per recorded fault. Subscribers marshal to their preferred thread.
     */
    public Flow.Publisher<PluginFault> publisher() {
        return publisher;
    }

    /**
     * Wraps {@code delegate} so that any exception is caught and the slot
     * bypassed. The returned processor is safe to insert into the channel's
     * {@link com.benesquivelmusic.daw.core.audio.EffectsChain EffectsChain}.
     */
    public AudioProcessor supervise(InsertSlot slot, AudioProcessor delegate) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(delegate, "delegate must not be null");
        SupervisedProcessor wrapper = new SupervisedProcessor(slot, delegate);
        slotsByPluginId.put(wrapper.pluginId, new WeakReference<>(slot));
        return wrapper;
    }

    /** Returns the fault count recorded for {@code pluginId} in this session. */
    public int getFaultCount(String pluginId) {
        AtomicInteger counter = faultCounts.get(pluginId);
        return counter == null ? 0 : counter.get();
    }

    /** Returns {@code true} if the plugin has exceeded the session quarantine threshold. */
    public boolean isQuarantined(String pluginId) {
        return Boolean.TRUE.equals(quarantined.get(pluginId));
    }

    /** Clears the quarantine flag and fault counter for {@code pluginId}. */
    public void clearQuarantine(String pluginId) {
        quarantined.remove(pluginId);
        AtomicInteger counter = faultCounts.get(pluginId);
        if (counter != null) {
            counter.set(0);
        }
    }

    /**
     * Re-enables a previously-bypassed slot and clears any quarantine flag
     * for its resolved plugin id. The caller is responsible for triggering
     * whatever chain rebuild is necessary (e.g.
     * {@code MixerChannel#setInsertBypassed}).
     */
    public void reenable(InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        slot.setBypassed(false);
        clearQuarantine(SupervisedProcessor.resolvePluginId(slot));
    }

    /**
     * Re-enables the most recently supervised slot for {@code pluginId}:
     * un-bypasses it and clears quarantine. Returns {@code true} if a slot
     * was found and un-bypassed. Used by the fault log dialog, which only
     * knows faults by their plugin id.
     */
    public boolean reenable(String pluginId) {
        WeakReference<InsertSlot> ref = slotsByPluginId.get(pluginId);
        InsertSlot slot = ref == null ? null : ref.get();
        if (slot == null) {
            // Prune stale reference if it was GC'd.
            if (ref != null) {
                slotsByPluginId.remove(pluginId, ref);
            }
            return false;
        }
        slot.setBypassed(false);
        clearQuarantine(pluginId);
        return true;
    }

    /**
     * Shuts down the drain thread and the event publisher. Intended for
     * tests and graceful application shutdown.
     */
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        faultQueue.offer(SHUTDOWN);
        try {
            drainThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        publisher.close();
    }

    // ── Drain thread: off-RT formatting + publishing + I/O ──────────────────

    private void drainLoop() {
        while (running) {
            try {
                PendingFault pending = faultQueue.take();
                if (pending == SHUTDOWN) {
                    return;
                }
                // Defensive: SHUTDOWN is the only legitimate null-throwable
                // sentinel; guard against future queue abuse by a bug elsewhere.
                if (pending.throwable == null) {
                    continue;
                }
                PluginFault fault = materialize(pending);
                // Persist before publishing so a subscriber that latches on
                // onNext can assert the log file exists (deterministic tests).
                appendToLog(fault);
                // Never block the drain thread on a slow subscriber; if a
                // subscriber is backpressured, drop this publication rather
                // than stalling faultQueue draining.
                publisher.offer(fault, (subscriber, dropped) -> false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Fail-soft: the drain thread must never die — a logging or
                // publisher glitch cannot silence future faults.
                LOG.log(Level.WARNING, "plugin fault drain loop error", e);
            }
        }
    }

    private PluginFault materialize(PendingFault pending) {
        StringWriter sw = new StringWriter();
        pending.throwable.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();

        AtomicInteger counter = faultCounts.computeIfAbsent(pending.pluginId, _ -> new AtomicInteger());
        int count = counter.incrementAndGet();
        boolean isQuarantined = count > QUARANTINE_THRESHOLD;
        if (isQuarantined) {
            quarantined.put(pending.pluginId, Boolean.TRUE);
        }

        return new PluginFault(
                pending.pluginId,
                pending.throwable.getClass().getName(),
                pending.throwable.getMessage(),
                stack,
                pending.clock,
                count,
                isQuarantined
        );
    }

    private void appendToLog(PluginFault fault) {
        try {
            Path parent = faultLogPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = toJson(fault);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    faultLogPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            // Never let log failure propagate — the session must continue.
            LOG.log(Level.WARNING, "failed to append plugin fault to " + faultLogPath, e);
        }
    }

    private static String toJson(PluginFault fault) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonField(sb, "pluginId", fault.pluginId());
        sb.append(',');
        appendJsonField(sb, "exceptionClass", fault.exceptionClass());
        sb.append(',');
        appendJsonField(sb, "message", fault.message());
        sb.append(',');
        appendJsonField(sb, "clock", fault.clock() == null ? null : fault.clock().toString());
        sb.append(',');
        sb.append("\"faultCountThisSession\":").append(fault.faultCountThisSession());
        sb.append(',');
        sb.append("\"quarantined\":").append(fault.quarantined());
        sb.append(',');
        appendJsonField(sb, "stackTrace", fault.stackTrace());
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    // Pre-allocated, minimal-allocation record of the raw fault. Captured on
    // the audio thread; materialized off-thread into the public PluginFault.
    private record PendingFault(String pluginId, Throwable throwable, Instant clock) {
    }

    // ── Supervised wrapper ──────────────────────────────────────────────────

    private final class SupervisedProcessor implements AudioProcessor {

        private final InsertSlot slot;
        private final AudioProcessor delegate;
        private final String pluginId;

        SupervisedProcessor(InsertSlot slot, AudioProcessor delegate) {
            this.slot = slot;
            this.delegate = delegate;
            this.pluginId = resolvePluginId(slot);
        }

        static String resolvePluginId(InsertSlot slot) {
            if (slot.getPlugin() != null && slot.getPlugin().getDescriptor() != null) {
                return slot.getPlugin().getDescriptor().id();
            }
            return slot.getName();
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            // The two-arm catch is deliberate: Exception covers ordinary
            // plugin bugs (NPE, AE, AIOOBE) and is the expected case; Error
            // (OOM, StackOverflowError, LinkageError) indicates the JVM is
            // in an undefined state — we still bypass to keep audio flowing
            // but log at SEVERE so the collapse isn't silenced alongside
            // routine plugin bugs.
            try {
                delegate.process(inputBuffer, outputBuffer, numFrames);
            } catch (Exception e) {
                zero(outputBuffer, numFrames);
                handleFault(e);
            } catch (Error err) {
                zero(outputBuffer, numFrames);
                logJvmError(err);
                handleFault(err);
            }
        }

        @Override
        public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
            try {
                delegate.processDouble(inputBuffer, outputBuffer, numFrames);
            } catch (Exception e) {
                zeroDouble(outputBuffer, numFrames);
                handleFault(e);
            } catch (Error err) {
                zeroDouble(outputBuffer, numFrames);
                logJvmError(err);
                handleFault(err);
            }
        }

        @Override
        public boolean supportsDouble() {
            return delegate.supportsDouble();
        }

        @Override
        public void reset() {
            try {
                delegate.reset();
            } catch (Exception e) {
                handleFault(e);
            } catch (Error err) {
                logJvmError(err);
                handleFault(err);
            }
        }

        @Override
        public int getInputChannelCount() {
            return delegate.getInputChannelCount();
        }

        @Override
        public int getOutputChannelCount() {
            return delegate.getOutputChannelCount();
        }

        @Override
        public int getLatencySamples() {
            return delegate.getLatencySamples();
        }

        private void handleFault(Throwable t) {
            // Only enqueue a fault event if the slot is not already bypassed.
            // If the chain hasn't been rebuilt yet the same faulting plugin
            // would otherwise spam the unbounded queue/log once per block.
            if (slot.isBypassed()) {
                return;
            }
            slot.setBypassed(true);
            // LinkedBlockingQueue.offer allocates one Node wrapper; the
            // Instant.now() and PendingFault allocations are unavoidable but
            // tiny and only occur on the exception path, not the hot path.
            faultQueue.offer(new PendingFault(pluginId, t, Instant.now()));
        }

        private void logJvmError(Error err) {
            String message = "JVM-level error in plugin " + pluginId
                    + " (" + err.getClass().getName()
                    + ") — session continued but the JVM may be unstable";
            LOG.log(Level.SEVERE, message, err);
        }

        private static void zero(float[][] buffer, int numFrames) {
            for (int ch = 0; ch < buffer.length; ch++) {
                Arrays.fill(buffer[ch], 0, numFrames, 0.0f);
            }
        }

        private static void zeroDouble(double[][] buffer, int numFrames) {
            for (int ch = 0; ch < buffer.length; ch++) {
                Arrays.fill(buffer[ch], 0, numFrames, 0.0);
            }
        }
    }
}
