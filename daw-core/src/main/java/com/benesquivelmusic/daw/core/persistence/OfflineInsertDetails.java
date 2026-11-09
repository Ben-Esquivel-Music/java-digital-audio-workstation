package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.mixer.OfflineCause;

/**
 * The words the project reader uses for an offline insert (story 334;
 * Persistence Integrity book §4.5): the detail of its
 * {@link UnreadProjectItem.Kind#INSERT_NOT_LOADED} item. Every value taken
 * from the file is quoted through {@link UnreadProjectItem#logText}; computed
 * values are not.
 */
final class OfflineInsertDetails {

    private OfflineInsertDetails() {
    }

    /**
     * Why the insert did not load, as the open's WARNING line says it after
     * its {@code — }, for example
     * {@code JAR plugin 'com.example.comp' version '1.2.0' is not installed}.
     * An unknown analyzer is logged only, by {@link AnalyzerInsertPersistence},
     * with its words from here.
     *
     * @param cause why the insert is offline
     * @return the detail
     */
    static String detail(OfflineCause cause) {
        return switch (cause) {
            case OfflineCause.UnknownEffectType unknown ->
                    "effect-type " + quoted(unknown.effectType()) + " is not an effect this build knows";
            case OfflineCause.FormatNotSupported format ->
                    "effect-type " + quoted(format.effectType()) + " cannot run in this project's audio format"
                            + " (channels " + format.channels() + ", sample rate " + format.sampleRate() + " Hz)";
            case OfflineCause.UnknownBuiltInPlugin unknown ->
                    "built-in plugin " + quoted(unknown.pluginId()) + " is not a plugin this build has";
            case OfflineCause.NotInstalled notInstalled ->
                    plugin(notInstalled.kind().displayName(), notInstalled.pluginId(), notInstalled.version())
                            + " is not installed";
            case OfflineCause.InstalledAsOtherKind other ->
                    plugin(other.kind().displayName(), other.pluginId(), other.version()) + " is not installed; "
                            + quoted(other.pluginId()) + " is installed as a " + other.installedKind().displayName()
                            + " plugin";
            case OfflineCause.MissingAtStartup missing ->
                    plugin(missing.kind().displayName(), missing.pluginId(), missing.version())
                            + " is installed, but its file did not load when the application started";
            case OfflineCause.StateRejected rejected ->
                    rejected.kind().displayName() + " plugin " + quoted(rejected.pluginId())
                            + " did not accept its saved state (saved with version "
                            + quoted(rejected.savedVersion()) + ", installed version " + rejected.installedVersion()
                            + "; " + (rejected.failure().isEmpty() ? "the plugin reported failure" : rejected.failure())
                            + ")";
            case OfflineCause.StateUnreadable unreadable ->
                    plugin(unreadable.kind().displayName(), unreadable.pluginId(), unreadable.version())
                            + " has a saved state that is not valid Base64; the plugin was not given it";
            case OfflineCause.NotLoadedBySnapshotRestore snapshot ->
                    plugin(snapshot.kind().displayName(), snapshot.pluginId(), snapshot.version())
                            + " was not loaded, because restoring a snapshot does not load plugins";
            case OfflineCause.FailedToLoad failed ->
                    plugin(failed.kind().displayName(), failed.pluginId(), failed.version())
                            + " failed to load (" + failed.failure() + ")";
            case OfflineCause.NoIdentity _ -> "the file records no plugin identity for it";
            case OfflineCause.UnknownAnalyzer unknown ->
                    "analyzer " + quoted(unknown.analyzerId()) + " is not an analyzer this build knows";
        };
    }

    /** {@code <K> plugin '<id>' version '<version>'}. */
    private static String plugin(String kind, String pluginId, String version) {
        return kind + " plugin " + quoted(pluginId) + " version " + quoted(version);
    }

    private static String quoted(String fileValue) {
        return "'" + UnreadProjectItem.logText(fileValue) + "'";
    }
}
