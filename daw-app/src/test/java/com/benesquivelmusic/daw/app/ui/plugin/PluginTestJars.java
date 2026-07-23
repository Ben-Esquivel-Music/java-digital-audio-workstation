package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestWriter;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Test helper that builds honest plugin JARs for the story-303 install-flow
 * tests: it writes a real {@code META-INF/daw-plugin.json} (via
 * {@link PluginManifestWriter}) and copies each fixture's <em>actual</em> class
 * bytes into the JAR.
 *
 * <p>The class bytes make the JAR self-describing, but registration would resolve
 * the fixture through the parent test classpath anyway — the loader's
 * {@code URLClassLoader} delegates to its parent — so the on-disk JAR need only
 * exist. Copying the bytes keeps the JAR honest rather than empty.</p>
 */
final class PluginTestJars {

    private PluginTestJars() {
        // test utility
    }

    /**
     * Builds a JAR at {@code dir/jarName} containing an optional manifest (a
     * top-level array when {@code manifests} is non-empty) and the class bytes of
     * each fixture in {@code classes}.
     *
     * @param dir       the directory to write the JAR into
     * @param jarName   the JAR file name
     * @param manifests the manifests to declare, or {@code null} / empty for a
     *                  manifest-less JAR
     * @param classes   the fixture classes whose bytes to embed
     * @return the JAR path
     */
    static Path buildJar(Path dir, String jarName,
                         List<PluginManifest> manifests, List<Class<?>> classes) throws IOException {
        String json = manifests == null || manifests.isEmpty()
                ? null
                : new PluginManifestWriter().toJson(manifests);
        return buildJarWithManifestJson(dir, jarName, json, classes);
    }

    /**
     * Builds a JAR whose {@code META-INF/daw-plugin.json} carries the given raw
     * JSON verbatim — for the story-300 legacy single-object form, which
     * {@link #buildJar} (always a top-level array) cannot emit.
     *
     * @param dir          the directory to write the JAR into
     * @param jarName      the JAR file name
     * @param manifestJson the manifest JSON, or {@code null} for a manifest-less JAR
     * @param classes      the fixture classes whose bytes to embed
     * @return the JAR path
     */
    static Path buildJarWithManifestJson(Path dir, String jarName,
                                         String manifestJson, List<Class<?>> classes) throws IOException {
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (manifestJson != null) {
                out.putNextEntry(new JarEntry(PluginManifestReader.MANIFEST_ENTRY));
                out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (Class<?> cls : classes) {
                String entryName = cls.getName().replace('.', '/') + ".class";
                try (InputStream in = cls.getResourceAsStream("/" + entryName)) {
                    if (in == null) {
                        throw new IOException("class bytes not found on classpath: " + entryName);
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
     * Builds a {@link PluginManifest} whose {@code pluginClass} is the fixture's
     * fully-qualified name and whose descriptor is the fixture's own descriptor.
     *
     * @param cls the fixture class
     * @return the manifest
     */
    static PluginManifest manifest(Class<? extends DawPlugin> cls) {
        try {
            DawPlugin instance = cls.getDeclaredConstructor().newInstance();
            return new PluginManifest(cls.getName(), instance.getDescriptor());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate fixture " + cls, e);
        }
    }
}
