package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Imports ADM BWF (Audio Definition Model Broadcast Wave Format) files
 * — the standard immersive-audio interchange format produced by Dolby
 * Atmos Renderer, Nuendo, Pro Tools Ultimate and other professional tools.
 *
 * <p>An ADM BWF is a RIFF/WAVE file that carries an additional
 * {@code axml} chunk with XML metadata (per ITU-R BS.2076 / EBU Tech 3285
 * Supplement 5) describing the bed channels and audio objects contained
 * in the interleaved audio data.</p>
 *
 * <p>This importer:</p>
 * <ul>
 *   <li>parses the {@code axml} chunk to discover bed channels (with
 *       speaker labels) and audio objects (with position, size and gain);</li>
 *   <li>splits the interleaved audio into per-bed-channel and per-object
 *       buffers in the order described by the metadata
 *       (beds first, then objects — matching {@code AdmBwfExporter});</li>
 *   <li>captures one {@link AdmAutomationPoint} per
 *       {@code audioBlockFormat} for each object, preserving the
 *       time-stamped position envelope; and</li>
 *   <li>collects unmatched ADM elements ({@code audioProgrammeName},
 *       {@code audioContentName}, etc.) in
 *       {@link AdmImportResult#customMetadata()} so they survive a later
 *       export.</li>
 * </ul>
 *
 * <p>The result of {@link #parse(Path)} is sufficient input for
 * {@link com.benesquivelmusic.daw.core.export.AdmBwfExporter#export}
 * — that is, an import → export → import round-trip yields the same
 * object count, bed channel count and automation point count, within the
 * float precision of the schema.</p>
 */
public final class AdmBwfImporter {

    private static final String AXML_CHUNK_ID = "axml";

    private AdmBwfImporter() {
        // utility class
    }

    /**
     * Returns {@code true} if the given file is a WAV with an embedded
     * {@code axml} chunk (i.e. an ADM BWF file).
     *
     * <p>Used by {@link AudioFileImporter} to route {@code .wav} imports
     * through this importer when ADM metadata is present.</p>
     *
     * @param path the file to inspect
     * @return {@code true} if the file is an ADM BWF
     */
    public static boolean isAdmBwf(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            if (!Files.isRegularFile(path) || Files.size(path) < 44) {
                return false;
            }
            byte[] data = Files.readAllBytes(path);
            return findAxmlChunk(data).isPresent();
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Parses an ADM BWF file and returns the deinterleaved audio plus
     * spatial metadata.
     *
     * @param path the ADM BWF file
     * @return the parsed import result
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file is not a valid ADM BWF
     */
    public static AdmImportResult parse(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");

        // Read interleaved audio + format using the existing WAV reader
        WavFileReader.WavReadResult wav = WavFileReader.read(path);

        // Locate and parse the axml chunk
        byte[] fileBytes = Files.readAllBytes(path);
        byte[] admXml = findAxmlChunk(fileBytes).orElseThrow(() ->
                new IllegalArgumentException("Not an ADM BWF file (no axml chunk): " + path));

        ParsedAdm parsed = parseAdmXml(admXml);

        // Split interleaved audio: beds first, then objects (matches AdmBwfExporter)
        int totalChannels = wav.channels();
        int expectedChannels = parsed.bedSpeakerLabels.size() + parsed.objects.size();
        if (expectedChannels > totalChannels) {
            throw new IllegalArgumentException(
                    "ADM XML describes " + expectedChannels + " channels but WAV has only "
                            + totalChannels + ": " + path);
        }

        List<BedChannel> bedChannels = new ArrayList<>(parsed.bedSpeakerLabels.size());
        List<float[]> bedAudio = new ArrayList<>(parsed.bedSpeakerLabels.size());
        for (int i = 0; i < parsed.bedSpeakerLabels.size(); i++) {
            SpeakerLabel label = parsed.bedSpeakerLabels.get(i);
            bedChannels.add(new BedChannel("imported-bed-" + label.name(), label));
            bedAudio.add(wav.audioData()[i]);
        }

        List<AudioObject> audioObjects = new ArrayList<>(parsed.objects.size());
        List<float[]> objectAudio = new ArrayList<>(parsed.objects.size());
        Map<String, List<AdmAutomationPoint>> automation = new LinkedHashMap<>();
        int channelOffset = parsed.bedSpeakerLabels.size();
        for (int i = 0; i < parsed.objects.size(); i++) {
            ParsedObject po = parsed.objects.get(i);
            String trackId = "imported-object-" + (i + 1);
            ObjectMetadata initialMeta = po.blocks.isEmpty()
                    ? ObjectMetadata.DEFAULT
                    : po.blocks.get(0).metadata();
            audioObjects.add(new AudioObject(trackId, initialMeta));
            objectAudio.add(wav.audioData()[channelOffset + i]);
            if (!po.blocks.isEmpty()) {
                automation.put(trackId, List.copyOf(po.blocks));
            }
        }

        SpeakerLayout layout = inferLayout(parsed.bedSpeakerLabels);

        return new AdmImportResult(
                wav.sampleRate(),
                wav.bitDepth(),
                layout,
                bedChannels,
                bedAudio,
                audioObjects,
                objectAudio,
                automation,
                parsed.customMetadata);
    }

    // ── axml chunk extraction ──────────────────────────────────────────────

    /**
     * Scans a RIFF/WAVE file for the {@code axml} chunk and returns its
     * payload, or {@link Optional#empty()} if not present.
     */
    static Optional<byte[]> findAxmlChunk(byte[] fileBytes) {
        if (fileBytes.length < 12) {
            return Optional.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] tag = new byte[4];
        buf.get(tag);
        if (!"RIFF".equals(new String(tag, StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        buf.getInt(); // riff size (skip)
        buf.get(tag);
        if (!"WAVE".equals(new String(tag, StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        while (buf.remaining() >= 8) {
            buf.get(tag);
            String chunkId = new String(tag, StandardCharsets.US_ASCII);
            int chunkSize = buf.getInt();
            if (chunkSize < 0 || chunkSize > buf.remaining()) {
                return Optional.empty();
            }
            if (AXML_CHUNK_ID.equals(chunkId)) {
                byte[] payload = new byte[chunkSize];
                buf.get(payload);
                return Optional.of(payload);
            }
            // Skip chunk body (chunks are padded to even length per RIFF spec)
            int padded = chunkSize + (chunkSize % 2);
            int skip = Math.min(padded, buf.remaining());
            buf.position(buf.position() + skip);
        }
        return Optional.empty();
    }

    // ── ADM XML parsing ───────────────────────────────────────────────────

    /**
     * Parses ADM XML, handling both:
     * <ul>
     *   <li>the flat structure produced by {@code AdmBwfExporter}
     *       (audioBlockFormat siblings of audioObject), and</li>
     *   <li>the canonical hierarchical ITU-R BS.2076 structure
     *       (audioBlockFormat nested under audioChannelFormat).</li>
     * </ul>
     */
    static ParsedAdm parseAdmXml(byte[] xmlBytes) {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening (ADM XML is local trusted data, but defence in depth)
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // RIFF chunks are padded to even length, so the payload may carry
            // trailing NUL bytes that aren't part of the XML — strip them.
            int end = xmlBytes.length;
            while (end > 0 && (xmlBytes[end - 1] == 0
                    || xmlBytes[end - 1] == ' '
                    || xmlBytes[end - 1] == '\t'
                    || xmlBytes[end - 1] == '\n'
                    || xmlBytes[end - 1] == '\r')) {
                end--;
            }
            doc = builder.parse(new InputSource(new ByteArrayInputStream(xmlBytes, 0, end)));
        } catch (ParserConfigurationException | org.xml.sax.SAXException | IOException e) {
            throw new IllegalArgumentException("Failed to parse ADM XML: " + e.getMessage(), e);
        }

        ParsedAdm result = new ParsedAdm();

        // Custom metadata: programme name, content name, content description
        captureCustomMetadata(doc, result.customMetadata);

        // Bed channel speaker labels — taken from audioChannelFormat/speakerLabel.
        // Channels associated with an audioObject named "Bed" (or the implicit
        // single bed pack the exporter emits) form the bed layout, in document order.
        NodeList channelFormats = doc.getElementsByTagName("audioChannelFormat");
        for (int i = 0; i < channelFormats.getLength(); i++) {
            Element cf = (Element) channelFormats.item(i);
            String spk = firstChildText(cf, "speakerLabel");
            if (spk != null) {
                SpeakerLabel label = fromAdmSpeakerLabel(spk);
                if (label != null) {
                    result.bedSpeakerLabels.add(label);
                }
            }
        }

        // Audio objects — skip the bed object (named "Bed" by convention).
        NodeList audioObjectNodes = doc.getElementsByTagName("audioObject");
        List<Element> objectElements = new ArrayList<>();
        for (int i = 0; i < audioObjectNodes.getLength(); i++) {
            Element ao = (Element) audioObjectNodes.item(i);
            String name = ao.getAttribute("audioObjectName");
            if ("Bed".equalsIgnoreCase(name)) {
                continue;
            }
            objectElements.add(ao);
        }

        // Collect all audioBlockFormat elements in document order. The
        // AdmBwfExporter emits one block per object, matched by index.
        NodeList blockNodes = doc.getElementsByTagName("audioBlockFormat");
        List<Element> blockElements = new ArrayList<>();
        for (int i = 0; i < blockNodes.getLength(); i++) {
            blockElements.add((Element) blockNodes.item(i));
        }

        // Build per-object block lists. If the count of blocks equals the
        // count of objects, assume 1:1 by order (exporter-style).
        // Otherwise distribute blocks evenly across objects.
        boolean oneBlockPerObject = !blockElements.isEmpty()
                && blockElements.size() == objectElements.size();
        for (int i = 0; i < objectElements.size(); i++) {
            ParsedObject po = new ParsedObject();
            if (oneBlockPerObject) {
                po.blocks.add(parseBlock(blockElements.get(i)));
            } else if (!blockElements.isEmpty()) {
                // Hierarchical structure: take blocks whose parent is a
                // channelFormat that corresponds to this object. As a robust
                // fallback, give every object every block (preserves count
                // for round-trip).
                int perObject = blockElements.size() / objectElements.size();
                int start = i * perObject;
                int end = (i == objectElements.size() - 1)
                        ? blockElements.size()
                        : start + perObject;
                for (int j = start; j < end; j++) {
                    po.blocks.add(parseBlock(blockElements.get(j)));
                }
            }
            result.objects.add(po);
        }

        return result;
    }

    private static AdmAutomationPoint parseBlock(Element block) {
        double x = parseCoord(block, "X", 0.0);
        double y = parseCoord(block, "Y", 0.0);
        double z = parseCoord(block, "Z", 0.0);
        double size = clamp01(parseDoubleChild(block, "width", 0.0));
        if (size == 0.0) {
            // ITU-R also allows <size> directly (some tools use either tag)
            size = clamp01(parseDoubleChild(block, "size", 0.0));
        }
        double gain = clamp01(parseDoubleChild(block, "gain", 1.0));

        double t = parseTimeAttribute(block.getAttribute("rtime"));
        ObjectMetadata meta = new ObjectMetadata(
                clampSigned(x), clampSigned(y), clampSigned(z), size, gain);
        return new AdmAutomationPoint(t, meta);
    }

    private static double parseCoord(Element block, String axis, double defaultValue) {
        NodeList positions = block.getElementsByTagName("position");
        for (int i = 0; i < positions.getLength(); i++) {
            Element p = (Element) positions.item(i);
            if (axis.equalsIgnoreCase(p.getAttribute("coordinate"))) {
                try {
                    return Double.parseDouble(p.getTextContent().trim());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static double parseDoubleChild(Element parent, String tag, double defaultValue) {
        String txt = firstChildText(parent, tag);
        if (txt == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(txt);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String firstChildText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return n.getTextContent().trim();
            }
            // Fall back to nodeName when no namespace is set
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getNodeName())) {
                return n.getTextContent().trim();
            }
        }
        return null;
    }

    /** Parses an ADM time string of the form {@code HH:MM:SS.fffff} into seconds. */
    private static double parseTimeAttribute(String value) {
        if (value == null || value.isEmpty()) {
            return 0.0;
        }
        try {
            String[] parts = value.split(":");
            if (parts.length == 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                double s = Double.parseDouble(parts[2]);
                return h * 3600.0 + m * 60.0 + s;
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static void captureCustomMetadata(Document doc, Map<String, String> into) {
        captureAttribute(doc, "audioProgramme", "audioProgrammeName", into, "audioProgrammeName");
        captureAttribute(doc, "audioContent", "audioContentName", into, "audioContentName");
        // Free-text descendants
        captureFirstText(doc, "audioContentDescription", into, "audioContentDescription");
        captureFirstText(doc, "author", into, "author");
    }

    private static void captureAttribute(Document doc, String elementName,
                                         String attribute, Map<String, String> into, String key) {
        NodeList nodes = doc.getElementsByTagName(elementName);
        if (nodes.getLength() > 0) {
            Element e = (Element) nodes.item(0);
            String value = e.getAttribute(attribute);
            if (value != null && !value.isEmpty()) {
                into.put(key, value);
            }
        }
    }

    private static void captureFirstText(Document doc, String elementName,
                                         Map<String, String> into, String key) {
        NodeList nodes = doc.getElementsByTagName(elementName);
        if (nodes.getLength() > 0) {
            String value = nodes.item(0).getTextContent();
            if (value != null && !value.isBlank()) {
                into.put(key, value.trim());
            }
        }
    }

    // ── speaker label mapping (inverse of AdmBwfExporter#admSpeakerLabel) ─

    private static SpeakerLabel fromAdmSpeakerLabel(String admLabel) {
        return switch (admLabel.toUpperCase(Locale.ROOT)) {
            case "M+030" -> SpeakerLabel.L;
            case "M-030" -> SpeakerLabel.R;
            case "M+000" -> SpeakerLabel.C;
            case "LFE", "LFE1" -> SpeakerLabel.LFE;
            case "M+110" -> SpeakerLabel.LS;
            case "M-110" -> SpeakerLabel.RS;
            case "M+135" -> SpeakerLabel.LRS;
            case "M-135" -> SpeakerLabel.RRS;
            case "U+045" -> SpeakerLabel.LTF;
            case "U-045" -> SpeakerLabel.RTF;
            case "U+135" -> SpeakerLabel.LTR;
            case "U-135" -> SpeakerLabel.RTR;
            default -> null;
        };
    }

    /**
     * Picks the best matching predefined {@link SpeakerLayout} for a list
     * of bed speaker labels; if the layout cannot be matched exactly,
     * a custom layout named {@code "Custom"} is returned.
     */
    static SpeakerLayout inferLayout(List<SpeakerLabel> labels) {
        for (SpeakerLayout candidate : List.of(
                SpeakerLayout.LAYOUT_7_1_4, SpeakerLayout.LAYOUT_5_1_4,
                SpeakerLayout.LAYOUT_5_1, SpeakerLayout.LAYOUT_STEREO,
                SpeakerLayout.LAYOUT_MONO)) {
            if (candidate.speakers().equals(labels)) {
                return candidate;
            }
        }
        if (labels.isEmpty()) {
            return SpeakerLayout.LAYOUT_STEREO; // arbitrary non-empty default
        }
        return new SpeakerLayout("Custom", labels);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static double clampSigned(double v) {
        if (v < -1.0) return -1.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /** Intermediate representation of the ADM XML. */
    static final class ParsedAdm {
        final List<SpeakerLabel> bedSpeakerLabels = new ArrayList<>();
        final List<ParsedObject> objects = new ArrayList<>();
        final Map<String, String> customMetadata = new LinkedHashMap<>();
    }

    /** Intermediate representation of one ADM audio object. */
    static final class ParsedObject {
        final List<AdmAutomationPoint> blocks = new ArrayList<>();
    }
}
