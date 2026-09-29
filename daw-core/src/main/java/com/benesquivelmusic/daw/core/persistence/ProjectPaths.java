package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The one rule for how an asset reference inside {@code project.daw} relates
 * to the project directory — shared by {@link ProjectSerializer} (write side,
 * {@link #relativize}) and {@link ProjectDeserializer} (read side,
 * {@link #resolve}) so the two cannot drift.
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
 * <p><strong>The rule.</strong></p>
 * <ul>
 *   <li>{@link #relativize(Path, String)} — a reference is rewritten only
 *       when the root is known, the reference parses as a {@link Path}, is
 *       {@linkplain Path#isAbsolute() absolute}, and (after normalisation)
 *       lies strictly under the normalised absolute root. The result is the
 *       root-relative path joined with {@code '/'}. Everything else — an
 *       already-relative reference, an absolute reference outside the root,
 *       the root itself, or an unparseable string — is returned
 *       <em>verbatim</em>.</li>
 *   <li>{@link #resolve(Path, String)} — a reference is resolved only when
 *       the root is known, the reference parses, and it has <em>no root
 *       component</em> ({@code Path.getRoot() == null} on the platform
 *       reading the file: neither absolute nor drive-/directory-relative
 *       there; a Windows-absolute reference read on Linux has no root
 *       component and is rebased under the root).
 *       The result is the absolute, normalised path under the root, in the
 *       platform's own string form. Everything else is returned
 *       verbatim.</li>
 * </ul>
 *
 * <p>Story 323 applies the rule to recorded-take clip references
 * ({@code source-file} and the {@code <source-segment>} children). Story 329
 * extends it to imported assets and relinked paths; the archive's own
 * relocation logic ({@code ProjectArchiver}) keys on the stored form and is
 * deliberately not routed through this helper.</p>
 *
 * <p>Pure functions; no I/O; callable from any thread.</p>
 */
final class ProjectPaths {

    private ProjectPaths() {
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
        if (!target.startsWith(rootAbs) || target.equals(rootAbs)) {
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
     * Read-side rule: returns the absolute path of a project-relative
     * {@code reference} under {@code root}; otherwise returns
     * {@code reference} unchanged.
     *
     * @param root      the project directory, or {@code null} to keep every
     *                  reference verbatim (the 1-arg
     *                  {@link ProjectDeserializer#deserialize(String)} semantics)
     * @param reference the persisted reference (may be {@code null})
     * @return the in-memory form
     */
    static String resolve(Path root, String reference) {
        if (root == null || reference == null || reference.isEmpty()) {
            return reference;
        }
        Path candidate = parse(reference);
        if (candidate == null || candidate.getRoot() != null) {
            return reference;
        }
        return root.toAbsolutePath().normalize().resolve(candidate).normalize().toString();
    }

    private static Path parse(String reference) {
        try {
            return Path.of(reference);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
