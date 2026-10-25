package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.persistence.ProjectDocument;

import java.util.List;
import java.util.Objects;

/**
 * A project as an archive will package it, taken on the thread that owns the
 * model ({@link ProjectArchiver#capture}) and written by a worker
 * ({@link ProjectArchiver#write}) — story 331, Persistence Integrity book
 * §2.3 and §4.2: the worker never reads the live project, so an edit made
 * after the capture never reaches the archive and no live clip is touched.
 *
 * <p>It holds the captured {@link ProjectDocument}; the asset references the
 * archive plans from — the document's, in document order, with SoundFonts
 * only when the options include them and without empty clip references;
 * the project's name and absolute directory for the archive header; and the
 * missing-asset decisions the user chose. The options are applied when the
 * capture is taken. Everything but the document is immutable.</p>
 *
 * <p><strong>Threading.</strong> The document is confined to one thread at
 * a time, like every {@link ProjectDocument}: the capture is taken on the
 * owner and handed to one worker, and that hand-off (a thread start or an
 * executor submission) is the happens-before edge. Writing a capture renders
 * its document with the archive's substitutes, so two threads must never
 * write the same capture at once.</p>
 */
public final class ArchiveCapture {

    private final ProjectDocument document;
    private final List<ProjectDocument.AssetReference> references;
    private final String projectName;
    private final String originalRoot;
    private final List<ArchiveAssetDecision> missingAssetDecisions;

    /**
     * @param document              the captured project document
     * @param references            the references the archive plans from, in document order
     * @param projectName           the project's name, for the archive header
     * @param originalRoot          the project's absolute directory, or {@code ""} when it has none
     * @param missingAssetDecisions what to do with each asset that cannot be found
     */
    ArchiveCapture(ProjectDocument document, List<ProjectDocument.AssetReference> references, String projectName,
                   String originalRoot, List<ArchiveAssetDecision> missingAssetDecisions) {
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.references = List.copyOf(references);
        this.projectName = Objects.requireNonNull(projectName, "projectName must not be null");
        this.originalRoot = Objects.requireNonNull(originalRoot, "originalRoot must not be null");
        this.missingAssetDecisions = List.copyOf(missingAssetDecisions);
    }

    ProjectDocument document() {
        return document;
    }

    List<ProjectDocument.AssetReference> references() {
        return references;
    }

    String projectName() {
        return projectName;
    }

    String originalRoot() {
        return originalRoot;
    }

    List<ArchiveAssetDecision> missingAssetDecisions() {
        return missingAssetDecisions;
    }
}
