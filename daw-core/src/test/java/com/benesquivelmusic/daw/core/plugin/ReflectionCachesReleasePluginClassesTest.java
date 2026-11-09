package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ReflectiveParameterRegistry;
import com.benesquivelmusic.daw.core.persistence.AnnotatedJarTestPlugin;
import com.benesquivelmusic.daw.core.preset.ParameterRestore;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static reflection caches never keep a JAR plugin's class — and with it
 * the class loader of its insert — reachable once the insert is disposed
 * (story 334, ruling-3 SC-1): every project open loads each JAR insert through
 * a class loader of its own, and a cache keyed strongly by {@code Class<?>}
 * would pin one plugin class graph per insert per open for the life of the
 * process. A plugin class defined by a throwaway loader goes through
 * {@link PluginCapabilityIntrospector}, {@link ReflectiveParameterRegistry}
 * and {@link ReflectivePresetSerializer}; once every reference is dropped and
 * the loader closed, the loader is collected.
 */
class ReflectionCachesReleasePluginClassesTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    /** How long the collection may take at most: generous, as a full collection under load can be slow. */
    private static final Duration COLLECTION_LIMIT = Duration.ofSeconds(30);

    @TempDir
    Path tempDir;

    @Test
    void aPluginClassTheCachesSawIsCollectedWithItsLoader() throws Exception {
        Path jar = PluginTestJars.buildJar(tempDir, "annotated-gain.jar",
                List.of(PluginTestJars.manifest(AnnotatedJarTestPlugin.class)), List.of(AnnotatedJarTestPlugin.class));
        ReferenceQueue<ClassLoader> collected = new ReferenceQueue<>();

        WeakReference<ClassLoader> loader = runThroughTheCachesAndDrop(jar, collected);

        assertThat(awaitCollected(loader, collected))
                .as("the plugin's class loader is collected once the insert let go of it").isTrue();
    }

    /**
     * Loads the plugin class from {@code jar} with a loader of its own, runs
     * an instance through the three reflection consumers, disposes it and
     * closes the loader; returns a weak reference to the loader, and nothing
     * else of it.
     */
    private static WeakReference<ClassLoader> runThroughTheCachesAndDrop(Path jar, ReferenceQueue<ClassLoader> queue)
            throws Exception {
        try (OwnClassesLoader loader = new OwnClassesLoader(jar, AnnotatedJarTestPlugin.class.getName())) {
            Class<?> pluginClass = loader.loadClass(AnnotatedJarTestPlugin.class.getName());
            assertThat(pluginClass).as("a class of the plugin's own loader, as a JAR plugin's is")
                    .isNotSameAs(AnnotatedJarTestPlugin.class);
            DawPlugin plugin = (DawPlugin) pluginClass.getConstructor().newInstance();
            plugin.initialize(new AudioFormatPluginContext(FORMAT));
            AudioProcessor processor = plugin.asAudioProcessor().orElseThrow();
            assertThat(processor.getClass().getClassLoader()).isSameAs(loader);

            assertThat(PluginCapabilityIntrospector.capabilitiesOf(processor).parameterCount()).isEqualTo(1);
            assertThat(PluginCapabilityIntrospector.capabilitiesOf(plugin).parameterCount()).isEqualTo(1);
            assertThat(ReflectiveParameterRegistry.hasAnnotatedParameters(processor.getClass())).isTrue();
            assertThat(ReflectiveParameterRegistry.getParameterDescriptors(processor.getClass())).hasSize(1);
            ReflectiveParameterRegistry.createParameterHandler(processor).accept(0, 0.5);
            assertThat(ReflectiveParameterRegistry.getParameterValues(processor)).containsEntry(0, 0.5);
            assertThat(ReflectivePresetSerializer.isSupported(processor)).isTrue();
            assertThat(ReflectivePresetSerializer.snapshot(processor)).containsEntry("Gain", 0.5);
            SequencedMap<String, Double> saved = new LinkedHashMap<>();
            saved.put("Gain", 0.75);
            assertThat(ReflectivePresetSerializer.restoreEach(processor, saved))
                    .containsExactly(new ParameterRestore.Applied("Gain", 0.75));

            plugin.dispose();
            return new WeakReference<>(loader, queue);
        }
    }

    /** Collects until {@code reference} is cleared or {@link #COLLECTION_LIMIT} passes; whether it was cleared. */
    private static boolean awaitCollected(Reference<ClassLoader> reference, ReferenceQueue<ClassLoader> queue)
            throws InterruptedException {
        long deadline = System.nanoTime() + COLLECTION_LIMIT.toNanos();
        while (!reference.refersTo(null) && System.nanoTime() < deadline) {
            System.gc();
            queue.remove(100);
        }
        return reference.refersTo(null);
    }

    /**
     * A class loader that defines the plugin class and the classes nested in
     * it itself, from its JAR, and delegates every other class to its parent:
     * the plugin class is one no other loader holds, as a third-party JAR's
     * is. (The test class path holds the same class, which a parent-first
     * loader would return instead.)
     */
    private static final class OwnClassesLoader extends URLClassLoader {

        private final String ownClassName;

        OwnClassesLoader(Path jar, String ownClassName) throws MalformedURLException {
            super(new URL[] {jar.toUri().toURL()}, OwnClassesLoader.class.getClassLoader());
            this.ownClassName = ownClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(ownClassName) && !name.startsWith(ownClassName + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
