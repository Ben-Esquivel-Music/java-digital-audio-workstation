package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;

/** An initializer Error escapes reflection directly instead of being wrapped as a constructor failure. */
public final class InitializerErrorInstallFixture implements DawPlugin {
    static {
        failInitialization();
    }

    private static void failInitialization() {
        throw new AssertionError("installation initializer failed");
    }

    @Override public PluginDescriptor getDescriptor() { throw new AssertionError("unreachable"); }
    @Override public void initialize(PluginContext context) { }
    @Override public void activate() { }
    @Override public void deactivate() { }
    @Override public void dispose() { }
}
