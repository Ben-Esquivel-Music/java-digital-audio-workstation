package com.benesquivelmusic.daw.core.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Disk-backed store for user-authored {@link TrackTemplate}s and
 * {@link ChannelStripPreset}s.
 *
 * <p>Templates are stored as XML files under
 * {@code <baseDirectory>/templates/} and presets under
 * {@code <baseDirectory>/presets/}. The default base directory is
 * {@code ~/.daw} (as specified in the issue), but a custom base directory can
 * be supplied for testing.</p>
 *
 * <p>Every file in these directories with the {@code .xml} extension is
 * considered a template/preset. A file's name (minus extension) is used as a
 * fallback display name, but the name embedded in the XML itself is authoritative.</p>
 *
 * <p>{@link TrackTemplateFactory factory defaults} are <em>not</em> returned by
 * this store; callers that want the union of factory and user templates should
 * concatenate the two lists (see {@link #allTemplates()} and
 * {@link #allPresets()} for convenience).</p>
 */
public final class TrackTemplateStore {

    /** Default subdirectory under {@code ~} for storing templates. */
    public static final String DEFAULT_BASE_RELATIVE = ".daw";

    /** Subdirectory for track templates. */
    public static final String TEMPLATES_SUBDIR = "templates";

    /** Subdirectory for channel strip presets. */
    public static final String PRESETS_SUBDIR = "presets";

    private static final String XML_SUFFIX = ".xml";

    private final Path baseDirectory;

    /**
     * Creates a store rooted at {@code ~/.daw}.
     */
    public TrackTemplateStore() {
        this(Path.of(System.getProperty("user.home", "."), DEFAULT_BASE_RELATIVE));
    }

    /**
     * Creates a store rooted at the given base directory.
     *
     * @param baseDirectory the base directory (the {@code templates/} and
     *                      {@code presets/} subdirectories live here)
     */
    public TrackTemplateStore(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory must not be null");
    }

    /** Returns the base directory used by this store. */
    public Path getBaseDirectory() {
        return baseDirectory;
    }

    /** Returns the directory under which track templates are stored. */
    public Path getTemplatesDirectory() {
        return baseDirectory.resolve(TEMPLATES_SUBDIR);
    }

    /** Returns the directory under which channel strip presets are stored. */
    public Path getPresetsDirectory() {
        return baseDirectory.resolve(PRESETS_SUBDIR);
    }

    // ── Templates ───────────────────────────────────────────────────────────

    /**
     * Saves a track template under {@code templates/&lt;name&gt;.xml}.
     *
     * @param template the template to save
     * @return the file path the template was written to
     * @throws IOException if writing fails
     */
    public Path saveTemplate(TrackTemplate template) throws IOException {
        Objects.requireNonNull(template, "template must not be null");
        Path dir = getTemplatesDirectory();
        Files.createDirectories(dir);
        Path file = dir.resolve(sanitizeFileName(template.templateName()) + XML_SUFFIX);
        Files.writeString(file, TrackTemplateXml.serializeTemplate(template), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Loads all user-authored track templates from disk. Returns an empty list
     * if the templates directory does not yet exist. Files that fail to parse
     * are silently skipped.
     *
     * @return the loaded templates (never {@code null})
     * @throws IOException if directory listing fails
     */
    public List<TrackTemplate> loadTemplates() throws IOException {
        return loadAll(getTemplatesDirectory(), TrackTemplateXml::deserializeTemplate);
    }

    /**
     * Returns the concatenation of {@link TrackTemplateFactory#factoryTemplates()}
     * and the user-authored templates loaded from disk. Useful for building
     * "New Track from Template" menus.
     *
     * @return all templates: factory first, then user templates
     * @throws IOException if directory listing fails
     */
    public List<TrackTemplate> allTemplates() throws IOException {
        List<TrackTemplate> all = new ArrayList<>(TrackTemplateFactory.factoryTemplates());
        all.addAll(loadTemplates());
        return all;
    }

    // ── Presets ─────────────────────────────────────────────────────────────

    /**
     * Saves a channel strip preset under {@code presets/&lt;name&gt;.xml}.
     *
     * @param preset the preset to save
     * @return the file path the preset was written to
     * @throws IOException if writing fails
     */
    public Path savePreset(ChannelStripPreset preset) throws IOException {
        Objects.requireNonNull(preset, "preset must not be null");
        Path dir = getPresetsDirectory();
        Files.createDirectories(dir);
        Path file = dir.resolve(sanitizeFileName(preset.presetName()) + XML_SUFFIX);
        Files.writeString(file, TrackTemplateXml.serializePreset(preset), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Loads all user-authored channel strip presets from disk.
     *
     * @return the loaded presets (never {@code null})
     * @throws IOException if directory listing fails
     */
    public List<ChannelStripPreset> loadPresets() throws IOException {
        return loadAll(getPresetsDirectory(), TrackTemplateXml::deserializePreset);
    }

    /**
     * Returns the concatenation of {@link TrackTemplateFactory#factoryPresets()}
     * and the user-authored presets loaded from disk.
     *
     * @return all presets: factory first, then user presets
     * @throws IOException if directory listing fails
     */
    public List<ChannelStripPreset> allPresets() throws IOException {
        List<ChannelStripPreset> all = new ArrayList<>(TrackTemplateFactory.factoryPresets());
        all.addAll(loadPresets());
        return all;
    }

    // ── internal helpers ────────────────────────────────────────────────────

    private static <T> List<T> loadAll(Path dir, XmlDeserializer<T> deserializer) throws IOException {
        List<T> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> files = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(XML_SUFFIX))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    String xml = Files.readString(file, StandardCharsets.UTF_8);
                    result.add(deserializer.deserialize(xml));
                } catch (IOException | RuntimeException ignored) {
                    // skip malformed files so one bad file does not break the menu
                }
            }
        }
        return result;
    }

    /**
     * Replaces filesystem-unsafe characters with underscores. The sanitized
     * form is used only for filenames; the authoritative display name lives
     * inside the XML document.
     */
    static String sanitizeFileName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "untitled";
        }
        return trimmed.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    @FunctionalInterface
    private interface XmlDeserializer<T> {
        T deserialize(String xml) throws IOException;
    }
}
