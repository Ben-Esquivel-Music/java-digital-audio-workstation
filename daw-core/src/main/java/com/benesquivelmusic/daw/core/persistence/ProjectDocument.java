package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.ProjectSerializer.AssetReferenceRemap;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;

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
    private final List<ReferenceSite> sites;
    private final List<AssetReference> references;

    /**
     * @param document    the captured document, holding the unremapped references
     * @param projectRoot the directory references are relativised against, or {@code null} for none
     * @param sites       every asset-reference site, in document order
     */
    ProjectDocument(Document document, Path projectRoot, List<ReferenceSite> sites) {
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.projectRoot = projectRoot;
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
        Objects.requireNonNull(remap, "remap must not be null");
        for (ReferenceSite site : sites) {
            String written = written(site.reference(), remap);
            if (!written.equals(site.attribute().getValue())) {
                site.attribute().setValue(written);
            }
        }
        return render();
    }

    /** The value a site is written with under {@code remap}: the serializer's remap-then-relativize rule. */
    private String written(AssetReference reference, AssetReferenceRemap remap) {
        return switch (reference.kind()) {
            case CLIP_SOURCE_FILE, CLIP_SOURCE_SEGMENT -> ProjectSerializer.clipReference(
                    projectRoot, remap, reference.reference());
            case SOUND_FONT -> ProjectSerializer.soundFontPath(remap, reference.reference());
        };
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
