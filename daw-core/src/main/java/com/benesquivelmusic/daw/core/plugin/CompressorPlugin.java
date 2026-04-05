package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Built-in compressor effect plugin.
 *
 * <p>Wraps the DAW's {@code CompressorProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
public final class CompressorPlugin implements BuiltInDawPlugin {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            "com.benesquivelmusic.daw.builtin.compressor",
            "Compressor",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private boolean active;

    public CompressorPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Compressor";
    }

    @Override
    public String getMenuIcon() {
        return "compressor";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.EFFECT;
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
