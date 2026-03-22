package com.benesquivelmusic.daw.core.session.dawproject;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses DAWproject XML ({@code project.xml}) into a {@link SessionData} instance.
 *
 * <p>The parser maps DAWproject elements to internal session data structures,
 * collecting warnings for any features that cannot be fully represented.
 * Unsupported elements (e.g., automation curves, plugin device chains) are
 * logged as warnings rather than causing parse failure — enabling graceful
 * degradation.</p>
 */
public final class DawProjectXmlParser {

    private static final String ELEMENT_PROJECT = "Project";
    private static final String ELEMENT_TRANSPORT = "Transport";
    private static final String ELEMENT_TEMPO = "Tempo";
    private static final String ELEMENT_TIME_SIGNATURE = "TimeSignature";
    private static final String ELEMENT_STRUCTURE = "Structure";
    private static final String ELEMENT_TRACK = "Track";
    private static final String ELEMENT_CHANNEL = "Channel";
    private static final String ELEMENT_LANES = "Lanes";
    private static final String ELEMENT_CLIPS = "Clips";
    private static final String ELEMENT_CLIP = "Clip";
    private static final String ELEMENT_AUDIO = "Audio";
    private static final String ELEMENT_FILE = "File";
    private static final String ELEMENT_AUTOMATION = "Automation";
    private static final String ELEMENT_DEVICES = "Devices";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_NUMERATOR = "numerator";
    private static final String ATTR_DENOMINATOR = "denominator";
    private static final String ATTR_CONTENT_TYPE = "contentType";
    private static final String ATTR_TIME = "time";
    private static final String ATTR_DURATION = "duration";
    private static final String ATTR_VOLUME = "volume";
    private static final String ATTR_PAN = "pan";
    private static final String ATTR_MUTE = "mute";
    private static final String ATTR_SOLO = "solo";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_OFFSET = "offset";
    private static final String ATTR_GAIN = "gain";
    private static final String ATTR_SAMPLE_RATE = "sampleRate";

