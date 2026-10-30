package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The open project as Save As captured it (story 333, ruling R5;
 * {@link ProjectManager#captureForSaveAs}): taken on the thread that owns the
 * model, with no disk access, when the copy was asked for — the edit
 * revision, the project document already named after the copy, and the
 * folder it was opened from. The live project is not changed: it is renamed,
 * re-pointed and re-rooted only once the copy is open.
 *
 * <p>The capture is relativised against the folder the project was opened
 * from — and the files it holds of its own former roots
 * ({@link ProjectSerializer#capture(com.benesquivelmusic.daw.core.project.DawProject,
 * FormerRoots)}). The copy's {@code project.daw} is rendered against the copy's
 * folder instead: a reference is written project-relative there only when the
 * copy holds, at the same relative path, the file of the folder it lies under
 * ({@link FormerRoots}), and every other one stays absolute (Persistence
 * Integrity book §3.3).</p>
 *
 * <p>Opaque: the planner ({@link ProjectManager#planSaveAs}) and the copy
 * ({@link ProjectManager#publishCopy}) read the document and its references;
 * the application reads the name, the revision, the source folder and the
 * metadata. <strong>Threading:</strong> the document is confined to one
 * thread at a time ({@link ProjectDocument}): hand the capture to the worker
 * that plans and copies through an executor or a queue, never to two
 * workers at once.</p>
 */
public final class SaveAsCapture {

    private final String name;
    private final long revision;
    private final Optional<Path> sourceFolder;
    private final FormerRoots formerRoots;
    private final ProjectMetadata metadata;
    private final ProjectDocument document;

    SaveAsCapture(String name, long revision, Optional<Path> sourceFolder, FormerRoots formerRoots,
                  ProjectMetadata metadata, ProjectDocument document) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.revision = revision;
        this.sourceFolder = Objects.requireNonNull(sourceFolder, "sourceFolder must not be null");
        this.formerRoots = Objects.requireNonNull(formerRoots, "formerRoots must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.document = Objects.requireNonNull(document, "document must not be null");
    }

    /**
     * The copy's name: the name its folder was given, which its document
     * already carries.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The edit revision of the live project when it was captured: the
     * revision the open project is marked clean at once the copy is open
     * ({@code markCleanIfUnchanged}), as a save marks it.
     *
     * @return the revision
     */
    public long revision() {
        return revision;
    }

    /**
     * The folder the project was opened from, whose files the copy takes;
     * empty for a project that was never saved.
     *
     * @return the source folder
     */
    public Optional<Path> sourceFolder() {
        return sourceFolder;
    }

    /**
     * The copy's metadata: its name, the project's creation time, the time
     * of the capture as its last modification, and no folder yet.
     *
     * @return the metadata
     */
    public ProjectMetadata metadata() {
        return metadata;
    }

    /** The folders the open project was itself saved from as a copy this session, each with what it holds of it. */
    FormerRoots formerRoots() {
        return formerRoots;
    }

    /** The captured document, confined to one thread at a time. */
    ProjectDocument document() {
        return document;
    }

    /** The asset references the document holds, each as the live project held it. */
    List<ProjectDocument.AssetReference> references() {
        return document.references();
    }
}
