package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Built-in spectrum analyzer plugin.
 *
 * <p>Wraps the DAW's {@code SpectrumAnalyzer} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
public final class SpectrumAnalyzerPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.spectrum-analyzer";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Spectrum Analyzer",
            "1.0.0",
            "DAW Built-in",
            PluginType.ANALYZER
    );

    private boolean active;

    public SpectrumAnalyzerPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Spectrum Analyzer";
    }

    @Override
    public String getMenuIcon() {
        return "spectrum";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.ANALYZER;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
    }

    @Override
    public void dispose() {
        active = false;
    }
}
