package com.benesquivelmusic.daw.core.persistence;

import java.util.Objects;

/**
 * A name the deserializer asks for although the current serializer never
 * emits it — the only exemption the write/read parity contract knows
 * ({@link ProjectSchemaParityContractTest}'s direction 2, "reader without a
 * writer"). There is deliberately no exemption the other way: a name the
 * serializer emits and the deserializer never reads is write-only
 * persistence, which the Persistence Integrity book forbids waiving (§2.9,
 * §9.3).
 *
 * <p>A tolerated read must say why it has no writer; an entry the writer now
 * emits, or the reader no longer asks for, is stale and fails the contract.</p>
 *
 * @param item   {@code tag@attribute} for an attribute the reader queries on
 *               elements with that tag, or a bare {@code tag} for an element
 *               the reader looks up
 * @param reason why the reader asks for a name no current file carries
 */
public record ToleratedRead(String item, String reason) {

    public ToleratedRead {
        Objects.requireNonNull(item, "item");
        if (item.isBlank()) {
            throw new IllegalArgumentException("a tolerated read must name an item");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a tolerated read must say why it has no writer: " + item);
        }
    }

    /** {@code true} for a {@code tag@attribute} item, {@code false} for an element name. */
    public boolean isAttribute() {
        return item.contains("@");
    }
}
