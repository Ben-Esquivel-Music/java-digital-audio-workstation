package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.mastering.DitherProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in dithered bit-depth reducer — the <em>terminal</em> stage of the
 * mastering chain.
 *
 * <p>Wraps {@link DitherProcessor} as a first-class built-in plugin so it
 * appears in the Plugins menu under
 * {@link BuiltInPluginCategory#MASTERING Mastering}. Marked
 * {@code terminal = true}: the host
 * {@link com.benesquivelmusic.daw.core.mastering.MasteringChain MasteringChain}
 * forbids inserting any non-terminal stage after it — dither must always be
 * the very last stage of a mastering chain so its quantization noise is not
 * subsequently re-quantized or smeared.</p>
 *
 * <p>Parameters (and their automation IDs):</p>
 * <ul>
 *   <li>{@code 0 — Bit Depth} (16, 20, or 24)</li>
 *   <li>{@code 1 — Type} ordinal of {@link DitherProcessor.DitherType}
 *       (0=NONE, 1=RPDF, 2=TPDF, 3=NOISE_SHAPED)</li>
 *   <li>{@code 2 — Shape} ordinal of {@link DitherProcessor.NoiseShape}
 *       (0=FLAT, 1=WEIGHTED, 2=POWR_1, 3=POWR_2, 4=POWR_3)</li>
 * </ul>
 */
@BuiltInPlugin(label = "Dither", icon = "dither",
        category = BuiltInPluginCategory.MASTERING, terminal = true)
public final class DitherPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.dither";

    /** Default target bit depth (CD red-book). */
    public static final int DEFAULT_BIT_DEPTH = 16;

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Dither",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private DitherProcessor processor;
    private boolean active;

    public DitherPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new DitherProcessor(
                context.getAudioChannels(),
                DEFAULT_BIT_DEPTH,
                DitherProcessor.DitherType.TPDF,
                DitherProcessor.NoiseShape.FLAT);
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        if (processor != null) {
            processor.reset();
        }
    }

    @Override
    public void dispose() {
        active = false;
        processor = null;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.ofNullable(processor);
    }

    /**
     * Returns the underlying {@link DitherProcessor}, or {@code null} if the
     * plugin has not been initialized or has been disposed.
     */
    public DitherProcessor getProcessor() {
        return processor;
    }

    /** Returns whether {@link #activate()} has been called and not yet undone. */
    public boolean isActive() {
        return active;
    }

    @Override
    public List<PluginParameter> getParameters() {
        return List.of(
                new PluginParameter(0, "Bit Depth", 16.0, 24.0, (double) DEFAULT_BIT_DEPTH),
                new PluginParameter(1, "Type",
                        0.0, (double) (DitherProcessor.DitherType.values().length - 1),
                        (double) DitherProcessor.DitherType.TPDF.ordinal()),
                new PluginParameter(2, "Shape",
                        0.0, (double) (DitherProcessor.NoiseShape.values().length - 1),
                        (double) DitherProcessor.NoiseShape.FLAT.ordinal()));
    }

    /**
     * Routes an automation value to the underlying {@link DitherProcessor}.
     *
     * <p>Bit-depth values are snapped to the nearest supported value (16, 20,
     * 24). Type and shape ordinals are clamped to their valid enum range.</p>
     */
    @Override
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        switch (parameterId) {
            case 0 -> processor.setTargetBitDepth(snapBitDepth(value));
            case 1 -> processor.setType(toType((int) Math.round(value)));
            case 2 -> processor.setShape(toShape((int) Math.round(value)));
            default -> { /* unknown parameter id */ }
        }
    }

    private static int snapBitDepth(double v) {
        // Supported targets per the issue: 16 / 20 / 24.
        if (v <= 18.0) return 16;
        if (v <= 22.0) return 20;
        return 24;
    }

    private static DitherProcessor.DitherType toType(int ordinal) {
        DitherProcessor.DitherType[] values = DitherProcessor.DitherType.values();
        if (ordinal < 0) ordinal = 0;
        if (ordinal >= values.length) ordinal = values.length - 1;
        return values[ordinal];
    }

    private static DitherProcessor.NoiseShape toShape(int ordinal) {
        DitherProcessor.NoiseShape[] values = DitherProcessor.NoiseShape.values();
        if (ordinal < 0) ordinal = 0;
        if (ordinal >= values.length) ordinal = values.length - 1;
        return values[ordinal];
    }
}
