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
import com.benesquivelmusic.daw.core.mixer.snapshot.ChannelSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.InsertSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.SendSnapshot;
import com.benesquivelmusic.daw.core.mixer.spatial.BedBus;
import com.benesquivelmusic.daw.core.mixer.spatial.BedBusManager;
import com.benesquivelmusic.daw.core.mixer.spatial.BedChannelRouting;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.project.edit.NudgeSettings;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackFoldState;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.AudienceMember;
import com.benesquivelmusic.daw.sdk.telemetry.CeilingShape;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SourceDirectivity;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
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
        root.setAttribute("version", Integer.toString(
                com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION));
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
        buildMixerSnapshots(document, root, project.getMixerSnapshotManager());
        buildBedBus(document, root, project.getBedBusManager());
        buildRippleMode(document, root, project);
        buildNudgeSettings(document, root, project);
        buildLoudnessTarget(document, root, project);
    }

    private void buildLoudnessTarget(Document document, Element root, DawProject project) {
        LoudnessTarget target = project.getLoudnessTarget();
        Element elem = document.createElement("loudness-target");
        elem.setAttribute("value", target.name());
        root.appendChild(elem);
    }

    private void buildNudgeSettings(Document document, Element root, DawProject project) {
        NudgeSettings ns = project.getNudgeSettings();
        Element elem = document.createElement("nudge-settings");
        elem.setAttribute("unit", ns.unit().name());
        elem.setAttribute("amount", String.valueOf(ns.amount()));
        root.appendChild(elem);
    }

    private void buildRippleMode(Document document, Element root, DawProject project) {
        // Always emit the element so that round-trip produces identical
        // output regardless of mode. OFF is the default and harmless to
        // preserve explicitly — it documents the user's intent.
        Element elem = document.createElement("ripple-mode");
        elem.setAttribute("value", project.getRippleMode().name());
        root.appendChild(elem);
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
        PunchRegion punch = transport.getPunchRegion();
        if (punch != null) {
            elem.setAttribute("punch-start-frames", String.valueOf(punch.startFrames()));
            elem.setAttribute("punch-end-frames", String.valueOf(punch.endFrames()));
            elem.setAttribute("punch-enabled", String.valueOf(punch.enabled()));
        }
        PreRollPostRoll prpr = transport.getPreRollPostRoll();
        if (prpr != null && !prpr.equals(PreRollPostRoll.DISABLED)) {
            elem.setAttribute("pre-roll-bars", String.valueOf(prpr.preBars()));
            elem.setAttribute("post-roll-bars", String.valueOf(prpr.postBars()));
            elem.setAttribute("pre-roll-enabled", String.valueOf(prpr.enabled()));
        }
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
        elem.setAttribute("input-routing-channel", String.valueOf(track.getInputRouting().firstChannel()));
        elem.setAttribute("input-routing-count", String.valueOf(track.getInputRouting().channelCount()));
        elem.setAttribute("collapsed", String.valueOf(track.isCollapsed()));
        elem.setAttribute("automation-mode", track.getAutomationMode().name());
        elem.setAttribute("input-monitoring", track.getInputMonitoring().name());

        TrackFoldState foldState = track.getFoldState();
        elem.setAttribute("automation-folded", String.valueOf(foldState.automationFolded()));
        elem.setAttribute("takes-folded", String.valueOf(foldState.takesFolded()));
        elem.setAttribute("midi-folded", String.valueOf(foldState.midiFolded()));
        elem.setAttribute("header-height-override",
                String.valueOf(foldState.headerHeightOverride()));

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
        if (clip.isLocked()) {
            elem.setAttribute("locked", "true");
        }
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
        clip.gainEnvelope().ifPresent(env -> {
            Element envElem = document.createElement("gain-envelope");
            for (com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope.BreakpointDb bp
                    : env.breakpoints()) {
                Element bpElem = document.createElement("breakpoint");
                bpElem.setAttribute("frame-offset", String.valueOf(bp.frameOffsetInClip()));
                bpElem.setAttribute("db-gain", String.valueOf(bp.dbGain()));
                bpElem.setAttribute("curve", bp.curve().name());
                envElem.appendChild(bpElem);
            }
            elem.appendChild(envElem);
        });
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

        Element cueBusesElem = document.createElement("cue-buses");
        mixerElem.appendChild(cueBusesElem);
        for (CueBus bus : project.getCueBusManager().getCueBuses()) {
            Element busElem = document.createElement("cue-bus");
            busElem.setAttribute("id", bus.id().toString());
            busElem.setAttribute("label", bus.label());
            busElem.setAttribute("hardware-output-index", String.valueOf(bus.hardwareOutputIndex()));
            busElem.setAttribute("master-gain", String.valueOf(bus.masterGain()));
            for (CueSend send : bus.sends()) {
                Element sendElem = document.createElement("cue-send");
                sendElem.setAttribute("track-id", send.trackId().toString());
                sendElem.setAttribute("gain", String.valueOf(send.gain()));
                sendElem.setAttribute("pan", String.valueOf(send.pan()));
                sendElem.setAttribute("pre-fader", String.valueOf(send.preFader()));
                busElem.appendChild(sendElem);
            }
            cueBusesElem.appendChild(busElem);
        }

        // VCA groups — write-only persistence, mirroring the cue-bus pattern.
        // Legacy projects without this element simply load with no VCAs (the
        // manager starts empty), satisfying the "legacy projects load with no
        // VCAs" goal in the issue.
        Element vcaGroupsElem = document.createElement("vca-groups");
        mixerElem.appendChild(vcaGroupsElem);
        for (VcaGroup group : project.getVcaGroupManager().getVcaGroups()) {
            Element groupElem = document.createElement("vca-group");
            groupElem.setAttribute("id", group.id().toString());
            groupElem.setAttribute("label", group.label());
            groupElem.setAttribute("master-gain-db", String.valueOf(group.masterGainDb()));
            if (group.color() != null) {
                groupElem.setAttribute("color", group.color().getHexColor());
            }
            for (java.util.UUID memberId : group.memberChannelIds()) {
                Element memberElem = document.createElement("vca-member");
                memberElem.setAttribute("channel-id", memberId.toString());
                groupElem.appendChild(memberElem);
            }
            vcaGroupsElem.appendChild(groupElem);
        }

        // Channel links — persisted in project XML, mirroring the vca-groups pattern.
        // Legacy projects without this element simply load with no links because
        // the manager starts empty.
        Element channelLinksElem = document.createElement("channel-links");
        mixerElem.appendChild(channelLinksElem);
        for (ChannelLink link : project.getChannelLinkManager().getLinks()) {
            Element linkElem = document.createElement("channel-link");
            linkElem.setAttribute("left-channel-id", link.leftChannelId().toString());
            linkElem.setAttribute("right-channel-id", link.rightChannelId().toString());
            linkElem.setAttribute("mode", link.mode().name());
            linkElem.setAttribute("link-faders", String.valueOf(link.linkFaders()));
            linkElem.setAttribute("link-pans", String.valueOf(link.linkPans()));
            linkElem.setAttribute("link-mute-solo", String.valueOf(link.linkMuteSolo()));
            linkElem.setAttribute("link-inserts", String.valueOf(link.linkInserts()));
            linkElem.setAttribute("link-sends", String.valueOf(link.linkSends()));
            channelLinksElem.appendChild(linkElem);
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
        elem.setAttribute("solo-safe", String.valueOf(channel.isSoloSafe()));
        elem.setAttribute("send-level", String.valueOf(channel.getSendLevel()));
        elem.setAttribute("phase-inverted", String.valueOf(channel.isPhaseInverted()));
        if (!channel.getOutputRouting().isMaster()) {
            elem.setAttribute("output-routing-channel",
                    String.valueOf(channel.getOutputRouting().firstChannel()));
            elem.setAttribute("output-routing-count",
                    String.valueOf(channel.getOutputRouting().channelCount()));
        }

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
                    if (ReflectivePresetSerializer.isSupported(slot.getProcessor())) {
                        // Annotated processor — emit name-keyed parameters via
                        // reflective snapshot. This is robust to parameter-id
                        // renumbering and keeps save/load working automatically
                        // when new @ProcessorParam fields are added.
                        Map<String, Double> named =
                                ReflectivePresetSerializer.snapshot(slot.getProcessor());
                        for (Map.Entry<String, Double> entry : named.entrySet()) {
                            Element paramElem = document.createElement("parameter");
                            paramElem.setAttribute("name", entry.getKey());
                            paramElem.setAttribute("value", String.valueOf(entry.getValue()));
                            slotElem.appendChild(paramElem);
                        }
                    } else {
                        // Fallback for processors that do not (yet) declare
                        // @ProcessorParam annotations: preserve the legacy
                        // id-keyed serialization.
                        Map<Integer, Double> paramValues =
                                InsertEffectFactory.getParameterValues(effectType, slot.getProcessor());
                        for (Map.Entry<Integer, Double> entry : paramValues.entrySet()) {
                            Element paramElem = document.createElement("parameter");
                            paramElem.setAttribute("id", String.valueOf(entry.getKey()));
                            paramElem.setAttribute("value", String.valueOf(entry.getValue()));
                            slotElem.appendChild(paramElem);
                        }
                    }
                }
                // Serialize sidechain source reference
                MixerChannel scSource = slot.getSidechainSource();
                if (scSource != null) {
                    int scIndex = mixer.getChannels().indexOf(scSource);
                    if (scIndex >= 0) {
                        slotElem.setAttribute("sidechain-source", "channel:" + scIndex);
                    } else {
                        int rbIndex = mixer.getReturnBuses().indexOf(scSource);
                        if (rbIndex >= 0) {
                            slotElem.setAttribute("sidechain-source", "return:" + rbIndex);
                        }
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
                sendElem.setAttribute("tap", send.getTap().name());
                int targetIndex = returnBuses.indexOf(send.getTarget());
                sendElem.setAttribute("target-index", String.valueOf(targetIndex));
                sendsElem.appendChild(sendElem);
            }
        }

        // Serialize per-track CPU budget
        TrackCpuBudget cpuBudget = channel.getCpuBudget();
        if (cpuBudget != null) {
            elem.appendChild(buildCpuBudgetElement(document, cpuBudget));
        }

        return elem;
    }

    private Element buildCpuBudgetElement(Document document, TrackCpuBudget cpuBudget) {
        Element budgetElem = document.createElement("cpu-budget");
        budgetElem.setAttribute("max-fraction", String.valueOf(cpuBudget.maxFractionOfBlock()));
        DegradationPolicy policy = cpuBudget.onOverBudget();
        switch (policy) {
            case DegradationPolicy.BypassExpensive _ ->
                budgetElem.setAttribute("policy", "bypass-expensive");
            case DegradationPolicy.ReduceOversampling r -> {
                budgetElem.setAttribute("policy", "reduce-oversampling");
                budgetElem.setAttribute("fallback-factor", String.valueOf(r.fallbackFactor()));
            }
            case DegradationPolicy.SubstituteSimpleKernel s -> {
                budgetElem.setAttribute("policy", "substitute-simple-kernel");
                budgetElem.setAttribute("kernel-id", s.kernelId());
            }
            case DegradationPolicy.DoNothing _ ->
                budgetElem.setAttribute("policy", "do-nothing");
        }
        return budgetElem;
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

        ClickOutput clickOutput = metronome.getClickOutput();
        if (clickOutput != null && !clickOutput.equals(ClickOutput.MAIN_MIX_ONLY)) {
            Element co = document.createElement("click-output");
            co.setAttribute("hardware-channel-index",
                    String.valueOf(clickOutput.hardwareChannelIndex()));
            co.setAttribute("gain", String.valueOf(clickOutput.gain()));
            co.setAttribute("main-mix-enabled",
                    String.valueOf(clickOutput.mainMixEnabled()));
            co.setAttribute("side-output-enabled",
                    String.valueOf(clickOutput.sideOutputEnabled()));
            metronomeElem.appendChild(co);
        }
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
            // Story 042 — Atmos A/B comparison: persist optional immersive
            // bed format and per-channel trim values discovered by the
            // AtmosAbComparator's auto-trim feature. Both attributes are
            // omitted for stereo references so older files stay clean.
            if (ref.getImmersiveFormat() != null) {
                trackElem.setAttribute("immersive-format",
                        ref.getImmersiveFormat().name());
            }
            double[] trims = ref.getPerChannelTrimDb();
            if (trims != null && trims.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < trims.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(trims[i]);
                }
                trackElem.setAttribute("per-channel-trim-db", sb.toString());
            }
            refElem.appendChild(trackElem);
        }
    }

    private void buildRoomConfiguration(Document document, Element root,
                                         RoomConfiguration config) {
        if (config == null) {
            return;
        }

        Element configElem = document.createElement("room-configuration");
        RoomDimensions dims = config.getDimensions();
        configElem.setAttribute("width", String.valueOf(dims.width()));
        configElem.setAttribute("length", String.valueOf(dims.length()));
        // Legacy "height" attribute (maxHeight) kept for readers that
        // predate CeilingShape support. New readers use the <ceiling> child.
        configElem.setAttribute("height", String.valueOf(dims.height()));
        // Legacy "wall-material" attribute kept for readers that predate
        // SurfaceMaterialMap support; it carries the predominant
        // (front-wall) material so older readers still produce a sane
        // single-material configuration on load.
        configElem.setAttribute("wall-material", config.getWallMaterial().name());
        SurfaceMaterialMap materialMap = config.getMaterialMap();
        Element materialsElem = document.createElement("surface-materials");
        materialsElem.setAttribute("floor", materialMap.floor().name());
        materialsElem.setAttribute("front-wall", materialMap.frontWall().name());
        materialsElem.setAttribute("back-wall", materialMap.backWall().name());
        materialsElem.setAttribute("left-wall", materialMap.leftWall().name());
        materialsElem.setAttribute("right-wall", materialMap.rightWall().name());
        materialsElem.setAttribute("ceiling", materialMap.ceiling().name());
        configElem.appendChild(materialsElem);
        configElem.appendChild(buildCeilingElement(document, dims.ceiling()));
        root.appendChild(configElem);

        for (SoundSource source : config.getSoundSources()) {
            Element sourceElem = document.createElement("sound-source");
            sourceElem.setAttribute("name", source.name());
            Position3D pos = source.position();
            sourceElem.setAttribute("x", String.valueOf(pos.x()));
            sourceElem.setAttribute("y", String.valueOf(pos.y()));
            sourceElem.setAttribute("z", String.valueOf(pos.z()));
            sourceElem.setAttribute("power-db", String.valueOf(source.powerDb()));
            // Directivity pattern — omitted for the OMNIDIRECTIONAL default
            // so older files remain readable and tests comparing raw XML
            // are not disturbed when the user never changed it.
            SourceDirectivity directivity =
                    config.getSourceDirectivity(source.name());
            if (directivity != SourceDirectivity.OMNIDIRECTIONAL) {
                sourceElem.setAttribute("directivity", directivity.name());
            }
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

        for (AcousticTreatment treatment : config.getAppliedTreatments()) {
            Element t = document.createElement("applied-treatment");
            t.setAttribute("kind", treatment.kind().name());
            t.setAttribute("size-w", String.valueOf(treatment.sizeMeters().getWidth()));
            t.setAttribute("size-h", String.valueOf(treatment.sizeMeters().getHeight()));
            t.setAttribute("improvement-lufs",
                    String.valueOf(treatment.predictedImprovementLufs()));
            switch (treatment.location()) {
                case WallAttachment.OnSurface on -> {
                    t.setAttribute("location", "on-surface");
                    t.setAttribute("surface", on.surface().name());
                    t.setAttribute("u", String.valueOf(on.u()));
                    t.setAttribute("v", String.valueOf(on.v()));
                }
                case WallAttachment.InCorner in -> {
                    t.setAttribute("location", "in-corner");
                    t.setAttribute("surface-a", in.surfaceA().name());
                    t.setAttribute("surface-b", in.surfaceB().name());
                    t.setAttribute("z", String.valueOf(in.z()));
                }
            }
            configElem.appendChild(t);
        }
    }

    private Element buildCeilingElement(Document document, CeilingShape shape) {
        Element ceiling = document.createElement("ceiling");
        ceiling.setAttribute("kind", shape.kind().name());
        switch (shape) {
            case CeilingShape.Flat flat ->
                    ceiling.setAttribute("height", String.valueOf(flat.height()));
            case CeilingShape.Domed dome -> {
                ceiling.setAttribute("base-height", String.valueOf(dome.baseHeight()));
                ceiling.setAttribute("apex-height", String.valueOf(dome.apexHeight()));
            }
            case CeilingShape.BarrelVault vault -> {
                ceiling.setAttribute("base-height", String.valueOf(vault.baseHeight()));
                ceiling.setAttribute("apex-height", String.valueOf(vault.apexHeight()));
                ceiling.setAttribute("axis", vault.axis().name());
            }
            case CeilingShape.Cathedral c -> {
                ceiling.setAttribute("eave-height", String.valueOf(c.eaveHeight()));
                ceiling.setAttribute("ridge-height", String.valueOf(c.ridgeHeight()));
                ceiling.setAttribute("axis", c.ridgeAxis().name());
            }
            case CeilingShape.Angled a -> {
                ceiling.setAttribute("low-height", String.valueOf(a.lowHeight()));
                ceiling.setAttribute("high-height", String.valueOf(a.highHeight()));
                ceiling.setAttribute("axis", a.slopeAxis().name());
            }
        }
        return ceiling;
    }

    private void buildMixerSnapshots(Document document, Element root,
                                     MixerSnapshotManager manager) {
        List<MixerSnapshot> snapshots = manager.getSnapshots();
        MixerSnapshot slotA = manager.getSlotA();
        MixerSnapshot slotB = manager.getSlotB();
        if (snapshots.isEmpty() && slotA == null && slotB == null) {
            return;
        }

        Element root0 = document.createElement("mixer-snapshots");
        root0.setAttribute("active-slot", manager.getActiveSlot().name());
        root.appendChild(root0);

        for (MixerSnapshot snapshot : snapshots) {
            root0.appendChild(buildSnapshotElement(document, "snapshot", snapshot));
        }
        if (slotA != null) {
            Element slotElem = buildSnapshotElement(document, "slot-a", slotA);
            root0.appendChild(slotElem);
        }
        if (slotB != null) {
            Element slotElem = buildSnapshotElement(document, "slot-b", slotB);
            root0.appendChild(slotElem);
        }
    }

    private Element buildSnapshotElement(Document document, String tagName, MixerSnapshot snapshot) {
        Element elem = document.createElement(tagName);
        elem.setAttribute("name", snapshot.name());
        elem.setAttribute("timestamp", snapshot.timestamp().toString());

        elem.appendChild(buildChannelSnapshotElement(document, "master", snapshot.master()));

        Element channelsElem = document.createElement("channels");
        elem.appendChild(channelsElem);
        for (ChannelSnapshot cs : snapshot.channels()) {
            channelsElem.appendChild(buildChannelSnapshotElement(document, "channel", cs));
        }

        Element returnsElem = document.createElement("return-buses");
        elem.appendChild(returnsElem);
        for (ChannelSnapshot cs : snapshot.returnBuses()) {
            returnsElem.appendChild(buildChannelSnapshotElement(document, "return-bus", cs));
        }
        return elem;
    }

    private Element buildChannelSnapshotElement(Document document, String tagName, ChannelSnapshot cs) {
        Element elem = document.createElement(tagName);
        elem.setAttribute("volume", String.valueOf(cs.volume()));
        elem.setAttribute("pan", String.valueOf(cs.pan()));
        elem.setAttribute("muted", String.valueOf(cs.muted()));
        elem.setAttribute("solo", String.valueOf(cs.solo()));
        elem.setAttribute("phase-inverted", String.valueOf(cs.phaseInverted()));
        elem.setAttribute("send-level", String.valueOf(cs.sendLevel()));
        if (!cs.outputRouting().isMaster()) {
            elem.setAttribute("output-routing-channel",
                    String.valueOf(cs.outputRouting().firstChannel()));
            elem.setAttribute("output-routing-count",
                    String.valueOf(cs.outputRouting().channelCount()));
        }

        if (!cs.inserts().isEmpty()) {
            Element insertsElem = document.createElement("inserts");
            elem.appendChild(insertsElem);
            for (InsertSnapshot is : cs.inserts()) {
                Element insertElem = document.createElement("insert");
                insertElem.setAttribute("bypassed", String.valueOf(is.bypassed()));
                if (is.effectType() != null) {
                    insertElem.setAttribute("effect-type", is.effectType().name());
                }
                for (Map.Entry<Integer, Double> entry : is.parameters().entrySet()) {
                    Element paramElem = document.createElement("parameter");
                    paramElem.setAttribute("id", String.valueOf(entry.getKey()));
                    paramElem.setAttribute("value", String.valueOf(entry.getValue()));
                    insertElem.appendChild(paramElem);
                }
                insertsElem.appendChild(insertElem);
            }
        }

        if (!cs.sends().isEmpty()) {
            Element sendsElem = document.createElement("sends");
            elem.appendChild(sendsElem);
            for (SendSnapshot ss : cs.sends()) {
                Element sendElem = document.createElement("send");
                sendElem.setAttribute("target-index", String.valueOf(ss.targetIndex()));
                sendElem.setAttribute("level", String.valueOf(ss.level()));
                sendElem.setAttribute("mode", ss.mode().name());
                sendsElem.appendChild(sendElem);
            }
        }

        TrackCpuBudget cpuBudget = cs.cpuBudget();
        if (cpuBudget != null) {
            elem.appendChild(buildCpuBudgetElement(document, cpuBudget));
        }

        return elem;
    }

    private void buildBedBus(Document document, Element root, BedBusManager manager) {
        BedBus bus = manager.getBedBus();
        Element bedBusElem = document.createElement("bed-bus");
        bedBusElem.setAttribute("id", bus.id().toString());
        bedBusElem.setAttribute("format", bus.format().name());
        double[] gains = bus.channelGainsDb();
        StringBuilder gainCsv = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) gainCsv.append(',');
            gainCsv.append(gains[i]);
        }
        bedBusElem.setAttribute("channel-gains-db", gainCsv.toString());

        Element routingsElem = document.createElement("bed-routings");
        for (BedChannelRouting routing : manager.getRoutings().values()) {
            Element routingElem = document.createElement("bed-routing");
            routingElem.setAttribute("track-id", routing.trackId().toString());
            routingElem.setAttribute("format", routing.format().name());
            double[] rGains = routing.channelGainsDb();
            StringBuilder rcsv = new StringBuilder();
            for (int i = 0; i < rGains.length; i++) {
                if (i > 0) rcsv.append(',');
                // Encode -inf as the literal token "-inf" — Double.toString
                // produces "-Infinity" which is verbose and not symmetric
                // with our parsing path; keep the encoding compact and
                // self-documenting.
                double db = rGains[i];
                if (Double.isInfinite(db) && db < 0) {
                    rcsv.append("-inf");
                } else {
                    rcsv.append(db);
                }
            }
            routingElem.setAttribute("channel-gains-db", rcsv.toString());
            routingsElem.appendChild(routingElem);
        }
        bedBusElem.appendChild(routingsElem);
        root.appendChild(bedBusElem);
    }

    @SuppressWarnings("unused")
    private static ImmersiveFormat parseFormat(String name) {
        try {
            return ImmersiveFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ImmersiveFormat.FORMAT_7_1_4;
        }
    }
}
