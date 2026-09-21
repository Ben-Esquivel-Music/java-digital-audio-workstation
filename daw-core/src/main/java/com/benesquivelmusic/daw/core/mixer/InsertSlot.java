package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.PluginCapabilities;
import com.benesquivelmusic.daw.core.plugin.PluginCapabilityIntrospector;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

/**
 * Represents a single insert effect slot on a mixer channel strip.
 *
 * <p>Each slot holds a reference to an {@link AudioProcessor}, a display name
 * identifying the loaded effect, and an independent bypass flag. When bypassed,
 * the slot's processor is excluded from the channel's processing chain.</p>
 *
 * <p>For dynamics processors that implement
 * {@link com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor}, a
 * sidechain source can be configured to route another mixer channel's audio
 * as the detection input.</p>
 */
public final class InsertSlot {

    private final UUID pluginInstanceId = UUID.randomUUID();
    private final String name;
    private final AudioProcessor processor;
    private final InsertEffectType effectType;
    private final DawPlugin plugin;
    private final PluginCapabilities capabilities;
    private final DawPlugin editorPlugin;
    private record ParameterBinding(PluginParameterStore store, PluginParameterStore.IndexConsumer sink) { }
    private volatile ParameterBinding parameterBinding;
    private final ReflectiveParameterRegistry.AudioParameterSetter parameterSetter;
    private boolean editorParametersFinalized;
    private Runnable disposal;
    private boolean disposed;
    private MixerChannel owner;
    private final boolean instrument;
    private com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor supervisor;
    private boolean parameterFaulted;
    private volatile boolean bypassed;
    private volatile boolean expensive;
    private volatile MixerChannel sidechainSource;

    /**
     * Creates a new insert slot with the specified name and processor.
     *
     * @param name      the display name of the effect (e.g., "Compressor")
     * @param processor the audio processor for this slot
     */
    public InsertSlot(String name, AudioProcessor processor) {
        this(name, processor, null, null);
    }

    /**
     * Creates a new insert slot with the specified name, processor, and effect type.
     *
     * @param name       the display name of the effect (e.g., "Compressor")
     * @param processor  the audio processor for this slot
     * @param effectType the built-in effect type, or {@code null} for CLAP/external plugins
     */
    public InsertSlot(String name, AudioProcessor processor, InsertEffectType effectType) {
        this(name, processor, effectType, null);
    }

