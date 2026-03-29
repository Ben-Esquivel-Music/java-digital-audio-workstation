package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.transport.Transport;

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
import java.io.StringWriter;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Serializes a {@link DawProject} into XML for persistence.
 *
 * <p>The serialized format captures the complete project state including
 * tracks, clips, mixer settings, transport state, and project metadata.
 * The output can be written to a {@code project.daw} file or used for
 * checkpoint snapshots.</p>
 */
public final class ProjectSerializer {

    /**
     * Serializes the given project to an XML string.
     *
     * @param project the project to serialize
     * @return the XML representation of the project
     * @throws IOException if serialization fails
     */
    public String serialize(DawProject project) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.newDocument();

            buildDocument(document, project);

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
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Failed to serialize project", e);
        }
    }

    private void buildDocument(Document document, DawProject project) {
        Element root = document.createElement("daw-project");
        root.setAttribute("version", "1");
        document.appendChild(root);

        buildMetadata(document, root, project);
        buildAudioFormat(document, root, project);
        buildTransport(document, root, project.getTransport());
        buildTracks(document, root, project);
        buildMixer(document, root, project.getMixer());
    }

    private void buildMetadata(Document document, Element root, DawProject project) {
        Element metadata = document.createElement("metadata");
        root.appendChild(metadata);

        Element name = document.createElement("name");
        name.setTextContent(project.getName());
        metadata.appendChild(name);

        ProjectMetadata meta = project.getMetadata();
        Element createdAt = document.createElement("created-at");
        createdAt.setTextContent(meta.createdAt().toString());
        metadata.appendChild(createdAt);

        Element lastModified = document.createElement("last-modified");
        lastModified.setTextContent(meta.lastModified().toString());
        metadata.appendChild(lastModified);
    }

    private void buildAudioFormat(Document document, Element root, DawProject project) {
        Element format = document.createElement("audio-format");
        format.setAttribute("sample-rate", String.valueOf(project.getFormat().sampleRate()));
        format.setAttribute("channels", String.valueOf(project.getFormat().channels()));
        format.setAttribute("bit-depth", String.valueOf(project.getFormat().bitDepth()));
        format.setAttribute("buffer-size", String.valueOf(project.getFormat().bufferSize()));
        root.appendChild(format);
    }

    private void buildTransport(Document document, Element root, Transport transport) {
        Element elem = document.createElement("transport");
        elem.setAttribute("tempo", String.valueOf(transport.getTempo()));
        elem.setAttribute("time-sig-numerator", String.valueOf(transport.getTimeSignatureNumerator()));
        elem.setAttribute("time-sig-denominator", String.valueOf(transport.getTimeSignatureDenominator()));
        elem.setAttribute("loop-enabled", String.valueOf(transport.isLoopEnabled()));
        elem.setAttribute("loop-start", String.valueOf(transport.getLoopStartInBeats()));
        elem.setAttribute("loop-end", String.valueOf(transport.getLoopEndInBeats()));
        elem.setAttribute("position", String.valueOf(transport.getPositionInBeats()));
        root.appendChild(elem);
    }

    private void buildTracks(Document document, Element root, DawProject project) {
        Element tracksElem = document.createElement("tracks");
        root.appendChild(tracksElem);

        for (Track track : project.getTracks()) {
            tracksElem.appendChild(buildTrackElement(document, track));
        }
    }

    private Element buildTrackElement(Document document, Track track) {
        Element elem = document.createElement("track");
        elem.setAttribute("id", track.getId());
        elem.setAttribute("name", track.getName());
        elem.setAttribute("type", track.getType().name());
        elem.setAttribute("volume", String.valueOf(track.getVolume()));
        elem.setAttribute("pan", String.valueOf(track.getPan()));
        elem.setAttribute("muted", String.valueOf(track.isMuted()));
        elem.setAttribute("solo", String.valueOf(track.isSolo()));
        elem.setAttribute("armed", String.valueOf(track.isArmed()));
        elem.setAttribute("phase-inverted", String.valueOf(track.isPhaseInverted()));
        elem.setAttribute("color", track.getColor().getHexColor());

        List<AudioClip> clips = track.getClips();
        if (!clips.isEmpty()) {
            Element clipsElem = document.createElement("clips");
            elem.appendChild(clipsElem);
            for (AudioClip clip : clips) {
                clipsElem.appendChild(buildClipElement(document, clip));
            }
        }

        SoundFontAssignment sfAssignment = track.getSoundFontAssignment();
        if (sfAssignment != null) {
            Element sfElem = document.createElement("soundfont-assignment");
            sfElem.setAttribute("path", sfAssignment.soundFontPath().toString().replace('\\', '/'));
            sfElem.setAttribute("bank", String.valueOf(sfAssignment.bank()));
            sfElem.setAttribute("program", String.valueOf(sfAssignment.program()));
            sfElem.setAttribute("preset-name", sfAssignment.presetName());
            elem.appendChild(sfElem);
        }

        return elem;
    }

    private Element buildClipElement(Document document, AudioClip clip) {
        Element elem = document.createElement("clip");
        elem.setAttribute("name", clip.getName());
        elem.setAttribute("start-beat", String.valueOf(clip.getStartBeat()));
        elem.setAttribute("duration-beats", String.valueOf(clip.getDurationBeats()));
        elem.setAttribute("source-offset", String.valueOf(clip.getSourceOffsetBeats()));
        elem.setAttribute("gain-db", String.valueOf(clip.getGainDb()));
        elem.setAttribute("reversed", String.valueOf(clip.isReversed()));
        elem.setAttribute("fade-in-beats", String.valueOf(clip.getFadeInBeats()));
        elem.setAttribute("fade-out-beats", String.valueOf(clip.getFadeOutBeats()));
        elem.setAttribute("fade-in-curve", clip.getFadeInCurveType().name());
        elem.setAttribute("fade-out-curve", clip.getFadeOutCurveType().name());
        if (clip.getSourceFilePath() != null) {
            elem.setAttribute("source-file", clip.getSourceFilePath());
        }
        return elem;
    }

    private void buildMixer(Document document, Element root, Mixer mixer) {
        Element mixerElem = document.createElement("mixer");
        root.appendChild(mixerElem);

        mixerElem.appendChild(buildMixerChannelElement(document, "master", mixer.getMasterChannel()));

        Element channelsElem = document.createElement("channels");
        mixerElem.appendChild(channelsElem);
        for (MixerChannel channel : mixer.getChannels()) {
            channelsElem.appendChild(buildMixerChannelElement(document, "channel", channel));
        }

        Element returnBusesElem = document.createElement("return-buses");
        mixerElem.appendChild(returnBusesElem);
        for (MixerChannel returnBus : mixer.getReturnBuses()) {
            returnBusesElem.appendChild(buildMixerChannelElement(document, "return-bus", returnBus));
        }
    }

    private Element buildMixerChannelElement(Document document, String tagName, MixerChannel channel) {
        Element elem = document.createElement(tagName);
        elem.setAttribute("name", channel.getName());
        elem.setAttribute("volume", String.valueOf(channel.getVolume()));
        elem.setAttribute("pan", String.valueOf(channel.getPan()));
        elem.setAttribute("muted", String.valueOf(channel.isMuted()));
        elem.setAttribute("solo", String.valueOf(channel.isSolo()));
        elem.setAttribute("send-level", String.valueOf(channel.getSendLevel()));
        elem.setAttribute("phase-inverted", String.valueOf(channel.isPhaseInverted()));
        return elem;
    }
}
