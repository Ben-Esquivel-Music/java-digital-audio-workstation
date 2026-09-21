package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.core.plugin.editor.MultibandCompressorEditor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.AutomatableParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in multiband compressor effect plugin.
 *
 * <p>Wraps {@link MultibandCompressorProcessor} as a first-class built-in
 * plugin so it appears in the Plugins menu alongside the existing
 * {@link CompressorPlugin} and {@link BusCompressorPlugin}.  The processor
 * splits the signal into 3 to 5 frequency bands using a Linkwitz-Riley
 * 4th-order crossover network and applies independent dynamics to each band
 * — the standard tool for surgical mastering and complex bus processing.</p>
 *
 * <h2>Default configuration</h2>
 * <p>The plugin initializes with {@value #DEFAULT_BAND_COUNT} bands and the
 * crossover layout {@code [200 Hz, 2000 Hz, 8000 Hz]}.  The band count can
 * be changed at any time via {@link #setBandCount(int)} (3, 4, or 5);
 * crossover frequencies and per-band controls are accessed through the
 * underlying {@linkplain #getProcessor() processor}.</p>
 */
@BuiltInPlugin(label = "Multiband Compressor", icon = "compressor", category = BuiltInPluginCategory.EFFECT)
public final class MultibandCompressorPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.multibandcompressor";

    /** Default number of bands when the plugin is first initialized. */
    public static final int DEFAULT_BAND_COUNT = 4;

    /** Maximum number of bands supported by this plugin (and the underlying processor). */
    public static final int MAX_BAND_COUNT = 5;

    /** Minimum number of bands (per spec the multiband plugin supports 3–5 bands). */
    public static final int MIN_BAND_COUNT = 3;

    /** Stable crossover controls; each band configuration uses the active prefix. */
    private static final double[] DEFAULT_CROSSOVERS = {200.0, 2000.0, 8000.0, 16000.0};

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Multiband Compressor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT,
            PluginCategory.DYNAMICS,
            "compressor"
    );

    private volatile MultibandCompressorProcessor processor;
    private final MultibandCompressorProcessor[] configurations =
            new MultibandCompressorProcessor[MAX_BAND_COUNT - MIN_BAND_COUNT + 1];
    private final double[] bandMakeup = new double[MAX_BAND_COUNT];
    private final boolean[] bandMuted = new boolean[MAX_BAND_COUNT];
    private final double[] requestedCrossovers = new double[MAX_BAND_COUNT - 1];
    private final StableAudioProcessor stableProcessor = new StableAudioProcessor();
    private PluginContext context;
    private int bandCount = DEFAULT_BAND_COUNT;
    private boolean linearPhase;
    private boolean active;

    public MultibandCompressorPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        for (int index = 0; index < requestedCrossovers.length; index++) {
            requestedCrossovers[index] = Math.min(DEFAULT_CROSSOVERS[index], maximumCrossoverFrequency());
        }
        for (int index = 0; index < configurations.length; index++) {
            configurations[index] = new MultibandCompressorProcessor(context.getAudioChannels(),
                    context.getSampleRate(), Arrays.copyOf(requestedCrossovers, index + MIN_BAND_COUNT - 1));
        }
        rebuildProcessor();
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
        Arrays.fill(configurations, null);
        context = null;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        if (processor == null) {
            return Optional.empty();
        }
        return Optional.of(stableProcessor);
    }

    /**
     * Returns the underlying {@link MultibandCompressorProcessor}, or
     * {@code null} if the plugin has not been initialized or has been disposed.
     *
     * @return the multiband compressor processor, or {@code null}
     */
    public MultibandCompressorProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the current band count (3, 4, or 5).
     *
     * @return the active band count
     */
    public int getBandCount() {
        return bandCount;
    }

    /**
     * Selects a preallocated band configuration. Crossover controls and shared
     * per-band parameters survive the change. The configuration uses the active
     * prefix of the four crossover controls. Call between audio blocks or
     * while processing is stopped.
     *
     * @param bandCount the desired band count, must be {@value #MIN_BAND_COUNT}
     *                  to {@value #MAX_BAND_COUNT}
     * @throws IllegalArgumentException if {@code bandCount} is out of range
     * @throws IllegalStateException    if the plugin has not been initialized
     */
    public void setBandCount(int bandCount) {
        if (bandCount < MIN_BAND_COUNT || bandCount > MAX_BAND_COUNT) {
            throw new IllegalArgumentException(
                    "bandCount must be in [" + MIN_BAND_COUNT + ", " + MAX_BAND_COUNT
                            + "]: " + bandCount);
        }
        if (context == null) {
            throw new IllegalStateException(
                    "plugin must be initialized before changing the band count");
        }
        if (this.bandCount == bandCount && processor != null) {
            return;
        }
        this.bandCount = bandCount;
        rebuildProcessor();
    }

    /**
     * Returns whether the linear-phase crossover mode is requested.
     *
     * <p>When enabled, the host should engage a linear-phase crossover
     * implementation suitable for mastering contexts (at the cost of
     * additional latency reported via plugin delay compensation).  The
     * current built-in processor implements zero-latency IIR Linkwitz-Riley
     * crossovers; this flag is preserved so projects can persist the user's
     * preference until the linear-phase variant lands.</p>
     *
     * @return {@code true} if linear-phase mode is requested
     */
    public boolean isLinearPhase() {
        return linearPhase;
    }

    /**
     * Sets whether the linear-phase crossover mode is requested.
     *
     * @param linearPhase {@code true} to request linear-phase crossovers
     */
    public void setLinearPhase(boolean linearPhase) {
        this.linearPhase = linearPhase;
    }

    /**
     * Returns the parameter descriptors for this multiband compressor plugin.
     *
     * <p>Parameter ids are laid out in two sections:</p>
     * <ul>
     *   <li><b>0</b>: {@code Band Count} (3..5)</li>
     *   <li><b>1</b>: {@code Linear Phase Toggle} (0/1)</li>
     *   <li><b>2..5</b>: {@code Crossover N (Hz)} for the four possible
     *       crossover points; defaults match the {@link #DEFAULT_BAND_COUNT}
     *       layout, with any trailing slots populated with sensible
     *       higher-frequency placeholders for use after a band-count up-shift.</li>
     *   <li><b>6 + 8*band + offset</b>: per-band parameters where {@code band}
     *       is in {@code 0..4} and {@code offset} is one of:
     *       0=Threshold (dB), 1=Ratio, 2=Attack (ms), 3=Release (ms),
     *       4=Makeup Gain (dB), 5=Bypass Toggle, 6=Mute Toggle, 7=Solo Toggle.</li>
     * </ul>
     *
     * <p>Boolean parameters are named with a {@code Toggle} suffix so the
     * generic {@code PluginParameterEditorPanel} renders them as on/off
     * toggles rather than continuous sliders.</p>
     *
     * @return an unmodifiable list of multiband compressor parameter descriptors
     */
    @Override
    public List<PluginParameter> getParameters() {
        var params = new ArrayList<PluginParameter>(2 + 4 + MAX_BAND_COUNT * 8);
        params.add(new PluginParameter(0, "Band Count",
                MIN_BAND_COUNT, MAX_BAND_COUNT, DEFAULT_BAND_COUNT));
        params.add(new PluginParameter(1, "Linear Phase Toggle", 0.0, 1.0, 0.0));

        double maximum = maximumCrossoverFrequency();
        for (int i = 0; i < 4; i++) {
            params.add(new PluginParameter(2 + i,
                    "Crossover " + (i + 1) + " (Hz)", 20.0, maximum,
                    Math.min(DEFAULT_CROSSOVERS[i], maximum)));
        }

        int base = 6;
        for (int band = 0; band < MAX_BAND_COUNT; band++) {
            int b = base + band * 8;
            String prefix = "Band " + (band + 1) + " ";
            params.add(new PluginParameter(b,     prefix + "Threshold (dB)",  -60.0,    0.0, -20.0));
            params.add(new PluginParameter(b + 1, prefix + "Ratio",             1.0,   20.0,   4.0));
            params.add(new PluginParameter(b + 2, prefix + "Attack (ms)",       0.01, 100.0,  10.0));
            params.add(new PluginParameter(b + 3, prefix + "Release (ms)",     10.0, 1000.0, 100.0));
            params.add(new PluginParameter(b + 4, prefix + "Makeup Gain (dB)",  0.0,   30.0,   0.0));
            params.add(new PluginParameter(b + 5, prefix + "Bypass Toggle",     0.0,    1.0,   0.0));
            params.add(new PluginParameter(b + 6, prefix + "Mute Toggle",       0.0,    1.0,   0.0));
            params.add(new PluginParameter(b + 7, prefix + "Solo Toggle",       0.0,    1.0,   0.0));
        }
        return List.copyOf(params);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Story 302 (Plugin View Design Book §8.3): returns the multiband
     * compressor's bespoke {@link PluginEditorFactory.Panel} editor. A fresh
     * editor is created on every call — the host asks again on Reload.</p>
     */
    @Override
    public PluginEditorFactory editorFactory() {
        return new MultibandCompressorEditor(this);
    }

    /**
     * Returns the automatable parameter subset.
     *
     * <p>{@code Band Count} (id {@code 0}) is a structural editor control and
     * is intentionally excluded from automation lanes.
     * All other parameters — linear-phase preference, crossover frequencies
     * and per-band threshold / ratio / attack / release / makeup / bypass /
     * mute / solo — are RT-safe numeric setters and are exposed for
     * automation.</p>
     *
     * @return the automatable parameter descriptors, never {@code null}
     */
    @Override
    public List<AutomatableParameter> getAutomatableParameters() {
        List<PluginParameter> all = getParameters();
        var out = new ArrayList<AutomatableParameter>(all.size() - 1);
        for (PluginParameter p : all) {
            if (p.id() == 0) {
                continue; // Band Count is not RT-safe to automate.
            }
            out.add(AutomatableParameter.from(p));
        }
        return List.copyOf(out);
    }

    /**
     * Routes a parameter value from the host's automation engine to the
     * underlying processor.
     *
     * <p>Implementation is real-time safe: each branch performs only a
     * numeric setter call on already-allocated state. Crossovers recalculate
     * their preallocated biquad coefficients and preserve filter history. Call
     * on the audio thread between blocks, or while processing is stopped.
     * Out-of-range band
     * indices (which can occur when automation lanes were authored against
     * a higher band count than the current configuration) are silently
     * ignored.</p>
     *
     * @param parameterId the parameter id (see {@link #getParameters()})
     * @param value       the new parameter value (already inside the declared range)
     */
    @Override
    @RealTimeSafe
    public void setAutomatableParameter(int parameterId, double value) {
        if (processor == null) {
            return;
        }
        if (parameterId == 0) {
            setBandCount((int) Math.round(Math.clamp(value, MIN_BAND_COUNT, MAX_BAND_COUNT)));
            return;
        }
        if (parameterId == 1) {
            this.linearPhase = value >= 0.5;
            return;
        }
        if (parameterId >= 2 && parameterId <= 5) {
            if (!Double.isFinite(value)) {
                return;
            }
            int index = parameterId - 2;
            requestedCrossovers[index] = Math.clamp(value, 20.0, maximumCrossoverFrequency());
            if (index < processor.getBandCount() - 1) {
                processor.setCrossoverFrequency(index, requestedCrossovers[index]);
            }
            return;
        }
        int local = parameterId - 6;
        if (local < 0) {
            return;
        }
        int band = local / 8;
        int offset = local % 8;
        if (band < 0 || band >= processor.getBandCount()) {
            return;
        }
        CompressorProcessor comp = processor.getBandCompressor(band);
        switch (offset) {
            case 0 -> comp.setThresholdDb(value);
            case 1 -> comp.setRatio(value);
            case 2 -> comp.setAttackMs(value);
            case 3 -> comp.setReleaseMs(value);
            case 4 -> {
                bandMakeup[band] = value;
                processor.setBandMakeupGainDb(band, bandMuted[band] ? -120.0 : value);
            }
            case 5 -> processor.setBandBypassed(band, value >= 0.5);
            case 6 -> {
                bandMuted[band] = value >= 0.5;
                processor.setBandMakeupGainDb(band, bandMuted[band] ? -120.0 : bandMakeup[band]);
            }
            case 7 -> processor.setBandSoloed(band, value >= 0.5);
            default -> { /* unknown offset */ }
        }
    }

    private double maximumCrossoverFrequency() {
        // Leave a finite Nyquist margin so rounded coefficients do not place poles on the unit circle.
        return context == null ? 20000.0 : Math.min(20000.0, context.getSampleRate() * 0.499);
    }

    private void rebuildProcessor() {
        if (context == null) {
            return;
        }
        MultibandCompressorProcessor previous = processor;
        MultibandCompressorProcessor next = configurations[bandCount - MIN_BAND_COUNT];
        for (int index = 0; index < next.getBandCount() - 1; index++) {
            next.setCrossoverFrequency(index, requestedCrossovers[index]);
        }
        if (previous != null && previous != next) {
            for (int band = 0; band < Math.min(previous.getBandCount(), next.getBandCount()); band++) {
                CompressorProcessor source = previous.getBandCompressor(band);
                CompressorProcessor target = next.getBandCompressor(band);
                target.setThresholdDb(source.getThresholdDb());
                target.setRatio(source.getRatio());
                target.setAttackMs(source.getAttackMs());
                target.setReleaseMs(source.getReleaseMs());
                next.setBandMakeupGainDb(band, previous.getBandMakeupGainDb(band));
                next.setBandBypassed(band, previous.isBandBypassed(band));
                next.setBandSoloed(band, previous.isBandSoloed(band));
            }
            next.reset();
        }
        processor = next;
    }

    /**
     * A stable {@link AudioProcessor} that delegates to the plugin's current
     * {@linkplain #processor inner processor}.  Wiring this wrapper into the
     * mixer's effects chain (via {@code InsertEffectFactory}) ensures that
     * a band-count change — which swaps the inner processor instance — is
     * picked up by the chain on the next audio block, rather than leaving
     * the chain pointing at the previous (orphaned) processor.
     *
     * <p>Channel counts are reported from the live inner processor; the
     * channel count never changes after {@link #initialize(PluginContext)},
     * so the value is stable for the lifetime of the chain.</p>
     */
    private final class StableAudioProcessor implements AudioProcessor {

        @Override
        @RealTimeSafe
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            MultibandCompressorProcessor p = processor;
            if (p != null) {
                p.process(inputBuffer, outputBuffer, numFrames);
            }
        }

        @Override
        public void reset() {
            MultibandCompressorProcessor p = processor;
            if (p != null) {
                p.reset();
            }
        }

        @Override
        public int getInputChannelCount() {
            MultibandCompressorProcessor p = processor;
            return p != null ? p.getInputChannelCount() : 0;
        }

        @Override
        public int getOutputChannelCount() {
            MultibandCompressorProcessor p = processor;
            return p != null ? p.getOutputChannelCount() : 0;
        }
    }
}
