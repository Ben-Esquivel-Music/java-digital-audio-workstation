package com.benesquivelmusic.daw.core.session;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reads and writes {@link WorkingSession} manifests under a project's
 * {@code sessions/} directory, per the Project Manager Design Book §3.2/§3.3.
 *
 * <p>Each session is stored as {@code sessions/<date>-<slug>.session.xml}; the
 * {@code project.daw} file remains the single full source of truth and these
 * manifests are derivable metadata layered on top. The format is
 * deliberately small and human-readable so it degrades gracefully — projects
 * that predate this feature simply have no {@code sessions/} directory, and
 * older builds ignore the folder entirely (design book §7 Stage&nbsp;3).</p>
 */
public final class SessionManifestStore {

    /** Sub-directory name that holds session manifests. */
    public static final String SESSIONS_DIR_NAME = "sessions";

    private static final String MANIFEST_SUFFIX = ".session.xml";

    private static final String EL_SESSION = "Session";
    private static final String EL_NAME = "Name";
    private static final String EL_NOTES = "Notes";
    private static final String EL_TAKES = "Takes";
    private static final String EL_CHECKPOINTS = "Checkpoints";
    private static final String EL_JOURNAL = "JournalSegments";
    private static final String EL_ITEM = "Item";

    private static final String ATTR_ID = "id";
    private static final String ATTR_START = "start";
    private static final String ATTR_END = "end";
    private static final String ATTR_RECORDED_MS = "recordedMillis";

    private final ZoneId zone;

    /** Creates a store using the system default time zone for manifest names. */
    public SessionManifestStore() {
        this(ZoneId.systemDefault());
    }

