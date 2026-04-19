package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reflectively binds automation lanes to {@link ProcessorParam}-annotated
 * setters on the DSP processors loaded in a {@link MixerChannel}'s
 * {@link InsertSlot insert slots}, and applies automation values on the
 * real-time audio thread via cached {@link MethodHandle}s.
 *
 * <p>The binder has two distinct phases:</p>
 *
 * <ol>
 *   <li><b>Discovery (non-real-time)</b> — {@link #rebind(MixerChannel)} walks
 *       the channel's insert slots, reflects over every
 *       {@link ProcessorParam}-annotated getter/setter pair on each processor,
 *       unreflects each setter into a {@link MethodHandle} pre-bound to its
 *       processor instance, and stores the resulting bindings in a
 *       pre-allocated {@code ParameterBinding[]}. A parallel list of
 *       {@link PluginParameterTarget} descriptors is built for the UI.</li>
 *   <li><b>Apply (real-time)</b> — {@link #apply(MixerChannel, AutomationData,
 *       double)} iterates the pre-built binding array, reads each active
 *       automation lane at the current transport position, clamps the value
 *       to the parameter's declared range, and invokes the cached
 *       {@code MethodHandle} via {@link MethodHandle#invokeExact(Object...)
 *       invokeExact}. The apply path performs no reflection lookups and no
 *       heap allocations — it is annotated {@link RealTimeSafe}.</li>
 * </ol>
 *
 * <p>Each binding is identified by the pair {@code (insertSlotIndex, paramId)}
 * and exposed to automation data through a synthetic
 * {@link PluginParameterTarget#pluginInstanceId() pluginInstanceId} of the
 * form {@code "reflective:<channelName>/slot-<index>/<ProcessorClass>"}.
 * The UI uses {@link #getAutomatablePluginParameters(MixerChannel)} to
 * populate parameter dropdowns.</p>
 *
 * <p>Typical host wiring:</p>
 * <pre>{@code
 * // Non-real-time: pre-allocate bindings when playback is prepared.
 * mixer.prepareForPlayback(channels, blockSize);   // internally calls binder.rebindAll()
 *
 * // Non-real-time: rebind after inserts change.
 * channel.insertEffect(slotIndex, "Compressor", compressor);
 * binder.rebind(channel);
 *
 * // Real-time: invoked from AudioEngine.processBlock via RenderPipeline.applyAutomation.
 * binder.apply(channel, automationData, transport.getPositionInBeats());
 * }</pre>
 */
public final class ReflectiveParameterBinder {

    /** Bindings for a single {@link MixerChannel}, pre-allocated for allocation-free apply. */
    private static final ParameterBinding[] EMPTY = new ParameterBinding[0];

    /**
     * Per-channel binding state. Keyed by identity so channels renamed in
     * the UI keep their bindings until an explicit {@link #rebind(MixerChannel)
     * rebind}.
     */
    private final Map<MixerChannel, ChannelBindings> byChannel = new IdentityHashMap<>();

    /** Creates a new, empty binder. */
    public ReflectiveParameterBinder() {
    }

    // ── Discovery (non-real-time) ───────────────────────────────────────────

    /**
     * Re-computes the binding array for the given channel. Safe to call from
     * non-real-time code such as UI callbacks and
     * {@link com.benesquivelmusic.daw.core.mixer.Mixer#prepareForPlayback(int, int)
     * Mixer.prepareForPlayback}.
     *
     * <p>This method performs reflection and may allocate. It must not be
     * called from the audio thread.</p>
     *
     * @param channel the channel whose inserts were just (re)configured
     */
    public void rebind(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        ChannelBindings bindings = discover(channel);
        byChannel.put(channel, bindings);
    }

    /**
     * Removes any cached bindings for the given channel.
     *
     * @param channel the channel to forget
     */
    public void forget(MixerChannel channel) {
        byChannel.remove(channel);
    }

    /**
     * Returns the list of automatable plugin-parameter targets for the given
     * channel. Suitable for populating a UI parameter dropdown.
     *
     * <p>If {@link #rebind(MixerChannel)} has never been called for this
     * channel, discovery is performed on demand.</p>
     *
     * @param channel the channel to query
     * @return an unmodifiable list of targets; never {@code null}
     */
    public List<PluginParameterTarget> getAutomatablePluginParameters(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        ChannelBindings bindings = byChannel.get(channel);
        if (bindings == null) {
            bindings = discover(channel);
            byChannel.put(channel, bindings);
        }
        return bindings.targets;
    }

    // ── Apply (real-time) ───────────────────────────────────────────────────

    /**
     * Applies every active plugin-parameter automation lane for the given
     * channel at the given transport position.
     *
     * <p>Values are clamped to each parameter's declared {@code [min, max]}
     * range before the setter is invoked. Lanes with zero points are skipped.
     * Channels that have never been bound (or whose inserts have changed
     * without a subsequent {@link #rebind(MixerChannel) rebind}) are ignored.</p>
     *
     * <p>This method performs no reflection, no allocation, and no
     * synchronization — it may be called from the audio thread.</p>
     *
     * @param channel     the channel to apply automation to
     * @param automation  the track's automation data
     * @param timeInBeats the current transport position in beats
     */
    @RealTimeSafe
    public void apply(MixerChannel channel, AutomationData automation, double timeInBeats) {
        ChannelBindings bindings = byChannel.get(channel);
        if (bindings == null) {
            return;
        }
        ParameterBinding[] array = bindings.bindings;
        for (int i = 0, n = array.length; i < n; i++) {
            ParameterBinding b = array[i];
            AutomationLane lane = automation.getPluginLane(b.target);
            if (lane == null || lane.getPointCount() == 0) {
                continue;
            }
            double raw = lane.getValueAtTime(timeInBeats);
            double value = Math.clamp(raw, b.min, b.max);
            try {
                b.setter.invokeExact(value);
            } catch (Throwable t) {
                // Setters should not throw for in-range values; surface any
                // unchecked exception and let the audio thread error path handle it.
                throw new IllegalStateException(
                        "automation setter for parameter " + b.target + " failed", t);
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Reflects over the channel's insert slots and produces the full list of
     * bindings. Allocation-friendly; never called from the audio thread.
     */
    private static ChannelBindings discover(MixerChannel channel) {
        List<InsertSlot> slots = channel.getInsertSlots();
        if (slots.isEmpty()) {
            return new ChannelBindings(EMPTY, List.of());
        }
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        List<ParameterBinding> out = new ArrayList<>();
        List<PluginParameterTarget> targets = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            Object processor = slots.get(slotIndex).getProcessor();
            if (processor == null) {
                continue;
            }
            List<ReflectedSetter> params = reflectAnnotatedSetters(processor.getClass());
            for (ReflectedSetter p : params) {
                MethodHandle setter;
                try {
                    // Unreflect -> (ProcessorClass, double)V, then bind receiver -> (double)V.
                    // Pre-binding the receiver produces a MethodHandle whose invokeExact call
                    // site matches the fixed (double)V signature, enabling allocation-free
                    // invocation on the audio thread.
                    setter = lookup.unreflect(p.setter).bindTo(processor);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "Cannot unreflect setter " + p.setter + " for @ProcessorParam binding", e);
                }
                String instanceId = instanceIdFor(channel, slotIndex, processor.getClass());
                String displayName = (p.unit == null || p.unit.isEmpty())
                        ? p.name
                        : p.name + " (" + p.unit + ")";
                PluginParameterTarget target = new PluginParameterTarget(
                        instanceId, p.id, displayName, p.min, p.max, p.defaultValue,
                        p.unit == null ? "" : p.unit);
                out.add(new ParameterBinding(slotIndex, p.id, p.min, p.max, setter, target));
                targets.add(target);
            }
        }
        ParameterBinding[] array = out.isEmpty() ? EMPTY : out.toArray(new ParameterBinding[0]);
        return new ChannelBindings(array, List.copyOf(targets));
    }

    /**
     * Returns the {@link PluginParameterTarget#pluginInstanceId() pluginInstanceId}
     * convention used by this binder:
     * {@code "reflective:<channelName>/slot-<slotIndex>/<ProcessorClass>"}.
     *
     * <p>This convention keys bindings by {@code (slotIndex, paramId)} within
     * a channel while remaining stable across identical channel configurations
     * so automation persists across sessions.</p>
     *
     * @param channel     the owning channel
     * @param slotIndex   the zero-based insert slot index
     * @param processor   the processor class
     * @return the synthetic instance id
     */
    public static String instanceIdFor(MixerChannel channel, int slotIndex, Class<?> processor) {
        return "reflective:" + channel.getName()
                + "/slot-" + slotIndex
                + "/" + processor.getName();
    }

    private static List<ReflectedSetter> reflectAnnotatedSetters(Class<?> processorClass) {
        // Key-by-id so duplicate declarations in subclasses/interfaces don't
        // produce duplicate bindings.
        Map<Integer, ReflectedSetter> byId = new HashMap<>();
        for (Method m : processorClass.getMethods()) {
            ProcessorParam ann = m.getAnnotation(ProcessorParam.class);
            if (ann == null || m.getParameterCount() != 0) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            if (ret != double.class && ret != Double.class) {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get")) {
                continue;
            }
            String setterName = "set" + name.substring(3);
            Method setter;
            try {
                setter = processorClass.getMethod(setterName, double.class);
            } catch (NoSuchMethodException e) {
                continue; // Already rejected by ReflectiveParameterRegistry with a clearer message.
            }
            byId.put(ann.id(), new ReflectedSetter(
                    ann.id(), ann.name(), ann.unit(), ann.min(), ann.max(),
                    ann.defaultValue(), setter));
        }
        List<ReflectedSetter> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparingInt(r -> r.id));
        return sorted;
    }

    /** Immutable, pre-reflected metadata about a single annotated setter. */
    private record ReflectedSetter(
            int id, String name, String unit,
            double min, double max, double defaultValue, Method setter) {}

    /**
     * A single {@code (slotIndex, paramId)} binding. The {@link #setter} is
     * pre-bound to its processor instance, so invoking it takes a single
     * {@code double} argument and allocates nothing on the happy path.
     */
    private static final class ParameterBinding {
        final int slotIndex;
        final int paramId;
        final double min;
        final double max;
        final MethodHandle setter;
        final PluginParameterTarget target;

        ParameterBinding(int slotIndex, int paramId, double min, double max,
                         MethodHandle setter, PluginParameterTarget target) {
            this.slotIndex = slotIndex;
            this.paramId = paramId;
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.target = target;
        }
    }

    /** Pre-allocated per-channel state held by the binder. */
    private static final class ChannelBindings {
        final ParameterBinding[] bindings;
        final List<PluginParameterTarget> targets;

        ChannelBindings(ParameterBinding[] bindings, List<PluginParameterTarget> targets) {
            this.bindings = bindings;
            this.targets = targets.isEmpty() ? List.of() : Collections.unmodifiableList(targets);
        }
    }
}
