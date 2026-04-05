package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Built-in virtual keyboard instrument plugin.
 *
 * <p>Wraps the DAW's {@code KeyboardProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 */
public final class VirtualKeyboardPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.virtual-keyboard";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Virtual Keyboard",
            "1.0.0",
            "DAW Built-in",
            PluginType.INSTRUMENT
    );

    private boolean active;

    public VirtualKeyboardPlugin() {
    }

    @Override
    public String getMenuLabel() {
        return "Virtual Keyboard";
    }

    @Override
    public String getMenuIcon() {
        return "keyboard";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.INSTRUMENT;
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
