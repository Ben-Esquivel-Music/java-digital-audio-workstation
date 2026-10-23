package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * A MIDI-effect chain entry the project file names but this build does not
 * recognise — an unknown or missing {@code plugin-id} — kept in its chain
 * position as a pass-through placeholder (Persistence Integrity book §4.5,
 * §9.4): the chain never shifts, the entry is never silently deleted, and
 * the next save writes the {@code <midi-effect>} element back with exactly
 * the attributes it was read with, so a build that recognises the effect
 * restores it losslessly.
 *
 * <p>Only {@link ProjectDeserializer} creates placeholders and only
 * {@link ProjectSerializer} interprets them; the class lives next to them
 * because it is a persistence artifact, not a plugin anyone can choose. It
 * is not a {@link java.util.ServiceLoader} provider of
 * {@link com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin} (so no menu,
 * discovery or scanner lists it) and carries no {@code @BuiltInPlugin}
 * annotation; the menu metadata methods are overridden so a caller that
 * labels a track's chain gets a truthful answer rather than an exception.</p>
 *
 * <p>The attributes are an immutable copy taken while the file is read — no
 * DOM node is retained, because saving runs on a captured copy of the
 * model (book §2.3). Their order is the order the parsed document presents
 * them, which is the order the serializer writes them back in (the JDK's
 * DOM keeps an element's attributes sorted by name, as every written
 * project file carries them).</p>
 */
public final class UnresolvedMidiEffect implements MidiEffectPlugin {

    /** The attribute that identifies a MIDI effect in a project file. */
    private static final String PLUGIN_ID_ATTRIBUTE = "plugin-id";

    private static final String UNNAMED_ID = "unresolved-midi-effect";

    private final SequencedMap<String, String> attributes;
    private final PluginDescriptor descriptor;

    /**
     * Creates a placeholder carrying the saved element's attributes.
     *
     * @param attributes the {@code <midi-effect>} element's attributes, in the
     *                   order to write them back; copied
     * @throws NullPointerException if the map, or any name or value, is {@code null}
     */
    public UnresolvedMidiEffect(SequencedMap<String, String> attributes) {
        Objects.requireNonNull(attributes, "attributes must not be null");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        attributes.forEach((name, value) -> copy.put(
                Objects.requireNonNull(name, "attribute name must not be null"),
                Objects.requireNonNull(value, "attribute value must not be null")));
        this.attributes = Collections.unmodifiableSequencedMap(copy);
        String savedId = copy.getOrDefault(PLUGIN_ID_ATTRIBUTE, "");
        String id = savedId.isBlank() ? UNNAMED_ID : savedId;
        this.descriptor = new PluginDescriptor(id, "Unrecognized MIDI effect (" + id + ")",
                "unknown", "unknown", PluginType.MIDI_EFFECT, PluginCategory.MIDI_EFFECT, "");
    }

    /**
     * Returns the saved element's attributes, in the order they are written
     * back.
     *
     * @return an unmodifiable view of the attributes
     */
    public SequencedMap<String, String> attributes() {
        return attributes;
    }

    /**
     * Returns the saved {@code plugin-id}, or {@code ""} when the element had
     * none.
     *
     * @return the unresolved plugin id
     */
    public String pluginId() {
        return attributes.getOrDefault(PLUGIN_ID_ATTRIBUTE, "");
    }

    /** Returns a descriptor naming the unresolved id. */
    @Override
    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
    }

    /** No-op: a placeholder holds no processing state. */
    @Override
    public void activate() {
    }

    /** No-op: a placeholder holds no processing state. */
    @Override
    public void deactivate() {
    }

    /** No-op: a placeholder holds no resources. */
    @Override
    public void dispose() {
    }

    /** Passes the input through unchanged. */
    @Override
    public MidiMessage[] process(MidiMessage[] in, int sampleOffset, MidiProcessContext ctx) {
        return in;
    }

    /** Returns the descriptor's name; the placeholder is never offered in a menu. */
    @Override
    public String getMenuLabel() {
        return descriptor.name();
    }

    /** Returns a generic MIDI-effect icon id; the placeholder is never offered in a menu. */
    @Override
    public String getMenuIcon() {
        return "midi-effect";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.MIDI_EFFECT;
    }

    @Override
    public String toString() {
        return "UnresolvedMidiEffect" + attributes;
    }
}
