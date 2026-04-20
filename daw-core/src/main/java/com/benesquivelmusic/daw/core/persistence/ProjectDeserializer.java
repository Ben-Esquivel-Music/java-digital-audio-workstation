package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.marker.MarkerType;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.mixer.snapshot.ChannelSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.InsertSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.SendSnapshot;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Deserializes a {@link DawProject} from XML produced by {@link ProjectSerializer}.
 *
 * <p>The deserializer reconstructs the complete project state including tracks,
 * clips, mixer settings, transport state, and project metadata. Malformed or
 * missing values fall back to sensible defaults to ensure graceful degradation.</p>
 */
public final class ProjectDeserializer {

    /** Maximum valid azimuth value — just below 360° (exclusive upper bound). */
    private static final double MAX_AZIMUTH_EXCLUSIVE = Math.nextDown(360.0);

    private final List<String> missingFiles = new ArrayList<>();

    /**
     * Returns a list of audio file paths referenced by the project that were
     * not found on the file system at the time of deserialization.
     *
     * <p>This list is populated during {@link #deserialize(String)} and can
     * be used by the UI layer to present a "missing files" notification with
     * relinking options. The list is cleared at the start of each
     * deserialization call.</p>
     *
     * @return an unmodifiable view of missing file paths
     */
    public List<String> getMissingFiles() {
        return Collections.unmodifiableList(missingFiles);
    }

