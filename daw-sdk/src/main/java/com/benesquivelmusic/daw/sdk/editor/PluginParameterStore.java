package com.benesquivelmusic.daw.sdk.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

/**
 * The real-time-safe parameter store the host owns and a plugin editor
 * reads / writes through {@link EditorContext#parameterStore()} (Plugin View
 * Design Book §4.6). It is the enforcement seam behind Principle §2.6: a
 * plugin's editor cannot reach its {@code AudioProcessor} directly — both the
 * FX side (a knob turn) and the audio side (an internal envelope follower)
 * talk only to this store.
 *
 * <h2>Coherence and threading (§4.7)</h2>
 * <ul>
 *   <li>Every parameter has an authoritative value stored in a lock-free
 *       {@link AtomicLongArray} (as raw {@code double} bits). {@link #value(int)}
 *       returns a coherent, allocation-free snapshot from either thread.</li>
 *   <li>{@link #writeFromUi(int, double)} runs on the FX thread: it clamps and
 *       publishes the new value, then posts the parameter's index onto a
 *       single-producer / single-consumer ring the audio thread drains in
 *       {@code process(...)} via {@link #drainToAudio(IndexConsumer)}.</li>
 *   <li>{@link #writeFromAudio(int, double)} runs on the audio thread and is
 *       {@link RealTimeSafe}: it clamps and publishes, then posts onto a
 *       <em>separate</em> ring the FX thread drains via
 *       {@link #drainToUi(IndexConsumer)} for display update.</li>
 * </ul>
 *
 * <p>The rings carry only the changed parameter <em>index</em>; the value is
 * read back from the coherent atomic array by the draining side. Both rings are
 * fixed-capacity and non-blocking: if a producer outruns its consumer the
 * oldest pending notification is dropped, never blocked — the atomic array
 * still holds the latest value, so a dropped notification only delays a
 * downstream apply / repaint until the next change. This keeps the audio-thread
 * path free of allocation, locking and unbounded work.</p>
 *
 * <p>Indices are dense ({@code 0 .. parameterCount()-1}) in the order the store
 * was constructed. {@link #indexOf(int)} maps a {@link PluginParameter#id()} to
 * its index; that lookup is for setup / FX-side code only — the audio-thread
 * methods take an index so they never touch the map.</p>
 */
public final class PluginParameterStore {

    /**
     * Allocation-free callback for draining a change ring. Receives the dense
     * index of a parameter whose value changed; read the new value with
     * {@link PluginParameterStore#value(int)}.
     */
    @FunctionalInterface
    public interface IndexConsumer {
        void accept(int parameterIndex);
    }

    private final PluginParameter[] parameters;
    private final int[] parameterIds;
    private final Map<Integer, Integer> idToIndex;
    private final AtomicLongArray values;
    private final IntRing uiToAudio;
    private final IntRing audioToUi;

    /**
     * Creates a store for the given ordered parameter list. Each parameter's
     * value is seeded to its {@link PluginParameter#defaultValue()}.
     *
     * @param parameters the plugin's parameters, in a stable order; must not be
     *                   {@code null} and must not contain duplicate ids
     * @throws IllegalArgumentException if two parameters share an id
     */
    public PluginParameterStore(List<PluginParameter> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        int n = parameters.size();
        this.parameters = parameters.toArray(new PluginParameter[0]);
        this.parameterIds = new int[n];
        this.idToIndex = new HashMap<>(Math.max(1, (n * 4 + 2) / 3));
        this.values = new AtomicLongArray(n);
        for (int i = 0; i < n; i++) {
            PluginParameter p = Objects.requireNonNull(
                    this.parameters[i], "parameters must not contain null");
            parameterIds[i] = p.id();
            if (idToIndex.putIfAbsent(p.id(), i) != null) {
                throw new IllegalArgumentException("duplicate parameter id: " + p.id());
            }
            values.set(i, Double.doubleToRawLongBits(p.defaultValue()));
        }
        int capacity = ringCapacity(n);
        this.uiToAudio = new IntRing(capacity);
        this.audioToUi = new IntRing(capacity);
    }

    /**
     * Returns the number of parameters in this store.
     *
     * @return the parameter count
     */
    public int parameterCount() {
        return parameters.length;
    }

    /**
     * Returns the parameter descriptor at the given dense index.
     *
     * @param index a dense index in {@code [0, parameterCount())}
     * @return the parameter descriptor
     */
    public PluginParameter parameter(int index) {
        return parameters[index];
    }

    /**
     * Maps a {@link PluginParameter#id()} to its dense index. For setup /
     * FX-side use only — not for the audio thread.
     *
     * @param parameterId the parameter id
     * @return the dense index of that parameter
     * @throws IllegalArgumentException if no parameter has that id
     */
    public int indexOf(int parameterId) {
        Integer index = idToIndex.get(parameterId);
        if (index == null) {
            throw new IllegalArgumentException("unknown parameter id: " + parameterId);
        }
        return index;
    }

    /**
     * Returns the {@link PluginParameter#id()} of the parameter at the given
     * dense index.
     *
     * @param index a dense index in {@code [0, parameterCount())}
     * @return the parameter id at that index
     */
    public int parameterIdAt(int index) {
        return parameterIds[index];
    }

