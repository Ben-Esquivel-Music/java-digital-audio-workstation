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
     * rotator / enhancer, a binaural renderer, an A-format or ASDM converter,
     * an air-absorption filter or an ambience upmixer. Declared by the twelve
     * {@code AudioProcessor}s under {@code core.spatial}; consumed by
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
