package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Objects;

/**
 * Represents a single insert effect slot on a mixer channel strip.
 *
 * <p>Each slot holds a reference to an {@link AudioProcessor}, a display name
 * identifying the loaded effect, and an independent bypass flag. When bypassed,
 * the slot's processor is excluded from the channel's processing chain.</p>
 */
public final class InsertSlot {

    private final String name;
    private final AudioProcessor processor;
    private final InsertEffectType effectType;
    private boolean bypassed;

    /**
     * Creates a new insert slot with the specified name and processor.
     *
     * @param name      the display name of the effect (e.g., "Compressor")
     * @param processor the audio processor for this slot
     */
    public InsertSlot(String name, AudioProcessor processor) {
        this(name, processor, null);
    }

    /**
     * Creates a new insert slot with the specified name, processor, and effect type.
     *
     * @param name       the display name of the effect (e.g., "Compressor")
     * @param processor  the audio processor for this slot
     * @param effectType the built-in effect type, or {@code null} for CLAP/external plugins
     */
    public InsertSlot(String name, AudioProcessor processor, InsertEffectType effectType) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
        this.effectType = effectType;
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
}
