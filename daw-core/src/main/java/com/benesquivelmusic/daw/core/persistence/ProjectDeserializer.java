package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.marker.MarkerType;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
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
import com.benesquivelmusic.daw.core.project.edit.NudgeUnit;
import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackFoldState;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
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
import java.util.UUID;
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
    private final MigrationRegistry migrationRegistry;
    private MigrationReport lastMigrationReport = MigrationReport.noOp(MigrationRegistry.CURRENT_VERSION);

    /**
     * Creates a deserializer backed by the production
     * {@link MigrationRegistry#defaultRegistry()}.
     */
    public ProjectDeserializer() {
        this(MigrationRegistry.defaultRegistry());
    }

    /**
     * Creates a deserializer backed by the given migration registry.
     * Used by tests to exercise alternate migration chains without
     * mutating global state.
     *
     * @param migrationRegistry registry to consult on every load
     */
    public ProjectDeserializer(MigrationRegistry migrationRegistry) {
        this.migrationRegistry = java.util.Objects.requireNonNull(
                migrationRegistry, "migrationRegistry");
    }

    /**
     * Returns the report produced by the most recent
     * {@link #deserialize(String)} call. Always non-null; use
     * {@link MigrationReport#wasMigrated()} to test whether any
     * migrations actually ran.
     */
    public MigrationReport getLastMigrationReport() {
        return lastMigrationReport;
    }

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
        lastMigrationReport = MigrationReport.noOp(migrationRegistry.currentVersion());
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();

            // Apply any registered schema migrations before parsing so the
            // rest of the deserializer always sees a current-version DOM.
            MigrationRegistry.MigrationResult migrated = migrationRegistry.migrate(document);
            document = migrated.document();
            lastMigrationReport = migrated.report();

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

        // Parse bed bus + per-track bed channel routings (legacy projects
        // that pre-date this element simply load with the BedBusManager
        // default of a unity-gain 7.1.4 bed bus and no routings).
        List<Element> bedBusElements = getDirectChildElements(root, "bed-bus");
        if (!bedBusElements.isEmpty()) {
            parseBedBus(bedBusElements.getFirst(), project);
        }

        // Parse ripple mode (per-project UI preference, defaults to OFF for
        // projects saved before this element was introduced)
        List<Element> rippleElements = getDirectChildElements(root, "ripple-mode");
        if (!rippleElements.isEmpty()) {
            String value = rippleElements.getFirst().getAttribute("value");
            if (!value.isEmpty()) {
                try {
                    project.setRippleMode(RippleMode.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                    // Unknown value — keep the default OFF.
                }
            }
        }

        // Parse nudge settings (per-project UI preference, defaults to
        // NudgeSettings.DEFAULT for projects saved before this element
        // was introduced)
        List<Element> nudgeElements = getDirectChildElements(root, "nudge-settings");
        if (!nudgeElements.isEmpty()) {
            Element nudge = nudgeElements.getFirst();
            String unitAttr = nudge.getAttribute("unit");
            String amountAttr = nudge.getAttribute("amount");
            if (!unitAttr.isEmpty() && !amountAttr.isEmpty()) {
                try {
                    NudgeUnit unit = NudgeUnit.valueOf(unitAttr);
                    double amount = Double.parseDouble(amountAttr);
                    project.setNudgeSettings(new NudgeSettings(unit, amount));
                } catch (IllegalArgumentException ignored) {
                    // Unknown unit or invalid amount — keep the default.
                }
            }
        }

        // Parse loudness target (per-project mastering preference,
        // defaults to LoudnessTarget.SPOTIFY for projects saved before
        // this element was introduced).
        List<Element> loudnessTargetElements = getDirectChildElements(root, "loudness-target");
        if (!loudnessTargetElements.isEmpty()) {
            String value = loudnessTargetElements.getFirst().getAttribute("value");
            if (!value.isEmpty()) {
                try {
                    project.setLoudnessTarget(LoudnessTarget.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                    // Unknown value — keep the default SPOTIFY.
                }
            }
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

        // Per-track lane fold state (automation, takes, MIDI). Older
        // projects predate this attribute set — default to UNFOLDED so
        // those sessions render exactly as before.
        if (elem.hasAttribute("automation-folded")
                || elem.hasAttribute("takes-folded")
                || elem.hasAttribute("midi-folded")
                || elem.hasAttribute("header-height-override")) {
            boolean automationFolded = parseBooleanAttr(elem, "automation-folded");
            boolean takesFolded = parseBooleanAttr(elem, "takes-folded");
            boolean midiFolded = parseBooleanAttr(elem, "midi-folded");
            double headerOverride = parseDoubleAttr(elem, "header-height-override", 0.0);
            if (!Double.isFinite(headerOverride) || headerOverride < 0.0) {
                headerOverride = 0.0;
            }
            try {
                track.setFoldState(new TrackFoldState(
                        automationFolded, takesFolded, midiFolded, headerOverride));
            } catch (IllegalArgumentException ignored) {
                // keep default UNFOLDED on any invalid attribute combination
            }
        }

        String automationModeStr = elem.getAttribute("automation-mode");
        if (!automationModeStr.isEmpty()) {
            try {
                track.setAutomationMode(AutomationMode.valueOf(automationModeStr));
            } catch (IllegalArgumentException ignored) {
                // keep default mode on invalid value
            }
        }

        // Per-track input monitoring mode. Older projects predate this
        // attribute — to match the engineer-expected default for a
        // re-opened session, fall back to AUTO rather than OFF when the
        // attribute is absent.
        String monitoringStr = elem.getAttribute("input-monitoring");
        if (monitoringStr.isEmpty()) {
            track.setInputMonitoring(InputMonitoringMode.AUTO);
        } else {
            try {
                track.setInputMonitoring(InputMonitoringMode.valueOf(monitoringStr));
            } catch (IllegalArgumentException ignored) {
                track.setInputMonitoring(InputMonitoringMode.AUTO);
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
        String irName = elem.getAttribute("input-routing-name");
        if (!irName.isEmpty()) {
            track.setInputRoutingDisplayName(irName);
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
        clip.setLocked(parseBooleanAttr(elem, "locked"));
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

        List<Element> envContainers = getDirectChildElements(elem, "gain-envelope");
        if (!envContainers.isEmpty()) {
            ClipGainEnvelope envelope = parseGainEnvelope(envContainers.getFirst());
            if (envelope != null) {
                clip.setGainEnvelope(envelope);
            }
        }

        return clip;
    }

    private ClipGainEnvelope parseGainEnvelope(Element envElem) {
        List<Element> bps = getDirectChildElements(envElem, "breakpoint");
        if (bps.isEmpty()) {
            return null;
        }
        var points = new java.util.ArrayList<ClipGainEnvelope.BreakpointDb>(bps.size());
        for (Element bp : bps) {
            long frame = Math.max(0L, parseLongAttr(bp, "frame-offset", 0L));
            double db = parseDoubleAttr(bp, "db-gain", 0.0);
            CurveShape curve = parseCurveShape(bp.getAttribute("curve"));
            points.add(new ClipGainEnvelope.BreakpointDb(frame, db, curve));
        }
        return new ClipGainEnvelope(points);
    }

    private CurveShape parseCurveShape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return CurveShape.LINEAR;
        }
        try {
            return CurveShape.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return CurveShape.LINEAR;
        }
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

        // Parse VCA groups. The element is optional: legacy projects predating
        // VCA support load with no VCAs because the manager starts empty.
        List<Element> vcaGroupsContainers = getDirectChildElements(mixerElem, "vca-groups");
        if (!vcaGroupsContainers.isEmpty()) {
            VcaGroupManager vcaManager = project.getVcaGroupManager();
            for (Element groupElem : getDirectChildElements(vcaGroupsContainers.getFirst(), "vca-group")) {
                String idAttr = groupElem.getAttribute("id");
                if (idAttr.isEmpty()) {
                    continue;
                }
                java.util.UUID id;
                try {
                    id = java.util.UUID.fromString(idAttr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String label = groupElem.getAttribute("label");
                if (label.isEmpty()) {
                    label = "VCA";
                }
                double gainDb = parseDoubleAttr(groupElem, "master-gain-db", 0.0);
                gainDb = Math.max(VcaGroup.MIN_GAIN_DB, Math.min(VcaGroup.MAX_GAIN_DB, gainDb));
                TrackColor color = null;
                String colorHex = groupElem.getAttribute("color");
                if (!colorHex.isEmpty()) {
                    try {
                        color = TrackColor.fromHex(colorHex);
                    } catch (RuntimeException ignored) {
                        // Unknown color — leave null.
                    }
                }
                List<java.util.UUID> members = new java.util.ArrayList<>();
                for (Element memberElem : getDirectChildElements(groupElem, "vca-member")) {
                    String channelIdAttr = memberElem.getAttribute("channel-id");
                    if (channelIdAttr.isEmpty()) {
                        continue;
                    }
                    try {
                        members.add(java.util.UUID.fromString(channelIdAttr));
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed UUIDs rather than failing the whole load.
                    }
                }
                vcaManager.addVcaGroup(new VcaGroup(id, label, gainDb, color, members));
            }
        }

        // Parse channel links. The element is optional: legacy projects predating
        // channel-link support load with no links because the manager starts empty.
        List<Element> channelLinksContainers = getDirectChildElements(mixerElem, "channel-links");
        if (!channelLinksContainers.isEmpty()) {
            ChannelLinkManager linkManager = project.getChannelLinkManager();
            for (Element linkElem : getDirectChildElements(channelLinksContainers.getFirst(), "channel-link")) {
                String leftAttr = linkElem.getAttribute("left-channel-id");
                String rightAttr = linkElem.getAttribute("right-channel-id");
                if (leftAttr.isEmpty() || rightAttr.isEmpty()) {
                    continue;
                }
                java.util.UUID leftId;
                java.util.UUID rightId;
                try {
                    leftId = java.util.UUID.fromString(leftAttr);
                    rightId = java.util.UUID.fromString(rightAttr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (leftId.equals(rightId)) {
                    continue;
                }
                LinkMode mode = LinkMode.RELATIVE;
                String modeAttr = linkElem.getAttribute("mode");
                if (!modeAttr.isEmpty()) {
                    try {
                        mode = LinkMode.valueOf(modeAttr);
                    } catch (IllegalArgumentException ignored) {
                        // Unknown mode — fall back to RELATIVE.
                    }
                }
                boolean linkFaders = parseBooleanAttr(linkElem, "link-faders", true);
                boolean linkPans = parseBooleanAttr(linkElem, "link-pans", true);
                boolean linkMuteSolo = parseBooleanAttr(linkElem, "link-mute-solo", true);
                boolean linkInserts = parseBooleanAttr(linkElem, "link-inserts", true);
                boolean linkSends = parseBooleanAttr(linkElem, "link-sends", true);
                try {
                    linkManager.link(new ChannelLink(leftId, rightId, mode,
                            linkFaders, linkPans, linkMuteSolo, linkInserts, linkSends));
                } catch (IllegalStateException ignored) {
                    // A duplicate link in the file is malformed — skip rather than fail the whole load.
                }
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
        // Restore solo-safe (solo-in-place defeat). Legacy projects predating
        // this attribute keep the channel's construction-time default: return
        // buses default to solo-safe = true, track and master channels to false.
        String soloSafeAttr = elem.getAttribute("solo-safe");
        if (!soloSafeAttr.isEmpty()) {
            channel.setSoloSafe("true".equalsIgnoreCase(soloSafeAttr));
        }

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
        String orName = elem.getAttribute("output-routing-name");
        if (!orName.isEmpty()) {
            channel.setOutputRoutingDisplayName(orName);
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
        // SendTap is the authoritative field; legacy projects without a "tap"
        // attribute migrate by deriving the tap from the legacy mode (and
        // ultimately default to POST_FADER per the migration spec).
        SendTap tap = parseSendTap(sendElem.getAttribute("tap"), mode);
        Send send = new Send(target, level, tap);
        channel.addSend(send);
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

        // Object-parameter lanes (Story 172). Older projects without
        // <object-lane> elements are loaded unchanged — the loop simply
        // finds nothing to read, which is the documented graceful upgrade
        // path.
        List<Element> objectLaneElements = getDirectChildElements(automationElem, "object-lane");
        for (Element laneElem : objectLaneElements) {
            String objectId = laneElem.getAttribute("object-id");
            String paramStr = laneElem.getAttribute("parameter");
            if (objectId == null || objectId.isBlank() || paramStr == null || paramStr.isBlank()) {
                continue;
            }
            ObjectParameter parameter;
            try {
                parameter = ObjectParameter.valueOf(paramStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ObjectParameterTarget target = new ObjectParameterTarget(objectId, parameter);
            AutomationLane lane = track.getAutomationData().getOrCreateObjectLane(target);
            lane.setVisible(parseBooleanAttr(laneElem, "visible"));

            List<Element> pointElements = getDirectChildElements(laneElem, "point");
            for (Element pointElem : pointElements) {
                double time = parseDoubleAttr(pointElem, "time", 0.0);
                double value = parseDoubleAttr(pointElem, "value", target.getDefaultValue());
                InterpolationMode interpolation = parseInterpolationMode(
                        pointElem.getAttribute("interpolation"));

                if (time >= 0.0 && target.isValidValue(value)) {
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

        List<Element> clickOutputs = getDirectChildElements(elem, "click-output");
        if (!clickOutputs.isEmpty()) {
            Element co = clickOutputs.getFirst();
            int channel = parseIntAttr(co, "hardware-channel-index", 0);
            double gain = clampDouble(parseDoubleAttr(co, "gain", 1.0), 0.0, 1.0);
            // Match pre-story-136 defaults (main on, side off) when the
            // attributes are absent so legacy files without them still
            // round-trip to ClickOutput.MAIN_MIX_ONLY.
            boolean mainMix = co.hasAttribute("main-mix-enabled")
                    ? parseBooleanAttr(co, "main-mix-enabled") : true;
            boolean sideOutput = parseBooleanAttr(co, "side-output-enabled");
            try {
                metronome.setClickOutput(
                        new ClickOutput(Math.max(0, channel), gain, mainMix, sideOutput));
            } catch (IllegalArgumentException ignored) {
                metronome.setClickOutput(ClickOutput.MAIN_MIX_ONLY);
            }
        }
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
            // Story 042 — Atmos A/B comparison: restore optional immersive
            // format and per-channel trims (silently ignore malformed values
            // so older / hand-edited files keep loading).
            String formatName = refTrackElem.getAttribute("immersive-format");
            if (formatName != null && !formatName.isEmpty()) {
                try {
                    refTrack.setImmersiveFormat(
                            com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat
                                    .valueOf(formatName));
                } catch (IllegalArgumentException ignored) {
                    // Unknown format — leave as null (stereo).
                }
            }
            String trimAttr = refTrackElem.getAttribute("per-channel-trim-db");
            if (trimAttr != null && !trimAttr.isEmpty()) {
                String[] parts = trimAttr.split(",");
                double[] trims = new double[parts.length];
                boolean ok = true;
                for (int i = 0; i < parts.length; i++) {
                    try {
                        trims[i] = Double.parseDouble(parts[i].trim());
                    } catch (NumberFormatException nfe) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    try {
                        refTrack.setPerChannelTrimDb(trims);
                    } catch (IllegalArgumentException ignored) {
                        // Length mismatch with declared format — drop trim.
                    }
                }
            }
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

    /**
     * Parses the {@code tap} attribute of a {@code <send>} element. Legacy
     * projects (saved before per-send tap-point support) omit the attribute;
     * we migrate them by deriving a tap from the legacy {@link SendMode}
     * (and falling back to {@link SendTap#POST_FADER} as the spec mandates).
     *
     * @param value         the raw attribute value (may be {@code null}/empty)
     * @param fallbackMode  the legacy mode to fall back to for migration
     * @return the parsed or migrated tap point
     */
    private SendTap parseSendTap(String value, SendMode fallbackMode) {
        if (value == null || value.isEmpty()) {
            return fallbackMode == SendMode.PRE_FADER ? SendTap.PRE_FADER : SendTap.POST_FADER;
        }
        try {
            return SendTap.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SendTap.POST_FADER;
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

    private static boolean parseBooleanAttr(Element element, String attr, boolean defaultValue) {
        String value = element.getAttribute(attr);
        if (value.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
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
            // Directivity is optional — legacy projects without it fall
            // back to OMNIDIRECTIONAL (handled by RoomConfiguration).
            String directivityRaw = sourceElem.getAttribute("directivity");
            if (directivityRaw != null && !directivityRaw.isEmpty()) {
                try {
                    config.setSourceDirectivity(
                            name, SourceDirectivity.valueOf(directivityRaw));
                } catch (IllegalArgumentException ignored) {
                    // Unknown enum constant: keep the default.
                }
            }
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
                    SendTap tap = parseSendTap(se.getAttribute("tap"), mode);
                    if (targetIndex >= 0) {
                        sends.add(new SendSnapshot(targetIndex, level, mode, tap));
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

    private void parseBedBus(Element elem, DawProject project) {
        BedBusManager manager = project.getBedBusManager();
        String formatName = elem.getAttribute("format");
        ImmersiveFormat format;
        try {
            format = formatName.isEmpty() ? ImmersiveFormat.FORMAT_7_1_4
                    : ImmersiveFormat.valueOf(formatName);
        } catch (IllegalArgumentException e) {
            // Unknown / corrupt format — fall back to the project default
            // and skip the rest of the bed bus block. Routings would not
            // be valid against an unknown channel count.
            return;
        }

        UUID busId;
        try {
            busId = UUID.fromString(elem.getAttribute("id"));
        } catch (IllegalArgumentException e) {
            busId = manager.getBedBus().id();
        }

        double[] busGains = parseGainCsv(elem.getAttribute("channel-gains-db"),
                format.channelCount());
        manager.setBedBus(new BedBus(busId, format, busGains));
        manager.clearRoutings();

        List<Element> routingsContainers = getDirectChildElements(elem, "bed-routings");
        if (routingsContainers.isEmpty()) {
            return;
        }
        for (Element re : getDirectChildElements(routingsContainers.getFirst(), "bed-routing")) {
            UUID trackId;
            try {
                trackId = UUID.fromString(re.getAttribute("track-id"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            // The routing element optionally carries its own format; if it
            // disagrees with the bus, we skip it rather than silently
            // mis-routing audio. This preserves the invariant that every
            // routing in the manager matches the bus format.
            String routingFormatName = re.getAttribute("format");
            if (!routingFormatName.isEmpty() && !routingFormatName.equals(format.name())) {
                continue;
            }
            double[] routingGains = parseGainCsv(re.getAttribute("channel-gains-db"),
                    format.channelCount());
            manager.setRouting(new BedChannelRouting(trackId, format, routingGains));
        }
    }

    private static double[] parseGainCsv(String csv, int expectedLength) {
        double[] gains = new double[expectedLength];
        java.util.Arrays.fill(gains, BedChannelRouting.SILENT_DB);
        if (csv == null || csv.isEmpty()) {
            return gains;
        }
        String[] tokens = csv.split(",");
        int n = Math.min(tokens.length, expectedLength);
        for (int i = 0; i < n; i++) {
            String token = tokens[i].trim();
            if (token.equalsIgnoreCase("-inf") || token.equalsIgnoreCase("-Infinity")) {
                gains[i] = BedChannelRouting.SILENT_DB;
                continue;
            }
            try {
                gains[i] = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                gains[i] = BedChannelRouting.SILENT_DB;
            }
        }
        return gains;
    }
}
