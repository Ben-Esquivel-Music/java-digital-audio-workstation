package com.benesquivelmusic.daw.core.session.dawproject;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Serializes a {@link SessionData} instance into DAWproject XML ({@code project.xml}).
 *
 * <p>The serializer maps internal session data to the DAWproject XML schema,
 * collecting warnings for any features that cannot be represented. The output
 * conforms to the DAWproject specification used by Bitwig and OpenDAW.</p>
 */
public final class DawProjectXmlSerializer {

    /**
     * Serializes the given session data to DAWproject XML, writing it to the
     * provided output stream.
     *
     * @param sessionData  the session data to serialize
     * @param outputStream the stream to write the XML to
     * @param warnings     a mutable list to which serialization warnings are appended
     * @throws IOException if an I/O or serialization error occurs
     */
    public void serialize(SessionData sessionData, OutputStream outputStream, List<String> warnings)
            throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            var document = builder.newDocument();

            buildDocument(document, sessionData, warnings);

            var transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            transformer.transform(new DOMSource(document), new StreamResult(outputStream));
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Failed to serialize DAWproject XML", e);
        }
    }

    private void buildDocument(Document document, SessionData sessionData, List<String> warnings) {
        var project = document.createElement("Project");
        project.setAttribute("version", "1.0");
        project.setAttribute("name", sessionData.projectName());
        if (sessionData.sampleRate() > 0) {
            project.setAttribute("sampleRate", String.valueOf(sessionData.sampleRate()));
        }
        document.appendChild(project);

        // Transport
        var transport = document.createElement("Transport");
        project.appendChild(transport);

        var tempo = document.createElement("Tempo");
        tempo.setAttribute("value", String.valueOf(sessionData.tempo()));
        transport.appendChild(tempo);

        var timeSignature = document.createElement("TimeSignature");
        timeSignature.setAttribute("numerator", String.valueOf(sessionData.timeSignatureNumerator()));
        timeSignature.setAttribute("denominator", String.valueOf(sessionData.timeSignatureDenominator()));
        transport.appendChild(timeSignature);

        // Structure (tracks)
        var structure = document.createElement("Structure");
        project.appendChild(structure);

        for (var track : sessionData.tracks()) {
            structure.appendChild(buildTrackElement(document, track, warnings));
        }
    }

    private Element buildTrackElement(Document document, SessionTrack track, List<String> warnings) {
        var trackElement = document.createElement("Track");
        trackElement.setAttribute("name", track.name());
        trackElement.setAttribute("contentType", mapTrackType(track.type()));

        // Channel (mixer settings)
        var channel = document.createElement("Channel");
        channel.setAttribute("volume", String.valueOf(track.volume()));
        channel.setAttribute("pan", String.valueOf(track.pan()));
        if (track.muted()) {
            channel.setAttribute("mute", "true");
        }
        if (track.solo()) {
            channel.setAttribute("solo", "true");
        }
        trackElement.appendChild(channel);

        // Lanes with Clips
        if (!track.clips().isEmpty()) {
            var lanes = document.createElement("Lanes");
            trackElement.appendChild(lanes);

            var clips = document.createElement("Clips");
            lanes.appendChild(clips);

            for (var clip : track.clips()) {
                clips.appendChild(buildClipElement(document, clip));
            }
        }

        return trackElement;
    }

    private Element buildClipElement(Document document, SessionClip clip) {
        var clipElement = document.createElement("Clip");
        clipElement.setAttribute("name", clip.name());
        clipElement.setAttribute("time", String.valueOf(clip.startBeat()));
        clipElement.setAttribute("duration", String.valueOf(clip.durationBeats()));

        if (clip.sourceFilePath() != null || clip.gainDb() != 0.0 || clip.sourceOffsetBeats() != 0.0) {
            var audio = document.createElement("Audio");
            if (clip.gainDb() != 0.0) {
                audio.setAttribute("gain", String.valueOf(clip.gainDb()));
            }
            if (clip.sourceOffsetBeats() != 0.0) {
                audio.setAttribute("offset", String.valueOf(clip.sourceOffsetBeats()));
            }
            clipElement.appendChild(audio);

            if (clip.sourceFilePath() != null) {
                var file = document.createElement("File");
                file.setAttribute("path", clip.sourceFilePath());
                audio.appendChild(file);
            }
        }

        return clipElement;
    }

    private String mapTrackType(String type) {
        if (type == null) {
            return "audio";
        }
        return switch (type.toUpperCase()) {
            case "AUDIO" -> "audio";
            case "MIDI" -> "notes";
            case "AUX" -> "aux";
            case "MASTER" -> "master";
            default -> "audio";
        };
    }
}