    /**
     * Returns the current value of the parameter at the given index. Coherent
     * and allocation-free — safe to call from any thread, including the audio
     * thread.
     *
     * @param index a dense index in {@code [0, parameterCount())}
     * @return the current value
     */
    @RealTimeSafe
    public double value(int index) {
        return Double.longBitsToDouble(values.get(index));
    }

    /**
     * Returns the current value of the parameter with the given id. Convenience
     * for FX-side code; performs an id-to-index lookup so it is <em>not</em>
     * for the audio thread — use {@link #value(int)} there.
     *
     * @param parameterId the parameter id
     * @return the current value
     * @throws IllegalArgumentException if no parameter has that id
     */
    public double valueById(int parameterId) {
        return value(indexOf(parameterId));
    }

    /**
     * FX-thread write (a knob turn): clamps the value into the parameter's
     * declared range, publishes it as the new coherent value, and notifies the
     * audio thread. Drain the notification on the audio thread with
     * {@link #drainToAudio(IndexConsumer)}.
     *
     * @param index a dense index in {@code [0, parameterCount())}
     * @param value the requested value (clamped to the parameter's range)
     */
    public void writeFromUi(int index, double value) {
        values.set(index, Double.doubleToRawLongBits(clamp(index, value)));
        uiToAudio.offer(index);
    }

    /**
     * FX-thread write addressed by parameter id. Equivalent to
     * {@code writeFromUi(indexOf(parameterId), value)}.
     *
     * @param parameterId the parameter id
     * @param value       the requested value (clamped to the parameter's range)
     * @throws IllegalArgumentException if no parameter has that id
     */
    public void writeFromUiById(int parameterId, double value) {
        writeFromUi(indexOf(parameterId), value);
    }

    /**
     * Audio-thread write (for example an internal envelope follower the plugin
     * wants to display): clamps and publishes the new coherent value, then
     * notifies the FX thread. Drain the notification on the FX thread with
     * {@link #drainToUi(IndexConsumer)}. Real-time safe — no allocation, no
     * locking, no blocking.
     *
     * @param index a dense index in {@code [0, parameterCount())}
     * @param value the value (clamped to the parameter's range)
     */
    @RealTimeSafe
    public void writeFromAudio(int index, double value) {
        values.set(index, Double.doubleToRawLongBits(clamp(index, value)));
        audioToUi.offer(index);
    }

    /**
     * Audio-thread drain of FX-side writes. Invokes {@code sink} once per
     * pending change with the changed parameter's index (read the value with
     * {@link #value(int)}), and returns the number of changes drained.
     * Real-time safe as long as {@code sink} is (the store allocates nothing).
     *
     * @param sink the per-change callback
     * @return the number of changes drained
     */
    @RealTimeSafe
    public int drainToAudio(IndexConsumer sink) {
        return uiToAudio.drain(sink);
    }

    /**
     * FX-thread drain of audio-side writes. Invokes {@code sink} once per
     * pending change with the changed parameter's index, and returns the number
     * of changes drained. Call this from an FX pulse / animation tick to refresh
     * the editor's controls.
     *
     * @param sink the per-change callback
     * @return the number of changes drained
     */
    public int drainToUi(IndexConsumer sink) {
        return audioToUi.drain(sink);
    }

    @RealTimeSafe
    private double clamp(int index, double value) {
        PluginParameter p = parameters[index];
        double min = p.minValue();
        double max = p.maxValue();
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /** Smallest power of two that comfortably buffers {@code n} parameters. */
    private static int ringCapacity(int n) {
        int wanted = Math.max(64, n * 4);
        int cap = 1;
        while (cap < wanted) {
            cap <<= 1;
        }
        return cap;
    }

    /**
     * A single-producer / single-consumer, lock-free, fixed-capacity ring of
     * {@code int} values. The producer ({@link #offer(int)}) and consumer
     * ({@link #drain(IndexConsumer)}) each run on exactly one thread. Neither
     * allocates; a full ring drops the incoming value rather than blocking.
     */
    private static final class IntRing {
        private final int[] buffer;
        private final int mask;
        private final AtomicLong head = new AtomicLong(); // consumer position
        private final AtomicLong tail = new AtomicLong(); // producer position

        IntRing(int capacityPowerOfTwo) {
            this.buffer = new int[capacityPowerOfTwo];
            this.mask = capacityPowerOfTwo - 1;
        }

        @RealTimeSafe
        void offer(int value) {
            long t = tail.get();
            if (t - head.get() >= buffer.length) {
                return; // full — drop; the coherent atomic value still holds truth
            }
            buffer[(int) (t & mask)] = value;
            tail.set(t + 1); // release: publishes the buffer write to the consumer
        }

        @RealTimeSafe
        int drain(IndexConsumer sink) {
            long h = head.get();
            long t = tail.get(); // acquire: sees buffer writes up to t
            int count = 0;
            while (h < t) {
                sink.accept(buffer[(int) (h & mask)]);
                h++;
                count++;
            }
            head.set(h);
            return count;
        }
    }
}
