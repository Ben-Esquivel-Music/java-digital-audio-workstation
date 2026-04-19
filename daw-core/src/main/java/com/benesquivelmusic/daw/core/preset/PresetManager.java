package com.benesquivelmusic.daw.core.preset;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Reads and writes {@link ProcessorPreset} files to a user directory (default:
 * {@code ~/.daw/presets/effects/}) and bundled factory presets shipped as
 * resources under {@code /presets/effects/} on the classpath.
 *
 * <p>User presets are plain JSON files and can be freely created, copied, or
 * deleted by the user. Factory presets are read-only and come from the
 * {@code daw-core} jar.</p>
 */
public final class PresetManager {

    /** Classpath resource directory for factory presets. */
    public static final String FACTORY_RESOURCE_DIR = "/presets/effects/";

    /** Classpath resource listing the factory preset filenames (one per line). */
    static final String FACTORY_INDEX = FACTORY_RESOURCE_DIR + "index.txt";

    /** File extension used by preset files. */
    public static final String PRESET_EXTENSION = ".preset.json";

    private final Path userPresetsDir;

    /**
     * Creates a manager that reads/writes user presets under the given
     * directory.
     */
    public PresetManager(Path userPresetsDir) {
        this.userPresetsDir = Objects.requireNonNull(userPresetsDir,
                "userPresetsDir must not be null");
    }

    /**
     * Creates a manager using the default user preset directory
     * ({@code ~/.daw/presets/effects/}).
     */
    public static PresetManager defaultManager() {
        String home = System.getProperty("user.home", ".");
        return new PresetManager(Path.of(home, ".daw", "presets", "effects"));
    }

    /** Returns the user preset directory this manager writes to. */
    public Path userPresetsDirectory() {
        return userPresetsDir;
    }

    // ── Snapshot/restore from live processors ───────────────────────────────

    /**
     * Captures the given processor's state as a {@link ProcessorPreset} with
     * the given display name.
     */
    public ProcessorPreset capture(AudioProcessor processor, String displayName) {
        Objects.requireNonNull(processor, "processor must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        return new ProcessorPreset(
                processor.getClass().getName(),
                displayName,
                ReflectivePresetSerializer.snapshot(processor));
    }

    /**
     * Applies a preset to a processor, clamping out-of-range values and
     * skipping unknown keys. The processor's runtime class must match
     * {@link ProcessorPreset#processorClassName()}.
     *
     * @return the number of parameters applied
     * @throws IllegalArgumentException if the preset targets a different
     *                                  processor class than {@code processor}
     */
    public int apply(ProcessorPreset preset, AudioProcessor processor) {
        Objects.requireNonNull(preset, "preset must not be null");
        Objects.requireNonNull(processor, "processor must not be null");
        if (!preset.processorClassName().equals(processor.getClass().getName())) {
            throw new IllegalArgumentException(
                    "Preset is for " + preset.processorClassName()
                            + " but processor is " + processor.getClass().getName());
        }
        return ReflectivePresetSerializer.restore(processor, preset.parameterValues());
    }

    // ── User preset I/O ─────────────────────────────────────────────────────

    /**
     * Writes {@code preset} to the user presets directory. The filename is
     * derived from {@link ProcessorPreset#displayName()} by slugifying it.
     *
     * @return the absolute path of the written file
     */
    public Path save(ProcessorPreset preset) throws IOException {
        Objects.requireNonNull(preset, "preset must not be null");
        Files.createDirectories(userPresetsDir);
        Path target = userPresetsDir.resolve(slugify(preset.displayName()) + PRESET_EXTENSION);
        Files.writeString(target, preset.toJson(), StandardCharsets.UTF_8);
        return target;
    }

    /**
     * Loads every {@code *.preset.json} file in the user presets directory.
     * Files that fail to parse are skipped.
     */
    public List<ProcessorPreset> loadUserPresets() throws IOException {
        if (!Files.isDirectory(userPresetsDir)) {
            return List.of();
        }
        List<ProcessorPreset> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(userPresetsDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().endsWith(PRESET_EXTENSION))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    out.add(ProcessorPreset.fromJson(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (RuntimeException malformed) {
                    // Skip malformed preset files; they should not block loading
                    // the valid ones. A higher-level UI can surface errors.
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ── Factory presets (bundled resources) ─────────────────────────────────

    /**
     * Loads every bundled factory preset listed in
     * {@code /presets/effects/index.txt} on the classpath.
     */
    public static List<ProcessorPreset> loadFactoryPresets() {
        return loadFactoryPresets(PresetManager.class.getClassLoader());
    }

    /** Overload used by tests to inject a specific classloader. */
    public static List<ProcessorPreset> loadFactoryPresets(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        InputStream indexStream = loader.getResourceAsStream(FACTORY_INDEX.substring(1));
        if (indexStream == null) {
            return List.of();
        }
        List<ProcessorPreset> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                String resource = FACTORY_RESOURCE_DIR.substring(1) + name;
                try (InputStream in = loader.getResourceAsStream(resource)) {
                    if (in == null) {
                        continue;
                    }
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.add(ProcessorPreset.fromJson(json));
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException("Failed to read factory preset index", ioe);
        }
        return Collections.unmodifiableList(out);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Converts a display name to a filesystem-safe slug. Non alphanumeric
     * runs collapse to single hyphens.
     */
    static String slugify(String displayName) {
        String lower = displayName.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevHyphen = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevHyphen = false;
            } else if (!prevHyphen && sb.length() > 0) {
                sb.append('-');
                prevHyphen = true;
            }
        }
        // strip trailing hyphen
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.length() == 0 ? "preset" : sb.toString();
    }
}
