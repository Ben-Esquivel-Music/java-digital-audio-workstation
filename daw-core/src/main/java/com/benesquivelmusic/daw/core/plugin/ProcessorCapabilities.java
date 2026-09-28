package com.benesquivelmusic.daw.core.plugin;

/**
 * The vocabulary of {@link ProcessorCapability @ProcessorCapability} tag
 * values used by production processors.
 *
 * <p>{@code @ProcessorCapability} is an open-ended, free-form string tag
 * surfaced through {@link PluginCapabilities#customCapabilities()}; nothing
 * validates the strings. Every tag a production processor declares — and
 * every tag a consumer tests for — is therefore spelled through one of these
 * constants, so a typo on either side is a compile error rather than a
 * silently-absent capability. Add a constant here (with a Javadoc stating
 * who declares it and who consumes it) before tagging a processor with a
 * new value.</p>
 *
 * <p>Story 322 introduced the first production tag, {@link #SPATIAL}.</p>
 */
public final class ProcessorCapabilities {

    /**
     * The processor is a spatial-audio node — an ambisonic encoder / decoder /
     * rotator / enhancer, a binaural renderer, converter or externalization
     * processor, an A-format converter or ASDM salient/diffuse separator, a
     * VBAP or panning-table 3D panner, a room simulator or the directional FDN
     * reverb with first-order Ambisonic output, the air-absorption filter, or
     * the ambience upmixer. Declared by every concrete {@code AudioProcessor}
     * under {@code core.spatial} — pinned by {@code SpatialInsertsTest}, whose
     * package scan fails on any concrete top-level processor there that it
     * does not list and tag-check. Consumed by
     * {@link com.benesquivelmusic.daw.core.mixer.SpatialInserts#hasSpatialNode},
     * which the mixer strip uses to show its 3D-panner affordance only for a
     * channel whose insert chain actually contains a spatial node (story 322,
     * Audio Engine Wiring Design Book §5.6 "3D panner button").
     */
    public static final String SPATIAL = "spatial";

    private ProcessorCapabilities() {
        // constants only
    }
}
