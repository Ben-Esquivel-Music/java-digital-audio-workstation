package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.ProjectSerializer.AssetReferenceRemap;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A project captured as a document, not yet rendered to XML (story 331;
 * Persistence Integrity book §2.3, §4.2): {@link ProjectSerializer#capture}
 * walks the model on the thread that owns it and builds this document; the
 * rendering — {@link #toXml()} — runs wherever the document is handed, and
 * never reads the model again. An edit made after the capture can therefore
 * never reach the rendered XML, and a renderer on a worker thread can never
 * meet a model the owner is changing.
 *
 * <p>The capture records every asset-reference site it wrote — a clip's
 * {@code source-file}, each {@code source-segment} path, a SoundFont
 * assignment's {@code path} — with the reference as the live project held
 * it ({@link #references()}), while the document holds what
 * {@link ProjectSerializer#serialize(com.benesquivelmusic.daw.core.project.DawProject)}
 * writes. {@link #toXml(AssetReferenceRemap)} rewrites those sites through
 * a remap — the archived copies a project archive points at — by the same
 * rule {@link ProjectSerializer#serialize(com.benesquivelmusic.daw.core.project.DawProject,
 * AssetReferenceRemap)} applies.</p>
 *
 * <p><strong>Threading.</strong> A document is confined to one thread at a
 * time and is not thread-safe: it is built on the model's owner thread,
 * then handed to one worker, which renders it. The hand-off (an
 * {@link java.util.concurrent.Executor} submission or a queue) is the
 * happens-before edge that makes the owner's writes visible to the worker.
 * {@link #toXml(AssetReferenceRemap)} changes the document's attribute
 * values, so two threads must never render the same document at once.</p>
 */
public final class ProjectDocument {

    /** Where an asset reference sits in the document. */
    public enum ReferenceKind {

        /** A clip's {@code source-file} attribute. */
        CLIP_SOURCE_FILE,

        /** A recorded take's {@code source-segment} {@code path}, one per segment. */
        CLIP_SOURCE_SEGMENT,

        /** A track's {@code soundfont-assignment} {@code path}. */
        SOUND_FONT
    }

    /**
     * An asset reference the capture wrote, as the live project held it —
     * the key a {@link AssetReferenceRemap} names it by (a SoundFont path in
     * its {@link Path#toString()} form).
     *
     * @param kind      where the reference sits
     * @param reference the live reference
     */
    public record AssetReference(ReferenceKind kind, String reference) {

        /** @throws NullPointerException if either component is {@code null} */
        public AssetReference {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
        }
    }

    /** One recorded site: the attribute the capture wrote and the live reference it stands for. */
    record ReferenceSite(Attr attribute, AssetReference reference) {
    }

    private final Document document;
    private final Path projectRoot;
    private final FormerRoots formerRoots;
    private final List<ReferenceSite> sites;
    private final List<AssetReference> references;

    /**
     * @param document    the captured document, holding the unremapped references
     * @param projectRoot the directory references are relativised against, or {@code null} for none
     * @param formerRoots the folders the project was saved from as a copy this session, whose references the
     *                    copy holds are written project-relative too (story 333)
     * @param sites       every asset-reference site, in document order
     */
    ProjectDocument(Document document, Path projectRoot, FormerRoots formerRoots, List<ReferenceSite> sites) {
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.projectRoot = projectRoot;
        this.formerRoots = Objects.requireNonNull(formerRoots, "formerRoots must not be null");
        this.sites = List.copyOf(sites);
        List<AssetReference> recorded = new ArrayList<>(this.sites.size());
        for (ReferenceSite site : this.sites) {
            recorded.add(site.reference());
        }
        this.references = List.copyOf(recorded);
    }

    /**
     * The asset references the document holds, in document order, each as
     * the live project held it when it was captured.
     *
     * @return an unmodifiable list
     */
    public List<AssetReference> references() {
        return references;
    }

    /**
     * Renders the document as the project's XML — byte for byte what
     * {@link ProjectSerializer#serialize(com.benesquivelmusic.daw.core.project.DawProject)}
     * returns for the project as it was captured.
     *
     * @return the XML
     * @throws IOException if the document cannot be rendered
     */
    public String toXml() throws IOException {
        return toXml(AssetReferenceRemap.NONE);
    }

    /**
     * Renders the document with every asset reference that {@code remap}
     * names replaced by its substitute — byte for byte what
     * {@link ProjectSerializer#serialize(com.benesquivelmusic.daw.core.project.DawProject,
     * AssetReferenceRemap)} returns for the project as it was captured. It
     * rewrites the recorded sites' values in this document (see the class
     * note, "Threading"); a later call with another remap, or
     * {@link #toXml()}, rewrites them again from the live references.
     *
     * @param remap the reference substitutions; {@link AssetReferenceRemap#NONE} for none
     * @return the XML
     * @throws IOException if the document cannot be rendered
     */
    public String toXml(AssetReferenceRemap remap) throws IOException {
        return toXml(projectRoot, formerRoots, remap);
    }

    /**
     * Renders the document as the project file of another folder (story 333,
     * Save As): every clip reference written as
     * {@link #toXml(AssetReferenceRemap)} writes it, but against
     * {@code root} and {@code rootsOfCopy} instead of the folder the project
     * was captured from — so a reference into the folders the copy was taken
     * from is written project-relative only when the copy holds that folder's
     * file at the same relative path
     * ({@link ProjectPaths#relativize(Path, FormerRoots, String)}), and stays
     * absolute, naming the original's file, when it does not. Like
     * {@link #toXml(AssetReferenceRemap)}, it rewrites the recorded sites'
     * values in this document.
     *
     * @param root        the folder the rendered file is the project file of
     * @param rootsOfCopy the folders that folder's copy was taken from, each with what the copy holds of it
     * @param remap       the reference substitutions; {@link AssetReferenceRemap#NONE} for none
     * @return the XML
     * @throws IOException if the document cannot be rendered
     */
    String toXml(Path root, FormerRoots rootsOfCopy, AssetReferenceRemap remap) throws IOException {
        Objects.requireNonNull(rootsOfCopy, "rootsOfCopy must not be null");
        Objects.requireNonNull(remap, "remap must not be null");
        for (ReferenceSite site : sites) {
            String written = written(site.reference(), root, rootsOfCopy, remap);
            if (!written.equals(site.attribute().getValue())) {
                site.attribute().setValue(written);
            }
        }
        return render();
    }

    /** The value a site is written with under {@code remap}: the serializer's remap-then-relativize rule. */
    private static String written(AssetReference reference, Path root, FormerRoots roots, AssetReferenceRemap remap) {
        return switch (reference.kind()) {
            case CLIP_SOURCE_FILE, CLIP_SOURCE_SEGMENT -> ProjectSerializer.clipReference(
                    root, roots, remap, reference.reference());
            case SOUND_FONT -> ProjectSerializer.soundFontPath(remap, reference.reference());
        };
    }

    /**
     * Names the captured project {@code name} — the document's
     * {@code /daw-project/metadata/name} — in this document, and returns it
     * (story 333: Save As writes the copy under the name the user gave its
     * folder, and the live project is renamed only once the copy is open).
     * The model is not read or changed.
     *
     * @param name the project's new name
     * @return this document
     * @throws IllegalStateException if the document has no metadata name (never, for a captured project)
     */
    ProjectDocument withProjectName(String name) {
        metadataElement("name").setTextContent(Objects.requireNonNull(name, "name must not be null"));
        return this;
    }

    /**
     * Stamps the document's {@code /daw-project/metadata/last-modified} with
     * {@code lastModified}, and returns this document (story 333: the copy's
     * head carries the time Save As took it, as a save's head carries its
     * capture's time). The model is not read or changed.
     *
     * @param lastModified the time the copy was taken
     * @return this document
     * @throws IllegalStateException if the document has no metadata time (never, for a captured project)
     */
    ProjectDocument withLastModified(Instant lastModified) {
        metadataElement("last-modified").setTextContent(
                Objects.requireNonNull(lastModified, "lastModified must not be null").toString());
        return this;
    }

    /** The {@code /daw-project/metadata/<child>} element. */
    private Element metadataElement(String child) {
        Element root = document.getDocumentElement();
        Element metadata = root == null ? null : firstChildElement(root, "metadata");
        Element element = metadata == null ? null : firstChildElement(metadata, child);
        if (element == null) {
            throw new IllegalStateException("the captured document has no /daw-project/metadata/" + child);
        }
        return element;
    }

    private static Element firstChildElement(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private String render() throws IOException {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new IOException("Failed to serialize project", e);
        }
    }
}
