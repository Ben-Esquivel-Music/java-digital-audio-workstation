package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The live asset references of a project — the places in the model that name
 * a file — and the one walk that finds them (story 333; Persistence Integrity
 * book §3.3): a clip's source file, each element of a recorded clip's
 * segment list, and a track's SoundFont path.
 *
 * <p>Two callers walk them. {@link #rebase} re-points every reference under
 * one folder whose file the copy holds to the same relative path under
 * another, after Save As made the copy the open project, so the session
 * plays, and its next save writes, the copy's files — every lane included,
 * not only the saved comp. The project
 * archive ({@code ProjectArchiver}) walks the holders its saved document
 * carries ({@link #collect(DawProject, Function, boolean)}) to package,
 * relocate or consolidate them.</p>
 *
 * <p>Call it on the thread that owns the model; no disk access. The model is
 * read through its live lists, so an edit must not interleave.</p>
 */
public final class ProjectAssetReferences {

    private ProjectAssetReferences() {
    }

    /**
     * One live asset reference: what it names now, and how to re-point it.
     * A recorded clip's segment list gives one reference per element and no
     * {@linkplain ProjectDocument.ReferenceKind#CLIP_SOURCE_FILE source-file} reference — the
     * list is the authority and the source file only its first element
     * ({@link AudioClip#setSourceFilePath(String)} refuses anything else) —
     * and each element's reference is captured from one read of the list, so
     * a list the model replaces meanwhile never makes a later element read
     * another reference.
     */
    public sealed interface Ref permits ClipFileRef, ClipSegmentRef, SoundFontRef {

        /**
         * Where the reference sits.
         *
         * @return its kind: {@code CLIP_SOURCE_FILE}, {@code CLIP_SOURCE_SEGMENT} or {@code SOUND_FONT}
         */
        ProjectDocument.ReferenceKind kind();

        /**
         * The reference as the model holds it now — a SoundFont path in its
         * {@link Path#toString()} form — or {@code null} for none.
         *
         * @return the reference
         */
        String currentPath();

        /**
         * Re-points the reference: the clip's source file, the one element of
         * its segment list (the others and their order kept), or the
         * SoundFont path (bank, program and preset kept).
         *
         * @param newPath the reference to hold from now on
         */
        void update(String newPath);
    }

    /**
     * Re-points every live reference of {@code project} that lies strictly
     * under {@code from} <em>and whose file the copy holds</em> — its path
     * relative to {@code from} is one of {@code held} — to the same relative
     * path under {@code to}: the source files of the clips of every track and
     * every take lane — not only the comp the project saves — each element of
     * a recorded clip's segment list, and each track's SoundFont path (story
     * 333: after Save As, the open project reads the copy's files). A
     * reference under {@code from} whose file the copy does not hold —
     * missing, unreadable, or left out — keeps naming the original's file, as
     * the copy's {@code project.daw} does. Containment is judged on the
     * absolute, normalised paths, as {@link ProjectPaths} judges it; a
     * reference that is not an absolute path, that names {@code from} itself,
     * or that lies elsewhere is left as it is. References held only by the
     * undo history are not reachable here (the former-root rule of
     * {@link ProjectSerializer#capture(DawProject, FormerRoots)} writes their
     * saved form project-relative when the copy holds their file). Owner
     * thread; no disk access; nothing is marked dirty.
     *
     * @param project the project
     * @param from    the folder the references lie under now
     * @param to      the folder they are to lie under
     * @param held    the files the copy in {@code to} holds, each relative to it
     *                ({@link SaveAsOutcome.Published#formerRoots()}'s {@link FormerRoots#heldFiles()})
     * @return how many references it re-pointed
     */
    public static int rebase(DawProject project, Path from, Path to, Set<Path> held) {
        Objects.requireNonNull(project, "project must not be null");
        Path fromRoot = Objects.requireNonNull(from, "from must not be null").toAbsolutePath().normalize();
        Path toRoot = Objects.requireNonNull(to, "to must not be null").toAbsolutePath().normalize();
        Set<Path> copied = Set.copyOf(Objects.requireNonNull(held, "held must not be null"));
        int rebased = 0;
        for (Ref ref : collect(project)) {
            Optional<String> moved = rebased(ref.currentPath(), fromRoot, toRoot, copied);
            if (moved.isPresent()) {
                ref.update(moved.get());
                rebased++;
            }
        }
        return rebased;
    }

    /**
     * Every live reference of {@code project}, track by track in project
     * order: the references of the track's clips, then those of each take
     * lane's clips, lane by lane — a clip held twice counted once — then the
     * track's SoundFont path.
     *
     * @param project the project
     * @return the references (a new, modifiable list)
     */
    public static List<Ref> collect(DawProject project) {
        return collect(project, ProjectAssetReferences::everyClipOf, true);
    }

    /**
     * The live references of {@code project} that {@code holders} chooses,
     * track by track in project order: the references of the clips
     * {@code holders} returns for the track, in that order — a clip returned
     * twice counted once — then, when {@code soundFonts}, the track's
     * SoundFont path. The project archive passes the clips its saved document
     * carries ({@code TakeComping.referenceHoldersAsSaved}).
     *
     * <p>Only a {@code null} or empty clip reference is skipped, the test
     * {@link AudioClip#setSourceSegmentPaths} and the project reader apply; a
     * whitespace-only one is a reference like any other.</p>
     *
     * @param project    the project
     * @param holders    the clips whose references a track contributes
     * @param soundFonts whether a track's SoundFont path is collected
     * @return the references (a new, modifiable list)
     */
    public static List<Ref> collect(DawProject project, Function<Track, List<AudioClip>> holders,
                                    boolean soundFonts) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(holders, "holders must not be null");
        List<Ref> refs = new ArrayList<>();
        for (Track track : project.getTracks()) {
            Set<AudioClip> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (AudioClip clip : holders.apply(track)) {
                if (seen.add(clip)) {
                    addClipRefs(clip, refs);
                }
            }
            if (soundFonts) {
                SoundFontAssignment assignment = track.getSoundFontAssignment();
                if (assignment != null && assignment.soundFontPath() != null) {
                    refs.add(new SoundFontRef(track, assignment));
                }
            }
        }
        return refs;
    }

    /** A track's clips, then every take lane's clips, lane by lane. */
    private static List<AudioClip> everyClipOf(Track track) {
        List<AudioClip> clips = new ArrayList<>(track.getClips());
        for (TakeLane lane : track.getTakeComping().getTakeLanes()) {
            clips.addAll(lane.getClips());
        }
        return clips;
    }

    /**
     * A clip's references: one per element of its segment list, read once,
     * or — a clip without one — its source file.
     */
    private static void addClipRefs(AudioClip clip, List<Ref> refs) {
        List<String> segments = clip.getSourceSegmentPaths();
        if (!segments.isEmpty()) {
            for (int index = 0; index < segments.size(); index++) {
                String segment = segments.get(index);
                if (!segment.isEmpty()) {
                    refs.add(new ClipSegmentRef(clip, index, segment));
                }
            }
            return;
        }
        String source = clip.getSourceFilePath();
        if (source != null && !source.isEmpty()) {
            refs.add(new ClipFileRef(clip));
        }
    }

    /**
     * {@code reference} re-rooted from {@code fromRoot} to {@code toRoot} when
     * it is an absolute path strictly under {@code fromRoot} whose path there
     * is one of {@code held}; empty otherwise. Both roots are absolute and
     * normalised.
     */
    static Optional<String> rebased(String reference, Path fromRoot, Path toRoot, Set<Path> held) {
        if (reference == null || reference.isEmpty()) {
            return Optional.empty();
        }
        Path candidate;
        try {
            candidate = Path.of(reference);
        } catch (InvalidPathException notAPath) {
            return Optional.empty();
        }
        if (!candidate.isAbsolute()) {
            return Optional.empty();
        }
        Path target = candidate.normalize();
        if (!target.startsWith(fromRoot) || target.equals(fromRoot)) {
            return Optional.empty();
        }
        Path relative = fromRoot.relativize(target);
        return held.contains(relative) ? Optional.of(toRoot.resolve(relative).toString()) : Optional.empty();
    }

    /** The single source file of a clip that carries no segment list. */
    private static final class ClipFileRef implements Ref {

        private final AudioClip clip;

        ClipFileRef(AudioClip clip) {
            this.clip = clip;
        }

        @Override
        public ProjectDocument.ReferenceKind kind() {
            return ProjectDocument.ReferenceKind.CLIP_SOURCE_FILE;
        }

        @Override
        public String currentPath() {
            return clip.getSourceFilePath();
        }

        @Override
        public void update(String newPath) {
            clip.setSourceFilePath(newPath);
        }
    }

    /**
     * One element of a recorded clip's segment list (story 323):
     * {@link #currentPath()} is the element captured from the one list read
     * the walk made for the clip, or what this ref's own {@link #update} last
     * wrote; {@link #update} rebuilds the live list with only this element
     * replaced, and replacing element 0 moves the clip's source file with it.
     */
    private static final class ClipSegmentRef implements Ref {

        private final AudioClip clip;
        private final int index;
        private String segment;

        ClipSegmentRef(AudioClip clip, int index, String segment) {
            this.clip = clip;
            this.index = index;
            this.segment = segment;
        }

        @Override
        public ProjectDocument.ReferenceKind kind() {
            return ProjectDocument.ReferenceKind.CLIP_SOURCE_SEGMENT;
        }

        @Override
        public String currentPath() {
            return segment;
        }

        @Override
        public void update(String newPath) {
            List<String> segments = new ArrayList<>(clip.getSourceSegmentPaths());
            segments.set(index, newPath);
            clip.setSourceSegmentPaths(segments);
            this.segment = newPath;
        }
    }

    /** A track's SoundFont path. */
    private static final class SoundFontRef implements Ref {

        private final Track track;
        private SoundFontAssignment current;

        SoundFontRef(Track track, SoundFontAssignment current) {
            this.track = track;
            this.current = current;
        }

        @Override
        public ProjectDocument.ReferenceKind kind() {
            return ProjectDocument.ReferenceKind.SOUND_FONT;
        }

        @Override
        public String currentPath() {
            return current.soundFontPath() == null ? null : current.soundFontPath().toString();
        }

        @Override
        public void update(String newPath) {
            SoundFontAssignment updated = new SoundFontAssignment(
                    Path.of(newPath), current.bank(), current.program(), current.presetName());
            track.setSoundFontAssignment(updated);
            this.current = updated;
        }
    }
}
