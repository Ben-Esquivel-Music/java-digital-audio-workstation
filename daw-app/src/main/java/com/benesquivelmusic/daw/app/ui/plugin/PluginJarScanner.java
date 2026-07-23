package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Off-FX-thread inspection of a plugin JAR (Plugin View Design Book §6.8). Opens
 * the JAR once, records whether it carries a {@code META-INF/daw-plugin.json}
 * manifest, counts a rough footprint (class files / resources / native
 * libraries), and reads every plugin the manifest declares through the SDK
 * {@link PluginManifestReader}.
 *
 * <h2>Off the FX thread ({@code javafx-application-design} §11)</h2>
 *
 * <p>{@link #scanBlocking(Path)} performs all the I/O on the calling thread and
 * never touches JavaFX. {@link #scan(Path, Consumer)} spawns a background
 * <strong>virtual thread</strong> ({@code Thread.ofVirtual()}), runs the
 * (injectable) inspector there, then marshals the result to the FX-thread
 * consumer through the story-289 {@link FxDispatcher} — exactly the shape of
 * {@code ProjectHealthScanner.scan}. A busy row in the browser / manager shows
 * while the scan is in flight, never a modal "loading" dialog.</p>
 */
public final class PluginJarScanner {

    /**
     * The result of inspecting a plugin JAR.
     *
     * @param jar             the inspected JAR
     * @param manifestPresent whether the JAR carries a {@code META-INF/daw-plugin.json} entry
     * @param manifests       every plugin the manifest declares (or the reader's
     *                        validation errors); {@link PluginManifestReader.BundleResult.Invalid}
     *                        when the manifest is absent, unreadable or malformed
     * @param classFiles      number of {@code .class} entries
     * @param resourceFiles   number of non-class, non-native, non-directory entries
     *                        (the manifest itself is counted here)
     * @param nativeLibs      number of {@code .dll} / {@code .so} / {@code .dylib} entries
     */
    public record JarInspection(
            Path jar,
            boolean manifestPresent,
            PluginManifestReader.BundleResult manifests,
            int classFiles,
            int resourceFiles,
            int nativeLibs) {}

    private final FxDispatcher dispatcher;
    private final Function<Path, JarInspection> inspector;

    /**
     * Creates a scanner that marshals scan results through {@code dispatcher}.
     *
     * @param dispatcher the FX marshalling seam, or {@code null} to fall back to
     *                   the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public PluginJarScanner(FxDispatcher dispatcher) {
        this(dispatcher, null);
    }

    /**
     * Creates a scanner with an explicit inspector — the deterministic-test seam
     * (inject a latch-blocking or canned inspector to drive the busy-row UI
     * without real I/O). A {@code null} inspector defaults to
     * {@link #scanBlocking(Path)}.
     *
     * @param dispatcher the FX marshalling seam, or {@code null} for the default
     * @param inspector  the blocking inspection function, or {@code null} to use
     *                   {@link #scanBlocking(Path)}
     */
    public PluginJarScanner(FxDispatcher dispatcher, Function<Path, JarInspection> inspector) {
        this.dispatcher = dispatcher;
        this.inspector = inspector != null ? inspector : this::scanBlocking;
    }

    /**
     * Inspects {@code jar} on the <strong>calling</strong> thread (all I/O; never
     * touches JavaFX). Robust: an unreadable JAR yields a zero footprint plus an
     * {@link PluginManifestReader.BundleResult.Invalid} rather than throwing.
     *
     * @param jar the JAR to inspect; must not be {@code null}
     * @return the inspection result (never {@code null})
     */
    public JarInspection scanBlocking(Path jar) {
        Objects.requireNonNull(jar, "jar must not be null");
        boolean manifestPresent = false;
        int classFiles = 0;
        int resourceFiles = 0;
        int nativeLibs = 0;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.equals(PluginManifestReader.MANIFEST_ENTRY)) {
                    manifestPresent = true;
                    resourceFiles++;
                } else if (name.endsWith(".class")) {
                    classFiles++;
                } else if (isNativeLib(name)) {
                    nativeLibs++;
                } else {
                    resourceFiles++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable JAR — report an empty footprint; readAllFromJar below
            // reports the same problem as a BundleResult.Invalid (never throws).
        }
        PluginManifestReader.BundleResult manifests =
                new PluginManifestReader().readAllFromJar(jar);
        return new JarInspection(
                jar, manifestPresent, manifests, classFiles, resourceFiles, nativeLibs);
    }

    /**
     * Spawns one inspection on a fresh background <strong>virtual</strong> thread
     * and returns it (so callers — and tests — can join on completion). The
     * inspection runs off the FX thread and marshals the result to
     * {@code fxConsumer} through {@link FxDispatcher#runOnFx(FxDispatcher, Runnable)}.
     *
     * @param jar        the JAR to inspect; must not be {@code null}
     * @param fxConsumer the FX-thread consumer of the result; must not be {@code null}
     * @return the started virtual scan thread
     */
    public Thread scan(Path jar, Consumer<JarInspection> fxConsumer) {
        Objects.requireNonNull(jar, "jar must not be null");
        Objects.requireNonNull(fxConsumer, "fxConsumer must not be null");
        Thread t = Thread.ofVirtual().name("daw-plugin-jar-scan").unstarted(() -> {
            JarInspection inspection = inspector.apply(jar);
            FxDispatcher.runOnFx(dispatcher, () -> fxConsumer.accept(inspection));
        });
        t.start();
        return t;
    }

    private static boolean isNativeLib(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
    }
}
