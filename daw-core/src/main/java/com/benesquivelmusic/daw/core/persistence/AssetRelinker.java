package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Relink: points every clip that holds an asset of the missing-assets
 * report at a file the user located, with that file's audio (Persistence
 * Integrity book §4.3; decision D17). Three steps, on two threads:
 * <ol>
 *   <li>{@link #request} — owner thread, no I/O: snapshots what the checks
 *       need from the live project (the clips that hold the asset, their
 *       names, how much audio each uses).</li>
 *   <li>{@link #prepare(Request, Path)} (Locate file) or
 *       {@link #prepare(Request, AssetReference)} (a
 *       {@link MissingAssetSearch} result) — a worker thread: decodes the
 *       located file with the clip rehydrator's own decoder, exactly as a
 *       reopen would (a file through the import's decode and conversion to
 *       the project rate, an ADM file into its bed and objects, a take at its
 *       native rate after {@code SegmentFile.readFrames}' consistency
 *       checks), and returns a {@link RelinkPreparation}: a candidate, which
 *       says whether the user must agree before it is applied and why (a
 *       take found outside its own folder, an ADM import's clips given a
 *       file without ADM metadata, audio shorter than a clip spans), or a
 *       refusal saying why it cannot be used. Never touches the project.</li>
 *   <li>{@link #apply} — owner thread: re-points every clip that still
 *       holds the asset, attaches the decoded audio — one array for every
 *       clip of the source, replacing audio a clip already has — invalidates
 *       each re-pointed clip's sample-rate conversions through the caller's
 *       invalidator, and marks the project dirty.</li>
 * </ol>
 *
 * <p>Relink is an edit but not an undoable one: undoing it would silence
 * the clips again. The next save writes the new reference project-relative
 * when it is under the project directory and absolute outside it
 * ({@link ProjectSerializer}, {@link ProjectPaths}'s write side).</p>
 *
 * <p>A take is relinked as a whole: Locate file takes the folder of the
 * file the user picked — any segment of the take — and finds every segment
 * by name directly in it (not below it; names compared ignoring case), or
 * refuses; a partial take is never relinked. Segment names repeat in every
 * track folder of every take, so a folder that does not carry the take's own
 * trailing {@code <stamp>_take-NNNN/<trackId>} — the rule Locate folder
 * applies ({@link MissingAssetSearch}) — is not refused, but its candidate
 * says so ({@link RelinkPreparation.Candidate#takeFolderMismatch()}) and the
 * user is asked first (decision D25).</p>
 */
public final class AssetRelinker {

    private final SourceDecoder decoder;
    private final long tooLargeBytes;

    /**
     * Creates the production relinker: the clip rehydrator's file decoders,
     * and half of {@link Runtime#maxMemory()} as the most one decode may
     * need (D10).
     */
    public AssetRelinker() {
        this(FileSourceDecoder.INSTANCE, Runtime.getRuntime().maxMemory());
    }

    /**
     * Test seam: a relinker with its own decoder and heap figure (the
     * too-large limit is half of it).
     */
    AssetRelinker(SourceDecoder decoder, long maxMemoryBytes) {
        this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
        if (maxMemoryBytes < 0) {
            throw new IllegalArgumentException("maxMemoryBytes must not be negative: " + maxMemoryBytes);
        }
        this.tooLargeBytes = maxMemoryBytes / 2;
    }

    /**
     * Snapshots what relinking {@code asset} needs from the live project.
     * Owner thread; no I/O.
     *
     * @param project the project the asset's clips are in
     * @param asset   the asset, as its report row names it
     * @return the request to prepare on a worker
     */
    public static Request request(DawProject project, AssetReference asset) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        double bpm = SourceShortfall.playbackTempo(project);
        List<Use> uses = new ArrayList<>();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                if (asset.isHeldBy(clip)) {
                    uses.add(new Use(clip.getName(), SourceShortfall.usedSeconds(clip, bpm)));
                }
            }
        }
        return new Request(project, asset, (int) project.getFormat().sampleRate(), uses);
    }

    /**
     * Prepares relinking the request's asset to the file the user located
     * (Locate file). For a single file, {@code located} is the replacement;
     * for a take, the take's segments are found by name in
     * {@code located}'s folder. Worker thread; never touches the project.
     *
     * @param request the request built on the owner thread
     * @param located the file the user picked
     * @return the candidate, or the refusal
     * @throws InterruptedException if the thread was interrupted while decoding
     */
    public RelinkPreparation prepare(Request request, Path located) throws InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(located, "located must not be null");
        Path file = located.toAbsolutePath().normalize();
        return switch (request.asset()) {
            case AssetReference.SingleFile _ -> prepare(request, new AssetReference.SingleFile(file.toString()));
            case AssetReference.Take take -> prepareTakeIn(request, take, file);
        };
    }

    /**
     * Prepares relinking the request's asset to {@code replacement} — a
     * {@link MissingAssetSearch.Resolved} result, or a file Locate file
     * resolved. Worker thread; never touches the project.
     *
     * @param request     the request built on the owner thread
     * @param replacement the reference the clips would hold: a file for a
     *                    file asset, the same number of segments for a take;
     *                    every reference absolute
     * @return the candidate, or the refusal
     * @throws IllegalArgumentException if {@code replacement} is not of the
     *                                  asset's kind or not absolute
     * @throws InterruptedException     if the thread was interrupted while decoding
     */
    public RelinkPreparation prepare(Request request, AssetReference replacement) throws InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(replacement, "replacement must not be null");
        requireSameKind(request.asset(), replacement);
        if (!replacement.isOpenable()) {
            throw new IllegalArgumentException("a replacement names files by absolute path: " + replacement);
        }
        String located = replacement.references().getFirst();
        DecodedAudio decoded;
        try {
            long cost = decoder.estimateCost(replacement, request.projectSampleRate());
            if (cost > tooLargeBytes) {
                return refusal(request, located, LoadFailure.tooLarge(cost, tooLargeBytes));
            }
            decoded = decoder.decode(replacement, request.projectSampleRate());
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (OutOfMemoryError outOfMemory) {
            return refusal(request, located, LoadFailure.outOfMemory());
        } catch (Throwable error) {
            // Contained (design book §5.2): a file that will not decode is a refusal, not a crash.
            return refusal(request, located, LoadFailure.of(replacement, error));
        }
        return candidate(request, replacement, decoded);
    }

    /**
     * Returns where Locate file's chooser opens for {@code asset} (decisions
     * D17, D25, D44): for a take whose first segment's folder lies inside
     * {@code projectDirectory}, that folder when it exists, else its nearest
     * existing ancestor inside {@code projectDirectory}, else
     * {@code projectDirectory}; for a take outside the project folder and for
     * a file, {@code projectDirectory}. Only folders inside the project
     * folder are looked at — a take referenced on an offline share or a
     * drive that is gone is never touched, so the call cannot stall on it.
     * Reads a few file attributes, lists nothing — call it where the chooser
     * is about to open.
     *
     * @param asset            the asset of the row being located
     * @param projectDirectory the project folder, or {@code null} when the
     *                         project has none
     * @return the directory, or empty when there is none to propose
     */
    public static Optional<Path> locateFileDirectory(AssetReference asset, Path projectDirectory) {
        Objects.requireNonNull(asset, "asset must not be null");
        if (projectDirectory == null) {
            return Optional.empty();
        }
        Path project = projectDirectory.toAbsolutePath().normalize();
        if (asset instanceof AssetReference.Take take
                && ProjectPaths.isAbsoluteReference(take.segments().getFirst())) {
            Path folder = Path.of(take.segments().getFirst()).normalize().getParent();
            // Inside the project only: nothing outside it is looked at.
            for (Path inside = folder;
                 inside != null && inside.startsWith(project) && !inside.equals(project);
                 inside = inside.getParent()) {
                if (Files.isDirectory(inside)) {
                    return Optional.of(inside);
                }
            }
        }
        return Optional.of(project);
    }

    /**
     * Relinks every clip of the candidate's project that still holds the
     * asset: re-points it ({@link AudioClip#setSourceFilePath}, or for a take
     * {@link AudioClip#setSourceSegmentPaths}, which keeps the head
     * invariant), attaches the decoded audio — the same array to every clip
     * of one source (of one bed or object of an ADM file), replacing audio it
     * already holds, the metadata of a take first — and then calls
     * {@code invalidator} with the clip's id, so a sample-rate conversion
     * cached for its old audio is dropped (the application passes the
     * engine's {@code SampleRateConversionCache::invalidateForClip}). Marks
     * the project dirty when it re-pointed a clip. Owner thread.
     *
     * <p>An ADM clip whose name selects no bed or object of the located file
     * is re-pointed with no audio and is a
     * {@link MissingAssetReason#ADM_OBJECT_UNKNOWN} row of the result, as a
     * reopen would make it.</p>
     *
     * @param candidate   a candidate {@link #prepare} returned
     * @param invalidator called once with the id of each re-pointed clip
     * @return the re-pointed clips, in project order, and the rows to add to the report
     */
    public static Applied apply(RelinkPreparation.Candidate candidate, Consumer<String> invalidator) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(invalidator, "invalidator must not be null");
        Request request = candidate.request();
        AssetReference replacement = candidate.replacement();
        DecodedAudio decoded = candidate.audio();
        List<AudioClip> relinked = new ArrayList<>();
        List<MissingAsset.Holder> holders = new ArrayList<>();
        List<MissingAsset.Holder> unselected = new ArrayList<>();
        for (Track track : request.project().getTracks()) {
            for (AudioClip clip : track.getClips()) {
                if (!request.asset().isHeldBy(clip)) {
                    continue;
                }
                holders.add(MissingAsset.Holder.of(track, clip));
                repoint(clip, replacement);
                Optional<DecodedAudio.ClipAudio> audio = decoded.forClip(clip.getName());
                if (audio.isPresent()) {
                    audio.get().attachTo(clip);
                } else {
                    clip.setSourceRateMetadata(null);
                    clip.setAudioData(null);
                    unselected.add(MissingAsset.Holder.of(track, clip));
                }
                // After the new audio is published: a conversion of the old one is dropped for good.
                invalidator.accept(clip.getId());
                relinked.add(clip);
            }
        }
        if (!relinked.isEmpty()) {
            request.project().markDirty();
        }
        List<MissingAsset> rows = new ArrayList<>(1);
        if (!unselected.isEmpty() && decoded instanceof DecodedAudio.AdmAudio adm) {
            rows.add(new MissingAsset(replacement, MissingAssetReason.ADM_OBJECT_UNKNOWN,
                    adm.unknownNamesDetail(unselected.size(), holders.size()), holders, unselected));
        }
        return new Applied(relinked, rows);
    }

    /**
     * The candidate for {@code decoded}, with what its confirmation needs
     * (decisions D25, D27, D30): the longest span among the clips, whether
     * the audio is shorter than a clip spans, a take found outside its own
     * folder, an ADM import's clips given a file without ADM metadata.
     */
    private static RelinkPreparation.Candidate candidate(Request request, AssetReference replacement,
                                                         DecodedAudio decoded) {
        boolean shorter = false;
        double longestSpan = 0.0;
        List<String> names = new ArrayList<>(request.uses().size());
        for (Use use : request.uses()) {
            names.add(use.clipName());
            longestSpan = Math.max(longestSpan, use.seconds());
            Optional<DecodedAudio.ClipAudio> audio = decoded.forClip(use.clipName());
            if (audio.isPresent() && SourceShortfall.isShort(audio.get().seconds(), use.seconds())) {
                shorter = true;
            }
        }
        RelinkPreparation.MissingAdmMetadata noAdmMetadata =
                decoded instanceof DecodedAudio.FileAudio file && file.channels() > 0
                        && DecodedAudio.isAdmImport(names)
                        ? new RelinkPreparation.MissingAdmMetadata(file.channels())
                        : null;
        return new RelinkPreparation.Candidate(request, replacement, decoded,
                locatedNameOf(request.asset(), replacement),
                decoded.seconds(), longestSpan, shorter, takeFolderMismatch(request.asset(), replacement),
                noAdmMetadata);
    }

    /**
     * A take's replacement segments that do not lie in the take's own folder
     * ({@link MissingAssetSearch#liesInItsTakeFolder}, the rule Locate folder
     * applies): the picked folder, named as the take's folder is
     * ({@link #takeFolderName}), and the take's folder; {@code null} when
     * they do, or for a file.
     */
    private static RelinkPreparation.TakeFolderMismatch takeFolderMismatch(AssetReference asset,
                                                                            AssetReference replacement) {
        if (!(asset instanceof AssetReference.Take original) || !(replacement instanceof AssetReference.Take found)) {
            return null;
        }
        for (int i = 0; i < found.segments().size(); i++) {
            Path segment = Path.of(found.segments().get(i));
            String reference = original.segments().get(i);
            if (!MissingAssetSearch.liesInItsTakeFolder(segment, reference)) {
                return new RelinkPreparation.TakeFolderMismatch(takeFolderName(segment.getParent(), reference),
                        MissingAssetSearch.takeFolderOf(reference).orElse(""));
            }
        }
        return null;
    }

    /**
     * The located file's name; for a take, the folder of its segments, named
     * as the take's own folder is ({@link #takeFolderName}).
     */
    private static String locatedNameOf(AssetReference asset, AssetReference replacement) {
        Path first = Path.of(replacement.references().getFirst());
        return switch (replacement) {
            case AssetReference.SingleFile _ -> nameOf(first);
            case AssetReference.Take _ -> takeFolderName(first.getParent(), asset.references().getFirst());
        };
    }

    /**
     * Names {@code folder} as the take folder of {@code segmentReference} is
     * named (decision D38): its trailing path elements, as many as that take
     * folder has ({@link MissingAssetSearch#takeFolderOf} — normally two,
     * {@code <stamp>_take-NNNN/<trackId>}), joined with {@code '/'}; just its
     * last element when the reference names no take folder. Every track
     * folder of a take has the track's id, and every take of a track has a
     * folder with that id, so the last element alone does not tell two takes
     * apart.
     */
    private static String takeFolderName(Path folder, String segmentReference) {
        if (folder == null) {
            return "";
        }
        int names = folder.getNameCount();
        int wanted = Math.max(1, MissingAssetSearch.takeFolderDepth(segmentReference));
        if (names == 0) {
            return nameOf(folder);
        }
        List<String> elements = new ArrayList<>(wanted);
        for (int i = Math.max(0, names - wanted); i < names; i++) {
            elements.add(folder.getName(i).toString());
        }
        return String.join("/", elements);
    }

    /** The last element of {@code path}; the whole path when it has none (a root). */
    private static String nameOf(Path path) {
        if (path == null) {
            return "";
        }
        Path name = path.getFileName();
        return (name == null ? path : name).toString();
    }

    private RelinkPreparation prepareTakeIn(Request request, AssetReference.Take take, Path pickedFile)
            throws InterruptedException {
        Path folder = pickedFile.getParent();
        if (folder == null) {
            return new RelinkPreparation.Refusal(take, MissingAssetReason.NOT_FOUND,
                    pickedFile + " is not in a folder");
        }
        MissingAssetSearch.NameIndex index;
        try {
            index = MissingAssetSearch.NameIndex.ofFolder(folder);
        } catch (IOException unreadable) {
            return refusal(request, folder.toString(), LoadFailure.of(new AssetReference.SingleFile(
                    folder.toString()), unreadable));
        }
        List<String> segments = new ArrayList<>(take.segments().size());
        List<String> unmatched = new ArrayList<>();
        for (String segment : take.segments()) {
            String name = MissingAssetSearch.fileNameOf(segment);
            Optional<Path> found = name.isEmpty() ? Optional.empty() : index.in(folder, name);
            if (found.isPresent()) {
                segments.add(found.get().toString());
            } else {
                unmatched.add(name.isEmpty() ? segment : name);
            }
        }
        if (!unmatched.isEmpty()) {
            return new RelinkPreparation.Refusal(take, MissingAssetReason.NOT_FOUND,
                    segmentsNotInDetail(take.segments().size(), folder, unmatched));
        }
        return prepare(request, new AssetReference.Take(segments));
    }

    /**
     * The detail of a take's Locate file refused because the picked file's
     * folder lacks segments (decision D43): {@code "1 of 3 segments is not in
     * D:\Moved: segment-002.wav"} — its own sentence, not the at-open
     * {@code "— the take is not loaded"} one, which a refusal's
     * {@code "Not relinked —"} would only repeat.
     *
     * @param total   how many segments the take has
     * @param folder  the folder they were looked for in
     * @param absent  the names of the segments it lacks; not empty
     * @return the detail
     */
    private static String segmentsNotInDetail(int total, Path folder, List<String> absent) {
        int count = absent.size();
        return count + " of " + total + (total == 1 ? " segment " : " segments ")
                + (count == 1 ? "is" : "are") + " not in " + folder + ": " + String.join(", ", absent);
    }

    private static RelinkPreparation.Refusal refusal(Request request, String located, LoadFailure failure) {
        return new RelinkPreparation.Refusal(request.asset(), failure.reason(), located + ": " + failure.detail());
    }

    private static void requireSameKind(AssetReference asset, AssetReference replacement) {
        boolean same = switch (asset) {
            case AssetReference.SingleFile _ -> replacement instanceof AssetReference.SingleFile;
            case AssetReference.Take take -> replacement instanceof AssetReference.Take other
                    && other.segments().size() == take.segments().size();
        };
        if (!same) {
            throw new IllegalArgumentException("a replacement has the asset's kind and segment count: "
                    + asset + " cannot be relinked to " + replacement);
        }
    }

    private static void repoint(AudioClip clip, AssetReference replacement) {
        switch (replacement) {
            case AssetReference.SingleFile file -> clip.setSourceFilePath(file.reference());
            // The head invariant: a take is re-pointed through its list, never its head.
            case AssetReference.Take take -> clip.setSourceSegmentPaths(take.segments());
        }
    }

    /**
     * What relinking one asset needs from the live project, snapshotted on
     * the owner thread by {@link #request}: the project (to re-point its
     * clips on apply), the asset, the project rate, and for each clip that
     * held the asset its name and how much audio it uses.
     */
    public static final class Request {

        private final DawProject project;
        private final AssetReference asset;
        private final int projectSampleRate;
        private final List<Use> uses;

        private Request(DawProject project, AssetReference asset, int projectSampleRate, List<Use> uses) {
            this.project = project;
            this.asset = asset;
            this.projectSampleRate = projectSampleRate;
            this.uses = List.copyOf(uses);
        }

        /**
         * Returns the asset being relinked.
         *
         * @return the asset
         */
        public AssetReference asset() {
            return asset;
        }

        /**
         * Returns how many clips held the asset when the request was made.
         *
         * @return the clip count
         */
        public int clipCount() {
            return uses.size();
        }

        DawProject project() {
            return project;
        }

        int projectSampleRate() {
            return projectSampleRate;
        }

        List<Use> uses() {
            return uses;
        }
    }

    /** One clip's use of the asset: its name (which selects an ADM bed or object) and the seconds it uses. */
    private record Use(String clipName, double seconds) {
        Use {
            Objects.requireNonNull(clipName, "clipName must not be null");
        }
    }

    /**
     * What applying a relink did.
     *
     * @param relinked the clips re-pointed at the located file, in project order
     * @param rows     the report rows the relink produced (an ADM clip whose
     *                 name selects nothing in the located file), to add to
     *                 the report after removing the relinked asset's rows
     */
    public record Applied(List<AudioClip> relinked, List<MissingAsset> rows) {
        public Applied {
            relinked = List.copyOf(Objects.requireNonNull(relinked, "relinked must not be null"));
            rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
        }
    }
}
