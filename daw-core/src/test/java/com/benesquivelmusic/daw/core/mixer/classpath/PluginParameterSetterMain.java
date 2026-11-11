package com.benesquivelmusic.daw.core.mixer.classpath;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.ReflectiveParameterRegistry;
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.nio.file.Path;

/**
 * The program {@code PluginSetterBindsWithoutModuleReadsTest} runs in a JVM
 * of its own, from the class path, with daw.core a named module on the
 * module path and no {@code --add-reads} — as the application's launchers
 * run daw.core (story 334, finding FX2-1). It loads the plugin class
 * {@code args[1]} from the JAR {@code args[0]} as the registry does, with a
 * class loader of its own whose unnamed module daw.core does not read, binds
 * the processor's {@code @ProcessorParam} setter, sets the parameter of id 0
 * to 0.5 through it, and builds the plugin's insert as the plugin browser
 * and a project open do. It prints one line per fact and exits 0; anything
 * thrown ends it with its stack trace and a non-zero exit.
 *
 * <p>Its package is one daw.core does not have, so the child's class path can
 * hold it: a package of the named module would hide it.</p>
 */
public final class PluginParameterSetterMain {

    private PluginParameterSetterMain() {
    }

    /**
     * Runs the program.
     *
     * @param args the plugin JAR and the plugin's class name
     * @throws Exception whatever loading or binding the plugin throws
     */
    public static void main(String[] args) throws Exception {
        ExternalPluginLoader.LoadResult loaded = ExternalPluginLoader.loadWithClassLoader(Path.of(args[0]), args[1]);
        DawPlugin plugin = loaded.plugin();
        plugin.initialize(new AudioFormatPluginContext(new AudioFormat(48_000.0, 2, 24, 256)));
        AudioProcessor processor = plugin.asAudioProcessor().orElseThrow();
        Module core = ReflectiveParameterRegistry.class.getModule();
        System.out.println("daw.core is a named module: " + core.isNamed());
        System.out.println("the plugin's class loader: " + (processor.getClass().getClassLoader() == loaded.classLoader()));
        System.out.println("daw.core reads the plugin's module: " + core.canRead(processor.getClass().getModule()));
        ReflectiveParameterRegistry.createAudioParameterSetter(processor).set(0, 0.5);
        System.out.println("parameter 0 after the setter: "
                + ReflectiveParameterRegistry.getParameterValues(processor).get(0));
        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        System.out.println("insert: " + slot.getName());
        plugin.dispose();
        ExternalPluginLoader.closeQuietly(loaded.classLoader());
        System.out.flush();
        System.exit(0);
    }
}
