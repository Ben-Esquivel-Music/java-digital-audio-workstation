package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.track.Track;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The missing-assets report of an open project (Persistence Integrity book
 * §4.3, §5.3 step 5 ACCOUNT, §6.2): ordered rows, one per asset and reason,
 * each naming the clips that hold the asset. Immutable — the open builds it
 * ({@link #atOpen}), the clip rehydrator's results add rows
 * ({@link #with(MissingAsset)}), and a relink or a skip removes them
 * ({@link #without(AssetReference)}), each producing a new report.
 *
 * <p>Counting follows the assets, not the clips: {@link #assetCount()}
 * counts distinct asset references (a take is one asset, two split halves
 * of one file are one asset), split into the {@linkplain #missingAssetCount()
 * missing} ones (a row that silences clips) and the
 * {@linkplain #shorterAssetCount() shorter} ones (a file that holds less than
 * its clips span, loaded as it is); {@link #silentClipCount()} counts the
 * distinct clips that have no audio because of a row.</p>
 *
 * <p>Reference tracks are not rows: nothing loads their audio yet (future
 * story 359), so a relink would restore nothing. Their missing paths are
 * kept apart in {@link #missingReferenceTrackPaths()}, for the log only, and
 * never counted.</p>
 *
 * @param assets                     the rows, in order; copied, rows with an
 *                                   equal reference and reason merged into
 *                                   the first
 * @param missingReferenceTrackPaths the missing reference-track files, for
 *                                   logging; copied, distinct, never counted
 */
public record MissingAssetsReport(List<MissingAsset> assets, List<String> missingReferenceTrackPaths) {

    /** The report with no rows and no reference-track paths. */
    public static final MissingAssetsReport EMPTY = new MissingAssetsReport(List.of(), List.of());

    private static final String NOT_ABSOLUTE = "is not an absolute path on this platform";

    public MissingAssetsReport {
        assets = mergeRows(Objects.requireNonNull(assets, "assets must not be null"));
        missingReferenceTrackPaths = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(
                missingReferenceTrackPaths, "missingReferenceTrackPaths must not be null")));
    }

    /**
     * Builds the report for a project that was just deserialized, without
     * touching the disk: the deserializer already checked which references
     * name no file ({@code missingFiles}, {@link ProjectDeserializer#getMissingFiles()}),
     * and this matches those strings against the clips that hold them.
     * <ul>
     *   <li>A clip reference that is not an absolute path on this platform
     *       is {@link MissingAssetReason#UNRESOLVABLE}, with the
     *       {@link ProjectPaths#unresolvable} reason against
     *       {@code projectDirectory} when there is one — whether or not it is
     *       in {@code missingFiles}.</li>
     *   <li>An absolute reference listed in {@code missingFiles} is
     *       {@link MissingAssetReason#NOT_FOUND}.</li>
     *   <li>A take with any such segment is one row for the whole take (all
     *       or none: a take with a segment missing is never loaded), its
     *       detail listing the segments; {@code UNRESOLVABLE} when one of
     *       them is, otherwise {@code NOT_FOUND}.</li>
     *   <li>A reference-track file in {@code missingFiles} goes to
     *       {@link #missingReferenceTrackPaths()}.</li>
     * </ul>
     * Rows are in project order (tracks, then each track's clips); every
     * clip holding a row's reference is a holder, and the holders without
     * audio are its silent clips.
     *
     * @param project          the deserialized project
     * @param missingFiles     the deserializer's missing references (may repeat)
     * @param projectDirectory the directory the project was read from, or
     *                         {@code null} for a load without one
     * @return the report
     */
    public static MissingAssetsReport atOpen(DawProject project, List<String> missingFiles,
                                             Path projectDirectory) {
        Objects.requireNonNull(project, "project must not be null");
        Set<String> missing = new HashSet<>(Objects.requireNonNull(missingFiles, "missingFiles must not be null"));
        Map<AssetReference, List<MissingAsset.Holder>> holders = new LinkedHashMap<>();
        Map<AssetReference, List<MissingAsset.Holder>> silent = new LinkedHashMap<>();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                Optional<AssetReference> reference = AssetReference.of(clip);
                if (reference.isEmpty()) {
                    continue;
                }
                MissingAsset.Holder holder = MissingAsset.Holder.of(track, clip);
                holders.computeIfAbsent(reference.get(), key -> new ArrayList<>()).add(holder);
                List<MissingAsset.Holder> silentOfReference =
                        silent.computeIfAbsent(reference.get(), key -> new ArrayList<>());
                if (clip.getAudioData() == null) {
                    silentOfReference.add(holder);
                }
            }
        }
        List<MissingAsset> rows = new ArrayList<>();
        for (Map.Entry<AssetReference, List<MissingAsset.Holder>> entry : holders.entrySet()) {
            AssetReference reference = entry.getKey();
            classify(reference, missing, projectDirectory).ifPresent(problem -> rows.add(new MissingAsset(
                    reference, problem.reason(), problem.detail(), entry.getValue(), silent.get(reference))));
        }
        List<String> referenceTrackPaths = new ArrayList<>();
        for (ReferenceTrack referenceTrack : project.getReferenceTrackManager().getReferenceTracks()) {
            String path = referenceTrack.getSourceFilePath();
            if (path != null && missing.contains(path)) {
                referenceTrackPaths.add(path);
            }
        }
        return new MissingAssetsReport(rows, referenceTrackPaths);
    }

    /** Why one reference is a row at open, and the row's detail. */
    private record Problem(MissingAssetReason reason, String detail) {
        Problem {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    private static Optional<Problem> classify(AssetReference reference, Set<String> missing,
                                              Path projectDirectory) {
        return switch (reference) {
            case AssetReference.SingleFile file -> problemOf(file.reference(), missing, projectDirectory);
            case AssetReference.Take take -> takeProblem(take, missing, projectDirectory);
        };
    }

    private static Optional<Problem> takeProblem(AssetReference.Take take, Set<String> missing,
                                                 Path projectDirectory) {
        List<String> parts = new ArrayList<>();
        boolean unresolvable = false;
        for (String segment : take.segments()) {
            Optional<Problem> problem = problemOf(segment, missing, projectDirectory);
            if (problem.isPresent()) {
                unresolvable |= problem.get().reason() == MissingAssetReason.UNRESOLVABLE;
                parts.add(segment + " (" + problem.get().detail() + ")");
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        MissingAssetReason reason = unresolvable ? MissingAssetReason.UNRESOLVABLE : MissingAssetReason.NOT_FOUND;
        return Optional.of(new Problem(reason, segmentsDetail(reason, take.segments().size(), parts)));
    }

    /**
     * The detail of a take row (decision D35): how many of its {@code total}
     * segments are missing — or, for {@link MissingAssetReason#UNREADABLE},
     * cannot be read — that the take as a whole is not loaded (a take is all
     * or none), then each of those segments ({@code parts}, "path (why)"),
     * for example {@code "1 of 3 segments is missing — the take is not
     * loaded: …/segment-002.wav (not found)"}.
     *
     * @param reason the row's reason
     * @param total  how many segments the take has
     * @param parts  the segments the reason concerns, each "path (why)"; not empty
     * @return the detail
     */
    static String segmentsDetail(MissingAssetReason reason, int total, List<String> parts) {
        int count = parts.size();
        String segments = count + " of " + total + (total == 1 ? " segment " : " segments ");
        String state = reason == MissingAssetReason.UNREADABLE
                ? "cannot be read"
                : count == 1 ? "is missing" : "are missing";
        return segments + state + " — the take is not loaded: " + String.join(", ", parts);
    }

    private static Optional<Problem> problemOf(String reference, Set<String> missing, Path projectDirectory) {
        Optional<ProjectPaths.Unresolvable> unresolvable = ProjectPaths.unresolvable(projectDirectory, reference);
        if (unresolvable.isPresent()) {
            return Optional.of(new Problem(MissingAssetReason.UNRESOLVABLE, unresolvable.get().reason()));
        }
        if (!ProjectPaths.isAbsoluteReference(reference)) {
            return Optional.of(new Problem(MissingAssetReason.UNRESOLVABLE, NOT_ABSOLUTE));
        }
        if (missing.contains(reference)) {
            return Optional.of(new Problem(MissingAssetReason.NOT_FOUND, "not found"));
        }
        return Optional.empty();
    }

    /**
     * Returns whether the report has no rows (reference-track paths do not count).
     *
     * @return {@code true} if there is nothing to report
     */
    public boolean isEmpty() {
        return assets.isEmpty();
    }

    /**
     * Returns the number of distinct assets the rows name.
     *
     * @return the asset count
     */
    public int assetCount() {
        Set<AssetReference> distinct = new HashSet<>();
        for (MissingAsset row : assets) {
            distinct.add(row.reference());
        }
        return distinct.size();
    }

    /**
     * Returns the number of distinct assets with a row whose reason
     * {@linkplain MissingAssetReason#silencesClips() silences clips} — the
     * report's missing assets (decision D24). An asset that also has a
     * {@link MissingAssetReason#CHANGED_ON_DISK} row is counted here and in
     * {@link #shorterAssetCount()}.
     *
     * @return the missing-asset count
     */
    public int missingAssetCount() {
        Set<AssetReference> distinct = new HashSet<>();
        for (MissingAsset row : assets) {
            if (row.reason().silencesClips()) {
                distinct.add(row.reference());
            }
        }
        return distinct.size();
    }

    /**
     * Returns the number of distinct assets with a
     * {@link MissingAssetReason#CHANGED_ON_DISK} row — files that hold less
     * audio than their clips span, loaded as they are (decision D24). They
     * are not missing and silence nothing.
     *
     * @return the shorter-asset count
     */
    public int shorterAssetCount() {
        Set<AssetReference> distinct = new HashSet<>();
        for (MissingAsset row : assets) {
            if (row.reason() == MissingAssetReason.CHANGED_ON_DISK) {
                distinct.add(row.reference());
            }
        }
        return distinct.size();
    }

    /**
     * Returns the number of distinct clips that have no audio because of a
     * row — every row's {@link MissingAsset#silentHolders()} together. A
     * {@link MissingAssetReason#CHANGED_ON_DISK} row adds none; an
     * {@link MissingAssetReason#ADM_OBJECT_UNKNOWN} row adds only the clips
     * it silences.
     *
     * @return the silent-clip count
     */
    public int silentClipCount() {
        Set<String> clips = new HashSet<>();
        for (MissingAsset row : assets) {
            for (MissingAsset.Holder holder : row.silentHolders()) {
                clips.add(holder.clipId());
            }
        }
        return clips.size();
    }

    /**
     * Returns the report of only the rows that name one of
     * {@code references}, in order — for counting a subset of the rows, such
     * as those a run added after its announcement. Reference-track paths are
     * kept.
     *
     * @param references the assets to keep
     * @return the new report
     */
    public MissingAssetsReport restrictedTo(Collection<AssetReference> references) {
        Objects.requireNonNull(references, "references must not be null");
        Set<AssetReference> kept = new HashSet<>(references);
        List<MissingAsset> rows = new ArrayList<>(assets.size());
        for (MissingAsset row : assets) {
            if (kept.contains(row.reference())) {
                rows.add(row);
            }
        }
        return rows.size() == assets.size() ? this : new MissingAssetsReport(rows, missingReferenceTrackPaths);
    }

    /**
     * Returns whether any row names {@code reference}.
     *
     * @param reference the asset
     * @return {@code true} if the asset is reported
     */
    public boolean contains(AssetReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        for (MissingAsset row : assets) {
            if (row.reference().equals(reference)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the rows that name {@code reference}, in order.
     *
     * @param reference the asset
     * @return the rows; empty when the asset is not reported
     */
    public List<MissingAsset> rowsFor(AssetReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        List<MissingAsset> rows = new ArrayList<>();
        for (MissingAsset row : assets) {
            if (row.reference().equals(reference)) {
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Returns this report with {@code row} added at the end — or, when a row
     * with the same reference and reason is already present, with its
     * holders merged into that row.
     *
     * @param row the row to add
     * @return the new report
     */
    public MissingAssetsReport with(MissingAsset row) {
        Objects.requireNonNull(row, "row must not be null");
        return withAll(List.of(row));
    }

    /**
     * Returns this report with every row of {@code rows} added, as by
     * {@link #with(MissingAsset)}, in order.
     *
     * @param rows the rows to add
     * @return the new report
     */
    public MissingAssetsReport withAll(List<MissingAsset> rows) {
        Objects.requireNonNull(rows, "rows must not be null");
        if (rows.isEmpty()) {
            return this;
        }
        List<MissingAsset> all = new ArrayList<>(assets);
        all.addAll(rows);
        return new MissingAssetsReport(all, missingReferenceTrackPaths);
    }

    /**
     * Returns this report without any row naming {@code reference} — the
     * asset was relinked or skipped.
     *
     * @param reference the asset
     * @return the new report
     */
    public MissingAssetsReport without(AssetReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        List<MissingAsset> kept = new ArrayList<>(assets.size());
        for (MissingAsset row : assets) {
            if (!row.reference().equals(reference)) {
                kept.add(row);
            }
        }
        return kept.size() == assets.size() ? this : new MissingAssetsReport(kept, missingReferenceTrackPaths);
    }

    /** Merges rows with an equal reference and reason into the first of them, keeping order. */
    private static List<MissingAsset> mergeRows(List<MissingAsset> rows) {
        Map<RowKey, MissingAsset> merged = new LinkedHashMap<>();
        for (MissingAsset row : rows) {
            Objects.requireNonNull(row, "row must not be null");
            merged.merge(new RowKey(row.reference(), row.reason()), row,
                    MissingAsset::mergedWith);
        }
        return List.copyOf(merged.values());
    }

    private record RowKey(AssetReference reference, MissingAssetReason reason) {
    }
}