    /**
     * Creates a store using the supplied time zone for deriving the date in
     * manifest file names.
     *
     * @param zone the zone used for {@code <date>} in manifest names
     */
    public SessionManifestStore(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /** {@return the {@code sessions/} directory inside {@code projectDir}} */
    public Path sessionsDirectory(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        return projectDir.resolve(SESSIONS_DIR_NAME);
    }

    /**
     * The manifest file name for a session, e.g.
     * {@code 2026-03-21-tracking-day-2.session.xml}.
     *
     * @param session the session to name
     * @return the manifest file name (no directory component)
     */
    public String manifestFileName(WorkingSession session) {
        Objects.requireNonNull(session, "session must not be null");
        LocalDate date = LocalDate.ofInstant(session.startTime(), zone);
        return date + "-" + slug(session.name()) + "-" + session.id() + MANIFEST_SUFFIX;
    }

    /**
     * Converts an arbitrary session name into a filesystem-safe slug:
     * lower-cased ASCII alphanumerics, runs of other characters collapsed to a
     * single hyphen, with leading/trailing hyphens trimmed.
     *
     * @param name the session name
     * @return a non-empty slug ({@code "session"} when nothing usable remains)
     */
    public static String slug(String name) {
        Objects.requireNonNull(name, "name must not be null");
        StringBuilder sb = new StringBuilder(name.length());
        boolean pendingHyphen = false;
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingHyphen && sb.length() > 0) {
                    sb.append('-');
                }
                pendingHyphen = false;
                sb.append(c);
            } else {
                pendingHyphen = true;
            }
        }
        return sb.isEmpty() ? "session" : sb.toString();
    }

    /**
     * Writes a session manifest into {@code projectDir/sessions/}, creating the
     * directory if needed.
     *
     * @param projectDir the project directory
     * @param session    the session to persist
     * @return the path of the written manifest
     * @throws IOException if an I/O or serialization error occurs
     */
    public Path write(Path projectDir, WorkingSession session) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Objects.requireNonNull(session, "session must not be null");
        Path dir = sessionsDirectory(projectDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(manifestFileName(session));
        try (OutputStream out = Files.newOutputStream(file)) {
            serialize(session, out);
        }
        return file;
    }

    /**
     * Reads a single session manifest.
     *
     * @param file the manifest file
     * @return the parsed session
     * @throws IOException if the file cannot be read or parsed
     */
    public WorkingSession read(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        try {
            DocumentBuilderFactory factory = secureBuilderFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc;
            try (var in = Files.newInputStream(file)) {
                doc = builder.parse(in);
            }
            return parse(doc.getDocumentElement());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse session manifest: " + file, e);
        }
    }

    /**
     * Lists every session manifest in {@code projectDir/sessions/}, parsed and
     * sorted with the most recently started session first.
     *
     * <p>Returns an empty list when the project has no {@code sessions/}
     * directory (graceful degradation per design book §7). Manifests that fail
     * to parse are skipped rather than aborting the whole listing.</p>
     *
     * @param projectDir the project directory
     * @return sessions ordered newest-first
     * @throws IOException if the directory cannot be listed
     */
    public List<WorkingSession> listSessions(Path projectDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path dir = sessionsDirectory(projectDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<WorkingSession> sessions = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, "*" + MANIFEST_SUFFIX)) {
            for (Path file : stream) {
                try {
                    sessions.add(read(file));
                } catch (IOException ignored) {
                    // Skip corrupt manifests — the dock degrades gracefully.
                }
            }
        }
        sessions.sort(Comparator.comparing(WorkingSession::startTime).reversed());
        return List.copyOf(sessions);
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private void serialize(WorkingSession session, OutputStream out) throws IOException {
        try {
            DocumentBuilderFactory factory = secureBuilderFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement(EL_SESSION);
            root.setAttribute(ATTR_ID, session.id());
            root.setAttribute(ATTR_START, session.startTime().toString());
            if (session.endTime() != null) {
                root.setAttribute(ATTR_END, session.endTime().toString());
            }
            root.setAttribute(ATTR_RECORDED_MS, Long.toString(session.recordedTime().toMillis()));
            doc.appendChild(root);

            appendText(doc, root, EL_NAME, session.name());
            appendText(doc, root, EL_NOTES, session.notes());
            appendItems(doc, root, EL_TAKES, session.takeIds());
            appendItems(doc, root, EL_CHECKPOINTS, session.checkpointIds());
            appendItems(doc, root, EL_JOURNAL, session.journalSegments());

            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(doc), new StreamResult(out));
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Failed to serialize session manifest", e);
        }
    }

    private static void appendText(Document doc, Element parent, String tag, String text) {
        Element el = doc.createElement(tag);
        el.setTextContent(text);
        parent.appendChild(el);
    }

    private static void appendItems(Document doc, Element parent, String tag, List<String> values) {
        Element container = doc.createElement(tag);
        for (String value : values) {
            Element item = doc.createElement(EL_ITEM);
            item.setTextContent(value);
            container.appendChild(item);
        }
        parent.appendChild(container);
    }

    private WorkingSession parse(Element root) throws IOException {
        if (root == null || !EL_SESSION.equals(root.getTagName())) {
            throw new IOException("Not a session manifest (missing <Session> root)");
        }
        String id = attrOrThrow(root, ATTR_ID);
        Instant start = parseInstant(attrOrThrow(root, ATTR_START), "start");
        String endText = root.getAttribute(ATTR_END);
        Instant end = endText.isEmpty() ? null : parseInstant(endText, "end");
        Duration recorded = parseDuration(root.getAttribute(ATTR_RECORDED_MS));

        String name = childText(root, EL_NAME);
        if (name == null || name.isBlank()) {
            name = WorkingSession.defaultName(LocalDate.ofInstant(start, zone));
        }
        String notes = childText(root, EL_NOTES);

        return new WorkingSession(
                id,
                name,
                start,
                end,
                recorded,
                childItems(root, EL_TAKES),
                childItems(root, EL_CHECKPOINTS),
                childItems(root, EL_JOURNAL),
                notes == null ? "" : notes);
    }

    private static String attrOrThrow(Element el, String attr) throws IOException {
        String value = el.getAttribute(attr);
        if (value.isEmpty()) {
            throw new IOException("Session manifest missing required attribute: " + attr);
        }
        return value;
    }

    private static Instant parseInstant(String text, String label) throws IOException {
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            throw new IOException("Invalid " + label + " instant in session manifest: " + text, e);
        }
    }

    private static Duration parseDuration(String millisText) {
        if (millisText == null || millisText.isBlank()) {
            return Duration.ZERO;
        }
        try {
            long millis = Long.parseLong(millisText.trim());
            return millis < 0 ? Duration.ZERO : Duration.ofMillis(millis);
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }

    private static String childText(Element parent, String tag) {
        Element child = firstChild(parent, tag);
        return child == null ? null : child.getTextContent();
    }

    private static List<String> childItems(Element parent, String tag) {
        Element container = firstChild(parent, tag);
        if (container == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        NodeList items = container.getElementsByTagName(EL_ITEM);
        for (int i = 0; i < items.getLength(); i++) {
            String text = items.item(i).getTextContent();
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return List.copyOf(values);
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && el.getTagName().equals(tag)) {
                return el;
            }
        }
        return null;
    }

    private static DocumentBuilderFactory secureBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
