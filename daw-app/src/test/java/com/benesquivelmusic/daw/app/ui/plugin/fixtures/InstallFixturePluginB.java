package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Story-303 install-flow fixture: a REVERB_AND_DELAY effect from vendor
 * "Acme Audio" (same vendor as {@link InstallFixturePluginA}, so a two-plugin
 * bundle reports a single common vendor). Public top-level type with a public
 * no-arg constructor.
 */
public final class InstallFixturePluginB implements DawPlugin {

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(
                "com.acme.echo", "Acme Echo", "2.1.0", "Acme Audio",
                PluginType.EFFECT, PluginCategory.REVERB_AND_DELAY, "reverb");
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
