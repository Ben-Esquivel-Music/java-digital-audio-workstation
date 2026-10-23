package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The one notification an open shows (story 329; Persistence Integrity book
 * §5.3 step 8 ANNOUNCE, §9.11; decisions D8, D14 and D24): what the door did,
 * qualified by what the open could not load as saved. A plain SUCCESS only
 * when every referenced asset resolved, none is shorter than its clips and
 * the open honoured every saved item as written; otherwise a WARNING naming
 * the distinct missing assets and the clips they silence, then the assets
 * shorter than their clips, with a {@code Review…} action when the report has
 * rows, then — story 330, counts only, in a fixed order — the saved items the
 * open could not honour ({@link UnreadProjectItem}): MIDI notes skipped, CC
 * events skipped (a skipped lane counts as its events, at least 1),
 * arpeggiator settings adjusted, MIDI effects not recognized and kept as
 * saved, and mixer assignments removed (to a track the file does not
 * contain, malformed, a self-link or a repeat). The doors that report a
 * failure keep their ERROR level.
 *
 * <p>The announcement is made when the references are classified, not when
 * the decodes finish: rows the rehydration adds later get their own single
 * follow-up ({@link #followUp}, shown by {@link OpenProjectAudio}).</p>
 *
 * @param level        the notification level
 * @param message      the text
 * @param offersReview whether the notification carries the {@code Review…}
 *                     action — the report has rows to show
 */
record OpenAnnouncement(NotificationLevel level, String message, boolean offersReview) {

    OpenAnnouncement {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    /**
     * Words the announcement of an open through {@code door}.
     *
     * <p>The counts are of distinct assets: the missing ones
     * ({@link MissingAssetsReport#missingAssetCount()}: a take is one, split
     * halves of one file are one), the clips they silence (left out when
     * none is), and those shorter than their clips
     * ({@link MissingAssetsReport#shorterAssetCount()}) — files that loaded
     * as they are, neither missing nor silent. The archive door counts as
     * missing the union of the open's missing assets and the references the
     * archive could not resolve itself, mapped into the destination
     * ({@code destination.toAbsolutePath().normalize().resolve(reference).normalize()}),
     * so an asset both lists name is counted once and a SoundFont only the
     * archive names is counted too.</p>
     *
     * <p>The saved items the open could not honour follow the asset clauses
     * — inside the archive door's parentheses — as one clause per kind of
     * damage, each only when there is one. The archive restore and the
     * journal replay announce the items their own read found
     * ({@link OpenDoor.ArchiveRestore#archiveUnread()},
     * {@link OpenDoor.JournalReplay#replayUnread()}) instead of
     * {@code unreadItems}: they re-serialized {@code project.daw} before the
     * load, so its read finds nothing they dropped, and finds again the
     * placeholders they wrote back — counting both would count those twice.
     * The items never offer {@code Review…}: the report it opens lists
     * assets only.</p>
     *
     * @param door             how the project was opened
     * @param projectDirectory the opened project's directory (the archive's
     *                         destination), or {@code null} when it has none
     * @param report           the open's missing-assets report
     * @param unreadItems      the saved items the open's read could not honour
     *                         as written
     *                         ({@code ProjectManager.getLastUnreadItems()}, or
     *                         for a snapshot its own read's)
     * @return the announcement
     */
    static OpenAnnouncement of(OpenDoor door, Path projectDirectory, MissingAssetsReport report,
                               List<UnreadProjectItem> unreadItems) {
        Objects.requireNonNull(door, "door must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(unreadItems, "unreadItems must not be null");
        String done = doorText(door, projectDirectory);
        NotificationLevel clean = cleanLevel(door);
        int missing = door instanceof OpenDoor.ArchiveRestore archive
                ? distinctUnion(report, archive.archiveMissing(), projectDirectory)
                : report.missingAssetCount();
        int shorter = report.shorterAssetCount();
        List<String> unread = unreadClauses(unreadItemsOf(door, unreadItems));
        if (missing + shorter == 0 && unread.isEmpty()) {
            return new OpenAnnouncement(clean, done, false);
        }
        NotificationLevel level = clean == NotificationLevel.ERROR ? NotificationLevel.ERROR : NotificationLevel.WARNING;
        int silent = report.silentClipCount();
        List<String> clauses = new ArrayList<>(door instanceof OpenDoor.ArchiveRestore
                ? archiveClauses(missing, silent, shorter)
                : clauses(missing, silent, shorter));
        clauses.addAll(unread);
        String message = door instanceof OpenDoor.ArchiveRestore archive
                ? ProjectAudioText.text("projectOpen.archive.qualified", archive.projectName(), joined(clauses))
                : ProjectAudioText.text("projectOpen.qualified", done, joined(clauses));
        return new OpenAnnouncement(level, message, !report.isEmpty());
    }

    /**
     * What {@code report} holds, in the words an announcement uses after its
     * em dash (decision D24) — the missing-assets report's summary, for
     * example {@code 1 missing asset (1 clip silent), 1 asset shorter than
     * its clips}.
     *
     * @param report the report
     * @return the summary; empty when the report has no rows
     */
    static String summary(MissingAssetsReport report) {
        Objects.requireNonNull(report, "report must not be null");
        return joined(clauses(report.missingAssetCount(), report.silentClipCount(), report.shorterAssetCount()));
    }

    /**
     * The one follow-up WARNING of a run (decisions D14, D24) for
     * {@code added} — the rows the run added after its announcement that are
     * still unresolved: how many assets could not be loaded as saved, then
     * how many are shorter than their clips, joined with {@code "; "}.
     *
     * @param added the rows to follow up on
     * @return the follow-up's text, or empty when there is nothing to say
     */
    static Optional<String> followUp(MissingAssetsReport added) {
        Objects.requireNonNull(added, "added must not be null");
        int missing = added.missingAssetCount();
        int shorter = added.shorterAssetCount();
        if (missing == 0 && shorter == 0) {
            return Optional.empty();
        }
        String notLoaded = ProjectAudioText.count("projectOpen.followUp", missing);
        String shorterThanClips = ProjectAudioText.count("projectOpen.shorterAssets", shorter);
        if (missing > 0 && shorter > 0) {
            return Optional.of(ProjectAudioText.text("projectOpen.followUp.both", notLoaded, shorterThanClips));
        }
        return Optional.of(missing > 0 ? notLoaded : shorterThanClips);
    }

    /**
     * The asset clauses after the em dash: the missing assets, the clips they
     * silence in parentheses (left out when none is), then the shorter
     * assets; each only when there is one.
     */
    private static List<String> clauses(int missing, int silent, int shorter) {
        List<String> clauses = new ArrayList<>(2);
        if (missing > 0) {
            String assets = ProjectAudioText.count("projectOpen.assets", missing);
            clauses.add(silent > 0
                    ? ProjectAudioText.text("projectOpen.missingSilent", assets,
                            ProjectAudioText.count("projectOpen.silentClips", silent))
                    : assets);
        }
        if (shorter > 0) {
            clauses.add(ProjectAudioText.count("projectOpen.shorterAssets", shorter));
        }
        return clauses;
    }

    /** The unread items an open through {@code door} announces: the door's own, or the open's read's. */
    private static List<UnreadProjectItem> unreadItemsOf(OpenDoor door, List<UnreadProjectItem> unreadItems) {
        return switch (door) {
            case OpenDoor.ArchiveRestore archive -> archive.archiveUnread();
            case OpenDoor.JournalReplay replay -> replay.replayUnread();
            case OpenDoor.Plain _, OpenDoor.RecoveredWriteFailed _, OpenDoor.FailedReplayFallback _,
                 OpenDoor.MigrationRollback _, OpenDoor.SnapshotRestore _ -> unreadItems;
        };
    }

    /**
     * One clause per {@link UnreadClause}, in its declaration order, each
     * only when it counts something: the saved items {@code items} stand for
     * ({@link UnreadProjectItem#count()} — a skipped lane counts as its
     * events, at least 1, so a lane saved with none is one skipped CC
     * event), summed per clause.
     */
    private static List<String> unreadClauses(List<UnreadProjectItem> items) {
        Map<UnreadClause, Integer> counts = new EnumMap<>(UnreadClause.class);
        for (UnreadProjectItem item : items) {
            counts.merge(UnreadClause.of(item.kind()), Math.max(1, item.count()), Integer::sum);
        }
        List<String> clauses = new ArrayList<>(counts.size());
        counts.forEach((clause, count) -> clauses.add(ProjectAudioText.count(clause.key, count)));
        return clauses;
    }

    /**
     * The clauses of the saved items an open could not honour as written, in
     * the order the announcement gives them, each with its count key.
     */
    private enum UnreadClause {
        MIDI_NOTES("projectOpen.unread.midiNotes"),
        CC_EVENTS("projectOpen.unread.ccEvents"),
        ARPEGGIATOR_SETTINGS("projectOpen.unread.arpeggiatorSettings"),
        MIDI_EFFECTS("projectOpen.unread.midiEffects"),
        MIXER_ASSIGNMENTS("projectOpen.unread.mixerAssignments");

        private final String key;

        UnreadClause(String key) {
            this.key = key;
        }

        /** The clause an item of {@code kind} counts in; exhaustive, so a new kind does not compile without one. */
        static UnreadClause of(UnreadProjectItem.Kind kind) {
            return switch (kind) {
                case MIDI_NOTE_SKIPPED -> MIDI_NOTES;
                case CC_LANE_SKIPPED, CC_EVENT_SKIPPED -> CC_EVENTS;
                case MIDI_EFFECT_SETTING_ADJUSTED -> ARPEGGIATOR_SETTINGS;
                case MIDI_EFFECT_NOT_RECOGNIZED -> MIDI_EFFECTS;
                case MIXER_REFERENCE_REMOVED -> MIXER_ASSIGNMENTS;
            };
        }
    }

    /** The archive door's clauses, every one inside its parentheses: missing, silent, shorter. */
    private static List<String> archiveClauses(int missing, int silent, int shorter) {
        List<String> clauses = new ArrayList<>(3);
        if (missing > 0) {
            clauses.add(ProjectAudioText.count("projectOpen.assets", missing));
        }
        if (silent > 0) {
            clauses.add(ProjectAudioText.count("projectOpen.silentClips", silent));
        }
        if (shorter > 0) {
            clauses.add(ProjectAudioText.count("projectOpen.shorterAssets", shorter));
        }
        return clauses;
    }

    /** The clauses, in order, comma-joined. */
    private static String joined(List<String> clauses) {
        String joined = "";
        for (String clause : clauses) {
            joined = joined.isEmpty() ? clause : ProjectAudioText.text("projectOpen.clauses", joined, clause);
        }
        return joined;
    }

    /** What the door did, as its clean announcement says it. */
    private static String doorText(OpenDoor door, Path projectDirectory) {
        return switch (door) {
            case OpenDoor.Plain _ -> ProjectAudioText.text("projectOpen.plain", subject(projectDirectory));
            case OpenDoor.ArchiveRestore archive -> ProjectAudioText.text("projectOpen.archive", archive.projectName());
            case OpenDoor.JournalReplay replay -> ProjectAudioText.count("projectOpen.journal", replay.appliedChanges());
            case OpenDoor.RecoveredWriteFailed failed ->
                    ProjectAudioText.text("projectOpen.recoveredWriteFailed", failed.failure());
            case OpenDoor.FailedReplayFallback failed ->
                    ProjectAudioText.text("projectOpen.failedReplay", failed.failure());
            case OpenDoor.MigrationRollback rollback ->
                    ProjectAudioText.text("projectOpen.migrationRollback", Integer.toString(rollback.fromVersion()));
            case OpenDoor.SnapshotRestore snapshot -> ProjectAudioText.text("projectOpen.snapshot", snapshot.label());
        };
    }

    /** The level of the door's announcement when nothing is missing. */
    private static NotificationLevel cleanLevel(OpenDoor door) {
        return switch (door) {
            case OpenDoor.RecoveredWriteFailed _, OpenDoor.FailedReplayFallback _ -> NotificationLevel.ERROR;
            case OpenDoor.SnapshotRestore _ -> NotificationLevel.INFO;
            case OpenDoor.Plain _, OpenDoor.ArchiveRestore _, OpenDoor.JournalReplay _,
                 OpenDoor.MigrationRollback _ -> NotificationLevel.SUCCESS;
        };
    }

    /** The opened folder as the plain announcement names it: its own name. */
    private static String subject(Path projectDirectory) {
        if (projectDirectory == null) {
            return "";
        }
        Path name = projectDirectory.getFileName();
        return (name == null ? projectDirectory : name).toString();
    }

    /**
     * The number of distinct missing assets of {@code report} and of the
     * archive's own {@code archiveMissing} list together: an archive
     * reference that names a reference of any report row is that row's
     * asset, counted (as missing) only when the row is.
     */
    static int distinctUnion(MissingAssetsReport report, List<String> archiveMissing, Path destination) {
        Set<String> reported = new HashSet<>();
        for (MissingAsset row : report.assets()) {
            reported.addAll(row.reference().references());
        }
        Set<String> onlyTheArchive = new LinkedHashSet<>();
        for (String reference : archiveMissing) {
            String mapped = inDestination(destination, reference);
            if (!reported.contains(mapped) && !reported.contains(reference)) {
                onlyTheArchive.add(mapped);
            }
        }
        return report.missingAssetCount() + onlyTheArchive.size();
    }

    /** {@code reference} as the open resolved it: under the destination when it is relative. */
    private static String inDestination(Path destination, String reference) {
        if (destination == null || reference == null) {
            return String.valueOf(reference);
        }
        try {
            return destination.toAbsolutePath().normalize().resolve(reference).normalize().toString();
        } catch (InvalidPathException notAPath) {
            return reference;
        }
    }
}
