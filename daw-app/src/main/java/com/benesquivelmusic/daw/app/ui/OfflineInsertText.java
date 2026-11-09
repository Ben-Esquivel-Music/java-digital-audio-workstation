package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;

/**
 * The words every insert surface shows for an offline insert — one a project
 * open kept at its chain position, passing audio through, because it could not
 * load what the file saved (story 334; studio-engineer ruling E22, with G7,
 * G16 and R1): the state word, the accessible text of a rack or Inspector row,
 * a track-header badge's tooltip and a row's tooltip, which names the cause and
 * what to do about it. Read from the application's resource bundle (UI Skill
 * §14).
 *
 * <p>The word is story 329's offline clips' word ({@code clipPlaceholder.offline}):
 * one concept across the session (E22; critique J25). Every key is read
 * through {@code ProjectAudioText.text}, the open's strings' one formatter
 * ({@link java.text.MessageFormat} at {@link java.util.Locale#ROOT}), so the
 * bundle writes an apostrophe twice.</p>
 */
public final class OfflineInsertText {

    private OfflineInsertText() {
    }

    /** @return the state word, {@code Offline} */
    public static String word() {
        return text("clipPlaceholder.offline");
    }

    /**
     * @param name the insert's name
     * @return what an assistive technology reads for the insert's row: {@code <name>, offline}
     */
    public static String accessible(String name) {
        return text("insertOffline.accessible", name);
    }

    /**
     * @param name the insert's name
     * @return a track-header badge's tooltip: {@code <name> (offline)}
     */
    public static String badgeTooltip(String name) {
        return text("insertOffline.badgeTooltip", name);
    }

    /** @return the one item of an offline rack row's context menu, {@code Remove} */
    public static String removeItem() {
        return text("insertOffline.removeItem");
    }

    /**
     * A row's tooltip: {@code <name> is offline: <reason>. It passes audio
     * through unchanged and is kept as saved.<action>}, the reason and the
     * action by cause (E22, G7, G16, R1).
     *
     * @param name  the insert's name
     * @param cause why the insert is offline
     * @return the tooltip
     */
    public static String tooltip(String name, OfflineCause cause) {
        return text("insertOffline.tooltip", name, reason(cause), action(cause));
    }

    private static String reason(OfflineCause cause) {
        return switch (cause) {
            case OfflineCause.UnknownEffectType(String effectType) ->
                    text("insertOffline.reason.unknownEffectType", effectType);
            case OfflineCause.FormatNotSupported _ -> text("insertOffline.reason.formatNotSupported");
            case OfflineCause.UnknownBuiltInPlugin(String pluginId) ->
                    text("insertOffline.reason.unknownBuiltInPlugin", pluginId);
            case OfflineCause.NotInstalled(PluginSourceKind kind, String pluginId, String version) ->
                    text(kind == PluginSourceKind.CLAP
                            ? "insertOffline.reason.clapNotInstalled" : "insertOffline.reason.notInstalled",
                            pluginId, version);
            case OfflineCause.InstalledAsOtherKind installed -> text("insertOffline.reason.installedAsOtherKind",
                    installed.pluginId(), installed.installedKind().displayName());
            case OfflineCause.MissingAtStartup missing ->
                    text("insertOffline.reason.missingAtStartup", missing.pluginId());
            case OfflineCause.StateRejected _ -> text("insertOffline.reason.stateRejected");
            case OfflineCause.StateUnreadable _ -> text("insertOffline.reason.stateUnreadable");
            case OfflineCause.NotLoadedBySnapshotRestore _ -> text("insertOffline.reason.notLoadedBySnapshotRestore");
            case OfflineCause.FailedToLoad _ -> text("insertOffline.reason.failedToLoad");
            case OfflineCause.NoIdentity _ -> text("insertOffline.reason.noIdentity");
            case OfflineCause.UnknownAnalyzer(String analyzerId) ->
                    text("insertOffline.reason.unknownAnalyzer", analyzerId);
        };
    }

    /** What the engineer can do, starting with a space; empty when nothing they do loads the insert. */
    private static String action(OfflineCause cause) {
        return switch (cause) {
            case OfflineCause.NotInstalled _ -> text("insertOffline.action.install");
            case OfflineCause.MissingAtStartup _ -> text("insertOffline.action.reinstall");
            case OfflineCause.NotLoadedBySnapshotRestore _ -> text("insertOffline.action.saveAndReopen");
            case OfflineCause.UnknownEffectType _, OfflineCause.FormatNotSupported _,
                 OfflineCause.UnknownBuiltInPlugin _, OfflineCause.InstalledAsOtherKind _,
                 OfflineCause.StateRejected _, OfflineCause.StateUnreadable _, OfflineCause.FailedToLoad _,
                 OfflineCause.NoIdentity _, OfflineCause.UnknownAnalyzer _ -> "";
        };
    }

    private static String text(String key, Object... arguments) {
        return ProjectAudioText.text(key, arguments);
    }
}
