package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

/**
 * Story-303 install-flow fixture: a DYNAMICS effect from vendor "Acme Audio".
 * Public top-level type with a public no-arg constructor so
 * {@code ExternalPluginLoader} can instantiate it via reflection when it is
 * registered from a test JAR (its class bytes are copied into the JAR by
 * {@code PluginTestJars}, and the class resolves via the parent test classpath).
 */
public final class InstallFixturePluginA implements DawPlugin {

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(
                "com.acme.punch", "Acme Punch", "1.0.0", "Acme Audio",
                PluginType.EFFECT, PluginCategory.DYNAMICS, "compressor");
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
