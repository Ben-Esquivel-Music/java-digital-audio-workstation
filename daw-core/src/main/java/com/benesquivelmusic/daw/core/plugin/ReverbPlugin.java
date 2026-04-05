package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Built-in reverb effect plugin.
 *
 * <p>Wraps the DAW's {@code ReverbProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
public final class ReverbPlugin implements BuiltInDawPlugin {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            "com.benesquivelmusic.daw.builtin.reverb",
            "Reverb",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT
    );

    private boolean active;

    public ReverbPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Reverb";
    }

    @Override
    public String getMenuIcon() {
        return "reverb";
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
