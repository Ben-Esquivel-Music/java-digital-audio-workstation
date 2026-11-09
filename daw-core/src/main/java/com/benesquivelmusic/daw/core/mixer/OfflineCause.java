package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;

import java.util.Objects;

/**
 * Why an insert is offline: the open could not load what its file saved, so
 * the insert is kept at its chain position, passing audio through, and is
 * written back as saved (story 334; Persistence Integrity book §4.5). Data
 * only: the project reader words the open's report from it and the
 * application its tooltip.
 */
public sealed interface OfflineCause permits OfflineCause.UnknownEffectType, OfflineCause.FormatNotSupported,
        OfflineCause.UnknownBuiltInPlugin, OfflineCause.NotInstalled, OfflineCause.InstalledAsOtherKind,
        OfflineCause.MissingAtStartup, OfflineCause.StateRejected, OfflineCause.StateUnreadable,
        OfflineCause.NotLoadedBySnapshotRestore, OfflineCause.FailedToLoad, OfflineCause.NoIdentity,
        OfflineCause.UnknownAnalyzer {

    /**
     * The saved {@code effect-type} names no built-in effect this build has.
     *
     * @param effectType the type as saved
     */
    record UnknownEffectType(String effectType) implements OfflineCause {
        public UnknownEffectType {
            Objects.requireNonNull(effectType, "effectType must not be null");
        }
    }

    /**
     * The saved built-in effect cannot run in the project's audio format (a
     * stereo-only effect in a project that is not stereo); it loads at an
     * open in a format it accepts.
     *
     * @param effectType the type as saved
     * @param channels   the project's channel count
     * @param sampleRate the project's sample rate, in Hz
     */
    record FormatNotSupported(String effectType, int channels, double sampleRate) implements OfflineCause {
        public FormatNotSupported {
            Objects.requireNonNull(effectType, "effectType must not be null");
        }
    }

    /**
     * The saved first-party plugin id names no plugin this build has.
     *
     * @param pluginId the id as saved
     */
    record UnknownBuiltInPlugin(String pluginId) implements OfflineCause {
        public UnknownBuiltInPlugin {
            Objects.requireNonNull(pluginId, "pluginId must not be null");
        }
    }

    /**
     * No plugin of the saved kind and id is installed.
     *
     * @param kind     the source kind as saved
     * @param pluginId the id as saved
     * @param version  the version as saved
     */
    record NotInstalled(PluginSourceKind kind, String pluginId, String version) implements OfflineCause {
        public NotInstalled {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(version, "version must not be null");
        }
    }

    /**
     * A plugin with the saved id is installed, but as another source kind;
     * identity is (source kind, id), so it does not stand in.
     *
     * @param kind          the source kind as saved
     * @param pluginId      the id as saved
     * @param version       the version as saved
     * @param installedKind the kind the id is installed as
     */
    record InstalledAsOtherKind(PluginSourceKind kind, String pluginId, String version,
                                PluginSourceKind installedKind) implements OfflineCause {
        public InstalledAsOtherKind {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(installedKind, "installedKind must not be null");
        }
    }

    /**
     * The plugin is installed, but its file did not load when the
     * application started.
     *
     * @param kind     the source kind as saved
     * @param pluginId the id as saved
     * @param version  the version as saved
     */
    record MissingAtStartup(PluginSourceKind kind, String pluginId, String version) implements OfflineCause {
        public MissingAtStartup {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(version, "version must not be null");
        }
    }

    /**
     * The installed plugin did not take its saved state (studio ruling E4:
     * never a plugin at its defaults).
     *
     * @param kind             the source kind as saved
     * @param pluginId         the id as saved
     * @param savedVersion     the version as saved
     * @param installedVersion the version of the installed plugin
     * @param failure          the simple class name of what the plugin
     *                         threw; empty when the plugin itself reported
     *                         that it did not take the state
     */
    record StateRejected(PluginSourceKind kind, String pluginId, String savedVersion, String installedVersion,
                         String failure) implements OfflineCause {
        public StateRejected {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(savedVersion, "savedVersion must not be null");
            Objects.requireNonNull(installedVersion, "installedVersion must not be null");
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /**
     * The saved state is not valid Base64: the file is damaged there, and the
     * plugin was not given it (studio ruling G7).
     *
     * @param kind     the source kind as saved
     * @param pluginId the id as saved
     * @param version  the version as saved
     */
    record StateUnreadable(PluginSourceKind kind, String pluginId, String version) implements OfflineCause {
        public StateUnreadable {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(version, "version must not be null");
        }
    }

    /**
     * The plugin is installed, but restoring a mixer snapshot loads no
     * plugin; it loads at the next open (studio ruling R1).
     *
     * @param kind     the source kind as saved
     * @param pluginId the id as saved
     * @param version  the version as saved
     */
    record NotLoadedBySnapshotRestore(PluginSourceKind kind, String pluginId, String version)
            implements OfflineCause {
        public NotLoadedBySnapshotRestore {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(version, "version must not be null");
        }
    }

    /**
     * The plugin did not load: constructing, initializing or activating it
     * threw, as did taking its saved parameters or, last, telling its
     * latency (ruling-4 Q8), or it has no audio processor to insert.
     *
     * @param kind     the source kind as saved
     * @param pluginId the id as saved
     * @param version  the version as saved
     * @param failure  what failed: the simple class name of what was thrown —
     *                 followed by {@code " when asked for its latency"} when
     *                 the latency query threw — or what the installed plugin
     *                 lacks
     */
    record FailedToLoad(PluginSourceKind kind, String pluginId, String version, String failure)
            implements OfflineCause {
        public FailedToLoad {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(pluginId, "pluginId must not be null");
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(failure, "failure must not be null");
        }
    }

    /**
     * The file records no plugin identity for the insert: a name-only stub of
     * a build before story 334, an {@code effect-type} of
     * {@code CLAP_PLUGIN}, or an unknown {@code plugin-source} or a blank
     * {@code plugin-id}.
     */
    record NoIdentity() implements OfflineCause {
    }

    /**
     * The saved {@code analyzer-plugin-id} names no analyzer this build has.
     * Logged only, never counted: an analyzer changes nothing the engineer
     * hears (story 330, studio ruling D1).
     *
     * @param analyzerId the analyzer id as saved
     */
    record UnknownAnalyzer(String analyzerId) implements OfflineCause {
        public UnknownAnalyzer {
            Objects.requireNonNull(analyzerId, "analyzerId must not be null");
        }
    }
}
