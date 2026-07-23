package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Story-303 install-flow fixture: a UTILITY effect from a different vendor
 * ("QuietSky DSP"), so a bundle mixing it with the Acme fixtures reports
 * "Multiple vendors" and the browser groups it under its own vendor. Public
 * top-level type with a public no-arg constructor.
 */
public final class InstallFixturePluginC implements DawPlugin {

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(
                "com.quietsky.hall", "QuietSky Hall", "0.9.0", "QuietSky DSP",
                PluginType.EFFECT, PluginCategory.UTILITY, "knob");
    }

    @Override
    public void initialize(PluginContext context) {
        // no-op
    }

    @Override
    public void activate() {
        // no-op
    }

    @Override
    public void deactivate() {
        // no-op
    }

    @Override
    public void dispose() {
        // no-op
    }
}
