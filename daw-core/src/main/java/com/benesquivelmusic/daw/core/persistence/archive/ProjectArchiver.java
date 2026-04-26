package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Bundles a {@link DawProject} and every audio asset it references into a
 * single portable {@code .dawz} archive (a regular ZIP file), and restores
 * such archives back to a working project on disk.
 *
 * <h2>Archive layout</h2>
 * <pre>
 *   archive.dawz
 *   ├── archive.properties        — {@link ArchiveHeader}: name, date, asset count,
 *   │                               original absolute root, DAW version,
 *   │                               SHA-256 of project.daw
 *   ├── project.daw               — full project XML with asset paths rewritten
 *   │                               to relative {@code assets/<sha>_<name>}
 *   └── assets/
 *       └── <sha256>_<filename>   — every referenced audio / SoundFont / IR file,
 *                                   deduplicated by content hash
 * </pre>
 *
 * <h2>Operations</h2>
 * <ul>
 *   <li>{@link #saveAsArchive} — writes a {@code .dawz} ZIP for sharing or cold
 *       storage. The in-memory project is never mutated; original asset paths
 *       are restored after serialization.</li>
 *   <li>{@link #openArchive} — extracts an archive into a temp directory and
 *       loads the project, rewriting asset paths to absolute locations under
 *       the temp directory. Missing assets are passed to the supplied
 *       {@link MissingAssetResolver}.</li>
 *   <li>{@link #consolidateInPlace} — copies external assets into a
 *       {@code <projectDir>/assets/} subfolder and rewrites the project's
 *       asset references, without zipping. Useful for converting a project
 *       to a relocatable form.</li>
 * </ul>
 *
 * <p>Asset collection walks every {@link AudioClip#getSourceFilePath()} and
 * every {@link Track#getSoundFontAssignment()} on the project. Assets are
 * deduplicated by SHA-256, so the same recording referenced from many clips
 * is stored only once.</p>
 *
 * <p>This class is thread-safe in the sense that distinct {@code ProjectArchiver}
 * instances may be used concurrently; a single instance should not be shared
 * across concurrent archive operations.</p>
 */
public final class ProjectArchiver {

    /** Archive file extension (with leading dot). */
    public static final String ARCHIVE_EXTENSION = ".dawz";

    /** Asset folder used by {@link #consolidateInPlace}. */
    public static final String CONSOLIDATED_ASSETS_DIR = "assets";

    private static final String DEFAULT_DAW_VERSION = "0.1.0-SNAPSHOT";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final ProjectSerializer serializer;
    private final ProjectDeserializer deserializer;
    private final String dawVersion;

    public ProjectArchiver() {
        this(new ProjectSerializer(), new ProjectDeserializer(), resolveDawVersion());
    }

    public ProjectArchiver(ProjectSerializer serializer,
                           ProjectDeserializer deserializer,
                           String dawVersion) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.dawVersion = (dawVersion == null || dawVersion.isBlank())
                ? DEFAULT_DAW_VERSION : dawVersion;
    }

    private static String resolveDawVersion() {
        String v = ProjectArchiver.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? DEFAULT_DAW_VERSION : v;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Save As Archive
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Writes the given project and every referenced asset to a {@code .dawz}
     * archive using {@link ArchiveOptions#defaults() default options}.
     */
    public ProjectArchiveSummary saveAsArchive(DawProject project, Path archiveFile)
            throws IOException {
        return saveAsArchive(project, archiveFile, ArchiveOptions.defaults());
    }

    /**
     * Writes the given project and every referenced asset to a {@code .dawz}
     * archive at {@code archiveFile} using the supplied options.
     *
     * <p>The in-memory {@code project} is not mutated: original asset paths
     * are restored before this method returns, even on failure.</p>
     *
     * @throws IOException              if the archive cannot be written
     * @throws IllegalArgumentException if {@code archiveFile} does not end in
     *                                  {@value #ARCHIVE_EXTENSION}
     */
    public ProjectArchiveSummary saveAsArchive(DawProject project,
                                                Path archiveFile,
                                                ArchiveOptions options) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(archiveFile, "archiveFile");
        Objects.requireNonNull(options, "options");
        if (!archiveFile.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(ARCHIVE_EXTENSION)) {
            throw new IllegalArgumentException(
                    "Archive file must end with " + ARCHIVE_EXTENSION + ": " + archiveFile);
        }

        // 1. Collect all unique assets, keyed by content hash.
        AssetPlan plan = collectAssets(project, options);

        // 2. Rewrite the project's asset paths to archive-relative form, save
        //    originals so we can restore them after writing.
        Map<AssetRef, String> originals = applyArchivePaths(project, plan);

        try {
            // 3. Serialize project document with rewritten paths.
            String xml = serializer.serialize(project);
            byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
            String sha = sha256Hex(xmlBytes);

            // 4. Determine "original root" for the header.
            String originalRoot = project.getMetadata() != null
                    && project.getMetadata().projectPath() != null
                    ? project.getMetadata().projectPath().toAbsolutePath().toString()
                    : "";

            ArchiveHeader header = new ArchiveHeader(
                    project.getName(),
                    Instant.now(),
                    plan.uniqueAssetCount(),
                    originalRoot,
                    dawVersion,
                    sha
            );

            // 5. Write the ZIP.
            Path parent = archiveFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            long totalAssetBytes = 0L;
            try (OutputStream out = Files.newOutputStream(archiveFile);
                 ZipOutputStream zip = new ZipOutputStream(out)) {

                writeStringEntry(zip, ArchiveHeader.FILE_NAME, headerToProperties(header));
                writeBytesEntry(zip, ArchiveHeader.PROJECT_DOC_NAME, xmlBytes);

                for (Map.Entry<String, Path> e : plan.archiveNameToSource().entrySet()) {
                    String entryName = ArchiveHeader.ASSETS_DIR + "/" + e.getKey();
                    Path src = e.getValue();
                    long size = Files.size(src);
                    totalAssetBytes += size;
                    zip.putNextEntry(new ZipEntry(entryName));
                    try (InputStream in = Files.newInputStream(src)) {
                        copy(in, zip);
                    }
                    zip.closeEntry();
                }
            }

            return new ProjectArchiveSummary(
                    archiveFile, plan.uniqueAssetCount(), totalAssetBytes, xmlBytes.length);
        } finally {
            restorePaths(project, originals);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Open Archive
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Extracts the given archive into {@code targetDir} (created if missing)
     * and loads the embedded project, rewriting asset paths to absolute
     * locations within {@code targetDir}.
     *
     * @param archiveFile    the {@code .dawz} archive to read
     * @param targetDir      destination directory for extraction (typically a
     *                       temp dir; will be created if it does not exist)
     * @param resolver       resolver to call when an embedded asset path
     *                       cannot be located on disk; may be {@code null}
     *                       for {@link MissingAssetResolver#none()}
     */
    public ArchivedProject openArchive(Path archiveFile,
                                       Path targetDir,
                                       MissingAssetResolver resolver) throws IOException {
        Objects.requireNonNull(archiveFile, "archiveFile");
        Objects.requireNonNull(targetDir, "targetDir");
        if (resolver == null) {
            resolver = MissingAssetResolver.none();
        }

        Files.createDirectories(targetDir);
        extractZip(archiveFile, targetDir);

        Path headerFile = targetDir.resolve(ArchiveHeader.FILE_NAME);
        if (!Files.isRegularFile(headerFile)) {
            throw new IOException("Archive missing " + ArchiveHeader.FILE_NAME + ": " + archiveFile);
        }
        ArchiveHeader header = headerFromProperties(Files.readString(headerFile));

        Path projectDoc = targetDir.resolve(ArchiveHeader.PROJECT_DOC_NAME);
        if (!Files.isRegularFile(projectDoc)) {
            throw new IOException("Archive missing " + ArchiveHeader.PROJECT_DOC_NAME
                    + ": " + archiveFile);
        }
        byte[] docBytes = Files.readAllBytes(projectDoc);
        String actualSha = sha256Hex(docBytes);
        if (header.projectDocSha256() != null && !header.projectDocSha256().isBlank()
                && !header.projectDocSha256().equalsIgnoreCase(actualSha)) {
            throw new IOException("Archive integrity check failed: project document SHA-256 mismatch");
        }

        DawProject project = deserializer.deserialize(new String(docBytes, StandardCharsets.UTF_8));

        // Resolve relative asset paths against the extracted directory; for
        // anything missing, give the resolver a chance to relocate it.
        List<String> missing = resolveAssetPaths(project, targetDir, header, resolver);

        return new ArchivedProject(project, targetDir, header, missing);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Consolidate In Place
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Copies every asset referenced by {@code project} that lives outside
     * {@code projectDir} into {@code projectDir/assets/} and rewrites the
     * project's asset references to point at the consolidated copies.
     *
     * <p>Assets already located beneath {@code projectDir} are left in place.
     * Assets are deduplicated by SHA-256 so the same recording referenced
     * many times costs one copy on disk.</p>
     */
    public ProjectArchiveSummary consolidateInPlace(DawProject project,
                                                     Path projectDir,
                                                     ArchiveOptions options) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(options, "options");
        Files.createDirectories(projectDir);
        Path assetsDir = projectDir.resolve(CONSOLIDATED_ASSETS_DIR);
        Files.createDirectories(assetsDir);
        Path projectDirAbs = projectDir.toAbsolutePath().normalize();

        Map<String, Path> hashToConsolidated = new LinkedHashMap<>();
        long totalBytes = 0L;

        for (AssetRef ref : collectRefs(project, options)) {
            Path src = ref.absolutePath();
            if (src == null || !Files.isRegularFile(src)) {
                continue;
            }
            Path srcAbs = src.toAbsolutePath().normalize();
            if (srcAbs.startsWith(projectDirAbs)) {
                continue; // already inside project tree
            }
            String hash = sha256Hex(src);
            Path consolidated = hashToConsolidated.get(hash);
            if (consolidated == null) {
                String safeName = hash + "_" + sanitize(srcAbs.getFileName().toString());
                consolidated = assetsDir.resolve(safeName);
                if (!Files.exists(consolidated)) {
                    Files.copy(src, consolidated, StandardCopyOption.REPLACE_EXISTING);
                    totalBytes += Files.size(consolidated);
                } else {
                    totalBytes += Files.size(consolidated);
                }
                hashToConsolidated.put(hash, consolidated);
            }
            ref.update(consolidated.toAbsolutePath().toString());
        }

        long projectDocBytes = serializer.serialize(project)
                .getBytes(StandardCharsets.UTF_8).length;

        return new ProjectArchiveSummary(
                projectDir, hashToConsolidated.size(), totalBytes, projectDocBytes);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Walks every clip and SoundFont assignment, building a list of mutable refs. */
    private List<AssetRef> collectRefs(DawProject project, ArchiveOptions options) {
        List<AssetRef> refs = new ArrayList<>();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                String p = clip.getSourceFilePath();
                if (p != null && !p.isBlank()) {
                    refs.add(new AudioClipRef(clip));
                }
            }
            if (options.includeSoundFonts()) {
                SoundFontAssignment sf = track.getSoundFontAssignment();
                if (sf != null && sf.soundFontPath() != null) {
                    refs.add(new SoundFontRef(track, sf));
                }
            }
            // Future: ConvolutionReverbPlugin.irPath when plugin is added —
            // honored by options.includeImpulseResponses().
        }
        return refs;
    }

    /** Plan stage: hash assets, deduplicate, and assign archive-relative names. */
    private AssetPlan collectAssets(DawProject project, ArchiveOptions options) throws IOException {
        List<AssetRef> refs = collectRefs(project, options);
        Map<String, String> hashToArchiveName = new LinkedHashMap<>();
        Map<String, Path> archiveNameToSource = new LinkedHashMap<>();
        Map<AssetRef, String> refToArchivePath = new LinkedHashMap<>();

        for (AssetRef ref : refs) {
            Path abs = ref.absolutePath();
            if (abs == null || !Files.isRegularFile(abs)) {
                // Unresolvable on disk — keep its existing path verbatim;
                // openArchive will hand it to the missing-asset resolver.
                continue;
            }
            String hash = sha256Hex(abs);
            String archiveName = hashToArchiveName.get(hash);
            if (archiveName == null) {
                archiveName = hash + "_" + sanitize(abs.getFileName().toString());
                hashToArchiveName.put(hash, archiveName);
                archiveNameToSource.put(archiveName, abs);
            }
            refToArchivePath.put(ref, ArchiveHeader.ASSETS_DIR + "/" + archiveName);
        }
        return new AssetPlan(hashToArchiveName, archiveNameToSource, refToArchivePath);
    }

    private Map<AssetRef, String> applyArchivePaths(DawProject project, AssetPlan plan) {
        Map<AssetRef, String> originals = new LinkedHashMap<>();
        for (Map.Entry<AssetRef, String> e : plan.refToArchivePath().entrySet()) {
            AssetRef ref = e.getKey();
            originals.put(ref, ref.currentPath());
            ref.update(e.getValue());
        }
        return originals;
    }

    private void restorePaths(DawProject project, Map<AssetRef, String> originals) {
        for (Map.Entry<AssetRef, String> e : originals.entrySet()) {
            e.getKey().update(e.getValue());
        }
    }

    private List<String> resolveAssetPaths(DawProject project,
                                           Path extractedDir,
                                           ArchiveHeader header,
                                           MissingAssetResolver resolver) {
        List<String> missing = new ArrayList<>();
        List<Path> hints = new ArrayList<>();
        hints.add(extractedDir);
        hints.add(extractedDir.resolve(ArchiveHeader.ASSETS_DIR));
        if (header.originalRoot() != null && !header.originalRoot().isBlank()) {
            try {
                hints.add(Paths.get(header.originalRoot()));
            } catch (RuntimeException ignored) {
                // best-effort — invalid path strings are silently ignored
            }
        }

        Path extractedAbs = extractedDir.toAbsolutePath().normalize();
        for (AssetRef ref : collectRefs(project, ArchiveOptions.defaults())) {
            String stored = ref.currentPath();
            if (stored == null || stored.isBlank()) {
                continue;
            }
            // Try interpreting the stored path as relative to the extracted dir.
            // A corrupt or malicious project document might contain a string
            // that isn't a valid path on this OS, or one that escapes the
            // extracted root via "..". Both are treated as missing-with-resolver.
            Path candidate = null;
            try {
                Path c = extractedAbs.resolve(stored).normalize();
                if (c.startsWith(extractedAbs)) {
                    candidate = c;
                }
            } catch (RuntimeException ignored) {
                // InvalidPathException (or similar) — fall through to resolver.
            }
            if (candidate != null && Files.isRegularFile(candidate)) {
                ref.update(candidate.toAbsolutePath().toString());
                continue;
            }
            // Try absolute interpretation for paths archived from a non-relocatable project.
            try {
                Path direct = Paths.get(stored);
                if (direct.isAbsolute() && Files.isRegularFile(direct)) {
                    ref.update(direct.toAbsolutePath().toString());
                    continue;
                }
            } catch (RuntimeException ignored) {
                // InvalidPathException — fall through to resolver.
            }
            Optional<Path> resolved = resolver.resolve(stored, hints);
            if (resolved.isEmpty()) {
                // Archive entries are named "<sha>_<originalName>". Re-ask the
                // resolver using the un-hashed original basename so a "Locate…"
                // search can match files the user has under their original
                // names rather than the archive-internal hash names.
                String original = unhashedBasename(stored);
                if (original != null && !original.equals(stored)) {
                    resolved = resolver.resolve(original, hints);
                }
            }
            if (resolved.isPresent() && Files.isRegularFile(resolved.get())) {
                ref.update(resolved.get().toAbsolutePath().toString());
            } else {
                missing.add(stored);
            }
        }
        return missing;
    }

    // ── ZIP I/O ──────────────────────────────────────────────────────────

    private static void writeStringEntry(ZipOutputStream zip, String name, String content)
            throws IOException {
        writeBytesEntry(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytesEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void extractZip(Path zipFile, Path destDir) throws IOException {
        Path destAbs = destDir.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(zipFile);
             ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                // Resolve against the absolute, normalized destination so the
                // zip-slip check works regardless of whether the caller passed
                // a relative or absolute destDir.
                Path target = destAbs.resolve(e.getName()).normalize();
                if (!target.startsWith(destAbs)) {
                    throw new IOException("Archive entry escapes target directory: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    try (OutputStream out = Files.newOutputStream(target)) {
                        copy(zin, out);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[COPY_BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    // ── Header (de)serialization ─────────────────────────────────────────

    private static String headerToProperties(ArchiveHeader header) throws IOException {
        Properties p = new Properties();
        p.setProperty("projectName", header.projectName());
        p.setProperty("archiveDate", header.archiveDate().toString());
        p.setProperty("assetCount", Integer.toString(header.assetCount()));
        p.setProperty("originalRoot", header.originalRoot() == null ? "" : header.originalRoot());
        p.setProperty("dawVersion", header.dawVersion());
        p.setProperty("projectDocSha256", header.projectDocSha256());
        try (java.io.StringWriter sw = new java.io.StringWriter()) {
            p.store(sw, "DAW Project Archive header");
            return sw.toString();
        }
    }

    private static ArchiveHeader headerFromProperties(String text) throws IOException {
        Properties p = new Properties();
        try (java.io.StringReader sr = new java.io.StringReader(text)) {
            p.load(sr);
        }
        Instant date;
        try {
            date = Instant.parse(p.getProperty("archiveDate", Instant.EPOCH.toString()));
        } catch (RuntimeException e) {
            date = Instant.EPOCH;
        }
        int count;
        try {
            count = Integer.parseInt(p.getProperty("assetCount", "0"));
        } catch (NumberFormatException e) {
            count = 0;
        }
        return new ArchiveHeader(
                p.getProperty("projectName", "Untitled"),
                date,
                count,
                p.getProperty("originalRoot", ""),
                p.getProperty("dawVersion", DEFAULT_DAW_VERSION),
                p.getProperty("projectDocSha256", "")
        );
    }

    // ── Hashing & sanitization ───────────────────────────────────────────

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Streams the contents of {@code file} through a SHA-256 digest without
     * loading the whole file into memory — important for multi-gigabyte
     * recordings.
     */
    static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[COPY_BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String sanitize(String name) {
        // Restrict to portable filename characters; collapse anything else
        // to underscore so the archive is safe on every host filesystem.
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    /**
     * Given an archive-relative path of the form
     * {@code assets/<sha256>_<originalName>}, returns the original basename
     * portion ({@code <originalName>}). Returns {@code null} if the path does
     * not match the archive layout.
     */
    private static String unhashedBasename(String storedPath) {
        if (storedPath == null) return null;
        String prefix = ArchiveHeader.ASSETS_DIR + "/";
        if (!storedPath.startsWith(prefix)) return null;
        String name = storedPath.substring(prefix.length());
        int underscore = name.indexOf('_');
        if (underscore <= 0 || underscore == name.length() - 1) return null;
        // SHA-256 hex is exactly 64 chars; only treat as hashed if that holds.
        if (underscore != 64) return null;
        return name.substring(underscore + 1);
    }

    /**
     * Deletes a directory tree recursively. Provided as a convenience for
     * callers that want to clean up an extracted archive temp dir.
     */
    public static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── Internal ref/plan types ──────────────────────────────────────────

    /** Mutable handle on an asset path inside the project graph. */
    private interface AssetRef {
        String currentPath();
        void update(String newPath);
        /** Best-effort resolution of the current path to an absolute file. */
        default Path absolutePath() {
            String s = currentPath();
            if (s == null || s.isBlank()) {
                return null;
            }
            try {
                return Paths.get(s).toAbsolutePath();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final class AudioClipRef implements AssetRef {
        private final AudioClip clip;
        AudioClipRef(AudioClip clip) { this.clip = clip; }
        @Override public String currentPath() { return clip.getSourceFilePath(); }
        @Override public void update(String newPath) { clip.setSourceFilePath(newPath); }
    }

    private static final class SoundFontRef implements AssetRef {
        private final Track track;
        private SoundFontAssignment current;
        SoundFontRef(Track track, SoundFontAssignment current) {
            this.track = track;
            this.current = current;
        }
        @Override public String currentPath() {
            return current.soundFontPath() == null ? null : current.soundFontPath().toString();
        }
        @Override public void update(String newPath) {
            SoundFontAssignment updated = new SoundFontAssignment(
                    Paths.get(newPath), current.bank(), current.program(), current.presetName());
            track.setSoundFontAssignment(updated);
            this.current = updated;
        }
    }

    private record AssetPlan(
            Map<String, String> hashToArchiveName,
            Map<String, Path> archiveNameToSource,
            Map<AssetRef, String> refToArchivePath
    ) {
        int uniqueAssetCount() { return archiveNameToSource.size(); }
    }

    // Exposed for tests — listing assets without committing the archive.
    /**
     * Returns, for the given project, the unique content hashes of every
     * referenced asset that currently resolves on disk. Useful for UI that
     * wants to estimate output size without writing.
     */
    public Map<String, Long> previewAssetSizes(DawProject project, ArchiveOptions options)
            throws IOException {
        Map<String, Long> sizes = new LinkedHashMap<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (AssetRef ref : collectRefs(project, options)) {
            Path p = ref.absolutePath();
            if (p == null || !Files.isRegularFile(p)) {
                continue;
            }
            String hash = sha256Hex(p);
            if (seen.putIfAbsent(hash, Boolean.TRUE) == null) {
                sizes.put(hash, Files.size(p));
            }
        }
        // Sort by size descending so callers can show the biggest assets first.
        return sizes.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new,
                         (m, e) -> m.put(e.getKey(), e.getValue()),
                         LinkedHashMap::putAll);
    }
}
