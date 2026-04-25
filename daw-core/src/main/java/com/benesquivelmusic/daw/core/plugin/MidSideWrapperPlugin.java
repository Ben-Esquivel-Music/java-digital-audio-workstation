package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.GainStagingProcessor;
import com.benesquivelmusic.daw.core.dsp.MidSideWrapperProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in Mid/Side processing wrapper plugin.
 *
 * <p>Hosts two independent {@link DawPlugin} chains: one operates on the Mid
 * (M = (L+R)·0.5) channel, the other on the Side (S = (L−R)·0.5) channel.
 * After each block the wrapper decodes back to L/R via {@link MidSideWrapperProcessor}.
 * Plugins inside the chains are unaware they are operating on M/S signals —
 * each chain receives a single mono channel (the Mid chain sees M, the Side
 * chain sees S). This is a documented simplification that covers 99% of
 * cases.</p>
 *
 * <h2>Use cases</h2>
 * <ul>
 *   <li>Stereo widening — apply gain only on the Side chain.</li>
 *   <li>Mono-bass — apply a high-pass filter on the Side chain to keep low
 *       frequencies centered.</li>
 *   <li>Center focus — apply a compressor only on the Mid chain to glue
 *       lead vocals / centered elements without affecting stereo image.</li>
 * </ul>
 *
 * <h2>Identity / null test</h2>
 * <p>With both chains empty (or {@link MidSideWrapperProcessor#setBypassed
 * bypass} set), the wrapper is bit-exact with passing the input straight
 * through, since encode followed by decode is mathematically identity.</p>
 *
 * @see MidSideWrapperProcessor
 */
