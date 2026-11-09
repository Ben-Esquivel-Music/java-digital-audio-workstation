package com.benesquivelmusic.daw.core.mixer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * An {@code <insert>} element as a project file saved it, captured when the
 * open could not load it (story 334; Persistence Integrity book §4.5): its
 * attributes in the order the reader saw them, then the children a writer
 * produces, in document order. Never a DOM node: saving runs on a captured
 * copy of the model (book §2.3).
 *
 * @param attributes the element's attributes, name to value as saved
 * @param children   its {@code <parameter>} and {@code <plugin-state>}
 *                   children, in document order
 */
public record SavedInsert(SequencedMap<String, String> attributes, List<SavedInsertChild> children) {

    public SavedInsert {
        Objects.requireNonNull(attributes, "attributes must not be null");
        Objects.requireNonNull(children, "children must not be null");
        attributes = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(attributes));
        children = List.copyOf(children);
    }

    /**
     * The value the element saved for {@code name}.
     *
     * @param name the attribute name
     * @return its value as saved, or empty when the element has no such attribute
     */
    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }
}
