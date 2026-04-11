package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.track.TrackColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single channel strip in the mixer.
 *
 * <p>Each mixer channel has independent volume, pan, mute, solo, and send level
 * controls. The send level controls how much of this channel's audio is routed
 * to the auxiliary/return bus (e.g., reverb, delay return).</p>
 *
 * <p>A channel may have multiple {@link Send} objects, each routing audio to a
 * different return bus with independent level and pre/post-fader mode.</p>
 *
 * <p>Each channel provides up to {@value #MAX_INSERT_SLOTS} insert effect slots.
 * Insert effects are applied in order via an internal {@link EffectsChain}.
 * Individual inserts can be bypassed, reordered, added, or removed.</p>
 */
public final class MixerChannel {

    /** Maximum number of insert effect slots per channel. */
    public static final int MAX_INSERT_SLOTS = 8;

    private final String name;
    private double volume;
    private double pan;
    private boolean muted;
    private boolean solo;
    private double sendLevel;
    private boolean phaseInverted;
    private TrackColor color;
    private final List<Send> sends = new ArrayList<>();
    private final List<InsertSlot> insertSlots = new ArrayList<>();
    private final EffectsChain effectsChain = new EffectsChain();
    private int allocatedChannels;
    private int allocatedBlockSize;
    private Runnable onEffectsChainChanged;

    /**
     * Creates a new mixer channel with the specified name.
     *
     * @param name the display name
     */
    public MixerChannel(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.volume = 1.0;
        this.pan = 0.0;
        this.muted = false;
        this.solo = false;
        this.sendLevel = 0.0;
        this.phaseInverted = false;
    }

    /** Returns the channel name. */
    public String getName() {
        return name;
    }

    /** Returns the volume level (0.0 – 1.0). */
    public double getVolume() {
        return volume;
    }

    /** Sets the volume level. */
    public void setVolume(double volume) {
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        this.volume = volume;
    }

    /** Returns the pan position (−1.0 to 1.0). */
    public double getPan() {
        return pan;
    }

    /** Sets the pan position. */
    public void setPan(double pan) {
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
        this.pan = pan;
    }

    /** Returns whether this channel is muted. */
    public boolean isMuted() {
        return muted;
    }

    /** Sets the muted state. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Returns whether this channel is soloed. */
    public boolean isSolo() {
        return solo;
    }

    /** Sets the solo state. */
    public void setSolo(boolean solo) {
        this.solo = solo;
    }

    /** Returns the send level (0.0 – 1.0). */
    public double getSendLevel() {
        return sendLevel;
    }

    /** Sets the send level. */
    public void setSendLevel(double sendLevel) {
        if (sendLevel < 0.0 || sendLevel > 1.0) {
            throw new IllegalArgumentException("sendLevel must be between 0.0 and 1.0: " + sendLevel);
        }
        this.sendLevel = sendLevel;
    }

    /** Returns whether this channel's phase is inverted. */
    public boolean isPhaseInverted() {
        return phaseInverted;
    }

    /** Sets the phase-inverted state. */
    public void setPhaseInverted(boolean phaseInverted) {
        this.phaseInverted = phaseInverted;
    }

    /**
     * Returns the color assigned to this mixer channel, or {@code null} if
     * no color has been assigned.
     *
     * @return the channel color, or {@code null}
     */
    public TrackColor getColor() {
        return color;
    }

    /**
     * Sets the color for this mixer channel.
     *
     * @param color the channel color (may be {@code null} to clear)
     */
    public void setColor(TrackColor color) {
        this.color = color;
    }

    /**
     * Adds a send routing to a return bus.
     *
     * @param send the send to add
     */
    public void addSend(Send send) {
        Objects.requireNonNull(send, "send must not be null");
        sends.add(send);
    }

    /**
     * Removes a send routing.
     *
     * @param send the send to remove
     * @return {@code true} if the send was removed
     */
    public boolean removeSend(Send send) {
        return sends.remove(send);
    }

    /**
     * Returns an unmodifiable view of the sends on this channel.
     *
     * @return the list of sends
     */
    public List<Send> getSends() {
        return Collections.unmodifiableList(sends);
    }

    /**
     * Returns the send targeting the specified return bus, or {@code null} if
     * no such send exists.
     *
     * @param target the return bus to look up
     * @return the send for the given target, or {@code null}
     */
    public Send getSendForTarget(MixerChannel target) {
        for (Send send : sends) {
            if (send.getTarget() == target) {
                return send;
            }
        }
        return null;
    }

    /**
     * Pre-allocates intermediate buffers for this channel's effects chain so
     * that real-time processing remains zero-allocation.
     *
     * <p>Call this when the audio engine starts or when the buffer size changes.
     * The dimensions are remembered so that {@link #rebuildEffectsChain()} can
     * re-allocate automatically when inserts are added or removed.</p>
     *
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     * @param blockSize     the number of sample frames per processing block
     */
    public void prepareEffectsChain(int audioChannels, int blockSize) {
        this.allocatedChannels = audioChannels;
        this.allocatedBlockSize = blockSize;
        if (!effectsChain.isEmpty()) {
            effectsChain.allocateIntermediateBuffers(audioChannels, blockSize);
        }
    }

    /**
     * Sets a callback that is invoked whenever the effects chain is rebuilt
     * (e.g., when inserts are added, removed, reordered, or bypassed).
     *
     * <p>The {@link com.benesquivelmusic.daw.core.mixer.Mixer Mixer} uses this
     * to trigger delay compensation recalculation.</p>
     *
     * @param callback the callback to invoke, or {@code null} to clear
     */
    public void setOnEffectsChainChanged(Runnable callback) {
        this.onEffectsChainChanged = callback;
    }

    // ── Insert effect slot management ───────────────────────────────────────

    /**
     * Returns the {@link EffectsChain} wired to this channel's insert slots.
     *
     * <p>The chain contains only the processors from non-bypassed insert slots,
     * in slot order. Use this chain in the audio processing pipeline.</p>
     *
     * @return the effects chain for this channel
     */
    public EffectsChain getEffectsChain() {
        return effectsChain;
    }

    /**
     * Adds an insert effect slot to the end of the insert chain.
     *
     * @param slot the insert slot to add
     * @throws IllegalStateException if the channel already has {@value #MAX_INSERT_SLOTS} inserts
     */
    public void addInsert(InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (insertSlots.size() >= MAX_INSERT_SLOTS) {
            throw new IllegalStateException(
                    "cannot exceed " + MAX_INSERT_SLOTS + " insert slots");
        }
        insertSlots.add(slot);
        rebuildEffectsChain();
    }

    /**
     * Inserts an effect slot at the specified index in the insert chain.
     *
     * @param index the insertion index
     * @param slot  the insert slot to add
     * @throws IllegalStateException if the channel already has {@value #MAX_INSERT_SLOTS} inserts
     */
    public void insertInsert(int index, InsertSlot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (insertSlots.size() >= MAX_INSERT_SLOTS) {
            throw new IllegalStateException(
                    "cannot exceed " + MAX_INSERT_SLOTS + " insert slots");
        }
        insertSlots.add(index, slot);
        rebuildEffectsChain();
    }

    /**
     * Removes the insert slot at the specified index.
     *
     * @param index the index of the slot to remove
     * @return the removed insert slot
     */
    public InsertSlot removeInsert(int index) {
        InsertSlot removed = insertSlots.remove(index);
        rebuildEffectsChain();
        return removed;
    }

    /**
     * Removes the specified insert slot.
     *
     * @param slot the insert slot to remove
     * @return {@code true} if the slot was removed
     */
    public boolean removeInsert(InsertSlot slot) {
        boolean removed = insertSlots.remove(slot);
        if (removed) {
            rebuildEffectsChain();
        }
        return removed;
    }

    /**
     * Moves an insert slot from one position to another in the insert chain.
     *
     * @param fromIndex the current index of the slot to move
     * @param toIndex   the target index for the slot
     * @throws IndexOutOfBoundsException if either index is out of range
     */
    public void moveInsert(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= insertSlots.size()) {
            throw new IndexOutOfBoundsException("fromIndex out of range: " + fromIndex);
        }
        if (toIndex < 0 || toIndex >= insertSlots.size()) {
            throw new IndexOutOfBoundsException("toIndex out of range: " + toIndex);
        }
        if (fromIndex == toIndex) {
            return;
        }
        InsertSlot slot = insertSlots.remove(fromIndex);
        insertSlots.add(toIndex, slot);
        rebuildEffectsChain();
    }

    /**
     * Sets the bypass state of the insert slot at the specified index and
     * updates the effects chain accordingly.
     *
     * @param index    the index of the insert slot
     * @param bypassed {@code true} to bypass the insert
     */
    public void setInsertBypassed(int index, boolean bypassed) {
        insertSlots.get(index).setBypassed(bypassed);
        rebuildEffectsChain();
    }

    /**
     * Returns an unmodifiable view of the insert slots on this channel.
     *
     * @return the list of insert slots
     */
    public List<InsertSlot> getInsertSlots() {
        return Collections.unmodifiableList(insertSlots);
    }

    /**
     * Returns the insert slot at the specified index.
     *
     * @param index the slot index
     * @return the insert slot
     */
    public InsertSlot getInsertSlot(int index) {
        return insertSlots.get(index);
    }

    /**
     * Returns the number of occupied insert slots.
     *
     * @return the insert count
     */
    public int getInsertCount() {
        return insertSlots.size();
    }

    /**
     * Rebuilds the internal {@link EffectsChain} from the current insert slots.
     *
     * <p>Only non-bypassed slots contribute their processors to the chain.
     * This method is called automatically by all insert-mutating methods.</p>
     */
    private void rebuildEffectsChain() {
        while (!effectsChain.isEmpty()) {
            effectsChain.removeProcessor(0);
        }
        for (InsertSlot slot : insertSlots) {
            if (!slot.isBypassed()) {
                effectsChain.addProcessor(slot.getProcessor());
            }
        }
        if (allocatedChannels > 0 && allocatedBlockSize > 0 && !effectsChain.isEmpty()) {
            effectsChain.allocateIntermediateBuffers(allocatedChannels, allocatedBlockSize);
        }
        Runnable callback = onEffectsChainChanged;
        if (callback != null) {
            callback.run();
        }
    }
}
