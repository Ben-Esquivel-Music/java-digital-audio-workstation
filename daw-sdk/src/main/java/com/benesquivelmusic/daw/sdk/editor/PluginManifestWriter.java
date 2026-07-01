package com.benesquivelmusic.daw.sdk.editor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;

/**
 * Serialises a {@link PluginManifest} to the JSON form stored at
 * {@code META-INF/daw-plugin.json} (Plugin View Design Book §4.5). The output
 * round-trips through {@link PluginManifestReader} to an equal manifest.
 *
 * <p>This is the library type only; the Maven goal that emits the manifest at
 * build time is out of scope for this story (§7.1/§7.3 — the book scopes the
 * plugin code out).</p>
 */
public final class PluginManifestWriter {

    /**
     * Renders the manifest as a pretty-printed JSON object string. The
     * {@code iconHint} key is omitted when the descriptor's icon hint is empty;
     * every other field is always written.
     *
     * @param manifest the manifest to serialise (never {@code null})
     * @return the JSON document, never {@code null}
     */
    public String toJson(PluginManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        PluginDescriptor d = manifest.descriptor();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(PluginManifestReader.KEY_PLUGIN_CLASS, manifest.pluginClass());
        fields.put(PluginManifestReader.KEY_ID, d.id());
        fields.put(PluginManifestReader.KEY_NAME, d.name());
        fields.put(PluginManifestReader.KEY_VERSION, d.version());
        fields.put(PluginManifestReader.KEY_VENDOR, d.vendor());
        fields.put(PluginManifestReader.KEY_TYPE, d.type().name());
        fields.put(PluginManifestReader.KEY_CATEGORY, d.category().name());
        if (!d.iconHint().isEmpty()) {
            fields.put(PluginManifestReader.KEY_ICON_HINT, d.iconHint());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append("  ");
            appendQuoted(sb, e.getKey());
            sb.append(": ");
            appendQuoted(sb, e.getValue());
            if (++i < fields.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Writes the manifest as a standalone {@code daw-plugin.json} file (UTF-8).
     *
     * @param manifest the manifest to write (never {@code null})
     * @param jsonFile the destination file path (never {@code null})
     * @throws IOException if writing fails
     */
    public void writeToFile(PluginManifest manifest, Path jsonFile) throws IOException {
        Objects.requireNonNull(jsonFile, "jsonFile must not be null");
        Files.writeString(jsonFile, toJson(manifest), StandardCharsets.UTF_8);
    }

    /**
     * Writes the manifest JSON to the given stream (UTF-8). Does not close the
     * stream.
     *
     * @param manifest the manifest to write (never {@code null})
     * @param out      the destination stream (never {@code null})
     * @throws IOException if writing fails
     */
    public void writeTo(PluginManifest manifest, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        out.write(toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    private static void appendQuoted(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