    /**
     * Creates a new insert slot associated with a {@link DawPlugin}.
     *
     * <p>The plugin reference is optional — it is retained so the host can
     * route plugin-parameter automation values back to the plugin via
     * {@link DawPlugin#setAutomatableParameter(int, double)} during playback.
     * Slots created without a plugin (built-in DSP processors, legacy code
     * paths) pass {@code null}.</p>
     *
     * @param name       the display name of the effect
     * @param processor  the audio processor for this slot
     * @param effectType the built-in effect type, or {@code null}
     * @param plugin     the source plugin, or {@code null}
     */
    public InsertSlot(String name, AudioProcessor processor,
                      InsertEffectType effectType, DawPlugin plugin) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
        if (processor instanceof com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor prepared) {
            prepared.enableRealtimeParameterPreparation();
        }
        this.effectType = effectType;
        this.plugin = plugin != null ? plugin : processor instanceof DawPlugin dawPlugin ? dawPlugin : null;
        this.instrument = this.plugin != null && this.plugin.getDescriptor().type() == PluginType.INSTRUMENT;
        this.capabilities = PluginCapabilityIntrospector.capabilitiesOf(processor);
        this.editorPlugin = this.plugin != null ? this.plugin : new ProcessorPlugin();
        var parameterStore = new PluginParameterStore(editorPlugin.getParameters());
        var reflected = ReflectiveParameterRegistry.getParameterValues(processor);
        boolean pluginSetter = hasParameterSetter(this.plugin);
        parameterSetter = pluginSetter ? this.plugin::setAutomatableParameter
                : this.plugin instanceof com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost external
                ? external::setParameterValue : ReflectiveParameterRegistry.createAudioParameterSetter(processor);
        for (int i = 0; i < parameterStore.parameterCount(); i++) {
            Double value = reflected.get(parameterStore.parameterIdAt(i));
            if (value != null && !pluginSetter) {
                parameterStore.writeFromAudio(i, value);
            } else if (this.plugin instanceof com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost external) {
                parameterStore.writeFromAudio(i, external.getParameterValue(parameterStore.parameterIdAt(i)));
            }
        }
        parameterBinding = bindParameters(parameterStore);
        this.bypassed = false;
        // Story 129 (UI): mark long-tail / oversampled / convolution
        // built-in DSP "expensive" by default so the BypassExpensive
        // degradation policy has sensible candidates without the user
        // having to flag each insert manually. Conservative dynamics
        // (compressor, gate, EQ) default to false and stay engaged.
        this.expensive = isExpensiveByDefault(effectType);
    }

    private static boolean hasParameterSetter(DawPlugin plugin) {
        if (plugin == null) {
            return false;
        }
        try {
            return plugin.getClass().getMethod("setAutomatableParameter", int.class, double.class)
                    .getDeclaringClass() != DawPlugin.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private ParameterBinding bindParameters(PluginParameterStore store) {
        return new ParameterBinding(store,
                index -> parameterSetter.set(store.parameterIdAt(index), store.value(index)));
    }

    /**
     * Finalizes metadata from an FX-thread Declarative factory before its controls bind.
     * The first factory may refine the plugin's initial descriptors. Matching values,
     * including pending UI changes, survive that refinement. The same finalized store
     * then survives every close and reload; rendering captures store and sink together.
     */
    public synchronized void prepareEditorParameters(List<PluginParameter> parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (editorParametersFinalized || parameters.isEmpty()) { return; }
        PluginParameterStore current = parameterBinding.store();
        boolean same = current.parameterCount() == parameters.size();
        for (int index = 0; same && index < parameters.size(); index++) {
            same = current.parameter(index).equals(parameters.get(index));
        }
        if (!same) {
            var prepared = new PluginParameterStore(parameters);
            for (int index = 0; index < prepared.parameterCount(); index++) {
                for (int previousIndex = 0; previousIndex < current.parameterCount(); previousIndex++) {
                    if (current.parameterIdAt(previousIndex) == prepared.parameterIdAt(index)) {
                        prepared.writeFromUi(index, current.value(previousIndex));
                        break;
                    }
                }
            }
            parameterBinding = bindParameters(prepared);
        }
        editorParametersFinalized = true;
    }

    /** The editor always describes this slot's live processor. */
    public DawPlugin getEditorPlugin() {
        return editorPlugin;
    }

    /** Slot lifetime state: closing a window never drops pending parameter writes. */
    public PluginParameterStore getParameterStore() {
        return parameterBinding.store();
    }

    @RealTimeSafe
    public void drainParametersToAudio() {
        if (parameterFaulted) { return; }
        try {
            ParameterBinding binding = parameterBinding;
            binding.store().drainToAudio(binding.sink());
            if (processor instanceof com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor prepared) {
                prepared.applyPreparedParameters();
            }
        } catch (RuntimeException | Error failure) {
            parameterFaulted = true;
            if (supervisor != null) {
                supervisor.reportAudioFault(this, failure);
            } else {
                setBypassed(true);
            }
        }
    }

    public boolean isInstrument() {
        return instrument;
    }

    /** Resolves pending controls and expensive DSP state before offline rendering begins. */
    public void prepareParametersForOfflineRendering() {
        drainParametersToAudio();
        if (processor instanceof com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor prepared) {
            prepared.awaitParameterPreparation();
        }
    }

    /** Transfers resource ownership (including a plugin loader) to this graph slot. */
    public synchronized void setDisposal(Runnable disposal) {
        if (disposed) {
            throw new IllegalStateException("Insert slot has been disposed");
        }
        this.disposal = Objects.requireNonNull(disposal);
    }

    void attachOwner(MixerChannel channel) {
        owner = channel;
        setBypassed(bypassed);
    }

    void attachSupervisor(com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    void removedFromGraph() {
        if (plugin instanceof com.benesquivelmusic.daw.core.plugin.MetronomePlugin metronome
                && metronome.getMetronome() != null) {
            metronome.getMetronome().releasePluginBypass(this);
        }
    }

    /** Off-audio-thread eviction/reenable publication for the exact owning channel. */
    public void rebuildOwningChain() {
        MixerChannel channel = owner;
        if (channel != null) { channel.refreshInsertChain(); }
    }

    public boolean isInGraph() {
        MixerChannel channel = owner;
        return channel != null && channel.getInsertSlots().contains(this);
    }

    /** Called only after the owning channel has quiesced every old render. */
    public synchronized void disposeAfterQuiescence() {
        if (disposed) {
            return;
        }
        disposed = true;
        removedFromGraph();
        if (processor instanceof com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor prepared) {
            prepared.closeParameterPreparation();
        }
        if (disposal != null) {
            disposal.run();
        } else if (plugin != null) {
            plugin.dispose();
        } else if (processor instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close insert " + name, e);
            }
        }
    }

    private final class ProcessorPlugin implements DawPlugin {
        private final PluginDescriptor descriptor = new PluginDescriptor(
                "processor." + processor.getClass().getName(), name, "1.0", "DAW", PluginType.EFFECT);

        @Override public PluginDescriptor getDescriptor() { return descriptor; }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public Optional<AudioProcessor> asAudioProcessor() { return Optional.of(processor); }
        @Override public List<PluginParameter> getParameters() {
            return ReflectiveParameterRegistry.getParameterDescriptors(processor.getClass());
        }
    }

    private static boolean isExpensiveByDefault(InsertEffectType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case CONVOLUTION_REVERB,
                 REVERB,
                 SPRING_REVERB,
                 VELVET_NOISE_REVERB,
                 ANALOG_DISTORTION,
                 WAVESHAPER,
                 PITCH_SHIFT,
                 TIME_STRETCH,
                 LESLIE -> true;
            default -> false;
        };
    }

    /**
     * Returns the stable, per-instance plugin id used by the typed
     * {@code PluginEvent} hierarchy (story 283). Each {@code InsertSlot}
     * instance has its own freshly-generated {@link UUID}; insert /
     * remove / bypass actions publish events carrying this id so
     * subscribers can locate the affected slot in the project model.
     *
     * @return the plugin instance id (never {@code null})
     */
    public UUID getPluginInstanceId() {
        return pluginInstanceId;
    }

    /**
     * Returns the display name of the effect in this slot.
     *
     * @return the effect name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the audio processor loaded in this slot.
     *
     * @return the audio processor
     */
    public AudioProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the built-in effect type for this slot, or {@code null} if the
     * slot was created without a type (e.g., for CLAP/external plugins or
     * legacy code that uses the two-argument constructor).
     *
     * @return the effect type, or {@code null}
     */
    public InsertEffectType getEffectType() {
        return effectType;
    }

    /**
     * Returns the {@link DawPlugin} that produced this slot, or {@code null}
     * if the slot was created directly from a raw {@link AudioProcessor}
     * (built-in DSP, CLAP/external plugin paths, legacy code).
     *
     * <p>The host uses the plugin reference to route plugin-parameter
     * automation values from {@link com.benesquivelmusic.daw.core.automation.AutomationData
     * AutomationData} back to {@link DawPlugin#setAutomatableParameter(int, double)}
     * during playback.</p>
     *
     * @return the originating plugin, or {@code null}
     */
    public DawPlugin getPlugin() {
        return plugin;
    }

    /**
     * Returns the reflectively-discovered {@link PluginCapabilities} of the
     * processor loaded in this slot.
     *
     * <p>UI components (mixer channel strips, insert rack, generic parameter
     * editor) should query this record instead of performing {@code instanceof}
     * checks against specific capability interfaces. The value is computed once
     * when the slot is constructed and cached per processor class by
     * {@link PluginCapabilityIntrospector}.</p>
     *
     * @return the capabilities of the slot's processor; never {@code null}
     */
    public PluginCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Returns whether this insert slot is bypassed.
     *
     * @return {@code true} if bypassed
     */
    public boolean isBypassed() {
        return bypassed;
    }

    /**
     * Sets the bypassed state of this insert slot.
     *
     * <p><strong>Note:</strong> When modifying bypass state, call
     * {@link MixerChannel#setInsertBypassed(int, boolean)} instead to ensure
     * the channel's effects chain is updated accordingly.</p>
     *
     * @param bypassed {@code true} to bypass this slot
     */
    public void setBypassed(boolean bypassed) {
        this.bypassed = bypassed;
        if (!bypassed) { parameterFaulted = false; }
        if (plugin instanceof com.benesquivelmusic.daw.core.plugin.MetronomePlugin metronome
                && metronome.getMetronome() != null) {
            metronome.getMetronome().setPluginBypassed(this, bypassed);
        }
    }

    /**
     * Returns whether this insert is flagged as "expensive" — i.e.
     * eligible for selective bypass when the channel's per-track CPU
     * budget triggers the
     * {@link com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy.BypassExpensive}
     * policy (story 129 UI).
     *
     * <p>Inserts the user considers mandatory (limiters, de-essers,
     * vocal compression) are left at {@code false} so that the engine
     * never silently disables them under load.</p>
     *
     * @return {@code true} when the insert is flagged as expensive
     */
    public boolean isExpensive() {
        return expensive;
    }

    /**
     * Sets the "expensive" flag for this insert. See {@link #isExpensive()}.
     *
     * @param expensive {@code true} to mark the insert as eligible for
     *                  bypass under {@code BypassExpensive}
     */
    public void setExpensive(boolean expensive) {
        this.expensive = expensive;
    }

    /**
     * Returns the mixer channel configured as the sidechain source for this
     * insert, or {@code null} if no sidechain source is set (internal
     * detection).
     *
     * <p>Only meaningful when the slot's processor implements
     * {@link com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor}.</p>
     *
     * @return the sidechain source channel, or {@code null}
     */
    public MixerChannel getSidechainSource() {
        return sidechainSource;
    }

    /**
     * Sets the mixer channel to use as the sidechain detection source.
     *
     * <p>Pass {@code null} to clear the sidechain source and revert to
     * internal detection. The change takes effect on the next audio
     * processing block.</p>
     *
     * @param source the sidechain source channel, or {@code null} to clear
     */
    public void setSidechainSource(MixerChannel source) {
        this.sidechainSource = source;
    }
}
