package com.benesquivelmusic.daw.core.plugin;

import java.util.Objects;

/**
 * A plugin-backed insert's saved identity (story 334): its source kind and
 * stable plugin id — never a JAR path — and the version it was saved with,
 * which does not take part in resolution (studio ruling E4).
 *
 * @param kind    where the plugin comes from
 * @param id      the plugin's stable id, as its descriptor and manifest declare it
 * @param version the version the insert was saved with
 */
public record PluginIdentity(PluginSourceKind kind, String id, String version) {

    public PluginIdentity {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
