package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one rule for how a clip reference inside {@code project.daw} relates
 * to the project directory, and for what a clip reference held in memory
 * names. The write side ({@link #relativize}, used by
 * {@link ProjectSerializer}) and the read side ({@link #resolve} and
 * {@link #unresolvable}, used by {@link ProjectDeserializer}) share it so the
 * two cannot drift, and the code that opens a clip reference held in memory
 * — {@code ProjectArchiver}, the Archive Project pre-flight and
 * {@code ClipProcessingService} — follows its in-memory rule
 * ({@link #isAbsoluteReference}), with the two exceptions stated below: a
 * load with no project directory, and the relocation
 * {@code ProjectArchiver.openArchive} does.
 *
 * <p><strong>Rationale.</strong> Recording Reliability book §3.3 / §5.3
 * "Relative paths" and §9.5: a clip reference to anything under the project
 * root is written <em>project-relative</em> with forward slashes
 * ({@code audio/takes/<take>/<trackId>/segment-000.wav}), never absolute, so
 * the project folder is self-contained — moving, copying, archiving or
 * backing it up preserves every take (§2.6). Persistence Integrity book
 * §3.3 "Path semantics": absolute paths are accepted on read for backward
 * compatibility and re-written relative on the next save when the asset
 * lives under the project directory; assets <em>outside</em> the project
 * (shared sample libraries) remain absolute — they are exactly the class the
 * missing-assets surface exists to manage.</p>
 *
 * <p><strong>The rule.</strong> Both sides judge containment the same way,
 * in one private helper: a normalised target is under the root only when it
 * lies <em>strictly</em> under the normalised absolute root — it starts with
 * the root and is not the root itself, which is not an asset.</p>
 * <ul>
 *   <li>{@link #relativize(Path, String)} — a reference is rewritten only
 *       when the root is known, the reference parses as a {@link Path}, is
 *       {@linkplain Path#isAbsolute() absolute}, and (after normalisation)
 *       lies strictly under the root. The result is the root-relative path
 *       joined with {@code '/'}. Everything else — an already-relative
 *       reference, an absolute reference outside the root, the root itself,
 *       or an unparseable string — is returned <em>verbatim</em>.</li>
 *   <li>The read side. With a known root, a non-empty reference falls in
 *       exactly one of five classes, decided in one private method that both
 *       {@link #resolve(Path, String)} and {@link #unresolvable(Path, String)}
 *       call, which parses the reference and normalises the root and the
 *       target once per call:
 *       <ol type="a">
 *         <li><em>project-relative</em> — it has no root component and its
 *             normalised target lies strictly under the root.
 *             {@code resolve} returns that absolute, normalised path, in the
 *             platform's own string form. Containment is judged on the
 *             normalised target, not on the raw string, so
 *             {@code ../<root name>/audio/x.wav} walks back under the root
 *             and is resolved;</li>
 *         <li><em>absolute</em> on the platform reading the file — a legacy
 *             file's absolute path, or an asset outside the project. Kept as
 *             written;</li>
 *         <li><em>not a valid path</em> on the platform reading the file
 *             (it throws {@link InvalidPathException});</li>
 *         <li><em>drive- or root-relative</em> — it has a root component but
 *             is not absolute: on Windows {@code C:x.wav}, {@code \x.wav} and
 *             {@code /x.wav};</li>
 *         <li><em>escaping</em> — it has no root component and its normalised
 *             target is not strictly under the root: outside it
 *             ({@code ../outside.wav}, {@code audio/../../outside.wav},
 *             {@code ..}) or the root itself ({@code .},
 *             {@code audio/..}).</li>
 *       </ol>
 *       Classes (a) and (b) are usable. Classes (c), (d) and (e) are
 *       <em>unresolvable</em>: {@code resolve} returns such a reference as
 *       written and {@code unresolvable} names the reason, and
 *       {@link ProjectDeserializer} keeps it as written, warns and reports it
 *       missing. With a {@code null} root nothing is unresolvable and
 *       {@code resolve} returns every reference as written; a {@code null} or
 *       empty reference is never unresolvable. After a load with a known root,
 *       then, every clip reference the deserializer produced is absolute or
 *       unresolvable, and {@code resolve} never returns a path outside the
 *       root that the file did not literally name.</li>
 *   <li>{@link #isAbsoluteReference(String)} — the in-memory rule: a clip
 *       reference names a file only when it is an absolute path on this
 *       platform. {@code ProjectArchiver}, the Archive Project pre-flight in
 *       {@code ProjectLifecycleController} and {@code ClipProcessingService}
 *       treat every other clip reference as naming no file, and never
 *       resolve one against the JVM's working directory. Only a load with
 *       no project directory (the one-argument
 *       {@link ProjectDeserializer#deserialize(String)}) still checks a
 *       relative clip reference's existence there, for
 *       {@link ProjectDeserializer#getMissingFiles()}.</li>
 * </ul>
 *
 * <p><strong>Platform facts.</strong> The read side uses the {@link Path}
 * semantics of the platform reading the file, so the same project file can
 * be classified differently on Windows and on Linux:</p>
 * <ul>
 *   <li>Containment follows the reading platform's {@code Path} equality,
 *       which is case-insensitive on Windows: for a project directory named
 *       {@code Session}, {@code ../SESSION/audio/x.wav} walks back in on
 *       Windows and escapes on Linux.</li>
 *   <li>A name that Win32 trims ({@code ...}, {@code a.}) is lexically a
 *       plain name, so it is project-relative: {@code a.} resolves to
 *       {@code <root>/a.}, which Windows opens as {@code <root>/a}, and
 *       {@code ...} to {@code <root>/...}, which Windows opens as the root
 *       itself. That is residue of a lexical rule, not an escape: no such
 *       name reaches outside the root.</li>
 *   <li>A Windows device name ({@code NUL}, {@code CON}, {@code COM1} to
 *       {@code COM9}, {@code con.wav}, {@code audio/nul.wav}) is lexically a
 *       plain name too, so it is class (a) and is rebased under the root; on
 *       Windows the rebased path names a device, not a file. The consumers
 *       require a regular file — {@code ProjectArchiver} and the Archive
 *       Project pre-flight check {@code Files.isRegularFile}, and
 *       {@code ClipProcessingService} relies on {@code WavFileReader}'s
 *       {@code isRegularFile} check — so none of them reads such a reference
 *       as a file.</li>
 *   <li>{@code ..\x.wav} is unresolvable on Windows, where it escapes, and a
 *       single in-root name on Linux, where a backslash is an ordinary
 *       character. In the same way {@code C:x.wav} and {@code \x.wav} are
 *       single names under the root on Linux, a Windows-absolute
 *       {@code C:\…} has no root component there and is rebased under the
 *       root, and {@code /x.wav} is absolute there.</li>
 *   <li>What does not parse differs as well: on Windows a character below
 *       U+0020 (NUL, a tab or another C0 control), one of
 *       {@code < > " | ? *} (a {@code \\?\} prefix parses all the same), a
 *       {@code :} anywhere but after a leading drive letter, a name that ends
 *       with a space, or a UNC prefix with no share name ({@code //x.wav}); on
 *       Linux only a NUL character or a character the file-name encoding
 *       cannot represent — with the usual UTF-8 encoding, an unpaired
 *       surrogate. XML 1.0 can carry neither a NUL nor an unpaired
 *       surrogate, so with UTF-8 no reference read from a project file is
 *       class (c) on Linux.</li>
 * </ul>
 *
 * <p>Story 323 applies the rule to every clip reference (a clip's
 * {@code source-file} and its {@code <source-segment>} children, recorded or
 * imported). Story 329 extends it to the other asset references and to
 * relinked paths; the archive's own relocation logic
 * ({@code ProjectArchiver.openArchive}) keys on the stored form and is
 * deliberately not routed through {@link #resolve}.</p>
 *
 * <p>The class is public for {@link #isAbsoluteReference} alone, because
 * the consumers live in other packages and in {@code daw-app}.
 * {@code relativize}, {@code resolve} and {@code unresolvable} stay
 * package-private: translating between the persisted and the in-memory form
 * is the serializer's and the deserializer's job, and a consumer that
 * resolved a reference against a directory of its own choosing would bring
 * back the drift this class exists to prevent.</p>
 *
 * <p>Pure functions; no I/O; callable from any thread.</p>
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    /**
     * Why a non-empty clip reference cannot be resolved against a known
     * project directory — the unresolvable classes (c), (d) and (e) of the
     * rule. {@link #reason()} is worded to follow the reference in a
     * sentence.
     */
    enum Unresolvable {
        /** Class (c): the reference does not parse as a path on this platform. */
        NOT_A_VALID_PATH("is not a valid path on this platform"),
        /**
         * Class (d): the reference has a root component but is not absolute
         * — on Windows {@code C:x.wav}, {@code \x.wav} or {@code /x.wav}.
         */
        DRIVE_OR_ROOT_RELATIVE("is drive- or root-relative on this platform"),
        /** Class (e): the reference's normalised target lies outside the directory. */
        ESCAPES_THE_DIRECTORY("escapes the project directory"),
        /** Class (e): the reference's normalised target is the directory itself. */
        IS_THE_DIRECTORY("is the directory itself");

        private final String reason;

        Unresolvable(String reason) {
            this.reason = reason;
        }

        /** The reason, worded to follow the reference: "'..' escapes the project directory". */
        String reason() {
            return reason;
        }
    }

    /**
     * Write-side rule: returns the project-relative, forward-slash form of
     * {@code reference} when it is an absolute path under {@code root};
     * otherwise returns {@code reference} unchanged.
     *
     * @param root      the project directory, or {@code null} when the
     *                  project has no directory yet (nothing is rewritten)
     * @param reference the in-memory reference (may be {@code null})
     * @return the serialised form
     */
    static String relativize(Path root, String reference) {
        if (root == null || reference == null || reference.isEmpty()) {
            return reference;
        }
        Path candidate = parse(reference);
        if (candidate == null || !candidate.isAbsolute()) {
            return reference;
        }
        Path rootAbs = root.toAbsolutePath().normalize();
        Path target = candidate.normalize();
        if (!isStrictlyUnder(rootAbs, target)) {
            return reference;
        }
        Path relative = rootAbs.relativize(target);
        StringBuilder joined = new StringBuilder();
        for (Path element : relative) {
            if (!joined.isEmpty()) {
                joined.append('/');
            }
            joined.append(element);
        }
        return joined.toString();
    }

    /**
     * Read-side rule: returns the absolute, normalised path that a
     * project-relative {@code reference} names under {@code root} — class
     * (a) of the rule — and every other reference unchanged: an absolute
     * one, an {@linkplain #unresolvable unresolvable} one, a {@code null} or
     * empty one, and every reference when the root is {@code null}. The
     * result is never a path outside the root that the file did not
     * literally name.
     *
     * @param root      the project directory, or {@code null} to keep every
     *                  reference verbatim (the 1-arg
     *                  {@link ProjectDeserializer#deserialize(String)} semantics)
     * @param reference the persisted reference (may be {@code null})
     * @return the in-memory form
     */
    static String resolve(Path root, String reference) {
        Path rebased = classify(root, reference).rebased();
        return rebased == null ? reference : rebased.toString();
    }

    /**
     * Returns why {@code reference} cannot be resolved against {@code root}
     * — class (c), (d) or (e) of the rule — or empty when it is usable:
     * project-relative or absolute, {@code null} or empty, or any reference
     * when the root is {@code null}. {@link ProjectDeserializer} keeps an
     * unresolvable reference as written, warns naming the reason and
     * reports it missing.
     *
     * @param root      the project directory, or {@code null} (then nothing
     *                  is unresolvable)
     * @param reference the persisted reference (may be {@code null})
     * @return the reason the reference is unresolvable, or empty
     */
    static Optional<Unresolvable> unresolvable(Path root, String reference) {
        return Optional.ofNullable(classify(root, reference).unresolvable());
    }

    /**
     * The in-memory rule for a clip reference (story 323): whether
     * {@code reference} is an absolute path on this platform, the only kind
     * of clip reference that names a file. Every other one names no file —
     * {@code null}, empty, one that does not parse on this platform, a
     * relative one and, on Windows, a drive- or root-relative one — so the
     * code that opens clip references ({@code ProjectArchiver}, the Archive
     * Project pre-flight and {@code ClipProcessingService}) lists it as
     * missing, skips it or refuses it, and never resolves it against the
     * JVM's working directory. Only a load with no project directory (the
     * one-argument {@link ProjectDeserializer#deserialize(String)}) still
     * checks a relative clip reference's existence there, for
     * {@link ProjectDeserializer#getMissingFiles()}. After a load with a
     * known project directory every clip reference the deserializer
     * produced is absolute or unresolvable, so this test tells the two
     * apart; a reference that reached memory another way (the
     * one-argument {@link ProjectDeserializer#deserialize(String)}, a
     * DAWproject import) is judged the same way.
     *
     * <p>Pure and free of I/O: it says nothing about whether a file exists
     * at the path.</p>
     *
     * @param reference an in-memory clip reference (may be {@code null})
     * @return {@code true} iff the reference is an absolute path on this
     *         platform
     */
    public static boolean isAbsoluteReference(String reference) {
        if (reference == null || reference.isEmpty()) {
            return false;
        }
        Path candidate = parse(reference);
        return candidate != null && candidate.isAbsolute();
    }

    /**
     * The one place the read side decides: puts a reference in one of the
     * rule's five classes, parsing it once and, for a reference with no root
     * component, normalising the root and the target once. At most one
     * component of the result is set — the rebased target for class (a), the
     * reason for class (c), (d) or (e) — and neither is set for class (b),
     * for a {@code null} root, and for a {@code null} or empty reference.
     */
    private static Classification classify(Path root, String reference) {
        if (root == null || reference == null || reference.isEmpty()) {
            return Classification.AS_WRITTEN;
        }
        Path candidate = parse(reference);
        if (candidate == null) {
            return new Classification(null, Unresolvable.NOT_A_VALID_PATH);
        }
        if (candidate.isAbsolute()) {
            return Classification.AS_WRITTEN;
        }
        if (candidate.getRoot() != null) {
            return new Classification(null, Unresolvable.DRIVE_OR_ROOT_RELATIVE);
        }
        Path rootAbs = root.toAbsolutePath().normalize();
        Path target = rootAbs.resolve(candidate).normalize();
        if (isStrictlyUnder(rootAbs, target)) {
            return new Classification(target, null);
        }
        return new Classification(null, target.equals(rootAbs)
                ? Unresolvable.IS_THE_DIRECTORY
                : Unresolvable.ESCAPES_THE_DIRECTORY);
    }

    /** One reference as {@link #classify} judged it; at most one component is non-null. */
    private record Classification(Path rebased, Unresolvable unresolvable) {
        static final Classification AS_WRITTEN = new Classification(null, null);
    }

    /**
     * The one containment test of both sides: {@code target} starts with
     * {@code rootAbs} and is not {@code rootAbs} itself. Both are normalised;
     * {@code rootAbs} is absolute.
     */
    private static boolean isStrictlyUnder(Path rootAbs, Path target) {
        return target.startsWith(rootAbs) && !target.equals(rootAbs);
    }

    private static Path parse(String reference) {
        try {
            return Path.of(reference);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
