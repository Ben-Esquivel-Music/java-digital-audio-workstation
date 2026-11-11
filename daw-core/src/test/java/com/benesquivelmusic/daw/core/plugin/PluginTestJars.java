package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestWriter;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds honest plugin JARs for daw-core's plugin and persistence tests — a
 * real {@code META-INF/daw-plugin.json} written by
 * {@link PluginManifestWriter}, and the actual class bytes of each fixture
 * and of the classes nested in it — and installs them as the install flow
 * does.
 *
 * <p>The class bytes make the JAR self-describing, but a load resolves the
 * fixture through the test class path anyway — {@link ExternalPluginLoader}'s
 * class loader delegates to its parent first — so a JAR need only exist and
 * declare its plugins. Public, with public methods: the persistence tests
 * build their JARs with it too.</p>
 */
public final class PluginTestJars {

    private PluginTestJars() {
    }

    /**
     * Builds {@code directory/jarName}, declaring {@code manifests} (a
     * top-level array; none for an empty list) and holding the bytes of
     * {@code classes} and their nested classes.
     *
     * @param directory the folder to write the JAR into
     * @param jarName   the JAR's file name
     * @param manifests the plugins the JAR declares
     * @param classes   the classes whose bytes the JAR holds
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildJar(Path directory, String jarName, List<PluginManifest> manifests,
                                List<Class<?>> classes) throws IOException {
        String json = manifests.isEmpty() ? null : new PluginManifestWriter().toJson(manifests);
        return buildJarWithManifestJson(directory, jarName, json, classes);
    }

    /**
     * Builds {@code directory/jarName} whose {@code META-INF/daw-plugin.json}
     * holds {@code manifestJson} verbatim — a manifest a test spoils on
     * purpose — or none when it is {@code null}.
     *
     * @param directory    the folder to write the JAR into
     * @param jarName      the JAR's file name
     * @param manifestJson the manifest's text, or {@code null} for a JAR without one
     * @param classes      the classes whose bytes the JAR holds
     * @return the JAR
     * @throws IOException if the JAR cannot be written
     */
    public static Path buildJarWithManifestJson(Path directory, String jarName, String manifestJson,
                                                List<Class<?>> classes) throws IOException {
        Path jar = directory.resolve(jarName);
        Files.createDirectories(directory);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (manifestJson != null) {
                out.putNextEntry(new JarEntry(PluginManifestReader.MANIFEST_ENTRY));
                out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (Class<?> type : withNested(classes)) {
                String entryName = type.getName().replace('.', '/') + ".class";
                try (InputStream in = type.getResourceAsStream("/" + entryName)) {
                    if (in == null) {
                        throw new IOException("class bytes not found on the class path: " + entryName);
                    }
                    out.putNextEntry(new JarEntry(entryName));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    /**
     * The manifest a fixture's JAR declares for it: its class name and the
     * descriptor a fresh instance reports.
     *
     * @param type the fixture class, with a public no-argument constructor
     * @return the manifest
     */
    public static PluginManifest manifest(Class<? extends DawPlugin> type) {
        try {
            DawPlugin instance = type.getDeclaredConstructor().newInstance();
            return new PluginManifest(type.getName(), instance.getDescriptor());
        } catch (ReflectiveOperationException notInstantiable) {
            throw new IllegalStateException("cannot instantiate fixture " + type, notInstantiable);
        }
    }

    /**
     * Installs {@code type} into {@code registry} as the install flow does
     * (story 334, studio ruling E20): builds {@code folder/jarName} declaring
     * it, copies it into the registry's own folder, loads the copy and
     * installs it with its manifest; an entry it replaces is disposed.
     *
     * @param registry the registry
     * @param folder   the folder the engineer installs from (their downloads)
     * @param jarName  the JAR's file name
     * @param type     the plugin class
     * @return the installed plugin
     * @throws Exception if the JAR cannot be built, copied or loaded
     */
    public static InstalledPlugin install(PluginRegistry registry, Path folder, String jarName,
                                          Class<? extends DawPlugin> type) throws Exception {
        PluginManifest manifest = manifest(type);
        Path userJar = buildJar(folder, jarName, List.of(manifest), List.of(type));
        Path staged = registry.stageJar(userJar, manifest);
        ExternalPluginEntry entry = new ExternalPluginEntry(staged, manifest.pluginClass());
        InstalledPlugin installed = new InstalledPlugin(entry, manifest, jarName);
        registry.install(installed, ExternalPluginLoader.loadWithClassLoader(entry))
                .ifPresent(replaced -> replaced.disposal().run());
        return installed;
    }

    /**
     * The working insert a project open makes of a resolved plugin: the slot,
     * the plugin activated, its disposal the slot's.
     *
     * @param resolution a {@link PluginResolution.Resolved} resolution
     * @return the insert
     */
    public static InsertSlot insertOf(PluginResolution resolution) {
        if (!(resolution instanceof PluginResolution.Resolved resolved)) {
            throw new AssertionError("the plugin did not resolve: " + resolution);
        }
        LoadedPlugin loaded = resolved.loaded();
        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(loaded.plugin()).orElseThrow();
        loaded.plugin().activate();
        slot.setDisposal(loaded.disposal());
        return slot;
    }

    private static List<Class<?>> withNested(List<Class<?>> classes) {
        List<Class<?>> all = new ArrayList<>();
        for (Class<?> type : classes) {
            addWithNested(type, all);
        }
        return all;
    }

    private static void addWithNested(Class<?> type, List<Class<?>> all) {
        if (all.contains(type)) {
            return;
        }
        all.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            addWithNested(nested, all);
        }
    }
}
