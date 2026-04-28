package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.sdk.mastering.AlbumTrackEntry;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumMetadata;
import com.benesquivelmusic.daw.sdk.mastering.album.AlbumTrackMetadata;
import com.benesquivelmusic.daw.sdk.mastering.album.CdText;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * XML serializer for {@link AlbumSequence} album-level and per-track metadata.
 *
 * <p>The serializer round-trips:</p>
 * <ul>
 *     <li>{@link AlbumMetadata} — title, artist, year, genre, UPC/EAN, release date</li>
 *     <li>per-track {@link AlbumTrackMetadata} — title, artist, composer, ISRC,
 *         {@link CdText} packs, and the free-form {@code extra} key/value tags</li>
 * </ul>
 *
 * <p>The output schema is intended to be embedded inside the existing
 * {@code project.daw} XML produced by
 * {@code com.benesquivelmusic.daw.core.persistence.ProjectSerializer} as a
 * single {@code <albumMetadata>} element. Callers wishing to persist
 * metadata standalone can call {@link #toXml(AlbumSequence)} /
 * {@link #fromXml(AlbumSequence, String)} directly.</p>
 */
public final class AlbumMetadataSerializer {

    /** Root element produced by {@link #toXml(AlbumSequence)}. */
    public static final String ROOT_ELEMENT = "albumMetadata";

    private AlbumMetadataSerializer() {
        // utility class
    }

    /**
     * Serializes the album-level and per-track metadata of {@code sequence}
     * to a standalone XML document string.
     *
     * @param sequence the album sequence
     * @return a UTF-8 XML string with root element {@value #ROOT_ELEMENT}
     */
    public static String toXml(AlbumSequence sequence) {
        Objects.requireNonNull(sequence, "sequence must not be null");
        try {
            Document doc = newDocumentBuilder().newDocument();
            Element root = doc.createElement(ROOT_ELEMENT);
            doc.appendChild(root);
            writeInto(sequence, doc, root);
            return writeDocument(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("Failed to serialize album metadata", e);
        }
    }

    /**
     * Restores the album-level and per-track metadata of {@code sequence}
     * from an XML document previously produced by {@link #toXml(AlbumSequence)}.
     *
     * <p>The number of tracks already present in {@code sequence} is the
     * authoritative limit — only metadata for indices {@code 0..size-1} is
     * applied; any extra entries in the XML are ignored.</p>
     *
     * @param sequence the target album sequence
     * @param xml      the XML produced by {@link #toXml(AlbumSequence)}
     */
    public static void fromXml(AlbumSequence sequence, String xml) {
        Objects.requireNonNull(sequence, "sequence must not be null");
        Objects.requireNonNull(xml, "xml must not be null");
        try {
            Document doc = newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            if (root == null || !ROOT_ELEMENT.equals(root.getNodeName())) {
                throw new IllegalArgumentException(
                        "Expected root element <" + ROOT_ELEMENT + "> in album metadata XML");
            }
            readFrom(sequence, root);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("Failed to parse album metadata XML", e);
        }
    }

    /**
     * Writes the metadata of {@code sequence} into a child element appended
     * to {@code parent} — used when embedding album metadata inside the main
     * project document.
     *
     * @param sequence the source sequence
     * @param doc      the owning document
     * @param parent   the element under which the {@value #ROOT_ELEMENT} block is appended
     */
    public static void writeInto(AlbumSequence sequence, Document doc, Element parent) {
        Objects.requireNonNull(sequence, "sequence must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(parent, "parent must not be null");

        // Album-level metadata
        AlbumMetadata album = sequence.getAlbumMetadata();
        Element albumEl = doc.createElement("album");
        albumEl.setAttribute("title", album.title());
        albumEl.setAttribute("artist", album.artist());
        if (album.year() != 0) {
            albumEl.setAttribute("year", Integer.toString(album.year()));
        }
        if (album.genre() != null && !album.genre().isEmpty()) {
            albumEl.setAttribute("genre", album.genre());
        }
        if (album.upcEan() != null && !album.upcEan().isEmpty()) {
            albumEl.setAttribute("upcEan", album.upcEan());
        }
        album.releaseDate().ifPresent(d ->
                albumEl.setAttribute("releaseDate", d.toString()));
        parent.appendChild(albumEl);

        // Per-track metadata
        Element tracksEl = doc.createElement("tracks");
        for (int i = 0; i < sequence.size(); i++) {
            Optional<AlbumTrackMetadata> meta = sequence.getTrackMetadata(i);
            if (meta.isEmpty()) {
                continue;
            }
            AlbumTrackMetadata m = meta.get();
            Element trackEl = doc.createElement("track");
            trackEl.setAttribute("index", Integer.toString(i));
            trackEl.setAttribute("title", m.title());
            if (m.artist() != null) trackEl.setAttribute("artist", m.artist());
            if (m.composer() != null) trackEl.setAttribute("composer", m.composer());
            if (m.isrc() != null) trackEl.setAttribute("isrc", m.isrc());

            m.cdText().ifPresent(cdt -> {
                Element cdEl = doc.createElement("cdText");
                if (cdt.songwriter() != null) cdEl.setAttribute("songwriter", cdt.songwriter());
                if (cdt.arranger() != null) cdEl.setAttribute("arranger", cdt.arranger());
                if (cdt.message() != null) cdEl.setAttribute("message", cdt.message());
                if (cdt.upcEan() != null) cdEl.setAttribute("upcEan", cdt.upcEan());
                trackEl.appendChild(cdEl);
            });

            for (Map.Entry<String, String> e : m.extra().entrySet()) {
                Element extraEl = doc.createElement("extra");
                extraEl.setAttribute("key", e.getKey());
                extraEl.setAttribute("value", e.getValue());
                trackEl.appendChild(extraEl);
            }
            tracksEl.appendChild(trackEl);
        }
        parent.appendChild(tracksEl);
    }

    /**
     * Reads metadata from a {@value #ROOT_ELEMENT} element into {@code sequence}.
     *
     * @param sequence the target sequence
     * @param root     the {@value #ROOT_ELEMENT} element
     */
    public static void readFrom(AlbumSequence sequence, Element root) {
        Objects.requireNonNull(sequence, "sequence must not be null");
        Objects.requireNonNull(root, "root must not be null");

        // Album-level metadata
        Element albumEl = firstChild(root, "album");
        if (albumEl != null) {
            String title = attr(albumEl, "title", sequence.getAlbumTitle());
            String artist = attr(albumEl, "artist", sequence.getArtist());
            int year = parseInt(albumEl.getAttribute("year"));
            String genre = nullIfBlank(albumEl.getAttribute("genre"));
            String upcEan = nullIfBlank(albumEl.getAttribute("upcEan"));
            Optional<LocalDate> releaseDate = parseDate(albumEl.getAttribute("releaseDate"));
            sequence.setAlbumMetadata(new AlbumMetadata(
                    title, artist, year, genre, upcEan, releaseDate));
        }

        // Per-track metadata
        Element tracksEl = firstChild(root, "tracks");
        if (tracksEl != null) {
            NodeList trackNodes = tracksEl.getChildNodes();
            for (int i = 0; i < trackNodes.getLength(); i++) {
                Node n = trackNodes.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE || !"track".equals(n.getNodeName())) {
                    continue;
                }
                Element t = (Element) n;
                String indexAttr = t.getAttribute("index");
                if (indexAttr == null || indexAttr.isBlank()) {
                    continue;
                }
                int idx;
                try {
                    idx = Integer.parseInt(indexAttr);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (idx < 0 || idx >= sequence.size()) {
                    continue; // ignore stale entries
                }
                String title = attr(t, "title", "Track " + (idx + 1));
                String artist = nullIfBlank(t.getAttribute("artist"));
                String composer = nullIfBlank(t.getAttribute("composer"));
                String isrc = nullIfBlank(t.getAttribute("isrc"));

                Optional<CdText> cdText = Optional.empty();
                Element cdEl = firstChild(t, "cdText");
                if (cdEl != null) {
                    cdText = Optional.of(new CdText(
                            nullIfBlank(cdEl.getAttribute("songwriter")),
                            nullIfBlank(cdEl.getAttribute("arranger")),
                            nullIfBlank(cdEl.getAttribute("message")),
                            nullIfBlank(cdEl.getAttribute("upcEan"))));
                }

                Map<String, String> extra = new LinkedHashMap<>();
                NodeList extras = t.getChildNodes();
                for (int j = 0; j < extras.getLength(); j++) {
                    Node en = extras.item(j);
                    if (en.getNodeType() == Node.ELEMENT_NODE && "extra".equals(en.getNodeName())) {
                        Element ee = (Element) en;
                        extra.put(ee.getAttribute("key"), ee.getAttribute("value"));
                    }
                }
                sequence.setTrackMetadata(idx,
                        new AlbumTrackMetadata(title, artist, composer, isrc, cdText, extra));
                // Mirror key fields back into AlbumTrackEntry so legacy
                // callers (cue sheet, PQ sheet) see the deserialized values.
                AlbumTrackEntry oldEntry = sequence.getTracks().get(idx);
                AlbumTrackEntry mirrored = new AlbumTrackEntry(
                        title, artist, isrc,
                        oldEntry.durationSeconds(), oldEntry.preGapSeconds(),
                        oldEntry.crossfadeDuration(), oldEntry.crossfadeCurve());
                sequence.setTrack(idx, mirrored);
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Mitigate XXE — same posture as ProjectSerializer.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static String writeDocument(Document doc) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    private static Element firstChild(Element parent, String name) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String attr(Element e, String name, String fallback) {
        String v = e.getAttribute(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Optional<LocalDate> parseDate(String s) {
        if (s == null || s.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(s));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }
}
