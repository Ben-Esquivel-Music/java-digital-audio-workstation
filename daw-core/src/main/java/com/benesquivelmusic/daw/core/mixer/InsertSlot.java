package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.PluginCapabilities;
import com.benesquivelmusic.daw.core.plugin.PluginCapabilityIntrospector;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.util.Objects;

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

    private final String name;
    private final AudioProcessor processor;
    private final InsertEffectType effectType;
    private final DawPlugin plugin;
    private final PluginCapabilities capabilities;
    private volatile boolean bypassed;
    private MixerChannel sidechainSource;

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
        this.effectType = effectType;
        this.plugin = plugin;
        this.capabilities = PluginCapabilityIntrospector.capabilitiesOf(processor);
        this.bypassed = false;
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