    /**
     * Deserializes a project from an XML string.
     *
     * @param xml the XML content produced by {@link ProjectSerializer}
     * @return the reconstructed project
     * @throws IOException if the XML cannot be parsed
     */
    public DawProject deserialize(String xml) throws IOException {
        missingFiles.clear();
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

        // Parse markers
        List<Element> markersContainers = getDirectChildElements(root, "markers");
        if (!markersContainers.isEmpty()) {
            parseMarkers(markersContainers.getFirst(), project);
        }

        // Parse track groups
        List<Element> trackGroupsContainers = getDirectChildElements(root, "track-groups");
        if (!trackGroupsContainers.isEmpty()) {
            parseTrackGroups(trackGroupsContainers.getFirst(), project);
        }

        // Parse metronome settings
        List<Element> metronomeElements = getDirectChildElements(root, "metronome");
        if (!metronomeElements.isEmpty()) {
            parseMetronome(metronomeElements.getFirst(), project.getMetronome());
        }

        // Parse reference tracks
        List<Element> refTrackContainers = getDirectChildElements(root, "reference-tracks");
        if (!refTrackContainers.isEmpty()) {
            parseReferenceTrackManager(refTrackContainers.getFirst(), project);
        }

        // Parse room configuration (sound wave telemetry)
        List<Element> roomConfigElements = getDirectChildElements(root, "room-configuration");
        if (!roomConfigElements.isEmpty()) {
            parseRoomConfiguration(roomConfigElements.getFirst(), project);
        }

        // Parse mixer snapshots
        List<Element> snapshotsContainers = getDirectChildElements(root, "mixer-snapshots");
        if (!snapshotsContainers.isEmpty()) {
            parseMixerSnapshots(snapshotsContainers.getFirst(), project);
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

        if (elem.hasAttribute("punch-start-frames") && elem.hasAttribute("punch-end-frames")) {
            long punchStart = parseLongAttr(elem, "punch-start-frames", -1L);
            long punchEnd = parseLongAttr(elem, "punch-end-frames", -1L);
            boolean punchEnabled = parseBooleanAttr(elem, "punch-enabled");
            if (punchStart >= 0 && punchEnd > punchStart) {
                transport.setPunchRegion(new PunchRegion(punchStart, punchEnd, punchEnabled));
            }
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
        track.setCollapsed(parseBooleanAttr(elem, "collapsed"));

        String automationModeStr = elem.getAttribute("automation-mode");
        if (!automationModeStr.isEmpty()) {
            try {
                track.setAutomationMode(AutomationMode.valueOf(automationModeStr));
            } catch (IllegalArgumentException ignored) {
                // keep default mode on invalid value
            }
        }

        int inputDevice = parseIntAttr(elem, "input-device", Track.NO_INPUT_DEVICE);
        if (inputDevice >= Track.NO_INPUT_DEVICE) {
            track.setInputDeviceIndex(inputDevice);
        }

        // Restore per-track input channel routing
        int irChannel = parseIntAttr(elem, "input-routing-channel", Integer.MIN_VALUE);
        if (irChannel != Integer.MIN_VALUE) {
            int irCount = parseIntAttr(elem, "input-routing-count", 2);
            try {
                track.setInputRouting(new InputRouting(irChannel, irCount));
            } catch (IllegalArgumentException ignored) {
                // keep default routing on invalid values
            }
        }

        String midiInputDevice = elem.getAttribute("midi-input-device");
        if (!midiInputDevice.isEmpty()) {
            track.setMidiInputDeviceName(midiInputDevice);
        }

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

        // Parse SoundFont assignment
        List<Element> sfElements = getDirectChildElements(elem, "soundfont-assignment");
        if (!sfElements.isEmpty()) {
            SoundFontAssignment assignment = parseSoundFontAssignment(sfElements.getFirst());
            if (assignment != null) {
                track.setSoundFontAssignment(assignment);
            }
        }

        // Parse automation data
        List<Element> automationContainers = getDirectChildElements(elem, "automation");
        if (!automationContainers.isEmpty()) {
            parseAutomationData(automationContainers.getFirst(), track);
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

        if (sourceFile != null) {
            try {
                if (!Files.exists(Path.of(sourceFile))) {
                    missingFiles.add(sourceFile);
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                missingFiles.add(sourceFile);
            }
        }

        AudioClip clip = new AudioClip(name, startBeat, durationBeats, sourceFile);
        clip.setSourceOffsetBeats(parseDoubleAttr(elem, "source-offset", 0.0));
        clip.setGainDb(parseDoubleAttr(elem, "gain-db", 0.0));
        clip.setReversed(parseBooleanAttr(elem, "reversed"));
        clip.setFadeInBeats(Math.max(0.0, parseDoubleAttr(elem, "fade-in-beats", 0.0)));
        clip.setFadeOutBeats(Math.max(0.0, parseDoubleAttr(elem, "fade-out-beats", 0.0)));
        clip.setFadeInCurveType(parseFadeCurveType(elem.getAttribute("fade-in-curve")));
        clip.setFadeOutCurveType(parseFadeCurveType(elem.getAttribute("fade-out-curve")));

        double stretchRatio = parseDoubleAttr(elem, "time-stretch-ratio", 1.0);
        if (stretchRatio >= 0.25 && stretchRatio <= 4.0) {
            clip.setTimeStretchRatio(stretchRatio);
        }

        double pitchShift = parseDoubleAttr(elem, "pitch-shift-semitones", 0.0);
        if (pitchShift >= -24.0 && pitchShift <= 24.0) {
            clip.setPitchShiftSemitones(pitchShift);
        }

        clip.setStretchQuality(parseStretchQuality(elem.getAttribute("stretch-quality")));

        return clip;
    }

    private SoundFontAssignment parseSoundFontAssignment(Element elem) {
        String pathStr = elem.getAttribute("path");
        if (pathStr.isEmpty()) {
            return null;
        }
        int bank = parseIntAttr(elem, "bank", 0);
        int program = parseIntAttr(elem, "program", 0);
        String presetName = elem.getAttribute("preset-name");
        if (presetName.isEmpty()) {
            presetName = "Preset " + bank + ":" + program;
        }
        try {
            return new SoundFontAssignment(Path.of(pathStr), bank, program, presetName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void parseMixer(Element mixerElem, DawProject project) {
        // Parse master channel
        List<Element> masterElements = getDirectChildElements(mixerElem, "master");
        if (!masterElements.isEmpty()) {
            applyMixerChannelAttrs(masterElements.getFirst(), project.getMixer().getMasterChannel(), project);
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
                applyMixerChannelAttrs(rbElem, bus, project);
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
                applyMixerChannelAttrs(channelElements.get(i), existingChannels.get(i), project);
            }
        }
    }

    private void applyMixerChannelAttrs(Element elem, MixerChannel channel, DawProject project) {
        double volume = clampDouble(parseDoubleAttr(elem, "volume", 1.0), 0.0, 1.0);
        channel.setVolume(volume);

        double pan = clampDouble(parseDoubleAttr(elem, "pan", 0.0), -1.0, 1.0);
        channel.setPan(pan);

        channel.setMuted(parseBooleanAttr(elem, "muted"));
        channel.setSolo(parseBooleanAttr(elem, "solo"));

        double sendLevel = clampDouble(parseDoubleAttr(elem, "send-level", 0.0), 0.0, 1.0);
        channel.setSendLevel(sendLevel);

        channel.setPhaseInverted(parseBooleanAttr(elem, "phase-inverted"));

        // Restore output routing (defaults to MASTER if not present)
        int orChannel = parseIntAttr(elem, "output-routing-channel", Integer.MIN_VALUE);
        if (orChannel != Integer.MIN_VALUE) {
            int orCount = parseIntAttr(elem, "output-routing-count", 2);
            try {
                channel.setOutputRouting(new OutputRouting(orChannel, orCount));
            } catch (IllegalArgumentException ignored) {
                // keep default routing on invalid values
            }
        }

        // Parse insert effect slots
        List<Element> insertsContainers = getDirectChildElements(elem, "inserts");
        if (!insertsContainers.isEmpty()) {
            List<Element> insertElements = getDirectChildElements(insertsContainers.getFirst(), "insert");
            for (Element insertElem : insertElements) {
                parseInsertSlot(insertElem, channel, project);
            }
        }

        // Parse sends
        List<Element> sendsContainers = getDirectChildElements(elem, "sends");
        if (!sendsContainers.isEmpty()) {
            List<Element> sendElements = getDirectChildElements(sendsContainers.getFirst(), "send");
            List<MixerChannel> returnBuses = project.getMixer().getReturnBuses();
            for (Element sendElem : sendElements) {
                parseSend(sendElem, channel, returnBuses);
            }
        }

        // Parse per-track CPU budget
        List<Element> budgetElements = getDirectChildElements(elem, "cpu-budget");
        if (!budgetElements.isEmpty()) {
            TrackCpuBudget budget = parseCpuBudget(budgetElements.getFirst());
            if (budget != null) {
                channel.setCpuBudget(budget);
            }
        }
    }

    private TrackCpuBudget parseCpuBudget(Element elem) {
        double maxFraction = parseDoubleAttr(elem, "max-fraction", Double.NaN);
        if (Double.isNaN(maxFraction) || maxFraction <= 0.0 || maxFraction > 1.0) {
            return null;
        }
        String policyStr = elem.getAttribute("policy");
        DegradationPolicy policy = switch (policyStr) {
            case "bypass-expensive" -> new DegradationPolicy.BypassExpensive();
            case "reduce-oversampling" -> {
                int factor = parseIntAttr(elem, "fallback-factor", 1);
                yield new DegradationPolicy.ReduceOversampling(Math.max(1, factor));
            }
            case "substitute-simple-kernel" -> {
                String kernelId = elem.getAttribute("kernel-id");
                if (kernelId == null || kernelId.isBlank()) {
                    yield new DegradationPolicy.DoNothing();
                }
                yield new DegradationPolicy.SubstituteSimpleKernel(kernelId);
            }
            case "do-nothing" -> new DegradationPolicy.DoNothing();
            default -> new DegradationPolicy.DoNothing();
        };
        return new TrackCpuBudget(maxFraction, policy);
    }

    private void parseInsertSlot(Element elem, MixerChannel channel, DawProject project) {
        String effectTypeStr = elem.getAttribute("effect-type");
        if (effectTypeStr.isEmpty()) {
            return;
        }
        try {
            InsertEffectType effectType = InsertEffectType.valueOf(effectTypeStr);
            if (effectType == InsertEffectType.CLAP_PLUGIN) {
                return;
            }
            int channels = project.getFormat().channels();
            double sampleRate = project.getFormat().sampleRate();
            InsertSlot slot = InsertEffectFactory.createSlot(effectType, channels, sampleRate);
            if (parseBooleanAttr(elem, "bypassed")) {
                slot.setBypassed(true);
            }

            // Restore parameter values.
            //
            // New-format presets carry a human-readable "name" attribute and
            // are restored via the reflective preset serializer (which clamps
            // out-of-range values and skips unknown keys for forward
            // compatibility). Legacy projects saved before the preset system
            // use the id-keyed path.
            List<Element> paramElements = getDirectChildElements(elem, "parameter");
            if (!paramElements.isEmpty()) {
                Map<String, Double> namedValues = new LinkedHashMap<>();
                List<Element> legacyIdElements = new ArrayList<>();
                for (Element paramElem : paramElements) {
                    String paramName = paramElem.getAttribute("name");
                    double paramValue = parseDoubleAttr(paramElem, "value", Double.NaN);
                    if (Double.isNaN(paramValue)) {
                        continue;
                    }
                    if (paramName != null && !paramName.isEmpty()) {
                        namedValues.put(paramName, paramValue);
                    } else {
                        legacyIdElements.add(paramElem);
                    }
                }
                if (!namedValues.isEmpty()
                        && ReflectivePresetSerializer.isSupported(slot.getProcessor())) {
                    ReflectivePresetSerializer.restore(slot.getProcessor(), namedValues);
                }
                if (!legacyIdElements.isEmpty()) {
                    BiConsumer<Integer, Double> handler =
                            InsertEffectFactory.createParameterHandler(effectType, slot.getProcessor());
                    for (Element paramElem : legacyIdElements) {
                        int paramId = parseIntAttr(paramElem, "id", -1);
                        double paramValue = parseDoubleAttr(paramElem, "value", Double.NaN);
                        if (paramId >= 0 && !Double.isNaN(paramValue)) {
                            handler.accept(paramId, paramValue);
                        }
                    }
                }
            }

            // Restore sidechain source reference
            String scSourceStr = elem.getAttribute("sidechain-source");
            if (!scSourceStr.isEmpty()) {
                MixerChannel scSource = resolveSidechainSource(scSourceStr, project.getMixer());
                if (scSource != null) {
                    slot.setSidechainSource(scSource);
                }
            }

            channel.addInsert(slot);
        } catch (IllegalArgumentException ignored) {
            // skip unknown effect types
        }
    }

    private MixerChannel resolveSidechainSource(String ref, Mixer mixer) {
        if (ref.startsWith("channel:")) {
            int index = parseIndex(ref.substring("channel:".length()));
            List<MixerChannel> channels = mixer.getChannels();
            if (index >= 0 && index < channels.size()) {
                return channels.get(index);
            }
        } else if (ref.startsWith("return:")) {
            int index = parseIndex(ref.substring("return:".length()));
            List<MixerChannel> returnBuses = mixer.getReturnBuses();
            if (index >= 0 && index < returnBuses.size()) {
                return returnBuses.get(index);
            }
        }
        return null;
    }

    private static int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void parseSend(Element sendElem, MixerChannel channel, List<MixerChannel> returnBuses) {
        int targetIndex = parseIntAttr(sendElem, "target-index", -1);
        if (targetIndex < 0 || targetIndex >= returnBuses.size()) {
            return;
        }
        MixerChannel target = returnBuses.get(targetIndex);
        double level = clampDouble(parseDoubleAttr(sendElem, "level", 0.0), 0.0, 1.0);
        SendMode mode = parseSendMode(sendElem.getAttribute("mode"));
        channel.addSend(new Send(target, level, mode));
    }

    private void parseAutomationData(Element automationElem, Track track) {
        List<Element> laneElements = getDirectChildElements(automationElem, "lane");
        for (Element laneElem : laneElements) {
            String paramStr = laneElem.getAttribute("parameter");
            AutomationParameter parameter = parseAutomationParameter(paramStr);
            if (parameter == null) {
                continue;
            }
            AutomationLane lane = track.getAutomationData().getOrCreateLane(parameter);
            lane.setVisible(parseBooleanAttr(laneElem, "visible"));

            List<Element> pointElements = getDirectChildElements(laneElem, "point");
            for (Element pointElem : pointElements) {
                double time = parseDoubleAttr(pointElem, "time", 0.0);
                double value = parseDoubleAttr(pointElem, "value", parameter.getDefaultValue());
                InterpolationMode interpolation = parseInterpolationMode(
                        pointElem.getAttribute("interpolation"));

                if (time >= 0.0 && parameter.isValidValue(value)) {
                    lane.addPoint(new AutomationPoint(time, value, interpolation));
                }
            }
        }
    }

    private void parseMarkers(Element markersElem, DawProject project) {
        List<Element> markerElements = getDirectChildElements(markersElem, "marker");
        for (Element markerElem : markerElements) {
            String name = markerElem.getAttribute("name");
            if (name.isEmpty()) {
                name = "Marker";
            }
            double position = parseDoubleAttr(markerElem, "position", 0.0);
            MarkerType type = parseMarkerType(markerElem.getAttribute("type"));
            if (position >= 0.0) {
                Marker marker = new Marker(name, position, type);
                String color = markerElem.getAttribute("color");
                if (!color.isEmpty()) {
                    marker.setColor(color);
                }
                project.getMarkerManager().addMarker(marker);
            }
        }

        List<Element> rangeElements = getDirectChildElements(markersElem, "marker-range");
        for (Element rangeElem : rangeElements) {
            String name = rangeElem.getAttribute("name");
            if (name.isEmpty()) {
                name = "Range";
            }
            double start = parseDoubleAttr(rangeElem, "start", 0.0);
            double end = parseDoubleAttr(rangeElem, "end", 1.0);
            MarkerType type = parseMarkerType(rangeElem.getAttribute("type"));
            if (start >= 0.0 && end > start) {
                MarkerRange range = new MarkerRange(name, start, end, type);
                String color = rangeElem.getAttribute("color");
                if (!color.isEmpty()) {
                    range.setColor(color);
                }
                project.getMarkerManager().addMarkerRange(range);
            }
        }
    }

    private void parseTrackGroups(Element groupsElem, DawProject project) {
        List<Element> groupElements = getDirectChildElements(groupsElem, "group");
        List<Track> allTracks = project.getTracks();
        for (Element groupElem : groupElements) {
            String name = groupElem.getAttribute("name");
            if (name.isEmpty()) {
                name = "Group";
            }
            List<Element> memberElements = getDirectChildElements(groupElem, "member");
            List<Track> memberTracks = new ArrayList<>();
            for (Element memberElem : memberElements) {
                int trackIndex = parseIntAttr(memberElem, "track-index", -1);
                if (trackIndex >= 0 && trackIndex < allTracks.size()) {
                    memberTracks.add(allTracks.get(trackIndex));
                }
            }
            if (!memberTracks.isEmpty()) {
                project.createTrackGroup(name, memberTracks);
            }
        }
    }

    private void parseMetronome(Element elem, Metronome metronome) {
        metronome.setEnabled(parseBooleanAttr(elem, "enabled"));
        float volume = (float) clampDouble(parseDoubleAttr(elem, "volume", 1.0), 0.0, 1.0);
        metronome.setVolume(volume);
        metronome.setClickSound(parseClickSound(elem.getAttribute("click-sound")));
        metronome.setSubdivision(parseSubdivision(elem.getAttribute("subdivision")));
    }

    private void parseReferenceTrackManager(Element elem, DawProject project) {
        ReferenceTrackManager manager = project.getReferenceTrackManager();

        List<Element> refTrackElements = getDirectChildElements(elem, "reference-track");
        for (Element refTrackElem : refTrackElements) {
            String name = refTrackElem.getAttribute("name");
            if (name.isEmpty()) {
                name = "Reference";
            }
            String sourceFile = refTrackElem.getAttribute("source-file");
            if (sourceFile.isEmpty()) {
                continue;
            }

            try {
                if (!Files.exists(Path.of(sourceFile))) {
                    missingFiles.add(sourceFile);
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                missingFiles.add(sourceFile);
            }

            ReferenceTrack refTrack = new ReferenceTrack(name, sourceFile);
            refTrack.setGainOffsetDb(parseDoubleAttr(refTrackElem, "gain-offset-db", 0.0));
            refTrack.setLoopEnabled(parseBooleanAttr(refTrackElem, "loop-enabled"));
            double loopStart = parseDoubleAttr(refTrackElem, "loop-start", 0.0);
            double loopEnd = parseDoubleAttr(refTrackElem, "loop-end", 0.0);
            if (loopEnd > loopStart && loopStart >= 0.0) {
                refTrack.setLoopRegion(loopStart, loopEnd);
            }
            refTrack.setIntegratedLufs(parseDoubleAttr(refTrackElem, "integrated-lufs", -120.0));
            manager.addReferenceTrack(refTrack);
        }

        int activeIndex = parseIntAttr(elem, "active-index", 0);
        if (activeIndex >= 0 && activeIndex < manager.getReferenceTrackCount()) {
            manager.setActiveIndex(activeIndex);
        }
        manager.setReferenceActive(parseBooleanAttr(elem, "reference-active"));
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

    private StretchQuality parseStretchQuality(String value) {
        if (value == null || value.isEmpty()) {
            return StretchQuality.MEDIUM;
        }
        try {
            return StretchQuality.valueOf(value);
        } catch (IllegalArgumentException e) {
            return StretchQuality.MEDIUM;
        }
    }

    private InterpolationMode parseInterpolationMode(String value) {
        if (value == null || value.isEmpty()) {
            return InterpolationMode.LINEAR;
        }
        try {
            return InterpolationMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return InterpolationMode.LINEAR;
        }
    }

    private AutomationParameter parseAutomationParameter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return AutomationParameter.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private MarkerType parseMarkerType(String value) {
        if (value == null || value.isEmpty()) {
            return MarkerType.SECTION;
        }
        try {
            return MarkerType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return MarkerType.SECTION;
        }
    }

    private SendMode parseSendMode(String value) {
        if (value == null || value.isEmpty()) {
            return SendMode.POST_FADER;
        }
        try {
            return SendMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SendMode.POST_FADER;
        }
    }

    private ClickSound parseClickSound(String value) {
        if (value == null || value.isEmpty()) {
            return ClickSound.WOODBLOCK;
        }
        try {
            return ClickSound.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ClickSound.WOODBLOCK;
        }
    }

    private Subdivision parseSubdivision(String value) {
        if (value == null || value.isEmpty()) {
            return Subdivision.QUARTER;
        }
        try {
            return Subdivision.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Subdivision.QUARTER;
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

    private static long parseLongAttr(Element element, String attr, long defaultValue) {
        String value = element.getAttribute(attr);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBooleanAttr(Element element, String attr) {
        String value = element.getAttribute(attr);
        return "true".equalsIgnoreCase(value);
    }

    private void parseRoomConfiguration(Element elem, DawProject project) {
        double width = parseDoubleAttr(elem, "width", 0);
        double length = parseDoubleAttr(elem, "length", 0);
        double height = parseDoubleAttr(elem, "height", 0);

        if (width <= 0 || length <= 0 || height <= 0) {
            return;
        }

        String materialStr = elem.getAttribute("wall-material");
        WallMaterial defaultMaterial;
        try {
            defaultMaterial = WallMaterial.valueOf(materialStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            defaultMaterial = WallMaterial.DRYWALL;
        }

        // Per-surface material map. Legacy projects only have the
        // wall-material attribute, in which case we broadcast it to all six
        // surfaces.
        SurfaceMaterialMap materialMap = parseSurfaceMaterialMap(elem, defaultMaterial);

        CeilingShape ceiling = parseCeilingShape(elem, height);
        RoomDimensions dimensions = new RoomDimensions(width, length, ceiling);
        RoomConfiguration config = new RoomConfiguration(dimensions, materialMap);

        for (Element sourceElem : getDirectChildElements(elem, "sound-source")) {
            String name = sourceElem.getAttribute("name");
            if (name.isEmpty()) continue;
            double x = parseDoubleAttr(sourceElem, "x", 0);
            double y = parseDoubleAttr(sourceElem, "y", 0);
            double z = parseDoubleAttr(sourceElem, "z", 0);
            double powerDb = parseDoubleAttr(sourceElem, "power-db", 85.0);
            config.addSoundSource(new SoundSource(name, new Position3D(x, y, z), powerDb));
        }

        for (Element micElem : getDirectChildElements(elem, "microphone")) {
            String name = micElem.getAttribute("name");
            if (name.isEmpty()) continue;
            double x = parseDoubleAttr(micElem, "x", 0);
            double y = parseDoubleAttr(micElem, "y", 0);
            double z = parseDoubleAttr(micElem, "z", 0);
            double rawAzimuth = parseDoubleAttr(micElem, "azimuth", 0);
            double normalizedAzimuth = rawAzimuth % 360.0;
            if (normalizedAzimuth < 0) {
                normalizedAzimuth += 360.0;
            }
            double azimuth = clampDouble(normalizedAzimuth, 0.0, MAX_AZIMUTH_EXCLUSIVE);
            double elevation = clampDouble(parseDoubleAttr(micElem, "elevation", 0), -90, 90);
            config.addMicrophone(new MicrophonePlacement(name, new Position3D(x, y, z),
                    azimuth, elevation));
        }

        for (Element memberElem : getDirectChildElements(elem, "audience-member")) {
            String name = memberElem.getAttribute("name");
            if (name.isEmpty()) continue;
            double x = parseDoubleAttr(memberElem, "x", 0);
            double y = parseDoubleAttr(memberElem, "y", 0);
            double z = parseDoubleAttr(memberElem, "z", 0);
            config.addAudienceMember(new AudienceMember(name, new Position3D(x, y, z)));
        }

        for (Element tElem : getDirectChildElements(elem, "applied-treatment")) {
            AcousticTreatment treatment = parseAppliedTreatment(tElem);
            if (treatment != null) {
                config.addAppliedTreatment(treatment);
            }
        }

        project.setRoomConfiguration(config);
    }

    private AcousticTreatment parseAppliedTreatment(Element t) {
        TreatmentKind kind;
        try {
            kind = TreatmentKind.valueOf(t.getAttribute("kind"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        double w = parseDoubleAttr(t, "size-w", 0);
        double h = parseDoubleAttr(t, "size-h", 0);
        if (w <= 0 || h <= 0) return null;
        double improvement = parseDoubleAttr(t, "improvement-lufs", 0);
        String location = t.getAttribute("location");
        WallAttachment attach;
        try {
            if ("in-corner".equals(location)) {
                RoomSurface a = RoomSurface.valueOf(t.getAttribute("surface-a"));
                RoomSurface b = RoomSurface.valueOf(t.getAttribute("surface-b"));
                double z = parseDoubleAttr(t, "z", 0);
                attach = new WallAttachment.InCorner(a, b, z);
            } else {
                RoomSurface surface = RoomSurface.valueOf(t.getAttribute("surface"));
                double u = parseDoubleAttr(t, "u", 0);
                double v = parseDoubleAttr(t, "v", 0);
                attach = new WallAttachment.OnSurface(surface, u, v);
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        Rectangle2D size = new Rectangle2D.Double(-w / 2.0, -h / 2.0, w, h);
        return new AcousticTreatment(kind, attach, size, improvement);
    }

    /**
     * Parses the {@code <surface-materials>} child element. When the element
     * is absent (legacy projects), {@code defaultMaterial} is broadcast to
     * every surface so the result is bit-identical to the pre-per-surface
     * single-material behaviour.
     */
    private SurfaceMaterialMap parseSurfaceMaterialMap(Element configElem, WallMaterial defaultMaterial) {
        List<Element> elems = getDirectChildElements(configElem, "surface-materials");
        if (elems.isEmpty()) {
            return new SurfaceMaterialMap(defaultMaterial);
        }
        Element materials = elems.getFirst();
        return new SurfaceMaterialMap(
                parseSurfaceMaterial(materials, "floor", defaultMaterial),
                parseSurfaceMaterial(materials, "front-wall", defaultMaterial),
                parseSurfaceMaterial(materials, "back-wall", defaultMaterial),
                parseSurfaceMaterial(materials, "left-wall", defaultMaterial),
                parseSurfaceMaterial(materials, "right-wall", defaultMaterial),
                parseSurfaceMaterial(materials, "ceiling", defaultMaterial));
    }

    private static WallMaterial parseSurfaceMaterial(Element elem, String attr, WallMaterial fallback) {
        String raw = elem.getAttribute(attr);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return WallMaterial.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private CeilingShape parseCeilingShape(Element configElem, double legacyHeight) {
        List<Element> ceilings = getDirectChildElements(configElem, "ceiling");
        if (ceilings.isEmpty()) {
            // Backward compatibility: older project files store only the
            // scalar height attribute and imply a flat ceiling.
            return new CeilingShape.Flat(legacyHeight);
        }
        Element ceiling = ceilings.getFirst();
        String kindStr = ceiling.getAttribute("kind");
        CeilingShape.Kind kind;
        try {
            kind = CeilingShape.Kind.valueOf(kindStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            return new CeilingShape.Flat(legacyHeight);
        }
        try {
            return switch (kind) {
                case FLAT -> new CeilingShape.Flat(
                        parseDoubleAttr(ceiling, "height", legacyHeight));
                case DOMED -> new CeilingShape.Domed(
                        parseDoubleAttr(ceiling, "base-height", legacyHeight),
                        parseDoubleAttr(ceiling, "apex-height", legacyHeight));
                case BARREL_VAULT -> new CeilingShape.BarrelVault(
                        parseDoubleAttr(ceiling, "base-height", legacyHeight),
                        parseDoubleAttr(ceiling, "apex-height", legacyHeight),
                        parseAxis(ceiling, "axis", CeilingShape.Axis.X));
                case CATHEDRAL -> new CeilingShape.Cathedral(
                        parseDoubleAttr(ceiling, "eave-height", legacyHeight),
                        parseDoubleAttr(ceiling, "ridge-height", legacyHeight),
                        parseAxis(ceiling, "axis", CeilingShape.Axis.X));
                case ANGLED -> new CeilingShape.Angled(
                        parseDoubleAttr(ceiling, "low-height", legacyHeight),
                        parseDoubleAttr(ceiling, "high-height", legacyHeight),
                        parseAxis(ceiling, "axis", CeilingShape.Axis.X));
            };
        } catch (IllegalArgumentException e) {
            // Invalid values (e.g. apex < base) fall back to a flat ceiling.
            return new CeilingShape.Flat(legacyHeight);
        }
    }

    private CeilingShape.Axis parseAxis(Element elem, String attr, CeilingShape.Axis fallback) {
        String raw = elem.getAttribute(attr);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return CeilingShape.Axis.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void parseMixerSnapshots(Element elem, DawProject project) {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();

        for (Element snapshotElem : getDirectChildElements(elem, "snapshot")) {
            MixerSnapshot snapshot = parseSnapshot(snapshotElem);
            if (snapshot != null && manager.getSnapshotCount() < MixerSnapshotManager.MAX_SNAPSHOTS) {
                manager.addSnapshot(snapshot);
            }
        }

        List<Element> slotA = getDirectChildElements(elem, "slot-a");
        if (!slotA.isEmpty()) {
            MixerSnapshot snap = parseSnapshot(slotA.getFirst());
            if (snap != null) {
                manager.setSlot(MixerSnapshotManager.Slot.A, snap);
            }
        }
        List<Element> slotB = getDirectChildElements(elem, "slot-b");
        if (!slotB.isEmpty()) {
            MixerSnapshot snap = parseSnapshot(slotB.getFirst());
            if (snap != null) {
                manager.setSlot(MixerSnapshotManager.Slot.B, snap);
            }
        }

        String active = elem.getAttribute("active-slot");
        if (!active.isEmpty()) {
            try {
                manager.setActiveSlot(MixerSnapshotManager.Slot.valueOf(active));
            } catch (IllegalArgumentException ignored) {
                // keep default active slot on invalid value
            }
        }
    }

    private MixerSnapshot parseSnapshot(Element elem) {
        String name = elem.getAttribute("name");
        if (name.isEmpty()) {
            name = "Untitled";
        }
        Instant timestamp = parseInstant(elem.getAttribute("timestamp"), Instant.now());

        List<Element> masterElems = getDirectChildElements(elem, "master");
        if (masterElems.isEmpty()) {
            return null;
        }
        ChannelSnapshot master = parseChannelSnapshot(masterElems.getFirst());
        if (master == null) {
            return null;
        }

        List<ChannelSnapshot> channels = new ArrayList<>();
        List<Element> channelContainers = getDirectChildElements(elem, "channels");
        if (!channelContainers.isEmpty()) {
            for (Element ce : getDirectChildElements(channelContainers.getFirst(), "channel")) {
                ChannelSnapshot cs = parseChannelSnapshot(ce);
                if (cs != null) {
                    channels.add(cs);
                }
            }
        }

        List<ChannelSnapshot> returnBuses = new ArrayList<>();
        List<Element> returnContainers = getDirectChildElements(elem, "return-buses");
        if (!returnContainers.isEmpty()) {
            for (Element re : getDirectChildElements(returnContainers.getFirst(), "return-bus")) {
                ChannelSnapshot cs = parseChannelSnapshot(re);
                if (cs != null) {
                    returnBuses.add(cs);
                }
            }
        }

        return new MixerSnapshot(name, timestamp, master, channels, returnBuses);
    }

    private ChannelSnapshot parseChannelSnapshot(Element elem) {
        try {
            double volume = clampDouble(parseDoubleAttr(elem, "volume", 1.0), 0.0, 1.0);
            double pan = clampDouble(parseDoubleAttr(elem, "pan", 0.0), -1.0, 1.0);
            boolean muted = parseBooleanAttr(elem, "muted");
            boolean solo = parseBooleanAttr(elem, "solo");
            boolean phaseInverted = parseBooleanAttr(elem, "phase-inverted");
            double sendLevel = clampDouble(parseDoubleAttr(elem, "send-level", 0.0), 0.0, 1.0);

            OutputRouting routing = OutputRouting.MASTER;
            int orChannel = parseIntAttr(elem, "output-routing-channel", Integer.MIN_VALUE);
            if (orChannel != Integer.MIN_VALUE) {
                int orCount = parseIntAttr(elem, "output-routing-count", 2);
                try {
                    routing = new OutputRouting(orChannel, orCount);
                } catch (IllegalArgumentException ignored) {
                    routing = OutputRouting.MASTER;
                }
            }

            List<InsertSnapshot> inserts = new ArrayList<>();
            List<Element> insertContainers = getDirectChildElements(elem, "inserts");
            if (!insertContainers.isEmpty()) {
                for (Element ie : getDirectChildElements(insertContainers.getFirst(), "insert")) {
                    InsertSnapshot is = parseInsertSnapshot(ie);
                    if (is != null) {
                        inserts.add(is);
                    }
                }
            }

            List<SendSnapshot> sends = new ArrayList<>();
            List<Element> sendContainers = getDirectChildElements(elem, "sends");
            if (!sendContainers.isEmpty()) {
                for (Element se : getDirectChildElements(sendContainers.getFirst(), "send")) {
                    int targetIndex = parseIntAttr(se, "target-index", -1);
                    double level = clampDouble(parseDoubleAttr(se, "level", 0.0), 0.0, 1.0);
                    SendMode mode = parseSendMode(se.getAttribute("mode"));
                    if (targetIndex >= 0) {
                        sends.add(new SendSnapshot(targetIndex, level, mode));
                    }
                }
            }

            TrackCpuBudget cpuBudget = null;
            List<Element> budgetElements = getDirectChildElements(elem, "cpu-budget");
            if (!budgetElements.isEmpty()) {
                cpuBudget = parseCpuBudget(budgetElements.getFirst());
            }

            return new ChannelSnapshot(volume, pan, muted, solo, phaseInverted,
                    sendLevel, routing, inserts, sends, cpuBudget);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private InsertSnapshot parseInsertSnapshot(Element elem) {
        boolean bypassed = parseBooleanAttr(elem, "bypassed");
        InsertEffectType effectType = null;
        String effectTypeStr = elem.getAttribute("effect-type");
        if (!effectTypeStr.isEmpty()) {
            try {
                effectType = InsertEffectType.valueOf(effectTypeStr);
            } catch (IllegalArgumentException ignored) {
                effectType = null;
            }
        }

        java.util.Map<Integer, Double> params = new java.util.LinkedHashMap<>();
        for (Element pe : getDirectChildElements(elem, "parameter")) {
            int id = parseIntAttr(pe, "id", -1);
            double value = parseDoubleAttr(pe, "value", Double.NaN);
            if (id >= 0 && !Double.isNaN(value)) {
                params.put(id, value);
            }
        }
        return new InsertSnapshot(effectType, bypassed, params);
    }
}
