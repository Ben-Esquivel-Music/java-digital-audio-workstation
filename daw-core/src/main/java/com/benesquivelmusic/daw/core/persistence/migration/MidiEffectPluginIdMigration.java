package com.benesquivelmusic.daw.core.persistence.migration;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The schema 1 → 2 step: re-keys every MIDI-effect chain entry from the Java
 * class name schema 1 wrote ({@code <midi-effect class="…">}) to the
 * plugin's stable descriptor id ({@code plugin-id="…"}), the identity schema
 * 2 reads (Persistence Integrity book §4.5: identity is a stable id, never an
 * accident of code layout).
 *
 * <p>The mapping is a closed, exact-match table frozen at schema 2 — the
 * class name and the id are literals, so moving or renaming the plugin class
 * later cannot change what an old file means. A {@code class} value the
 * table does not name is left untouched: the reader keeps such an entry as a
 * placeholder that writes its original attributes back. Nothing else in the
 * document changes — schema 1 files legitimately carry the pre/post-roll
 * attributes and the chain, which the schema 2 readers restore, and lack the
 * per-track {@code <midi-clip>}, which they default to empty — so the step
 * only re-expresses the format ({@link ProjectMigration#formatOnly}). The
 * registry stamps the new version.</p>
 */
final class MidiEffectPluginIdMigration {

    /** The step's summary, shown in the migration history. */
    static final String DESCRIPTION = "add per-track MIDI clips; key MIDI effects by id";

    /** Schema 1 class names of built-in MIDI effects, to their stable plugin ids. */
    private static final Map<String, String> PLUGIN_ID_BY_SCHEMA_1_CLASS = Map.of(
            "com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin",
            "com.benesquivelmusic.daw.builtin.arpeggiator");

    private MidiEffectPluginIdMigration() {
    }

    /** The registered 1 → 2 step. */
    static ProjectMigration step() {
        return ProjectMigration.formatOnly(1, DESCRIPTION, MidiEffectPluginIdMigration::apply);
    }

    /**
     * Rewrites {@code class} to {@code plugin-id} on every
     * {@code daw-project/tracks/track/midi-effect-chain/midi-effect} whose
     * class the table names, in place.
     *
     * @param document the schema 1 project document
     * @return the same document
     */
    static Document apply(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) {
            return document;
        }
        for (Element tracks : childElements(root, "tracks")) {
            for (Element track : childElements(tracks, "track")) {
                for (Element chain : childElements(track, "midi-effect-chain")) {
                    for (Element effect : childElements(chain, "midi-effect")) {
                        String pluginId = PLUGIN_ID_BY_SCHEMA_1_CLASS.get(effect.getAttribute("class"));
                        if (pluginId != null) {
                            effect.removeAttribute("class");
                            effect.setAttribute("plugin-id", pluginId);
                        }
                    }
                }
            }
        }
        return document;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && child.getTagName().equals(tagName)) {
                result.add(child);
            }
        }
        return result;
    }
}
