package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerManager;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.telemetry.AudienceMember;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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
import java.util.Map;

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
        buildMixer(document, root, project);
        buildMarkers(document, root, project.getMarkerManager());
        buildTrackGroups(document, root, project);
        buildMetronome(document, root, project.getMetronome());
        buildReferenceTrackManager(document, root, project.getReferenceTrackManager());
        buildRoomConfiguration(document, root, project.getRoomConfiguration());
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
        elem.setAttribute("input-device", String.valueOf(track.getInputDeviceIndex()));
        elem.setAttribute("collapsed", String.valueOf(track.isCollapsed()));
        elem.setAttribute("automation-mode", track.getAutomationMode().name());

        String midiInputDeviceName = track.getMidiInputDeviceName();
        if (midiInputDeviceName != null) {
            elem.setAttribute("midi-input-device", midiInputDeviceName);
        }

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

        buildAutomationData(document, elem, track.getAutomationData());

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
        elem.setAttribute("time-stretch-ratio", String.valueOf(clip.getTimeStretchRatio()));
        elem.setAttribute("pitch-shift-semitones", String.valueOf(clip.getPitchShiftSemitones()));
        elem.setAttribute("stretch-quality", clip.getStretchQuality().name());
        if (clip.getSourceFilePath() != null) {
            elem.setAttribute("source-file", clip.getSourceFilePath());
        }
        return elem;
    }

    private void buildMixer(Document document, Element root, DawProject project) {
        Mixer mixer = project.getMixer();
        Element mixerElem = document.createElement("mixer");
        root.appendChild(mixerElem);

        mixerElem.appendChild(buildMixerChannelElement(document, "master", mixer.getMasterChannel(), mixer));

        Element channelsElem = document.createElement("channels");
        mixerElem.appendChild(channelsElem);
        for (MixerChannel channel : mixer.getChannels()) {
            channelsElem.appendChild(buildMixerChannelElement(document, "channel", channel, mixer));
        }

        Element returnBusesElem = document.createElement("return-buses");
        mixerElem.appendChild(returnBusesElem);
        for (MixerChannel returnBus : mixer.getReturnBuses()) {
            returnBusesElem.appendChild(buildMixerChannelElement(document, "return-bus", returnBus, mixer));
        }
    }

    private Element buildMixerChannelElement(Document document, String tagName,
                                             MixerChannel channel, Mixer mixer) {
        Element elem = document.createElement(tagName);
        elem.setAttribute("name", channel.getName());
        elem.setAttribute("volume", String.valueOf(channel.getVolume()));
        elem.setAttribute("pan", String.valueOf(channel.getPan()));
        elem.setAttribute("muted", String.valueOf(channel.isMuted()));
        elem.setAttribute("solo", String.valueOf(channel.isSolo()));
        elem.setAttribute("send-level", String.valueOf(channel.getSendLevel()));
        elem.setAttribute("phase-inverted", String.valueOf(channel.isPhaseInverted()));

        // Serialize insert effect slots
        List<InsertSlot> insertSlots = channel.getInsertSlots();
        if (!insertSlots.isEmpty()) {
            Element insertsElem = document.createElement("inserts");
            elem.appendChild(insertsElem);
            for (InsertSlot slot : insertSlots) {
                Element slotElem = document.createElement("insert");
                slotElem.setAttribute("name", slot.getName());
                slotElem.setAttribute("bypassed", String.valueOf(slot.isBypassed()));
                InsertEffectType effectType = slot.getEffectType();
                if (effectType != null) {
                    slotElem.setAttribute("effect-type", effectType.name());
                    Map<Integer, Double> paramValues =
                            InsertEffectFactory.getParameterValues(effectType, slot.getProcessor());
                    for (Map.Entry<Integer, Double> entry : paramValues.entrySet()) {
                        Element paramElem = document.createElement("parameter");
                        paramElem.setAttribute("id", String.valueOf(entry.getKey()));
                        paramElem.setAttribute("value", String.valueOf(entry.getValue()));
                        slotElem.appendChild(paramElem);
                    }
                }
                insertsElem.appendChild(slotElem);
            }
        }

        // Serialize sends
        List<Send> sends = channel.getSends();
        if (!sends.isEmpty()) {
            Element sendsElem = document.createElement("sends");
            elem.appendChild(sendsElem);
            List<MixerChannel> returnBuses = mixer.getReturnBuses();
            for (Send send : sends) {
                Element sendElem = document.createElement("send");
                sendElem.setAttribute("level", String.valueOf(send.getLevel()));
                sendElem.setAttribute("mode", send.getMode().name());
                int targetIndex = returnBuses.indexOf(send.getTarget());
                sendElem.setAttribute("target-index", String.valueOf(targetIndex));
                sendsElem.appendChild(sendElem);
            }
        }

        return elem;
    }

    private void buildAutomationData(Document document, Element trackElem, AutomationData automationData) {
        Map<AutomationParameter, AutomationLane> lanes = automationData.getLanes();
        if (lanes.isEmpty()) {
            return;
        }

        Element automationElem = document.createElement("automation");
        trackElem.appendChild(automationElem);

        for (Map.Entry<AutomationParameter, AutomationLane> entry : lanes.entrySet()) {
            AutomationLane lane = entry.getValue();
            Element laneElem = document.createElement("lane");
            laneElem.setAttribute("parameter", entry.getKey().name());
            laneElem.setAttribute("visible", String.valueOf(lane.isVisible()));
            automationElem.appendChild(laneElem);

            for (AutomationPoint point : lane.getPoints()) {
                Element pointElem = document.createElement("point");
                pointElem.setAttribute("time", String.valueOf(point.getTimeInBeats()));
                pointElem.setAttribute("value", String.valueOf(point.getValue()));
                pointElem.setAttribute("interpolation", point.getInterpolationMode().name());
                laneElem.appendChild(pointElem);
            }
        }
    }

    private void buildMarkers(Document document, Element root, MarkerManager markerManager) {
        List<Marker> markers = markerManager.getMarkers();
        List<MarkerRange> ranges = markerManager.getMarkerRanges();
        if (markers.isEmpty() && ranges.isEmpty()) {
            return;
        }

        Element markersElem = document.createElement("markers");
        root.appendChild(markersElem);

        for (Marker marker : markers) {
            Element markerElem = document.createElement("marker");
            markerElem.setAttribute("name", marker.getName());
            markerElem.setAttribute("position", String.valueOf(marker.getPositionInBeats()));
            markerElem.setAttribute("type", marker.getType().name());
            markerElem.setAttribute("color", marker.getColor());
            markersElem.appendChild(markerElem);
        }

        for (MarkerRange range : ranges) {
            Element rangeElem = document.createElement("marker-range");
            rangeElem.setAttribute("name", range.getName());
            rangeElem.setAttribute("start", String.valueOf(range.getStartPositionInBeats()));
            rangeElem.setAttribute("end", String.valueOf(range.getEndPositionInBeats()));
            rangeElem.setAttribute("type", range.getType().name());
            rangeElem.setAttribute("color", range.getColor());
            markersElem.appendChild(rangeElem);
        }
    }

    private void buildTrackGroups(Document document, Element root, DawProject project) {
        List<TrackGroup> groups = project.getTrackGroups();
        if (groups.isEmpty()) {
            return;
        }

        Element groupsElem = document.createElement("track-groups");
        root.appendChild(groupsElem);

        List<Track> allTracks = project.getTracks();
        for (TrackGroup group : groups) {
            Element groupElem = document.createElement("group");
            groupElem.setAttribute("name", group.getName());
            groupsElem.appendChild(groupElem);

            for (Track track : group.getTracks()) {
                int trackIndex = allTracks.indexOf(track);
                if (trackIndex >= 0) {
                    Element memberElem = document.createElement("member");
                    memberElem.setAttribute("track-index", String.valueOf(trackIndex));
                    groupElem.appendChild(memberElem);
                }
            }
        }
    }

    private void buildMetronome(Document document, Element root, Metronome metronome) {
        Element metronomeElem = document.createElement("metronome");
        metronomeElem.setAttribute("enabled", String.valueOf(metronome.isEnabled()));
        metronomeElem.setAttribute("volume", String.valueOf(metronome.getVolume()));
        metronomeElem.setAttribute("click-sound", metronome.getClickSound().name());
        metronomeElem.setAttribute("subdivision", metronome.getSubdivision().name());
        root.appendChild(metronomeElem);
    }

    private void buildReferenceTrackManager(Document document, Element root,
                                             ReferenceTrackManager manager) {
        List<ReferenceTrack> refTracks = manager.getReferenceTracks();
        if (refTracks.isEmpty()) {
            return;
        }

        Element refElem = document.createElement("reference-tracks");
        refElem.setAttribute("active-index", String.valueOf(manager.getActiveIndex()));
        refElem.setAttribute("reference-active", String.valueOf(manager.isReferenceActive()));
        root.appendChild(refElem);

        for (ReferenceTrack ref : refTracks) {
            Element trackElem = document.createElement("reference-track");
            trackElem.setAttribute("name", ref.getName());
            trackElem.setAttribute("source-file", ref.getSourceFilePath());
            trackElem.setAttribute("gain-offset-db", String.valueOf(ref.getGainOffsetDb()));
            trackElem.setAttribute("loop-enabled", String.valueOf(ref.isLoopEnabled()));
            trackElem.setAttribute("loop-start", String.valueOf(ref.getLoopStartInBeats()));
            trackElem.setAttribute("loop-end", String.valueOf(ref.getLoopEndInBeats()));
            trackElem.setAttribute("integrated-lufs", String.valueOf(ref.getIntegratedLufs()));
            refElem.appendChild(trackElem);
        }
    }

    private void buildRoomConfiguration(Document document, Element root,
                                         RoomConfiguration config) {
        if (config == null) {
            return;
        }

        Element configElem = document.createElement("room-configuration");
        configElem.setAttribute("width", String.valueOf(config.getDimensions().width()));
        configElem.setAttribute("length", String.valueOf(config.getDimensions().length()));
        configElem.setAttribute("height", String.valueOf(config.getDimensions().height()));
        configElem.setAttribute("wall-material", config.getWallMaterial().name());
        root.appendChild(configElem);

        for (SoundSource source : config.getSoundSources()) {
            Element sourceElem = document.createElement("sound-source");
            sourceElem.setAttribute("name", source.name());
            Position3D pos = source.position();
            sourceElem.setAttribute("x", String.valueOf(pos.x()));
            sourceElem.setAttribute("y", String.valueOf(pos.y()));
            sourceElem.setAttribute("z", String.valueOf(pos.z()));
            sourceElem.setAttribute("power-db", String.valueOf(source.powerDb()));
            configElem.appendChild(sourceElem);
        }

        for (MicrophonePlacement mic : config.getMicrophones()) {
            Element micElem = document.createElement("microphone");
            micElem.setAttribute("name", mic.name());
            Position3D pos = mic.position();
            micElem.setAttribute("x", String.valueOf(pos.x()));
            micElem.setAttribute("y", String.valueOf(pos.y()));
            micElem.setAttribute("z", String.valueOf(pos.z()));
            micElem.setAttribute("azimuth", String.valueOf(mic.azimuth()));
            micElem.setAttribute("elevation", String.valueOf(mic.elevation()));
            configElem.appendChild(micElem);
        }

        for (AudienceMember member : config.getAudienceMembers()) {
            Element memberElem = document.createElement("audience-member");
            memberElem.setAttribute("name", member.name());
            Position3D pos = member.position();
            memberElem.setAttribute("x", String.valueOf(pos.x()));
            memberElem.setAttribute("y", String.valueOf(pos.y()));
            memberElem.setAttribute("z", String.valueOf(pos.z()));
            configElem.appendChild(memberElem);
        }
    }
}
