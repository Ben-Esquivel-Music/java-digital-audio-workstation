package com.benesquivelmusic.daw.core.persistence;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Side-effect-free reader of a project's {@code project.daw} metadata — the
 * cheap, lock-free, migration-free name/timestamp peek the story-296
 * Welcome/Hub project cards use to populate a card without opening the project
 * (no {@link ProjectLockManager#tryAcquire lock}, no
 * {@link com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry
 * migration}, no {@link DawProject} deserialization).
 *
 * <p>JavaFX-free (this is {@code daw-core}). The full
 * {@link ProjectDeserializer} reads the same {@code <metadata>} block but also
 * runs migrations and rebuilds the entire project graph; this reader does the
 * minimum a hub tile needs and is robust to malformed content (it never throws
 * for bad input — it returns {@link Optional#empty()} or a best-effort
 * {@link ProjectMetadata}).</p>
 *
 * <h2>Format detection</h2>
 *
 * <p>Mirrors {@link ProjectManager#openProject(Path)}: if the file content
 * starts with {@code <?xml} or {@code <daw-project} it is parsed as XML (the
 * {@code <metadata><name>/<created-at>/<last-modified></metadata>} elements,
 * exactly as {@link ProjectDeserializer} reads them); otherwise it is parsed as
 * the legacy text format ({@code name=}/{@code created_at=}/{@code last_modified=}
 * lines, exactly as {@link ProjectManager}'s legacy {@code parseProjectFile}
 * does).</p>
 */
public final class ProjectMetadataReader {

    private static final String PROJECT_FILE_NAME = "project.daw";

    private ProjectMetadataReader() {
        // Static utility — no instances.
    }

    /**
     * Reads the {@code project.daw} metadata in {@code projectDir} without any
     * side effect. Returns the parsed {@link ProjectMetadata} (with
     * {@code projectPath} set to {@code projectDir}), or {@link Optional#empty()}
     * when {@code project.daw} is missing or unreadable.
     *
     * <p>The returned metadata's {@code name} falls back to the directory's
     * file name when the file carries no usable name; {@code createdAt} /
     * {@code lastModified} fall back to the {@code project.daw} file's
     * last-modified time when the file carries no parseable timestamp (and only
     * to {@link Instant#now()} when even that is unreadable) — so a card's
     * "last opened" reflects the file on disk rather than the moment of the scan.
     * Malformed content never throws.</p>
     *
     * @param projectDir the project directory to read; must not be {@code null}
     * @return the project metadata, or empty when no readable {@code project.daw} exists
     */
    public static Optional<ProjectMetadata> read(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        Path projectFile = projectDir.resolve(PROJECT_FILE_NAME);
        if (!Files.isRegularFile(projectFile)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(projectFile);
        } catch (IOException | RuntimeException e) {
            // Unreadable / undecodable — treated as "no metadata available".
            return Optional.empty();
        }

        String stripped = content.strip();
        Parsed parsed = (stripped.startsWith("<?xml") || stripped.startsWith("<daw-project"))
                ? parseXml(content)
                : parseLegacyText(content);

        String name = (parsed.name == null || parsed.name.isBlank())
                ? fileNameOf(projectDir)
                : parsed.name.strip();
        // When the content has no parseable timestamp, prefer the file's own
        // last-modified time over Instant.now() so the card's "last opened" tracks
        // the project on disk instead of the instant this scan happened to run.
        Instant fileModified = fileLastModified(projectFile);
        Instant createdAt = firstNonNull(parsed.createdAt, fileModified);
        Instant lastModified = firstNonNull(parsed.lastModified, fileModified);
        return Optional.of(new ProjectMetadata(name, createdAt, lastModified, projectDir));
    }

    /** Best-effort parse holder — any field may be {@code null} (use a fallback). */
    private record Parsed(String name, Instant createdAt, Instant lastModified) {
    }

    private static Parsed parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Same hardening as ProjectDeserializer — no external DTD entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();

            Element root = document.getDocumentElement();
            List<Element> metadataElements = directChildren(root, "metadata");
            if (metadataElements.isEmpty()) {
                return new Parsed(null, null, null);
            }
            Element metadata = metadataElements.get(0);
            String name = firstChildText(metadata, "name");
            Instant createdAt = parseInstant(firstChildText(metadata, "created-at"));
            Instant lastModified = parseInstant(firstChildText(metadata, "last-modified"));
            return new Parsed(name, createdAt, lastModified);
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            // Malformed XML — best-effort empty parse, fallbacks fill in.
            return new Parsed(null, null, null);
        }
    }

    private static Parsed parseLegacyText(String content) {
        String name = null;
        Instant createdAt = null;
        Instant lastModified = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("name=")) {
                name = line.substring("name=".length()).strip();
            } else if (line.startsWith("created_at=")) {
                createdAt = parseInstant(line.substring("created_at=".length()).strip());
            } else if (line.startsWith("last_modified=")) {
                lastModified = parseInstant(line.substring("last_modified=".length()).strip());
            }
        }
        return new Parsed(name, createdAt, lastModified);
    }

    /**
     * The first non-{@code null} of {@code parsed} (the content timestamp) then
     * {@code fileModified} (the file's mtime), falling back to {@link Instant#now()}
     * only when both are absent.
     */
    private static Instant firstNonNull(Instant parsed, Instant fileModified) {
        if (parsed != null) {
            return parsed;
        }
        return fileModified != null ? fileModified : Instant.now();
    }

    /** The {@code project.daw} file's last-modified instant, or {@code null} if unreadable. */
    private static Instant fileLastModified(Path projectFile) {
        try {
            return Files.getLastModifiedTime(projectFile).toInstant();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static String firstChildText(Element parent, String tagName) {
        List<Element> elements = directChildren(parent, tagName);
        if (elements.isEmpty()) {
            return null;
        }
        String text = elements.get(0).getTextContent();
        return (text == null || text.isBlank()) ? null : text.strip();
    }

    // Core-side base-name helper; intentionally mirrors the daw-app
    // HubFormat.baseName semantics (full-path fallback for a root path) so the
    // hub and this reader agree on a card's seeded name. daw-core cannot depend
    // on daw-app, hence the small duplicate.
    private static String fileNameOf(Path projectDir) {
        Path fileName = projectDir.getFileName();
        return fileName != null ? fileName.toString() : projectDir.toString();
    }
}
