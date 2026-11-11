package com.benesquivelmusic.daw.core.mixer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * A child element of a {@link SavedInsert} a writer produces, as saved: a
 * {@code <parameter>} (attributes only) or a {@code <plugin-state>} (its
 * text, the plugin's state in Base64).
 *
 * @param tag        which child it is
 * @param attributes its attributes, name to value as saved
 * @param text       its text as saved; empty for a {@code <parameter>}
 */
public record SavedInsertChild(Tag tag, SequencedMap<String, String> attributes, String text) {

    /** The children of an {@code <insert>} a writer produces. */
    public enum Tag {
        /** A {@code <parameter>} child. */
        PARAMETER,
        /** The {@code <plugin-state>} child. */
        PLUGIN_STATE
    }

    public SavedInsertChild {
        Objects.requireNonNull(tag, "tag must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        Objects.requireNonNull(text, "text must not be null");
        attributes = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(attributes));
    }
}
