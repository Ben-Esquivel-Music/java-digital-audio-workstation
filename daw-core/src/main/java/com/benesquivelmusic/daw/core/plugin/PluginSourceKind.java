package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a plugin-backed insert's plugin comes from, as a project file's
 * {@code plugin-source} attribute names it (story 334): one of this build's
 * own first-party plugins, an external JAR plugin, or a CLAP plugin. A saved
 * insert's identity is its (source kind, plugin id).
 */
public enum PluginSourceKind {

    /** A first-party {@link BuiltInDawPlugin} of this build. */
    BUILT_IN("built-in", "built-in"),
    /** An external JAR plugin. */
    JAR("jar", "JAR"),
    /** A CLAP plugin. */
    CLAP("clap", "CLAP");

    private final String token;
    private final String displayName;

    PluginSourceKind(String token, String displayName) {
        this.token = token;
        this.displayName = displayName;
    }

    /** The {@code plugin-source} value a project file writes for this kind. */
    public String token() {
        return token;
    }

    /** The kind as log lines name it: {@code JAR plugin 'com.example.comp' …}. */
    public String displayName() {
        return displayName;
    }

    /**
     * The kind a project file's {@code plugin-source} value names.
     *
     * @param token the value as saved
     * @return the kind, or empty when the value names none
     */
    public static Optional<PluginSourceKind> ofToken(String token) {
        Objects.requireNonNull(token, "token must not be null");
        for (PluginSourceKind kind : values()) {
            if (kind.token.equals(token)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * The kind of a plugin an insert holds: a CLAP host is {@link #CLAP}; any
     * other external plugin host is none, because this build hosts no other
     * format (such an insert is written with no identity); a
     * {@link BuiltInDawPlugin} whose class belongs to this build's own module
     * is {@link #BUILT_IN}; every other plugin is {@link #JAR} — a third-party
     * class that implements {@link BuiltInDawPlugin} included, since only this
     * build's providers resolve as first-party.
     *
     * @param plugin the plugin
     * @return its kind, or empty for an external host of a format other than CLAP
     */
    public static Optional<PluginSourceKind> of(DawPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        if (plugin instanceof ExternalPluginHost host) {
            return host.getFormat() == ExternalPluginFormat.CLAP ? Optional.of(CLAP) : Optional.empty();
        }
        if (plugin instanceof BuiltInDawPlugin
                && plugin.getClass().getModule() == BuiltInDawPlugin.class.getModule()) {
            return Optional.of(BUILT_IN);
        }
        return Optional.of(JAR);
    }
}