@BuiltInPlugin(label = "Mid/Side Wrapper", icon = "stereo", category = BuiltInPluginCategory.EFFECT)
public final class MidSideWrapperPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.midsidewrapper";

    /**
     * Discriminator used by the undo system to identify which inner chain a
     * given operation targets.
     */
    public enum ChainOwner {
        /** Targets the Mid chain (centered content). */
        MID,
        /** Targets the Side chain (stereo content). */
        SIDE
    }

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Mid/Side Wrapper",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private final List<DawPlugin> midChain  = new ArrayList<>();
    private final List<DawPlugin> sideChain = new ArrayList<>();

    private MidSideWrapperProcessor processor;
    private PluginContext context;
    private boolean active;

    public MidSideWrapperPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        // Inner plugins receive a single mono buffer per chain. Wrap the host
        // context so getAudioChannels() reports 1 — otherwise built-ins would
        // allocate stereo state and third-party plugins that trust the
        // declared channel count could break.
        this.context = new MonoChainContext(context);
        this.processor = new MidSideWrapperProcessor();
        // Initialize and wire any plugins added before initialize() was called
        // (e.g., via a preset factory).
        for (DawPlugin p : midChain)  initAndWire(p, processor.getMidChain());
        for (DawPlugin p : sideChain) initAndWire(p, processor.getSideChain());
    }

    @Override
    public void activate() {
        active = true;
        for (DawPlugin p : midChain)  p.activate();
        for (DawPlugin p : sideChain) p.activate();
    }

    @Override
    public void deactivate() {
        active = false;
        for (DawPlugin p : midChain)  p.deactivate();
        for (DawPlugin p : sideChain) p.deactivate();
        if (processor != null) {
            processor.reset();
        }
    }

    @Override
    public void dispose() {
        active = false;
        for (DawPlugin p : midChain)  p.dispose();
        for (DawPlugin p : sideChain) p.dispose();
        midChain.clear();
        sideChain.clear();
        processor = null;
        // Drop the host context reference so a long-lived wrapper instance
        // can't keep heavy host state alive after disposal.
        context = null;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return Optional.ofNullable((AudioProcessor) processor);
    }

    /** Returns the underlying processor, or {@code null} if not yet initialized. */
    public MidSideWrapperProcessor getProcessor() {
        return processor;
    }

    /** Returns an unmodifiable view of the Mid chain. */
    public List<DawPlugin> getMidChain() {
        return Collections.unmodifiableList(midChain);
    }

    /** Returns an unmodifiable view of the Side chain. */
    public List<DawPlugin> getSideChain() {
        return Collections.unmodifiableList(sideChain);
    }

    /** Returns the chain corresponding to the given owner. */
    public List<DawPlugin> getChain(ChainOwner owner) {
        return switch (Objects.requireNonNull(owner, "owner must not be null")) {
            case MID  -> getMidChain();
            case SIDE -> getSideChain();
        };
    }

    /**
     * Adds a plugin to the chain identified by {@code owner}. If this wrapper
     * has already been {@linkplain #initialize initialized}, the inner plugin
     * is initialized with the same {@link PluginContext} and its audio
     * processor (if any) is wired into the underlying
     * {@link MidSideWrapperProcessor}.
     *
     * @param owner  which inner chain to append to
     * @param plugin the plugin to add
     */
    public void addPlugin(ChainOwner owner, DawPlugin plugin) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(plugin, "plugin must not be null");
        List<DawPlugin> chain = (owner == ChainOwner.MID) ? midChain : sideChain;
        chain.add(plugin);
        if (processor != null && context != null) {
            List<AudioProcessor> dspChain = (owner == ChainOwner.MID)
                    ? processor.getMidChain()
                    : processor.getSideChain();
            initAndWire(plugin, dspChain);
            if (active) {
                plugin.activate();
            }
        }
    }

    /**
     * Removes a plugin from the chain identified by {@code owner}.
     *
     * @return {@code true} if the plugin was present and removed
     */
    public boolean removePlugin(ChainOwner owner, DawPlugin plugin) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(plugin, "plugin must not be null");
        List<DawPlugin> chain = (owner == ChainOwner.MID) ? midChain : sideChain;
        boolean removed = chain.remove(plugin);
        if (removed && processor != null) {
            List<AudioProcessor> dspChain = (owner == ChainOwner.MID)
                    ? processor.getMidChain()
                    : processor.getSideChain();
            plugin.asAudioProcessor().ifPresent(dspChain::remove);
            plugin.deactivate();
            plugin.dispose();
        }
        return removed;
    }

    @Override
    public List<PluginParameter> getParameters() {
        // The wrapper itself exposes no automatable parameters — automation
        // targets the inner plugins directly through the standard UI.
        return List.of();
    }

    private void initAndWire(DawPlugin plugin, List<AudioProcessor> dspChain) {
        plugin.initialize(context);
        plugin.asAudioProcessor().ifPresent(dspChain::add);
    }

    // ── Presets ─────────────────────────────────────────────────────────────

    /**
     * "Stereo Widener" preset — boosts the Side chain by +3 dB to widen the
     * stereo image while leaving the Mid channel untouched.
     *
     * <p>The returned plugin must still be {@linkplain #initialize initialized}
     * by the host before use.</p>
     */
    public static MidSideWrapperPlugin stereoWidenerPreset() {
        var wrapper = new MidSideWrapperPlugin();
        wrapper.sideChain.add(new MidSideGainPlugin(3.0));
        return wrapper;
    }

    /**
     * "Mono Bass" preset — high-passes the Side chain at 120 Hz so low
     * frequencies remain centered (a standard mastering trick that improves
     * mono compatibility on club / vinyl playback).
     */
    public static MidSideWrapperPlugin monoBassPreset() {
        var wrapper = new MidSideWrapperPlugin();
        wrapper.sideChain.add(new SideHighPassPlugin(120.0));
        return wrapper;
    }

    /**
     * "Center Focus" preset — applies gentle compression to the Mid chain
     * (4:1 ratio, −18 dB threshold) to glue centered elements (lead vocal,
     * snare, kick) without affecting the stereo width.
     */
    public static MidSideWrapperPlugin centerFocusPreset() {
        var wrapper = new MidSideWrapperPlugin();
        wrapper.midChain.add(new MidCompressorPlugin());
        return wrapper;
    }

    // ── Internal lightweight inner-chain plugins for presets ───────────────

    /**
     * Delegating {@link PluginContext} that overrides {@link #getAudioChannels()}
     * to return {@code 1} so inner plugins know each chain is invoked with a
     * single mono buffer (Mid chain → M, Side chain → S). All other host
     * services (sample rate, buffer size, logging) are delegated unchanged.
     */
    private static final class MonoChainContext implements PluginContext {
        private final PluginContext delegate;
        MonoChainContext(PluginContext delegate) { this.delegate = delegate; }
        @Override public double getSampleRate()  { return delegate.getSampleRate(); }
        @Override public int    getBufferSize()  { return delegate.getBufferSize(); }
        @Override public int    getAudioChannels() { return 1; }
        @Override public void   log(String message) { delegate.log(message); }
    }


    /**
     * Minimal mono gain plugin used by presets to apply a fixed dB boost to
     * one of the wrapper's inner chains. Not exposed as a built-in plugin —
     * intentionally outside the {@link BuiltInDawPlugin} sealed hierarchy.
     */
    static final class MidSideGainPlugin implements DawPlugin {
        private static final PluginDescriptor DESC = new PluginDescriptor(
                PLUGIN_ID + ".gain", "M/S Gain", "1.0.0", "DAW Built-in",
                PluginType.EFFECT);
        private final double gainDb;
        private GainStagingProcessor processor;
        MidSideGainPlugin(double gainDb) { this.gainDb = gainDb; }
        @Override public PluginDescriptor getDescriptor() { return DESC; }
        @Override public void initialize(PluginContext ctx) {
            // Inner chain runs mono: 1 channel.
            processor = new GainStagingProcessor(1, gainDb);
        }
        @Override public void activate()   { /* no-op */ }
        @Override public void deactivate() { if (processor != null) processor.reset(); }
        @Override public void dispose()    { processor = null; }
        @Override public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.ofNullable(processor);
        }
    }

    /**
     * Minimal high-pass plugin used by the "Mono Bass" preset.
     */
    static final class SideHighPassPlugin implements DawPlugin {
        private static final PluginDescriptor DESC = new PluginDescriptor(
                PLUGIN_ID + ".sidehpf", "M/S HPF", "1.0.0", "DAW Built-in",
                PluginType.EFFECT);
        private final double cutoffHz;
        private ParametricEqProcessor processor;
        SideHighPassPlugin(double cutoffHz) { this.cutoffHz = cutoffHz; }
        @Override public PluginDescriptor getDescriptor() { return DESC; }
        @Override public void initialize(PluginContext ctx) {
            processor = new ParametricEqProcessor(1, ctx.getSampleRate());
            processor.addBand(ParametricEqProcessor.BandConfig.of(
                    BiquadFilter.FilterType.HIGH_PASS, cutoffHz, 0.707, 0.0));
        }
        @Override public void activate()   { /* no-op */ }
        @Override public void deactivate() { if (processor != null) processor.reset(); }
        @Override public void dispose()    { processor = null; }
        @Override public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.ofNullable(processor);
        }
    }

    /**
     * Minimal compressor plugin used by the "Center Focus" preset.
     */
    static final class MidCompressorPlugin implements DawPlugin {
        private static final PluginDescriptor DESC = new PluginDescriptor(
                PLUGIN_ID + ".midcomp", "M/S Comp", "1.0.0", "DAW Built-in",
                PluginType.EFFECT);
        private CompressorProcessor processor;
        MidCompressorPlugin() {}
        @Override public PluginDescriptor getDescriptor() { return DESC; }
        @Override public void initialize(PluginContext ctx) {
            processor = new CompressorProcessor(1, ctx.getSampleRate());
            processor.setThresholdDb(-18.0);
            processor.setRatio(4.0);
            processor.setAttackMs(10.0);
            processor.setReleaseMs(120.0);
            processor.setKneeDb(6.0);
            processor.setMakeupGainDb(0.0);
        }
        @Override public void activate()   { /* no-op */ }
        @Override public void deactivate() { if (processor != null) processor.reset(); }
        @Override public void dispose()    { processor = null; }
        @Override public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.ofNullable(processor);
        }
    }
}
