package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.PluginCapabilityIntrospector;
import com.benesquivelmusic.daw.core.plugin.ProcessorCapabilities;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.List;
import java.util.Objects;

/**
 * Answers "does this channel's insert chain contain a spatial node?" — the
 * fact the mixer strip's 3D-panner affordance is gated on (story 322, Audio
 * Engine Wiring Design Book §5.6 "3D panner button": hidden until a spatial
 * node exists in the channel chain).
 *
 * <p>A spatial node is any insert whose processor class declares
 * {@code @ProcessorCapability(}{@link ProcessorCapabilities#SPATIAL}{@code )}.
 * The tag is read through {@link PluginCapabilityIntrospector}, which
 * reflects once per processor class and caches the result, so repeated calls
 * for the same chain cost a few map lookups. A bypassed spatial insert still
 * counts: it exists in the chain, and the panner is an affordance over the
 * node, not over its bypass state.</p>
 *
 * <p><strong>Not for the real-time thread.</strong> The first call for a
 * processor class reflects over it; later calls hit a
 * {@code ConcurrentHashMap}. Call this from UI / view-model code in response
 * to {@link MixerChannel.ChangeKind#INSERTS}, never from a render path.</p>
 */
public final class SpatialInserts {

    private SpatialInserts() {
        // static utility
    }

    /**
     * Returns whether any insert on {@code channel} is a spatial node.
     *
     * @param channel the channel whose insert chain is inspected (must not be {@code null})
     * @return {@code true} if at least one insert's processor carries the
     *         {@link ProcessorCapabilities#SPATIAL} capability
     */
    public static boolean hasSpatialNode(MixerChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        List<InsertSlot> slots = channel.getInsertSlots();
        for (InsertSlot slot : slots) {
            if (isSpatial(slot.getProcessor())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether {@code processor}'s class declares the
     * {@link ProcessorCapabilities#SPATIAL} capability; {@code false} for
     * {@code null}.
     */
    public static boolean isSpatial(AudioProcessor processor) {
        if (processor == null) {
            return false;
        }
        return PluginCapabilityIntrospector.capabilitiesOf(processor.getClass())
                .customCapabilities()
                .contains(ProcessorCapabilities.SPATIAL);
    }
}