    /**
     * Parses DAWproject XML from the given input stream.
     *
     * @param inputStream the XML input stream ({@code project.xml} content)
     * @param warnings    a mutable list to which parse warnings are appended
     * @return the parsed session data
     * @throws IOException if an I/O or parse error occurs
     */
    public SessionData parse(InputStream inputStream, List<String> warnings) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            var document = builder.parse(inputStream);
            document.getDocumentElement().normalize();
            return parseDocument(document, warnings);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse DAWproject XML", e);
        }
    }

    private SessionData parseDocument(Document document, List<String> warnings) {
        var root = document.getDocumentElement();
        var projectName = root.getAttribute(ATTR_NAME);
        if (projectName.isEmpty()) {
            projectName = "Untitled";
        }

        double tempo = 120.0;
        int tsNumerator = 4;
        int tsDenominator = 4;
        double sampleRate = 44_100.0;

        // Parse Transport (tempo, time signature)
        var transportList = root.getElementsByTagName(ELEMENT_TRANSPORT);
        if (transportList.getLength() > 0) {
            var transport = (Element) transportList.item(0);

            var tempoElements = transport.getElementsByTagName(ELEMENT_TEMPO);
            if (tempoElements.getLength() > 0) {
                tempo = parseDoubleAttr((Element) tempoElements.item(0), ATTR_VALUE, 120.0);
            }

            var tsElements = transport.getElementsByTagName(ELEMENT_TIME_SIGNATURE);
            if (tsElements.getLength() > 0) {
                var tsElement = (Element) tsElements.item(0);
                tsNumerator = parseIntAttr(tsElement, ATTR_NUMERATOR, 4);
                tsDenominator = parseIntAttr(tsElement, ATTR_DENOMINATOR, 4);
            }
        }

        // Parse sample rate from project attributes
        sampleRate = parseDoubleAttr(root, ATTR_SAMPLE_RATE, 44_100.0);

        // Parse tracks
        List<SessionTrack> tracks = new ArrayList<>();
        var structureList = root.getElementsByTagName(ELEMENT_STRUCTURE);
        if (structureList.getLength() > 0) {
            var structure = (Element) structureList.item(0);
            var trackElements = getDirectChildElements(structure, ELEMENT_TRACK);
            for (var trackElement : trackElements) {
                tracks.add(parseTrack(trackElement, warnings));
            }
        }

        return new SessionData(projectName, tempo, tsNumerator, tsDenominator, sampleRate, tracks);
    }

    private SessionTrack parseTrack(Element trackElement, List<String> warnings) {
        var name = trackElement.getAttribute(ATTR_NAME);
        if (name.isEmpty()) {
            name = "Untitled Track";
        }

        var contentType = trackElement.getAttribute(ATTR_CONTENT_TYPE);
        var type = mapContentType(contentType);

        double volume = 1.0;
        double pan = 0.0;
        boolean muted = false;
        boolean solo = false;

        // Parse Channel element for mixer settings
        var channelElements = getDirectChildElements(trackElement, ELEMENT_CHANNEL);
        if (!channelElements.isEmpty()) {
            var channel = channelElements.getFirst();
            volume = parseDoubleAttr(channel, ATTR_VOLUME, 1.0);
            pan = parseDoubleAttr(channel, ATTR_PAN, 0.0);
            muted = parseBooleanAttr(channel, ATTR_MUTE);
            solo = parseBooleanAttr(channel, ATTR_SOLO);
        }

        // Warn about unsupported Device elements (plugin chains)
        var deviceElements = getDirectChildElements(trackElement, ELEMENT_DEVICES);
        if (!deviceElements.isEmpty()) {
            warnings.add("Track '" + name + "': Device/plugin chains are not yet supported and were skipped");
        }

        // Parse clips
        List<SessionClip> clips = new ArrayList<>();
        var lanesElements = getDirectChildElements(trackElement, ELEMENT_LANES);
        if (!lanesElements.isEmpty()) {
            var lanes = lanesElements.getFirst();
            var clipsElements = getDirectChildElements(lanes, ELEMENT_CLIPS);
            if (!clipsElements.isEmpty()) {
                var clipsContainer = clipsElements.getFirst();
                var clipElements = getDirectChildElements(clipsContainer, ELEMENT_CLIP);
                for (var clipElement : clipElements) {
                    clips.add(parseClip(clipElement, warnings));
                }
            }

            // Warn about automation lanes
            var automationElements = getDirectChildElements(lanes, ELEMENT_AUTOMATION);
            if (!automationElements.isEmpty()) {
                warnings.add("Track '" + name + "': Automation data is not yet supported and was skipped");
            }
        }

        return new SessionTrack(name, type, volume, pan, muted, solo, clips);
    }

    private SessionClip parseClip(Element clipElement, List<String> warnings) {
        var name = clipElement.getAttribute(ATTR_NAME);
        if (name.isEmpty()) {
            name = "Untitled Clip";
        }

        double startBeat = parseDoubleAttr(clipElement, ATTR_TIME, 0.0);
        double durationBeats = parseDoubleAttr(clipElement, ATTR_DURATION, 1.0);

        String sourceFilePath = null;
        double sourceOffset = 0.0;
        double gain = 0.0;

        // Parse Audio/File reference
        var audioElements = getDirectChildElements(clipElement, ELEMENT_AUDIO);
        if (!audioElements.isEmpty()) {
            var audio = audioElements.getFirst();
            gain = parseDoubleAttr(audio, ATTR_GAIN, 0.0);
            sourceOffset = parseDoubleAttr(audio, ATTR_OFFSET, 0.0);

            var fileElements = getDirectChildElements(audio, ELEMENT_FILE);
            if (!fileElements.isEmpty()) {
                sourceFilePath = ((Element) fileElements.getFirst()).getAttribute(ATTR_PATH);
                if (sourceFilePath.isEmpty()) {
                    sourceFilePath = null;
                }
            }
        }

        return new SessionClip(name, startBeat, durationBeats, sourceOffset, sourceFilePath, gain);
    }

    private String mapContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return "AUDIO";
        }
        return switch (contentType.toLowerCase()) {
            case "audio" -> "AUDIO";
            case "midi", "notes" -> "MIDI";
            case "aux", "bus" -> "AUX";
            case "master" -> "MASTER";
            default -> "AUDIO";
        };
    }

    private static List<Element> getDirectChildElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && child.getTagName().equals(tagName)) {
                result.add(child);
            }
        }
        return result;
    }

    private static double parseDoubleAttr(Element element, String attr, double defaultValue) {
        var value = element.getAttribute(attr);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parseIntAttr(Element element, String attr, int defaultValue) {
        var value = element.getAttribute(attr);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBooleanAttr(Element element, String attr) {
        var value = element.getAttribute(attr);
        return "true".equalsIgnoreCase(value);
    }
}
