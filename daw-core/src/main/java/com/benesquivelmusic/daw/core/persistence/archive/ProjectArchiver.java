package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.concurrent.DawScope;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.ProjectAssetReferences;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectDocument;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectPaths;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
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
 *   <li>{@link #capture} and {@link #write} — a {@code .dawz} ZIP for sharing or
 *       cold storage, in two steps (story 331): the capture, on the thread that
 *       owns the model, takes the project document and the references it holds;
 *       the write, on a worker, packages them without reading the project
 *       again. The in-memory project is never mutated: the document's
 *       archive-relative paths are substituted as it is rendered.</li>
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
 * <p>An archive packages the asset references its captured document holds:
 * those of every clip it writes and every
 * {@link Track#getSoundFontAssignment()}. A single-file clip
 * contributes its {@link AudioClip#getSourceFilePath()}; a clip that carries a
 * segment list (a recorded take) contributes every element of
 * {@link AudioClip#getSourceSegmentPaths()}, in order, so each segment of a
 * rotated take is archived and the restored clip references all of them.
 * Assets are deduplicated by SHA-256, so the same recording referenced from
 * many clips is stored only once. A clip reference that is not an absolute
 * path on this platform names no file ({@link ProjectPaths#isAbsoluteReference},
 * story 323): {@link #write} gives it the missing-asset treatment,
 * {@link #previewAssetSizes} leaves it out and {@link #consolidateInPlace}
 * leaves it as it is — none of them resolves it against the JVM's working
 * directory. A SoundFont path is still resolved against the working
 * directory when it is relative.</p>
 *
 * <p>This class is thread-safe in the sense that distinct {@code ProjectArchiver}
 * instances may be used concurrently; a single instance should not be shared
 * across concurrent archive operations.</p>
 */
public final class ProjectArchiver {

    /** Archive file extension (with leading dot). */
    public static final String ARCHIVE_EXTENSION = ".dawz";

    /** Asset folder used by {@link #consolidateInPlace}. */
    public static final String CONSOLIDATED_ASSETS_DIR = ProjectManager.ASSETS_DIR_NAME;

    private static final String DEFAULT_DAW_VERSION = "0.1.0-SNAPSHOT";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final ProjectSerializer serializer;
    private final ProjectDeserializer deserializer;
    private final String dawVersion;
    private final AssetWriteHook assetWriteHook;
    private final AssetPlanHook assetPlanHook;
    private final AtomicFileWriter fileWriter;

    public ProjectArchiver() {
        this(new ProjectSerializer(), new ProjectDeserializer(), resolveDawVersion());
    }

    public ProjectArchiver(ProjectSerializer serializer,
                           ProjectDeserializer deserializer,
                           String dawVersion) {
        this(serializer, deserializer, dawVersion, AssetWriteHook.NONE);
    }

    /**
     * Test seam: {@code assetWriteHook} runs on the archiving thread before
     * each asset entry of {@link #write} is written, so a test can hold an
     * archive mid-write (and edit the live project meanwhile) or fail it
     * part-way through.
     */
    ProjectArchiver(ProjectSerializer serializer,
                    ProjectDeserializer deserializer,
                    String dawVersion,
                    AssetWriteHook assetWriteHook) {
        this(serializer, deserializer, dawVersion, assetWriteHook, AssetPlanHook.NONE);
    }

    /**
     * Test seam: as above, and {@code assetPlanHook} runs on the archiving
     * thread while {@link #write} plans, before each captured reference's
     * file is hashed, so a test can edit the live project then and show the
     * archive never sees the edit.
     */
    ProjectArchiver(ProjectSerializer serializer,
                    ProjectDeserializer deserializer,
                    String dawVersion,
                    AssetWriteHook assetWriteHook,
                    AssetPlanHook assetPlanHook) {
        this(serializer, deserializer, dawVersion, assetWriteHook, assetPlanHook, new AtomicFileWriter());
    }

    /**
     * Test seam: as above, and {@code fileWriter} writes the {@code .dawz}
     * and every consolidated asset (story 331), so a test can fail or
     * observe those writes.
     */
    ProjectArchiver(ProjectSerializer serializer,
                    ProjectDeserializer deserializer,
                    String dawVersion,
                    AssetWriteHook assetWriteHook,
                    AssetPlanHook assetPlanHook,
                    AtomicFileWriter fileWriter) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.dawVersion = (dawVersion == null || dawVersion.isBlank())
                ? DEFAULT_DAW_VERSION : dawVersion;
        this.assetWriteHook = Objects.requireNonNull(assetWriteHook, "assetWriteHook");
        this.assetPlanHook = Objects.requireNonNull(assetPlanHook, "assetPlanHook");
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter");
    }

    /** Runs before each asset entry an archive writes (test seam). */
    @FunctionalInterface
    interface AssetWriteHook {

        /** Does nothing: the production behaviour. */
        AssetWriteHook NONE = entryName -> { };

        /**
         * @param entryName the ZIP entry about to be written ({@code assets/<sha>_<name>})
         * @throws IOException to fail the archive at this entry
         */
        void beforeAssetWrite(String entryName) throws IOException;
    }

    /** Runs while an archive's plan is built, between a reference's read and its hash (test seam). */
    @FunctionalInterface
    interface AssetPlanHook {

        /** Does nothing: the production behaviour. */
        AssetPlanHook NONE = reference -> { };

        /** @param reference the reference just read, whose file is about to be hashed */
        void beforeAssetHash(String reference);
    }

    private static String resolveDawVersion() {
        String v = ProjectArchiver.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? DEFAULT_DAW_VERSION : v;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Save As Archive
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Captures {@code project} for an archive, on the thread that owns the
     * model (story 331, Persistence Integrity book §2.3, §4.2): the model
     * walk builds the project document here, and the asset references the
     * archive packages are the ones that document records — in document
     * order, a SoundFont only when {@code options} include SoundFonts, an
     * empty clip reference never. No disk is touched; {@link #write} does the
     * rest on a worker without reading the project again, so an edit made
     * after this call never reaches the archive.
     *
     * @param project               the project to archive
     * @param options               what the archive includes
     * @param missingAssetDecisions what to do with each asset that cannot be
     *                              found, matched by the reference the project
     *                              holds; a missing asset without one is skipped
     * @return the capture {@link #write} packages
     * @throws IOException if no project document can be created
     */
    public ArchiveCapture capture(DawProject project, ArchiveOptions options,
                                  List<ArchiveAssetDecision> missingAssetDecisions) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(missingAssetDecisions, "missingAssetDecisions");
        ProjectDocument document = serializer.capture(project);
        List<ProjectDocument.AssetReference> planned = new ArrayList<>();
        for (ProjectDocument.AssetReference reference : document.references()) {
            boolean included = reference.kind() == ProjectDocument.ReferenceKind.SOUND_FONT
                    ? options.includeSoundFonts()
                    : !reference.reference().isEmpty();
            if (included) {
                planned.add(reference);
            }
        }
        String originalRoot = project.getMetadata() != null && project.getMetadata().projectPath() != null
                ? project.getMetadata().projectPath().toAbsolutePath().toString()
                : "";
        return new ArchiveCapture(document, planned, project.getName(), originalRoot, missingAssetDecisions);
    }

    /**
     * Writes {@code capture} and every asset it references to the
     * {@code .dawz} archive {@code archiveFile}, on the calling thread — a
     * worker: it plans the assets from the captured references (hashing
     * them, deduplicating by content), renders the captured document with
     * the archive-relative substitutes and writes the ZIP through the
     * {@link AtomicFileWriter} (a temp sibling, forced, then moved into
     * place). It never reads the project the capture was taken from: the
     * live project and its clips are never read or changed, and the archive
     * holds the project as it was captured.
     *
     * <p>A failed plan, pack or move leaves no archive and no temp at
     * {@code archiveFile}, and an archive already there as it was; the
     * exception that ended the write reaches the caller as thrown.</p>
     *
     * @param capture     what {@link #capture} took
     * @param archiveFile the archive to write
     * @return what was archived
     * @throws IOException              if the archive cannot be written
     * @throws IllegalArgumentException if {@code archiveFile} does not end in
     *                                  {@value #ARCHIVE_EXTENSION}
     */
    public ProjectArchiveSummary write(ArchiveCapture capture, Path archiveFile) throws IOException {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(archiveFile, "archiveFile");
        if (!archiveFile.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(ARCHIVE_EXTENSION)) {
            throw new IllegalArgumentException(
                    "Archive file must end with " + ARCHIVE_EXTENSION + ": " + archiveFile);
        }

        // 1. Plan every unique asset, keyed by content hash, and each
        //    captured reference's archive-relative substitute.
        AssetPlan plan = planAssets(capture.references(), decisionsByPath(capture.missingAssetDecisions()));

        // 2. Render the captured document with the substitutes written in
        //    place of the planned references. No live reference is read or
        //    rewritten: the session stays editable while this runs.
        String xml = capture.document().toXml(plan.remap());
        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        String sha;
        long totalAssetBytes;
        try (DawScope scope = DawScope.openShutdownOnFailure("archive-pack")) {
            DawScope.Subtask<String> projectSha = scope.fork("checksum-project", () -> sha256Hex(xmlBytes));
            DawScope.Subtask<Long> assetBytes = scope.fork("measure-assets", plan::totalAssetBytes);
            scope.joinAll();
            sha = projectSha.resultNow();
            totalAssetBytes = assetBytes.resultNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while preparing archive", e);
        } catch (ExecutionException e) {
            throw asIOException("Failed to prepare archive", e.getCause());
        }

        // 3. The header: the "original root" is the captured project directory.
        ArchiveHeader header = new ArchiveHeader(
                capture.projectName(),
                Instant.now(),
                plan.uniqueAssetCount(),
                capture.originalRoot(),
                dawVersion,
                sha
        );

        // 4. Write the ZIP through the atomic-replace writer (a temp sibling,
        //    forced, then moved into place) so a failed pack or a failed
        //    move never leaves a partial .dawz, nor its temp, at the target.
        //    A read-only archive is refused before the pack (ruling H1b); once
        //    that passes, the archive-sized temps earlier archives to this
        //    file left when they were cut off are deleted (ruling M2).
        Path parent = archiveFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        fileWriter.write(archiveFile, out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                writeStringEntry(zip, ArchiveHeader.FILE_NAME, headerToProperties(header));
                writeBytesEntry(zip, ArchiveHeader.PROJECT_DOC_NAME, xmlBytes);

                for (Map.Entry<String, ArchivePayload> e : plan.archiveNameToSource().entrySet()) {
                    String entryName = ArchiveHeader.ASSETS_DIR + "/" + e.getKey();
                    assetWriteHook.beforeAssetWrite(entryName);
                    zip.putNextEntry(new ZipEntry(entryName));
                    e.getValue().writeTo(zip);
                    zip.closeEntry();
                }
            }
        }, AtomicFileWriter.ReplacePermission.TARGET, AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS);

        return new ProjectArchiveSummary(
                archiveFile, plan.uniqueAssetCount(), totalAssetBytes, xmlBytes.length);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Open Archive
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Extracts the given archive into {@code targetDir} (created if missing)
     * and loads the embedded project, rewriting asset paths to absolute
     * locations within {@code targetDir}.
     *
     * <p>A {@code targetDir} that does not exist is extracted into its staging
     * sibling ({@code .<name>.restoring.tmp}, hidden on POSIX) and, once the archive
     * checked out, published whole by the writer's one directory publish
     * ({@link AtomicFileWriter#publishDirectory}), so it never holds half a
     * project; a failure, before the publish or after it, deletes the folder
     * this call extracted.</p>
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

        boolean useStaging = !Files.exists(targetDir);
        Path extractDir = useStaging ? restoreStagingDir(targetDir) : targetDir;
        try {
            Files.createDirectories(extractDir);
            extractZip(archiveFile, extractDir);

            Path headerFile = extractDir.resolve(ArchiveHeader.FILE_NAME);
            Path projectDoc = extractDir.resolve(ArchiveHeader.PROJECT_DOC_NAME);
            if (!Files.isRegularFile(headerFile)) {
                throw new IOException("Archive missing " + ArchiveHeader.FILE_NAME + ": " + archiveFile);
            }
            if (!Files.isRegularFile(projectDoc)) {
                throw new IOException("Archive missing " + ArchiveHeader.PROJECT_DOC_NAME
                        + ": " + archiveFile);
            }

            ArchiveHeader header;
            byte[] docBytes;
            String actualSha;
            try (DawScope scope = DawScope.openShutdownOnFailure("archive-restore")) {
                DawScope.Subtask<ArchiveHeader> headerTask = scope.fork("read-header",
                        () -> headerFromProperties(Files.readString(headerFile)));
                DawScope.Subtask<byte[]> docTask = scope.fork("read-project",
                        () -> Files.readAllBytes(projectDoc));
                scope.joinAll();
                header = headerTask.resultNow();
                docBytes = docTask.resultNow();
                actualSha = sha256Hex(docBytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while restoring archive", e);
            } catch (ExecutionException e) {
                throw asIOException("Failed to restore archive", e.getCause());
            }

            if (header.projectDocSha256() != null && !header.projectDocSha256().isBlank()
                    && !header.projectDocSha256().equalsIgnoreCase(actualSha)) {
                throw new IOException("Archive integrity check failed: project document SHA-256 mismatch");
            }

            if (useStaging) {
                moveRestoreIntoPlace(extractDir, targetDir);
                extractDir = targetDir;
            }

            DawProject project = deserializer.deserialize(new String(docBytes, StandardCharsets.UTF_8));
            // Read right after the load: the deserializer is reused, and the
            // next load clears its list.
            List<UnreadProjectItem> unreadItems = deserializer.getUnreadItems();

            // Resolve relative asset paths against the extracted directory; for
            // anything missing, give the resolver a chance to relocate it.
            List<String> missing = resolveAssetPaths(project, extractDir, header, resolver);

            return new ArchivedProject(project, extractDir, header, missing, unreadItems);
        } catch (IOException | RuntimeException e) {
            if (useStaging) {
                deleteRecursively(extractDir);
            }
            throw e;
        }
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
     * many times costs one copy on disk. A clip reference that is not an
     * absolute path names no file and is left as it is.</p>
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

        for (ProjectAssetReferences.Ref ref : collectRefs(project, options)) {
            Path src = fileOf(ref);
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
                    // No production caller yet (ruling M2): whoever wires consolidation passes
                    // AtomicFileWriter.ReplaceOption.DELETE_EARLIER_TEMPS here, as the archive's publish does.
                    fileWriter.copy(src, consolidated, AtomicFileWriter.Overwrite.REPLACE);
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

    /**
     * The archive's live references ({@link ProjectAssetReferences}): those
     * of the clips the saved project carries
     * ({@link com.benesquivelmusic.daw.core.comping.TakeComping#referenceHoldersAsSaved})
     * — a track's clips, or, while a take lane is auditioned, its non-comp
     * clips and the take-lane clips the comp selection slices, because the
     * serializer then writes the selection compile, whose clips copy their
     * take clip's references: the same references the captured document
     * holds ({@link #capture}), so a selection drawn from a lane other than
     * the soloed one is counted (PR #984 review), and the soloed lane, not
     * saved, is not — then each track's SoundFont path when {@code options}
     * include SoundFonts. {@link #previewAssetSizes} only reads the refs;
     * {@link #consolidateInPlace} and the freshly read project of
     * {@link #openArchive} update them.
     *
     * <p>A clip that carries a segment list (a recorded take, story 323 — even
     * a short take carries a one-element list) contributes one reference per
     * segment and no source-file reference, each captured from one read of
     * the list (see {@link ProjectAssetReferences.Ref}). Only a {@code null}
     * or empty clip reference is skipped, the same test
     * {@code AudioClip.setSourceSegmentPaths} and the project reader apply. A
     * whitespace-only one gets a ref like any other; it names no file, and no
     * {@link ArchiveAssetDecision} can name it (its constructor refuses a
     * blank path), so it gets no payload and is written into the archive as
     * it is.</p>
     */
    private static List<ProjectAssetReferences.Ref> collectRefs(DawProject project, ArchiveOptions options) {
        // Future: ConvolutionReverbPlugin.irPath when plugin is added —
        // honored by options.includeImpulseResponses().
        return ProjectAssetReferences.collect(project,
                track -> track.getTakeComping().referenceHoldersAsSaved(track), options.includeSoundFonts());
    }

    /**
     * Plan stage: hash assets, deduplicate, and assign archive-relative names,
     * from the references {@link #capture} recorded — never from the live
     * project. Each distinct reference is planned once, in the order the
     * document first holds it (a segmented clip's {@code source-file} is its
     * first segment, so it plans nothing new), and its substitute is keyed by
     * that reference: the file hashed, the missing-asset decision, the stub
     * name and the planned key all derive from the one captured string. An
     * edit made after the capture — a clip re-pointed, a segment list
     * replaced — therefore changes nothing here: the archive packages and
     * points at what the capture held.
     */
    private AssetPlan planAssets(List<ProjectDocument.AssetReference> references,
                                 Map<String, ArchiveAssetDecision> missingDecisions)
            throws IOException {
        Set<PlannedReference> distinct = new LinkedHashSet<>();
        for (ProjectDocument.AssetReference reference : references) {
            distinct.add(new PlannedReference(
                    reference.kind() == ProjectDocument.ReferenceKind.SOUND_FONT, reference.reference()));
        }
        Map<String, String> hashToArchiveName = new LinkedHashMap<>();
        Map<String, ArchivePayload> archiveNameToSource = new LinkedHashMap<>();
        Map<PlannedReference, String> refToArchivePath = new LinkedHashMap<>();

        for (PlannedReference planned : distinct) {
            String reference = planned.reference();
            Path abs = planned.soundFont() ? workingDirectoryFile(reference) : clipReferenceFile(reference);
            if (abs != null && Files.isRegularFile(abs)) {
                includeFilePayload(planned, abs, hashToArchiveName,
                        archiveNameToSource, refToArchivePath);
                continue;
            }

            ArchiveAssetDecision decision = missingDecisions.get(reference);
            if (decision == null || decision.action() == ArchiveAssetDecision.Action.SKIP) {
                // Unresolvable on disk — keep its existing path verbatim;
                // openArchive will hand it to the missing-asset resolver.
                continue;
            }
            switch (decision.action()) {
                case LOCATE -> {
                    Path located = decision.locatedPath();
                    if (!Files.isRegularFile(located)) {
                        throw new IOException("Located asset does not exist: " + located);
                    }
                    includeFilePayload(planned, located, hashToArchiveName,
                            archiveNameToSource, refToArchivePath);
                }
                case USE_STUB -> includeStubPayload(planned,
                        archiveNameToSource, refToArchivePath);
                case SKIP -> {
                    // handled above
                }
            }
        }
        return new AssetPlan(hashToArchiveName, archiveNameToSource, refToArchivePath);
    }

    private void includeFilePayload(PlannedReference planned,
                                    Path source,
                                    Map<String, String> hashToArchiveName,
                                    Map<String, ArchivePayload> archiveNameToSource,
                                    Map<PlannedReference, String> refToArchivePath)
            throws IOException {
        assetPlanHook.beforeAssetHash(planned.reference());
        String hash = sha256Hex(source);
        String archiveName = hashToArchiveName.get(hash);
        if (archiveName == null) {
            archiveName = hash + "_" + sanitize(source.getFileName().toString());
            hashToArchiveName.put(hash, archiveName);
            archiveNameToSource.put(archiveName, new FilePayload(source));
        }
        refToArchivePath.put(planned, ArchiveHeader.ASSETS_DIR + "/" + archiveName);
    }

    private static void includeStubPayload(PlannedReference planned,
                                           Map<String, ArchivePayload> archiveNameToSource,
                                           Map<PlannedReference, String> refToArchivePath) {
        String original = planned.reference();
        byte[] bytes = stubBytes(original);
        String hash = sha256Hex(bytes);
        String archiveName = hash + "_" + sanitize(basename(original));
        archiveNameToSource.putIfAbsent(archiveName, new BytesPayload(bytes));
        refToArchivePath.put(planned, ArchiveHeader.ASSETS_DIR + "/" + archiveName);
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
        for (ProjectAssetReferences.Ref ref : collectRefs(project, ArchiveOptions.defaults())) {
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

    private static String basename(String path) {
        if (path == null || path.isBlank()) {
            return "missing-asset.stub";
        }
        try {
            Path p = Paths.get(path);
            Path name = p.getFileName();
            return name == null ? "missing-asset.stub" : name.toString();
        } catch (RuntimeException e) {
            return "missing-asset.stub";
        }
    }

    private static byte[] stubBytes(String originalPath) {
        String original = originalPath == null ? "" : originalPath;
        return ("DAW missing asset stub\noriginal=" + original + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, ArchiveAssetDecision> decisionsByPath(
            List<ArchiveAssetDecision> decisions) {
        Map<String, ArchiveAssetDecision> byPath = new LinkedHashMap<>();
        for (ArchiveAssetDecision decision : decisions) {
            byPath.put(decision.originalPath(), decision);
        }
        return byPath;
    }

    /**
     * The staging sibling a restore into a new folder extracts into,
     * {@code .<name>.restoring.tmp} ({@link AtomicFileWriter#stagingSiblingOf}),
     * with its parent created and a staging directory an earlier restore left
     * deleted first.
     */
    private static Path restoreStagingDir(Path targetDir) throws IOException {
        Path staging = AtomicFileWriter.stagingSiblingOf(targetDir, "restoring");
        Path parent = staging.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(staging)) {
            deleteRecursively(staging);
        }
        return staging;
    }

    /** Publishes the fully extracted staging directory as {@code targetDir}: the writer's one directory publish. */
    private void moveRestoreIntoPlace(Path stagingDir, Path targetDir) throws IOException {
        fileWriter.publishDirectory(stagingDir, targetDir);
    }

    private static IOException asIOException(String message, Throwable cause) {
        return cause instanceof IOException io
                ? io
                : new IOException(message + ": " + cause.getMessage(), cause);
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

    /**
     * Best-effort resolution of {@code ref}'s current reference to an
     * absolute file: a clip reference only when it is absolute
     * ({@link #clipReferenceFile}), a SoundFont path against the working
     * directory when it is relative ({@link #workingDirectoryFile}).
     */
    private static Path fileOf(ProjectAssetReferences.Ref ref) {
        return ref.kind() == ProjectDocument.ReferenceKind.SOUND_FONT
                ? workingDirectoryFile(ref.currentPath())
                : clipReferenceFile(ref.currentPath());
    }

    /**
     * The file a SoundFont path names: the path resolved against the working
     * directory when it is relative, or {@code null} when it is {@code null},
     * blank or no path on this platform.
     */
    private static Path workingDirectoryFile(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Paths.get(s).toAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The file a clip reference names (story 323): the reference as a path
     * when it is absolute on this platform, and {@code null} for every other
     * one — relative, drive- or root-relative, unparseable, {@code null} or
     * empty — which names no file ({@link ProjectPaths#isAbsoluteReference})
     * and is never resolved against the JVM's working directory. A SoundFont
     * path is resolved against the working directory instead
     * ({@link #workingDirectoryFile}).
     */
    private static Path clipReferenceFile(String reference) {
        return ProjectPaths.isAbsoluteReference(reference) ? Path.of(reference) : null;
    }

    private interface ArchivePayload {
        long size() throws IOException;
        void writeTo(OutputStream out) throws IOException;
    }

    private record FilePayload(Path path) implements ArchivePayload {
        private FilePayload {
            Objects.requireNonNull(path, "path must not be null");
        }

        @Override
        public long size() throws IOException {
            return Files.size(path);
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            try (InputStream in = Files.newInputStream(path)) {
                copy(in, out);
            }
        }
    }

    private record BytesPayload(byte[] bytes) implements ArchivePayload {
        private BytesPayload {
            Objects.requireNonNull(bytes, "bytes must not be null");
            bytes = bytes.clone();
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                copy(in, out);
            }
        }
    }

    private record AssetPlan(
            Map<String, String> hashToArchiveName,
            Map<String, ArchivePayload> archiveNameToSource,
            Map<PlannedReference, String> refToArchivePath
    ) {
        int uniqueAssetCount() { return archiveNameToSource.size(); }

        /**
         * The planned substitutes, keyed by the reference each ref holds:
         * audio-clip references (single files and segments) and SoundFont
         * paths in separate maps, so a SoundFont the options leave out is
         * never substituted because a clip happens to name the same file.
         */
        ProjectSerializer.AssetReferenceRemap remap() {
            Map<String, String> clipReferences = new HashMap<>();
            Map<String, String> soundFontPaths = new HashMap<>();
            for (Map.Entry<PlannedReference, String> e : refToArchivePath.entrySet()) {
                PlannedReference planned = e.getKey();
                (planned.soundFont() ? soundFontPaths : clipReferences).put(planned.reference(), e.getValue());
            }
            return new ProjectSerializer.AssetReferenceRemap(clipReferences, soundFontPaths);
        }

        long totalAssetBytes() throws IOException {
            long total = 0L;
            for (ArchivePayload payload : archiveNameToSource.values()) {
                total += payload.size();
            }
            return total;
        }
    }

    /**
     * A planned reference: one asset reference as {@link #capture} recorded
     * it. Everything {@link #planAssets} plans for it derives from this
     * string, and the live project is never read — not while the plan is
     * built, not while the archive is written — so an edit racing the archive
     * (a clip re-pointed, a segment list replaced) can neither map a new
     * reference to an old file's asset nor fail the write.
     *
     * @param soundFont whether it is a SoundFont path rather than an audio-clip reference
     * @param reference the reference as the live project held it when it was captured
     */
    private record PlannedReference(boolean soundFont, String reference) {
    }

    // Exposed for tests — listing assets without committing the archive.
    /**
     * Returns, for the given project, the unique content hashes of every
     * referenced asset that currently resolves on disk — a clip reference
     * that is not an absolute path never does. Useful for UI that wants to
     * estimate output size without writing.
     */
    public Map<String, Long> previewAssetSizes(DawProject project, ArchiveOptions options)
            throws IOException {
        Map<String, Long> sizes = new LinkedHashMap<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (ProjectAssetReferences.Ref ref : collectRefs(project, options)) {
            Path p = fileOf(ref);
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
