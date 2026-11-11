package com.benesquivelmusic.daw.app.ui.plugin.fixtures;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.application.Platform;

/**
 * A plugin whose next descriptor read on the JavaFX application thread runs a hook a test arms,
 * once. The plug-in browser reads each loaded plugin's descriptor there while it is built, right
 * after it has read which plugins are loaded, so the hook runs inside that window. Reads off that
 * thread — the manifest a test writes, the restore's check — leave the hook armed. Static, as
 * {@link BlockingInstallFixture}'s switches are: a JAR's class loader finds this class through its
 * parent.
 */
public final class DescriptorReadHookFixture implements DawPlugin {

    /** The vendor the descriptor reports: the browser's group for this plugin. */
    public static final String VENDOR = "Hook Audio";

    /** Disarmed, then run, by the next descriptor read on the FX thread; {@code null} runs nothing. */
    public static volatile Runnable onDescriptorReadOnFx;

    @Override public PluginDescriptor getDescriptor() {
        Runnable hook = onDescriptorReadOnFx;
        if (hook != null && Platform.isFxApplicationThread()) {
            onDescriptorReadOnFx = null;
            hook.run();
        }
        return new PluginDescriptor("test.descriptor.hook", "Descriptor hook", "1", VENDOR, PluginType.EFFECT);
    }
    @Override public void initialize(PluginContext context) { }
    @Override public void activate() { }
    @Override public void deactivate() { }
    @Override public void dispose() { }
}
