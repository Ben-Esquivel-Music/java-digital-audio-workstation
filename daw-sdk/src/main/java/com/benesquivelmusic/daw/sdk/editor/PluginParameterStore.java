package com.benesquivelmusic.daw.sdk.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

/**
 * The real-time-safe parameter store the host owns and a plugin editor
 * reads / writes through {@link EditorContext#parameterStore()} (Plugin View
 * Design Book §4.6). It is the enforcement seam behind Principle §2.6: a
 * plugin's editor cannot reach its {@code AudioProcessor} directly — both the
 * FX side (a knob turn) and the audio side (an internal envelope follower)
 * talk only to this store. Alongside parameter values the store also carries
 * the plugin's audio-side meter snapshot (§6.4): the processor
 * {@linkplain #publishMeters(PluginMeterSnapshot) publishes} its instantaneous
 * reading and the host taps it via {@link #meters()} to drive the always-on
 * I/O meters in the editor chrome.
 *
 * <h2>Coherence and threading (§4.7)</h2>
 * <ul>
 *   <li>Every parameter has an authoritative value stored in a lock-free
 *       {@link AtomicLongArray} (as raw {@code double} bits). {@link #value(int)}
 *       returns a coherent, allocation-free snapshot from either thread.</li>
 *   <li>{@link #writeFromUi(int, double)} runs on the FX thread: it clamps and
 *       publishes the new value, then posts the parameter's index onto a
 *       coalescing pending set the audio thread drains in
 *       {@code process(...)} via {@link #drainToAudio(IndexConsumer)}.</li>
 *   <li>{@link #writeFromAudio(int, double)} runs on the audio thread and is
 *       {@link RealTimeSafe}: it clamps and publishes, then posts onto a
 *       <em>separate</em> pending set the FX thread drains via
 *       {@link #drainToUi(IndexConsumer)} for display update.</li>
 *   <li>The audio-side meter snapshot (§6.4) is a single
 *       {@link AtomicReference} the audio thread {@linkplain
 *       #publishMeters(PluginMeterSnapshot) publishes} into and the FX thread
 *       reads via {@link #meters()}. Latest-wins with no ring: meters are a
 *       continuously refreshed level, so a missed intermediate frame carries
 *       no information.</li>
 * </ul>
 *
 * <p>Each direction has a single producer and consumer, with one published
 * generation per parameter and thread-owned sequence arrays. Repeated writes
 * coalesce, so even a burst larger than a render block cannot lose the final
 * value of a parameter. A drain makes one bounded pass over the generations and reads
 * values from the coherent array. This keeps the audio-thread path free of
 * allocation, locking and unbounded work.</p>
 *
 * <p>Indices are dense ({@code 0 .. parameterCount()-1}) in the order the store
 * was constructed. {@link #indexOf(int)} maps a {@link PluginParameter#id()} to
 * its index; that lookup is for setup / FX-side code only — the audio-thread
 * methods take an index so they never touch the map.</p>
 */
public final class PluginParameterStore {

    /**
     * Allocation-free callback for draining parameter changes. Receives the dense
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
    private final AtomicLongArray uiToAudio;
    private final AtomicLongArray audioToUi;
    private final long[] uiGeneration;
    private final long[] audioGeneration;
    private final long[] audioSeen;
    private final long[] uiSeen;
    private final AtomicReference<PluginMeterSnapshot> meterSnapshot =
            new AtomicReference<>(PluginMeterSnapshot.SILENT);

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
        this.uiToAudio = new AtomicLongArray(n);
        this.audioToUi = new AtomicLongArray(n);
        this.uiGeneration = new long[n];
        this.audioGeneration = new long[n];
        this.audioSeen = new long[n];
        this.uiSeen = new long[n];
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
        uiToAudio.set(index, ++uiGeneration[index]);
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
        audioToUi.set(index, ++audioGeneration[index]);
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
        return drain(uiToAudio, audioSeen, sink);
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
        return drain(audioToUi, uiSeen, sink);
    }

    /**
     * Audio-thread publish of the plugin's instantaneous meter reading (§6.4).
     * The plugin's processor allocates the immutable
     * {@link PluginMeterSnapshot}; the store only publishes the reference — no
     * locking, no blocking, no allocation inside the store. Latest-wins and
     * there is no ring: meters are a continuously refreshed level, so a missed
     * intermediate frame is meaningless — the FX side always reads the newest
     * snapshot via {@link #meters()}.
     *
     * @param snapshot the instantaneous meter reading; must not be {@code null}
     */
    @RealTimeSafe
    public void publishMeters(PluginMeterSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        meterSnapshot.set(snapshot);
    }

    /**
     * FX-side read of the latest audio-side meter snapshot (§6.4). The host
     * taps this to drive the always-on I/O meters in the editor chrome.
     *
     * @return the latest published snapshot; never {@code null} —
     *         {@link PluginMeterSnapshot#SILENT} until the first
     *         {@link #publishMeters(PluginMeterSnapshot)}
     */
    public PluginMeterSnapshot meters() {
        return meterSnapshot.get();
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

    /** One bounded pass coalesces each changed parameter without losing burst writes. */
    @RealTimeSafe
    private static int drain(AtomicLongArray published, long[] seen, IndexConsumer sink) {
        int count = 0;
        for (int index = 0; index < seen.length; index++) {
            long generation = published.get(index);
            if (generation != seen[index]) {
                seen[index] = generation;
                sink.accept(index);
                count++;
            }
        }
        return count;
    }
}
