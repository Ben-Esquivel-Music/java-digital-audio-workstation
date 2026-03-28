package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Deserializes a {@link DawProject} from XML produced by {@link ProjectSerializer}.
 *
 * <p>The deserializer reconstructs the complete project state including tracks,
 * clips, mixer settings, transport state, and project metadata. Malformed or
 * missing values fall back to sensible defaults to ensure graceful degradation.</p>
 */
public final class ProjectDeserializer {

    /**
     * Deserializes a project from an XML string.
     *
     * @param xml the XML content produced by {@link ProjectSerializer}
     * @return the reconstructed project
     * @throws IOException if the XML cannot be parsed
     */
    public DawProject deserialize(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return parseDocument(document);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to deserialize project XML", e);
        }
    }

    private DawProject parseDocument(Document document) {
        Element root = document.getDocumentElement();

        // Parse metadata
        String projectName = "Untitled";
        Instant createdAt = Instant.now();
        Instant lastModified = Instant.now();

        List<Element> metadataElements = getDirectChildElements(root, "metadata");
        if (!metadataElements.isEmpty()) {
            Element metadata = metadataElements.getFirst();
            List<Element> nameElements = getDirectChildElements(metadata, "name");
            if (!nameElements.isEmpty()) {
                String nameText = nameElements.getFirst().getTextContent();
                if (nameText != null && !nameText.isBlank()) {
                    projectName = nameText;
                }
            }
            List<Element> createdAtElements = getDirectChildElements(metadata, "created-at");
            if (!createdAtElements.isEmpty()) {
                createdAt = parseInstant(createdAtElements.getFirst().getTextContent(), createdAt);
            }
            List<Element> lastModifiedElements = getDirectChildElements(metadata, "last-modified");
            if (!lastModifiedElements.isEmpty()) {
                lastModified = parseInstant(lastModifiedElements.getFirst().getTextContent(), lastModified);
            }
        }

        // Parse audio format
        AudioFormat audioFormat = AudioFormat.CD_QUALITY;
        List<Element> formatElements = getDirectChildElements(root, "audio-format");
        if (!formatElements.isEmpty()) {
            Element fmt = formatElements.getFirst();
            double sampleRate = parseDoubleAttr(fmt, "sample-rate", 44100.0);
            int channels = parseIntAttr(fmt, "channels", 2);
            int bitDepth = parseIntAttr(fmt, "bit-depth", 16);
            int bufferSize = parseIntAttr(fmt, "buffer-size", 512);
            audioFormat = new AudioFormat(sampleRate, channels, bitDepth, bufferSize);
        }

        // Create project
        DawProject project = new DawProject(projectName, audioFormat);
        ProjectMetadata meta = new ProjectMetadata(projectName, createdAt, lastModified, null);
        project.setMetadata(meta);

        // Parse transport
        List<Element> transportElements = getDirectChildElements(root, "transport");
        if (!transportElements.isEmpty()) {
            parseTransport(transportElements.getFirst(), project.getTransport());
        }

        // Parse tracks
        List<Element> tracksContainers = getDirectChildElements(root, "tracks");
        if (!tracksContainers.isEmpty()) {
            List<Element> trackElements = getDirectChildElements(tracksContainers.getFirst(), "track");
            for (Element trackElement : trackElements) {
                Track track = parseTrack(trackElement);
                project.addTrack(track);
            }
        }

        // Parse mixer settings
        List<Element> mixerElements = getDirectChildElements(root, "mixer");
        if (!mixerElements.isEmpty()) {
            parseMixer(mixerElements.getFirst(), project);
        }

        return project;
    }

    private void parseTransport(Element elem, Transport transport) {
        double tempo = parseDoubleAttr(elem, "tempo", 120.0);
        if (tempo >= 20.0 && tempo <= 999.0) {
            transport.setTempo(tempo);
        }

        int tsNumerator = parseIntAttr(elem, "time-sig-numerator", 4);
        int tsDenominator = parseIntAttr(elem, "time-sig-denominator", 4);
        if (tsNumerator > 0 && tsDenominator > 0) {
            transport.setTimeSignature(tsNumerator, tsDenominator);
        }

        boolean loopEnabled = parseBooleanAttr(elem, "loop-enabled");
        transport.setLoopEnabled(loopEnabled);

        double loopStart = parseDoubleAttr(elem, "loop-start", 0.0);
        double loopEnd = parseDoubleAttr(elem, "loop-end", 16.0);
        if (loopStart >= 0 && loopEnd > loopStart) {
            transport.setLoopRegion(loopStart, loopEnd);
        }

        double position = parseDoubleAttr(elem, "position", 0.0);
        if (position >= 0) {
            transport.setPositionInBeats(position);
        }
    }

    private Track parseTrack(Element elem) {
        String name = elem.getAttribute("name");
        if (name.isEmpty()) {
            name = "Untitled Track";
        }

        String typeStr = elem.getAttribute("type");
        TrackType type = parseTrackType(typeStr);

        Track track = new Track(name, type);
        track.setVolume(clampDouble(parseDoubleAttr(elem, "volume", 1.0), 0.0, 1.0));
        track.setPan(clampDouble(parseDoubleAttr(elem, "pan", 0.0), -1.0, 1.0));
        track.setMuted(parseBooleanAttr(elem, "muted"));
        track.setSolo(parseBooleanAttr(elem, "solo"));
        track.setArmed(parseBooleanAttr(elem, "armed"));
        track.setPhaseInverted(parseBooleanAttr(elem, "phase-inverted"));

        String colorHex = elem.getAttribute("color");
        if (!colorHex.isEmpty()) {
            try {
                track.setColor(TrackColor.fromHex(colorHex));
            } catch (IllegalArgumentException ignored) {
                // keep default color on invalid hex
            }
        }

        // Parse clips
        List<Element> clipsContainers = getDirectChildElements(elem, "clips");
        if (!clipsContainers.isEmpty()) {
            List<Element> clipElements = getDirectChildElements(clipsContainers.getFirst(), "clip");
            for (Element clipElement : clipElements) {
                AudioClip clip = parseClip(clipElement);
                track.addClip(clip);
            }
        }

        return track;
    }

    private AudioClip parseClip(Element elem) {
        String name = elem.getAttribute("name");
        if (name.isEmpty()) {
            name = "Untitled Clip";
        }

        double startBeat = Math.max(0.0, parseDoubleAttr(elem, "start-beat", 0.0));
        double durationBeats = Math.max(0.001, parseDoubleAttr(elem, "duration-beats", 1.0));
        String sourceFile = elem.getAttribute("source-file");
        if (sourceFile.isEmpty()) {
            sourceFile = null;
        }

        AudioClip clip = new AudioClip(name, startBeat, durationBeats, sourceFile);
        clip.setSourceOffsetBeats(parseDoubleAttr(elem, "source-offset", 0.0));
        clip.setGainDb(parseDoubleAttr(elem, "gain-db", 0.0));
        clip.setReversed(parseBooleanAttr(elem, "reversed"));
        clip.setFadeInBeats(Math.max(0.0, parseDoubleAttr(elem, "fade-in-beats", 0.0)));
        clip.setFadeOutBeats(Math.max(0.0, parseDoubleAttr(elem, "fade-out-beats", 0.0)));
        clip.setFadeInCurveType(parseFadeCurveType(elem.getAttribute("fade-in-curve")));
        clip.setFadeOutCurveType(parseFadeCurveType(elem.getAttribute("fade-out-curve")));

        return clip;
    }

    private void parseMixer(Element mixerElem, DawProject project) {
        // Parse master channel
        List<Element> masterElements = getDirectChildElements(mixerElem, "master");
        if (!masterElements.isEmpty()) {
            applyMixerChannelAttrs(masterElements.getFirst(), project.getMixer().getMasterChannel());
        }

        // Parse return buses — apply settings to existing return buses
        List<Element> returnBusesContainers = getDirectChildElements(mixerElem, "return-buses");
        if (!returnBusesContainers.isEmpty()) {
            List<Element> returnBusElements = getDirectChildElements(
                    returnBusesContainers.getFirst(), "return-bus");
            List<MixerChannel> existingBuses = project.getMixer().getReturnBuses();
            for (int i = 0; i < returnBusElements.size(); i++) {
                Element rbElem = returnBusElements.get(i);
                MixerChannel bus;
                if (i < existingBuses.size()) {
                    bus = existingBuses.get(i);
                } else {
                    String busName = rbElem.getAttribute("name");
                    if (busName.isEmpty()) {
                        busName = "Return " + (i + 1);
                    }
                    bus = project.getMixer().addReturnBus(busName);
                }
                applyMixerChannelAttrs(rbElem, bus);
            }
        }

        // Parse track mixer channels — apply settings to the channels created during track addition
        List<Element> channelsContainers = getDirectChildElements(mixerElem, "channels");
        if (!channelsContainers.isEmpty()) {
            List<Element> channelElements = getDirectChildElements(
                    channelsContainers.getFirst(), "channel");
            List<MixerChannel> existingChannels = project.getMixer().getChannels();
            int count = Math.min(channelElements.size(), existingChannels.size());
            for (int i = 0; i < count; i++) {
                applyMixerChannelAttrs(channelElements.get(i), existingChannels.get(i));
            }
        }
    }

    private void applyMixerChannelAttrs(Element elem, MixerChannel channel) {
        double volume = clampDouble(parseDoubleAttr(elem, "volume", 1.0), 0.0, 1.0);
        channel.setVolume(volume);

        double pan = clampDouble(parseDoubleAttr(elem, "pan", 0.0), -1.0, 1.0);
        channel.setPan(pan);

        channel.setMuted(parseBooleanAttr(elem, "muted"));
        channel.setSolo(parseBooleanAttr(elem, "solo"));

        double sendLevel = clampDouble(parseDoubleAttr(elem, "send-level", 0.0), 0.0, 1.0);
        channel.setSendLevel(sendLevel);

        channel.setPhaseInverted(parseBooleanAttr(elem, "phase-inverted"));
    }

    private TrackType parseTrackType(String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) {
            return TrackType.AUDIO;
        }
        try {
            return TrackType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            return TrackType.AUDIO;
        }
    }

    private FadeCurveType parseFadeCurveType(String value) {
        if (value == null || value.isEmpty()) {
            return FadeCurveType.LINEAR;
        }
        try {
            return FadeCurveType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return FadeCurveType.LINEAR;
        }
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
        String value = element.getAttribute(attr);
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
        String value = element.getAttribute(attr);
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
        String value = element.getAttribute(attr);
        return "true".equalsIgnoreCase(value);
    }
}
