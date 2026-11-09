package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.*;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.marker.MarkerType;
import com.benesquivelmusic.daw.core.midi.MidiCcEvent;
import com.benesquivelmusic.daw.core.midi.MidiCcLane;
import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.MidiRecorder;
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
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginGraph;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginIdentity;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.preset.ParameterRestore;
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
import com.benesquivelmusic.daw.core.transport.TempoChangeEvent;
import com.benesquivelmusic.daw.core.transport.TempoTransitionType;
import com.benesquivelmusic.daw.core.transport.TimeSignatureChangeEvent;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginStateRejectedException;
import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deserializes a {@link DawProject} from XML produced by {@link ProjectSerializer}.
 *
 * <p>The deserializer reconstructs the complete project state including tracks,
 * clips, mixer settings, transport state, and project metadata. Malformed or
 * missing values fall back to sensible defaults to ensure graceful degradation.</p>
 */
public final class ProjectDeserializer {

    private static final Logger LOG = Logger.getLogger(ProjectDeserializer.class.getName());

    /** Maximum valid azimuth value — just below 360° (exclusive upper bound). */
    private static final double MAX_AZIMUTH_EXCLUSIVE = Math.nextDown(360.0);

    /**
     * How {@code ProjectSerializer} writes a note, a CC lane or a CC event
     * ({@code buildMidiClip}), a tempo or meter change
     * ({@code buildTransport}), or a comp region ({@code buildTakes}): its
     * tag and its attributes, in the writer's
     * order — the order the snippet a skipped item's WARNING quotes
     * ({@link #savedAs}) lists them in, never the DOM's.
     *
     * @param tag        the element's tag
     * @param attributes the attributes the writer emits, in its order
     */
    private record WrittenForm(String tag, List<String> attributes) {
    }

    private static final WrittenForm NOTE = new WrittenForm("note",
            List.of("note-number", "start-column", "duration-columns", "velocity", "channel"));
    private static final WrittenForm CC_LANE = new WrittenForm("cc-lane",
            List.of("type", "cc-number", "high-resolution", "channel", "height-ratio"));
    private static final WrittenForm CC_EVENT = new WrittenForm("event", List.of("column", "value"));
    private static final WrittenForm TEMPO_CHANGE = new WrittenForm("tempo-change",
            List.of("position", "bpm", "transition"));
    private static final WrittenForm METER_CHANGE = new WrittenForm("meter-change",
            List.of("position", "numerator", "denominator"));
    private static final WrittenForm COMP_REGION = new WrittenForm("comp-region",
            List.of("take-id", "start-beat", "duration-beats"));

    private final List<String> missingFiles = new ArrayList<>();

    /**
     * The saved items the call in progress could not honour as written — see
     * {@link #getUnreadItems()}. Per-call state, like {@link #missingFiles};
     * filled only through {@link #reportUnread}.
     */
    private final List<UnreadProjectItem> unreadItems = new ArrayList<>();

    /**
     * Whether the call in progress dropped saved content it lists no
     * {@linkplain #unreadItems item} for — a malformed or duplicate track or
     * take-lane id it replaced with a fresh one, the child elements or text of a MIDI
     * effect it kept as a placeholder, or a VCA group whose id repeats an
     * earlier group's, which it skipped with its members — see
     * {@link #losesSavedContent()}. Per-call state, like {@link #unreadItems}.
     */
    private boolean droppedUnlistedContent;
    private final MigrationRegistry migrationRegistry;
    private MigrationReport lastMigrationReport = MigrationReport.noOp(MigrationRegistry.CURRENT_VERSION);

    /**
     * The directory project-relative clip references resolve against for
     * the call in progress ({@link #deserialize(String, Path)}); {@code null}
     * keeps every reference verbatim. Per-call state, like
     * {@link #missingFiles}: one deserialization at a time per instance.
     */
    private Path projectDirectory;

    /** Turns a saved JAR or CLAP plugin identity into a plugin; see {@link #setPluginResolver}. */
    private volatile PluginResolver pluginResolver = PluginResolver.NONE;

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
     * Sets how this deserializer turns a saved external JAR or CLAP plugin
     * insert into a plugin (story 334; Persistence Integrity book §4.5): the
     * installed-plugin registry for the opens that hand the project to the
     * application, and {@link PluginResolver#NONE} — the default — for a read
     * whose project is only re-serialized or checked, which then keeps every
     * such insert offline and writes it back as saved. A first-party plugin
     * insert never goes through the resolver: every read resolves it from
     * this build's own plugins. The resolver runs on the thread that calls
     * {@link #deserialize(String, Path)}, and may construct third-party code.
     *
     * @param resolver the resolver
     */
    public void setPluginResolver(PluginResolver resolver) {
        this.pluginResolver = java.util.Objects.requireNonNull(resolver, "resolver must not be null");
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
     * <p>This list is populated during {@link #deserialize(String, Path)}.
     * {@code ProjectManager.openProject} turns it into the open's
     * missing-assets report ({@link MissingAssetsReport#atOpen}, read back
     * through {@code ProjectManager.getLastMissingAssets()}), which matches
     * these strings against the clips holding them — deduplicated, one row
     * per asset, reference-track files kept apart — and which the
     * application surfaces with relink options. Entries are the references as
     * held in memory: a clip reference the project directory resolved
     * appears as its absolute resolved path, every other reference as
     * written. A clip reference that is
     * {@linkplain ProjectPaths#unresolvable unresolvable} against the project
     * directory — it escapes the directory or is the directory itself, it is
     * not a valid path on this platform, or it is drive- or root-relative on
     * this platform — is listed whether or not a file exists anywhere it
     * could name, because the deserializer does not resolve it (see
     * {@link #deserialize(String, Path)}). A recorded take's segments are
     * listed one per missing segment file, the head segment once; a reference
     * held by several clips (split halves, duplicates) is listed once per
     * clip, and a reference track's missing file is listed as well. The list
     * is cleared at the start of each deserialization call.</p>
     *
     * @return an unmodifiable view of missing file paths
     */
    public List<String> getMissingFiles() {
        return Collections.unmodifiableList(missingFiles);
    }

    /**
     * Returns the saved items the most recent deserialization could not
     * honour as written (Persistence Integrity book §2.4, §9.4), in the
     * order they were read: MIDI notes, CC lanes and CC events that could
     * not be read and were skipped, arpeggiator settings that were adjusted
     * to a valid value, MIDI effects this build does not recognise (kept as
     * {@link UnresolvedMidiEffect} placeholders), and cue sends, VCA
     * memberships, channel links and bed routings it could not honour —
     * naming a track the file does not contain, a malformed id, a channel
     * linked to itself, or a repeat of an earlier assignment (removed; their
     * containers are kept), and the transport's tempo or meter changes and
     * beat-0 tempo or meter that are not usable (skipped; story 334); and,
     * story 334, inserts the open could not load (kept offline at their
     * place, written back as saved), a strip's ninth or later insert
     * (skipped), insert parameters it could not apply as saved (rejected,
     * clamped, unknown, repeated or not a finite number), a working
     * insert's {@code sidechain-source} that names no strip (removed), and
     * a track's comp regions that name no take of the track, hold an
     * unusable start or duration, or overlap an earlier region (skipped).
     * Each item was logged once at WARNING when it was found
     * ({@link UnreadProjectItem#logLine()}). Problems with pre/post-roll
     * values, with track ids and take-lane ids and with VCA groups (an
     * unreadable gain, a repeated id) are logged only and are not listed,
     * and so are a tempo or meter change at beat 0 that repeats the beat-0
     * attributes, an analyzer insert this build does not know (kept offline,
     * written back as saved), an offline insert's child content no writer
     * produces, and a clip saved as comp output on a track that has no takes
     * (kept as an ordinary clip).
     *
     * <p>The list is cleared at the start of each deserialization call; the
     * result is a copy, because one deserializer instance serves many loads
     * ({@code ProjectManager}, {@code SnapshotsController},
     * {@code JournalReplayer}, {@code ProjectArchiver}).</p>
     *
     * @return an unmodifiable copy of the unread items; empty for a file the
     *         open honoured completely
     */
    public List<UnreadProjectItem> getUnreadItems() {
        return List.copyOf(unreadItems);
    }

    /**
     * Returns whether saving the project the most recent deserialization
     * built would drop content its file saved (Persistence Integrity book
     * §9.4): an {@linkplain #getUnreadItems() unread item} whose kind
     * {@linkplain UnreadProjectItem.Kind#losesSavedContent() loses its saved
     * content}, a malformed or duplicate track id replaced by a fresh one
     * (logged only: the saved id is gone, and take folders and manifests on
     * disk still carry it), a malformed or duplicate take-lane id replaced
     * by a fresh one (logged only; the file's comp regions resolved by the
     * id as saved), child elements or non-blank text of a MIDI
     * effect kept as a placeholder or of an offline insert other than its
     * {@code <parameter>} and {@code <plugin-state>} children, a plugin
     * state on an insert whose plugin takes none (each logged and dropped),
     * or a VCA group whose id repeats an earlier group's (logged and skipped
     * with its label, gain, colour and members). {@code ProjectManager}
     * backs the original file up before the first save of such an open.
     *
     * <p>Nothing else the open adjusts loses saved content: a track without
     * an id gets a fresh one and a non-canonical spelling of a valid id is
     * restored as the same UUID; a take lane without an id gets a fresh one,
     * and a clip saved as comp output on a track that has no takes stays an
     * ordinary clip, the mark meaning nothing there; a missing asset's
     * reference is kept as written; a CC lane's unusable height ratio is
     * view layout, and a
     * malformed pre/post-roll value a transport preference; a VCA group's
     * unreadable gain carries no level and opens at 0 dB, the attribute's
     * default; a placeholder's attributes are written back as saved, and so
     * is an offline insert, with its parameters and plugin state; a
     * tempo or meter change at beat 0 that repeats the beat-0 attributes
     * adds nothing they do not already hold. Reset with the unread items at
     * the start of each deserialization call.</p>
     *
     * @return {@code true} when the next save of the deserialized project
     *         would not write back everything its file saved
     */
    public boolean losesSavedContent() {
        if (droppedUnlistedContent) {
            return true;
        }
        for (UnreadProjectItem item : unreadItems) {
            if (item.kind().losesSavedContent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deserializes a project from an XML string with no project directory:
     * every persisted reference is kept verbatim — a project-relative
     * reference stays relative in memory and its existence is checked
     * against the JVM's working directory, while the code that opens clip
     * references treats a relative one as naming no file
     * ({@link ProjectPaths#isAbsoluteReference}). Prefer
     * {@link #deserialize(String, Path)} whenever the project directory is
     * known.
     *
     * @param xml the XML content produced by {@link ProjectSerializer}
     * @return the reconstructed project
     * @throws IOException if the XML cannot be parsed
     */
    public DawProject deserialize(String xml) throws IOException {
        return deserialize(xml, null);
    }

    /**
     * Deserializes a project from an XML string, resolving project-relative
     * clip references against {@code projectDirectory} (story 323; Recording
     * Reliability book §3.3, Persistence Integrity book §3.3).
     *
     * <p>A clip's {@code source-file} attribute and its
     * {@code <source-segment>} children go through the {@link ProjectPaths}
     * rule shared with the serializer, judged with the {@code Path} semantics
     * of the platform reading the file (the platform facts are listed on
     * {@link ProjectPaths}). A reference with no root component whose
     * normalised target lies strictly under {@code projectDirectory} becomes
     * that absolute in-memory path, and an absolute reference — a legacy
     * file's absolute path, or an asset outside the project — is kept
     * verbatim. Every other non-empty reference is
     * {@linkplain ProjectPaths#unresolvable unresolvable}: it escapes the
     * directory ({@code ../outside.wav}) or is the directory itself
     * ({@code .}), it is not a valid path on this platform, or it is drive-
     * or root-relative on this platform (on Windows {@code C:x.wav},
     * {@code \x.wav}, {@code /x.wav}). The deserializer does not resolve such
     * a reference: it is kept as written, so the next save writes it back
     * unchanged and a segment list keeps its count, order and head; it is
     * logged once at WARNING naming the track, the clip and the reason; and it is
     * reported in {@link #getMissingFiles()} whether or not a file exists
     * anywhere it could name. With a directory, every clip reference is
     * therefore absolute or unresolvable after this call, and the code that
     * opens clip references — {@code ProjectArchiver}, the Archive Project
     * pre-flight in {@code ProjectLifecycleController},
     * {@code ClipProcessingService} and the {@link ClipRehydrator} — treats an
     * unresolvable one as naming no file
     * ({@link ProjectPaths#isAbsoluteReference}). A usable reference is
     * reported missing only when no file exists at its in-memory path. A
     * clip with segments is checked through its segments only: the head IS
     * the first segment ({@code AudioClip.setSourceSegmentPaths} makes it
     * so), and the clip's {@code source-file} attribute is neither resolved
     * nor checked, warned about or reported. A {@code null} directory gives
     * the {@link #deserialize(String)} semantics: nothing is
     * unresolvable.</p>
     *
     * <p>The WARNING goes only to {@code java.util.logging}'s default console
     * handler (standard error); the application installs no handler of its
     * own until story 336. The visible trace of an unresolvable reference is
     * the missing-assets report the open builds from
     * {@link #getMissingFiles()} ({@link MissingAssetsReport#atOpen}), where
     * it is an {@link MissingAssetReason#UNRESOLVABLE} row, and the Archive
     * Project pre-flight, which lists it as missing unless it is blank.</p>
     *
     * <p>The clips this call builds carry their references but no sample
     * data: the {@link ClipRehydrator} loads it after the open.</p>
     *
     * <p>Tracks keep the ids they were saved with, so every id-keyed mixer
     * reference that names a track of this file resolves to it. A saved
     * item the open cannot honour as written — a MIDI note, CC lane or CC
     * event that cannot be read, an invalid arpeggiator setting, a MIDI
     * effect this build does not recognise, a mixer assignment it cannot
     * honour, a comp region it cannot honour — never fails the load: it is
     * skipped, adjusted, kept as a placeholder or removed, logged, and
     * listed in {@link #getUnreadItems()}. Take lanes keep the ids they were
     * saved with too, so every comp region resolves to the lane it named;
     * the main-lane clips saved as comp output are adopted as the restored
     * comping's output, never recompiled (story 334).</p>
     *
     * <p>Called on whichever thread opens the project (the caller of
     * {@code ProjectManager.openProject}, a recovery subtask of
     * {@code JournalReplayer}, the FX thread in
     * {@code SnapshotsController.loadFromEntry}); one call at a time per
     * instance.</p>
     *
     * @param xml              the XML content produced by {@link ProjectSerializer}
     * @param projectDirectory the directory project-relative references
     *                         resolve against, or {@code null}
     * @return the reconstructed project
     * @throws IOException if the XML cannot be parsed
     */
    public DawProject deserialize(String xml, Path projectDirectory) throws IOException {
        // Per-call state is reset before the parse, so a file that does not
        // parse still leaves a fresh missing-files list, unread-items list
        // and migration report behind; the document overload resets it again
        // (idempotent) and does the rest.
        resetForLoad(projectDirectory);
        Document document = parseXml(xml);
        return deserialize(document, projectDirectory);
    }

    /**
     * Deserializes an already parsed project document: the per-call state
     * is reset, the document is migrated, then parsed — the second half of
     * every {@link #deserialize(String, Path)} call, which parses its text
     * and hands the document here. Every read goes through
     * {@code document}'s DOM, so a test can hand in a view that records what
     * the reader asks for (the write/read parity harness) and exercise the
     * very path a production open takes.
     *
     * @param document         a project document as {@link #parseXml} returns it
     * @param projectDirectory the directory project-relative references
     *                         resolve against, or {@code null}
     * @return the reconstructed project
     */
    DawProject deserialize(Document document, Path projectDirectory) {
        resetForLoad(projectDirectory);
        return migrateAndParse(document);
    }

    /**
     * Decodes a project document's bytes as strict UTF-8, as
     * {@link Files#readString(Path)} reads {@code project.daw}: malformed or
     * unmappable input throws instead of becoming U+FFFD replacement
     * characters. Shared by {@link RecoveryTemps#inspect}, which calls an
     * interrupted save complete only if its bytes decode here, and by that
     * save's promotion ({@code RecoveryPlan.NewerHead.readUnchanged}), so a
     * temp the inspection calls complete is one the promotion can read; the
     * journal replay reads its recovery point with it too.
     *
     * @param bytes the document's bytes
     * @return the document's text
     * @throws CharacterCodingException if {@code bytes} are not strict UTF-8
     */
    public static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Parses project XML into a normalized DOM document, with DOCTYPE
     * declarations refused.
     *
     * @param xml the XML content produced by {@link ProjectSerializer}
     * @return the parsed document
     * @throws IOException if the XML cannot be parsed
     */
    static Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return document;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to deserialize project XML", e);
        }
    }

    private void resetForLoad(Path projectDirectory) {
        missingFiles.clear();
        unreadItems.clear();
        droppedUnlistedContent = false;
        this.projectDirectory = projectDirectory;
        lastMigrationReport = MigrationReport.noOp(migrationRegistry.currentVersion());
    }

    private DawProject migrateAndParse(Document document) {
        // Apply any registered schema migrations before parsing so the
        // rest of the deserializer always sees a current-version DOM.
        MigrationRegistry.MigrationResult migrated = migrationRegistry.migrate(document);
        lastMigrationReport = migrated.report();
        return parseDocument(migrated.document());
    }

    /** Logs {@code item} once at WARNING and adds it to {@link #unreadItems}: the only way in. */
    private void reportUnread(UnreadProjectItem item) {
        LOG.log(Level.WARNING, item.logLine());
        unreadItems.add(item);
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
        try {
            parseContent(root, project);
        } catch (Throwable failure) {
            // Nobody installs a project whose read failed: release the plugins
            // its inserts built (a JAR plugin's loader keeps its file open)
            // before the failure leaves, whatever it is — a plugin's code can
            // throw a checked exception it does not declare (story 334).
            disposeUnopened(project);
            throw failure;
        }
        return project;
    }

    /**
     * Releases every insert of a project nobody will install — a read that
     * failed half way, or a project read only to be re-serialized or checked.
     * Its plugins are disposed once no render uses them, which for such a
     * project is at once; a disposal failure is logged at FINE.
     *
     * @param project the project to release
     */
    public static void disposeUnopened(DawProject project) {
        project.disposeInsertsWhenQuiescent().whenComplete((done, failure) -> {
            if (failure != null) {
                LOG.log(Level.FINE, "Releasing the inserts of a project that was not opened failed", failure);
            }
        });
    }

    /** Reads everything below the audio format into {@code project}, in the order the writer emits it. */
    private void parseContent(Element root, DawProject project) {
        // Parse transport
        List<Element> transportElements = getDirectChildElements(root, "transport");
        if (!transportElements.isEmpty()) {
            parseTransport(transportElements.getFirst(), project.getTransport());
        }

        // Parse tracks. restoredTracks maps the ids restored from this file —
        // the only ids an id-keyed mixer reference can resolve to — to the
        // track each names, as the log names it.
        Map<UUID, UnreadProjectItem.OnTrack> restoredTracks = new HashMap<>();
        List<Element> tracksContainers = getDirectChildElements(root, "tracks");
        if (!tracksContainers.isEmpty()) {
            List<Element> trackElements = getDirectChildElements(tracksContainers.getFirst(), "track");
            for (int i = 0; i < trackElements.size(); i++) {
                Track track = parseTrack(trackElements.get(i), i, restoredTracks);
                project.addTrack(track);
            }
        }

        // Parse mixer settings
        List<Element> mixerElements = getDirectChildElements(root, "mixer");
        if (!mixerElements.isEmpty()) {
            parseMixer(mixerElements.getFirst(), project, restoredTracks);
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
            parseBedBus(bedBusElements.getFirst(), project, restoredTracks);
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

        // Parse active HRTF profile reference (binaural monitoring). Missing element
        // means the project has no preference and the binaural plugin should use its
        // factory default. The reference is purely a name; resolving it (and falling
        // back to a generic profile when not present in the local library) is the
        // loader's responsibility.
        List<Element> hrtfElements = getDirectChildElements(root, "active-hrtf-profile");
        if (!hrtfElements.isEmpty()) {
            String name = hrtfElements.getFirst().getAttribute("name");
            if (!name.isEmpty()) {
                project.setActiveHrtfProfileName(name);
            }
        }

        // Story 282 — opaque LayoutManager JSON blob. Treated as an
        // arbitrary string; the layout package parses it. Missing element
        // is the normal case for legacy projects and is equivalent to
        // "use the Default built-in layout".
        List<Element> layoutElements = getDirectChildElements(root, "layout");
        if (!layoutElements.isEmpty()) {
            String json = layoutElements.getFirst().getTextContent();
            if (json != null && !json.isBlank()) {
                project.setLayoutJson(json);
            }
        }
    }

    private void parseTransport(Element elem, Transport transport) {
        // The tempo map (story 334): the beat-0 attributes first, then the
        // lists of changes after beat 0, whose beat-0 entries are compared
        // with what the attributes put in force.
        parseBeatZeroTempo(elem, transport);
        parseBeatZeroMeter(elem, transport);
        parseTempoChanges(elem, transport);
        parseMeterChanges(elem, transport);

        // Loop: flag and bounds go out as ONE window when the parsed bounds
        // are valid (story 315 review — an enable-then-region pair would let
        // the RT clock see the flag over the previous bounds); malformed
        // bounds — including "NaN"/"Infinity", which Double.parseDouble
        // accepts but the transport rejects — keep the transport's defaults
        // and only the flag is applied.
        boolean loopEnabled = parseBooleanAttr(elem, "loop-enabled");
        double loopStart = parseDoubleAttr(elem, "loop-start", 0.0);
        double loopEnd = parseDoubleAttr(elem, "loop-end", 16.0);
        if (Double.isFinite(loopStart) && Double.isFinite(loopEnd)
                && loopStart >= 0 && loopEnd > loopStart) {
            transport.setLoopWindow(loopEnabled, loopStart, loopEnd);
        } else {
            transport.setLoopEnabled(loopEnabled);
        }

        double position = parseDoubleAttr(elem, "position", 0.0);
        if (Double.isFinite(position) && position >= 0) {
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

        // Pre/post-roll: three stored facts, each read on its own. The writer
        // omits all three exactly when the value is DISABLED, the transport's
        // default. The flag is never re-derived from the bar counts:
        // (2, 0, false) and (0, 0, true) restore exactly as stored.
        if (elem.hasAttribute("pre-roll-bars")
                || elem.hasAttribute("post-roll-bars")
                || elem.hasAttribute("pre-roll-enabled")) {
            int preBars = parseRollBars(elem, "pre-roll-bars");
            int postBars = parseRollBars(elem, "post-roll-bars");
            boolean enabled = parseBooleanAttr(elem, "pre-roll-enabled");
            transport.setPreRollPostRoll(new PreRollPostRoll(preBars, postBars, enabled));
        }
    }

    /**
     * The beat-0 tempo (story 334, studio ruling E7): absent, it is 120 BPM,
     * silently; a value that is not a number from 20 to 999 — NaN and the
     * infinities included — is skipped and reported, and 120 BPM stays.
     */
    private void parseBeatZeroTempo(Element elem, Transport transport) {
        if (!elem.hasAttribute("tempo")) {
            return;
        }
        String raw = elem.getAttribute("tempo");
        OptionalDouble tempo = usableBpm(raw);
        if (tempo.isPresent()) {
            transport.setTempo(tempo.getAsDouble());
            return;
        }
        reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.TEMPO_MAP_ENTRY_SKIPPED,
                new UnreadProjectItem.InTransport(UnreadProjectItem.TransportEntry.BEAT_ZERO_TEMPO), 0,
                "tempo '" + UnreadProjectItem.logText(raw) + "' is not a number from 20 to 999; "
                        + transport.getTempo() + " is used", 1));
    }

    /**
     * The beat-0 meter (story 334, studio rulings E7 and ruling-2 G2): an
     * absent attribute is 4, silently; a present one that is not a positive
     * whole number is reported — the numerator before the denominator, the
     * first such one only — and the pair stays 4/4.
     */
    private void parseBeatZeroMeter(Element elem, Transport transport) {
        int[] meter = {4, 4};
        String[] attributes = {"time-sig-numerator", "time-sig-denominator"};
        for (int i = 0; i < attributes.length; i++) {
            if (!elem.hasAttribute(attributes[i])) {
                continue;
            }
            String raw = elem.getAttribute(attributes[i]);
            OptionalInt value = positiveWholeNumber(raw);
            if (value.isEmpty()) {
                reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.TEMPO_MAP_ENTRY_SKIPPED,
                        new UnreadProjectItem.InTransport(UnreadProjectItem.TransportEntry.BEAT_ZERO_METER), 0,
                        attributes[i] + " '" + UnreadProjectItem.logText(raw)
                                + "' is not a positive whole number; 4/4 is used", 1));
                return;
            }
            meter[i] = value.getAsInt();
        }
        transport.setTimeSignature(meter[0], meter[1]);
    }

    /**
     * The tempo changes after beat 0 (story 334, studio rulings E7 and E19),
     * numbered from 1 among the saved {@code <tempo-change>} elements: the
     * reader takes exactly what the model takes, finite values only. An
     * entry is skipped whole and reported — the first failing check names
     * the reason — when its position is not a beat after beat 0, its bpm is
     * not a number from 20 to 999, its transition is not exactly
     * {@code INSTANT}, {@code LINEAR} or {@code CURVED}, or its position
     * repeats an earlier accepted entry's (the first in document order
     * wins). An entry at beat 0 ({@code -0.0} included) that repeats the
     * tempo the beat-0 attribute put in force adds nothing: it is logged
     * only. Entries out of order are taken; the model sorts them.
     */
    private void parseTempoChanges(Element elem, Transport transport) {
        double beatZeroTempo = transport.getTempo();
        Map<Double, Integer> acceptedAt = new HashMap<>();
        List<Element> changes = getDirectChildElements(elem, "tempo-change");
        for (int i = 0; i < changes.size(); i++) {
            Element change = changes.get(i);
            String rawPosition = change.getAttribute("position");
            String rawBpm = change.getAttribute("bpm");
            String rawTransition = change.getAttribute("transition");
            OptionalDouble position = finiteNumber(rawPosition);
            OptionalDouble bpm = usableBpm(rawBpm);
            Optional<TempoTransitionType> transition = transitionType(rawTransition);
            String reason;
            if (position.isEmpty() || !(position.getAsDouble() > 0)) {
                if (position.isPresent() && position.getAsDouble() == 0.0 && bpm.isPresent()
                        && Double.compare(bpm.getAsDouble(), beatZeroTempo) == 0) {
                    LOG.log(Level.WARNING, "Transport: tempo change " + (i + 1) + " at position '"
                            + UnreadProjectItem.logText(rawPosition)
                            + "' repeats the tempo at beat 0; it is not added");
                    continue;
                }
                reason = notAPositionAfterBeatZero(rawPosition);
            } else if (bpm.isEmpty()) {
                reason = "bpm '" + UnreadProjectItem.logText(rawBpm) + "' is not a number from 20 to 999";
            } else if (transition.isEmpty()) {
                reason = "transition '" + UnreadProjectItem.logText(rawTransition)
                        + "' is not INSTANT, LINEAR or CURVED";
            } else if (acceptedAt.containsKey(position.getAsDouble())) {
                reason = "position '" + UnreadProjectItem.logText(rawPosition) + "' repeats tempo change "
                        + acceptedAt.get(position.getAsDouble());
            } else {
                transport.getTempoMap().addTempoChange(
                        new TempoChangeEvent(position.getAsDouble(), bpm.getAsDouble(), transition.get()));
                acceptedAt.put(position.getAsDouble(), i + 1);
                continue;
            }
            reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.TEMPO_MAP_ENTRY_SKIPPED,
                    new UnreadProjectItem.InTransport(UnreadProjectItem.TransportEntry.TEMPO_CHANGES), i,
                    reason + savedAs(change, TEMPO_CHANGE), 1));
        }
    }

    /**
     * The meter changes after beat 0 (story 334, studio rulings E7 and E19),
     * numbered from 1 among the saved {@code <meter-change>} elements, read
     * as {@link #parseTempoChanges} reads the tempo changes: skipped whole and
     * reported when the position is not a beat after beat 0, the numerator
     * or the denominator (checked in that order) is not a positive whole
     * number, or the position repeats an earlier accepted entry's; an entry
     * at beat 0 that repeats the beat-0 meter is logged only.
     */
    private void parseMeterChanges(Element elem, Transport transport) {
        int beatZeroNumerator = transport.getTimeSignatureNumerator();
        int beatZeroDenominator = transport.getTimeSignatureDenominator();
        Map<Double, Integer> acceptedAt = new HashMap<>();
        List<Element> changes = getDirectChildElements(elem, "meter-change");
        for (int i = 0; i < changes.size(); i++) {
            Element change = changes.get(i);
            String rawPosition = change.getAttribute("position");
            String rawNumerator = change.getAttribute("numerator");
            String rawDenominator = change.getAttribute("denominator");
            OptionalDouble position = finiteNumber(rawPosition);
            OptionalInt numerator = positiveWholeNumber(rawNumerator);
            OptionalInt denominator = positiveWholeNumber(rawDenominator);
            String reason;
            if (position.isEmpty() || !(position.getAsDouble() > 0)) {
                if (position.isPresent() && position.getAsDouble() == 0.0
                        && numerator.isPresent() && numerator.getAsInt() == beatZeroNumerator
                        && denominator.isPresent() && denominator.getAsInt() == beatZeroDenominator) {
                    LOG.log(Level.WARNING, "Transport: meter change " + (i + 1) + " at position '"
                            + UnreadProjectItem.logText(rawPosition)
                            + "' repeats the meter at beat 0; it is not added");
                    continue;
                }
                reason = notAPositionAfterBeatZero(rawPosition);
            } else if (numerator.isEmpty()) {
                reason = "numerator '" + UnreadProjectItem.logText(rawNumerator) + "' is not a positive whole number";
            } else if (denominator.isEmpty()) {
                reason = "denominator '" + UnreadProjectItem.logText(rawDenominator)
                        + "' is not a positive whole number";
            } else if (acceptedAt.containsKey(position.getAsDouble())) {
                reason = "position '" + UnreadProjectItem.logText(rawPosition) + "' repeats meter change "
                        + acceptedAt.get(position.getAsDouble());
            } else {
                transport.getTempoMap().addTimeSignatureChange(new TimeSignatureChangeEvent(
                        position.getAsDouble(), numerator.getAsInt(), denominator.getAsInt()));
                acceptedAt.put(position.getAsDouble(), i + 1);
                continue;
            }
            reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.TEMPO_MAP_ENTRY_SKIPPED,
                    new UnreadProjectItem.InTransport(UnreadProjectItem.TransportEntry.METER_CHANGES), i,
                    reason + savedAs(change, METER_CHANGE), 1));
        }
    }

    /** The reason a tempo-map entry whose {@code position} is not after beat 0 is skipped. */
    private static String notAPositionAfterBeatZero(String rawPosition) {
        return "position '" + UnreadProjectItem.logText(rawPosition) + "' is not a beat position after beat 0";
    }

    /** A tempo of the model's range, 20 to 999 BPM, parsed from {@code raw}; empty when it is not one. */
    private static OptionalDouble usableBpm(String raw) {
        OptionalDouble bpm = finiteNumber(raw);
        return bpm.isPresent() && bpm.getAsDouble() >= 20.0 && bpm.getAsDouble() <= 999.0
                ? bpm : OptionalDouble.empty();
    }

    /** A finite number parsed from {@code raw}; empty when it does not parse or is NaN or infinite. */
    private static OptionalDouble finiteNumber(String raw) {
        try {
            double value = Double.parseDouble(raw.strip());
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException notANumber) {
            return OptionalDouble.empty();
        }
    }

    /** A whole number greater than 0 parsed from {@code raw}; empty when it is not one. */
    private static OptionalInt positiveWholeNumber(String raw) {
        try {
            int value = Integer.parseInt(raw.strip());
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException notAWholeNumber) {
            return OptionalInt.empty();
        }
    }

    /** The transition {@code raw} names exactly, as the model spells it; empty for any other text. */
    private static Optional<TempoTransitionType> transitionType(String raw) {
        for (TempoTransitionType type : TempoTransitionType.values()) {
            if (type.name().equals(raw)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads one pre/post-roll bar count: missing is {@code 0}; a negative or
     * unparseable count is {@code 0} and one above
     * {@link PreRollPostRoll#MAX_BARS} is {@code MAX_BARS}, each with a
     * WARNING — a transport preference, so a bad value is logged, not
     * reported to the user.
     */
    private static int parseRollBars(Element elem, String attr) {
        String raw = elem.getAttribute(attr);
        if (raw.isEmpty()) {
            return 0;
        }
        int bars;
        try {
            bars = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            LOG.log(Level.WARNING, "Transport " + attr + " '" + UnreadProjectItem.logText(raw)
                    + "' is not a bar count; 0 is used");
            return 0;
        }
        if (bars < 0) {
            LOG.log(Level.WARNING, "Transport " + attr + " " + bars
                    + " is negative; 0 is used");
            return 0;
        }
        if (bars > PreRollPostRoll.MAX_BARS) {
            LOG.log(Level.WARNING, "Transport " + attr + " " + bars + " is above "
                    + PreRollPostRoll.MAX_BARS + "; " + PreRollPostRoll.MAX_BARS + " is used");
            return PreRollPostRoll.MAX_BARS;
        }
        return bars;
    }

    /**
     * Rebuilds the {@code trackIndex}-th track of the file, with the id it was
     * saved with (see {@link #restoreTrackId}).
     */
    private Track parseTrack(Element elem, int trackIndex, Map<UUID, UnreadProjectItem.OnTrack> restoredTracks) {
        String name = elem.getAttribute("name");
        if (name.isEmpty()) {
            name = "Untitled Track";
        }
        UnreadProjectItem.OnTrack location = new UnreadProjectItem.OnTrack(trackIndex, name);

        String typeStr = elem.getAttribute("type");
        TrackType type = parseTrackType(typeStr);

        Track track = new Track(restoreTrackId(elem, location, restoredTracks), name, type);
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

        // Stable input identity first; the bare "input-device" enumeration index of
        // projects saved before identities existed is kept only as a legacy hint.
        String inputBackend = elem.getAttribute("input-device-backend");
        String inputName = elem.getAttribute("input-device-name");
        if (!inputBackend.isBlank() && !inputName.isBlank()) {
            track.setInputDevice(Optional.of(new DeviceId(inputBackend, inputName)));
        } else {
            int legacyInputDevice = parseIntAttr(elem, "input-device", Track.NO_INPUT_DEVICE);
            if (legacyInputDevice >= Track.NO_INPUT_DEVICE) {
                track.setLegacyInputDeviceIndexHint(legacyInputDevice);
            }
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

        // Parse clips; the main-lane clips saved as comp output are adopted
        // once the take lanes and comp regions are restored (story 334).
        Map<Integer, AudioClip> savedAsCompOutput = new LinkedHashMap<>();
        List<Element> clipsContainers = getDirectChildElements(elem, "clips");
        if (!clipsContainers.isEmpty()) {
            List<Element> clipElements = getDirectChildElements(clipsContainers.getFirst(), "clip");
            for (int i = 0; i < clipElements.size(); i++) {
                Element clipElem = clipElements.get(i);
                AudioClip clip = parseClip(clipElem, "", i, location);
                boolean compOutput = parseBooleanAttr(clipElem, "comp-output");
                track.addClip(clip);
                if (compOutput) {
                    savedAsCompOutput.put(i, clip);
                }
            }
        }
        List<Element> takesContainers = getDirectChildElements(elem, "takes");
        if (!takesContainers.isEmpty()) {
            parseTakes(takesContainers.getFirst(), track, location);
        }
        adoptSavedCompOutput(track, savedAsCompOutput, location);

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

        // MIDI notes and CC lanes, filled into the track's own clip (the
        // clip is final and owned by the track's id). Absent for a track
        // that holds no MIDI and for files written before schema version 2.
        List<Element> midiClipElements = getDirectChildElements(elem, "midi-clip");
        if (!midiClipElements.isEmpty()) {
            parseMidiClip(midiClipElements.getFirst(), track.getMidiClip(), location);
        }

        // MIDI-effect chain, in chain order. Absent for an empty chain.
        List<Element> chainElements = getDirectChildElements(elem, "midi-effect-chain");
        if (!chainElements.isEmpty()) {
            parseMidiEffectChain(chainElements.getFirst(), track.getMidiEffectChain(), location);
        }

        return track;
    }

    /**
     * The id the track is rebuilt with: the saved {@code id} in canonical
     * {@link UUID#toString()} form, so the track's mixer channel (built by
     * {@code DawProject.addTrack} with {@code channelId == trackId}), its
     * MIDI clip and every id-keyed reference in the file resolve to it.
     * {@code restoredTracks} — the ids already restored from this document,
     * each with the track it names — receives it with {@code track}. A
     * missing, malformed or non-UUID id, and a duplicate of an earlier
     * track's id (the first track in document order keeps it), gets a
     * freshly minted id instead, with a WARNING naming the track; references
     * that named it then resolve to nothing, or to the first track. Checked
     * before the track is added, so two tracks never share one mixer
     * channel. A malformed or duplicate id is saved content the next save
     * drops ({@link #losesSavedContent()}); a missing one is not.
     */
    private UUID restoreTrackId(Element elem, UnreadProjectItem.OnTrack track,
                                Map<UUID, UnreadProjectItem.OnTrack> restoredTracks) {
        String raw = elem.getAttribute("id");
        if (raw.isEmpty()) {
            LOG.log(Level.WARNING, track.describe() + ": the id is missing; a new id is used");
            return UUID.randomUUID();
        }
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, track.describe() + ": id '" + UnreadProjectItem.logText(raw)
                    + "' is not a UUID; a new id is used");
            droppedUnlistedContent = true;
            return UUID.randomUUID();
        }
        UnreadProjectItem.OnTrack earlier = restoredTracks.putIfAbsent(id, track);
        if (earlier != null) {
            LOG.log(Level.WARNING, track.describe() + ": id " + id + " is already used by "
                    + earlier.describe() + "; a new id is used");
            droppedUnlistedContent = true;
            return UUID.randomUUID();
        }
        return id;
    }

    /**
     * Restores the take lanes and comp regions of {@code track} from its
     * {@code <takes>} element (story 334; Persistence Integrity book §4.6;
     * studio rulings E8, E13, E15). Each {@code <take>}, numbered from 1, is
     * a lane in document order: its id ({@link #restoreTakeId}), its name —
     * {@code Take <t>} when absent or blank — its mute flag, and its
     * {@code <clip>} children through the main lane's clip reader, whose
     * WARNINGs name the take ({@code take <t> '<name>' clip <c> '<name>'}).
     * A lane with no clips is kept. The comp regions then resolve their lane
     * by the id as saved, before any fresh id was given, a repeated id to the
     * first lane carrying it ({@link #parseCompRegions}). The lanes' solo is
     * never saved: every lane opens unsoloed. Nothing is compiled.
     */
    private void parseTakes(Element takesElem, Track track, UnreadProjectItem.OnTrack location) {
        TakeComping comping = track.getTakeComping();
        Map<String, Integer> laneBySavedId = new HashMap<>();
        Map<UUID, String> restoredIds = new HashMap<>();
        List<Element> takeElements = getDirectChildElements(takesElem, "take");
        for (int t = 0; t < takeElements.size(); t++) {
            Element takeElem = takeElements.get(t);
            String rawId = takeElem.getAttribute("id");
            String name = takeElem.getAttribute("name");
            if (name.isBlank()) {
                name = "Take " + (t + 1);
            }
            String takeInLog = "take " + (t + 1) + " '" + UnreadProjectItem.logText(name) + "'";
            TakeLane lane = new TakeLane(restoreTakeId(rawId, takeInLog, location, restoredIds), name);
            lane.setMuted(parseBooleanAttr(takeElem, "muted"));
            List<Element> clipElements = getDirectChildElements(takeElem, "clip");
            for (int c = 0; c < clipElements.size(); c++) {
                lane.addClip(parseClip(clipElements.get(c), takeInLog + " ", c, location));
            }
            comping.addTakeLane(lane);
            if (!rawId.isEmpty()) {
                laneBySavedId.putIfAbsent(rawId, t);
            }
        }
        parseCompRegions(takesElem, comping, laneBySavedId, location);
    }

    /**
     * The id a take lane is restored with: its saved {@code id} when that is
     * a UUID in canonical {@link UUID#toString()} form that no earlier lane
     * of the track took (the id its comp regions name it by). A missing id,
     * one that is not a canonical UUID, or one an earlier lane took gets a
     * fresh id instead, with a WARNING naming the track and the take
     * ({@code takeInLog}), as {@link #restoreTrackId} does for a track (story
     * 330 R1; studio ruling E15): a missing id loses nothing, while a
     * malformed or repeated one is saved content the next save drops
     * ({@link #losesSavedContent()}). {@code restoredIds} receives the id
     * with the take that took it.
     */
    private UUID restoreTakeId(String raw, String takeInLog, UnreadProjectItem.OnTrack location,
                               Map<UUID, String> restoredIds) {
        if (raw.isEmpty()) {
            LOG.log(Level.WARNING, location.describe() + ": " + takeInLog + " has no id; a new id is used");
            return UUID.randomUUID();
        }
        Optional<UUID> canonical = canonicalUuid(raw);
        if (canonical.isEmpty()) {
            LOG.log(Level.WARNING, location.describe() + ": " + takeInLog + " id '" + UnreadProjectItem.logText(raw)
                    + "' is not a UUID; a new id is used");
            droppedUnlistedContent = true;
            return UUID.randomUUID();
        }
        String earlier = restoredIds.putIfAbsent(canonical.get(), takeInLog);
        if (earlier != null) {
            LOG.log(Level.WARNING, location.describe() + ": " + takeInLog + " id " + canonical.get()
                    + " is already used by " + earlier + "; a new id is used");
            droppedUnlistedContent = true;
            return UUID.randomUUID();
        }
        return canonical.get();
    }

    /** The UUID {@code raw} spells in canonical {@link UUID#toString()} form; empty for any other text. */
    private static Optional<UUID> canonicalUuid(String raw) {
        try {
            UUID uuid = UUID.fromString(raw);
            return uuid.toString().equals(raw) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /**
     * The comp regions of a track's {@code <takes>}, numbered from 1 among
     * the saved {@code <comp-region>} elements (story 334, studio ruling
     * E15). A region is skipped and reported ({@code COMP_REGION_SKIPPED},
     * arming the degraded-open backup) when the first of these fails, in
     * order: its {@code take-id} names a take of this track as saved
     * ({@code laneBySavedId}); its {@code start-beat} is a finite beat
     * &ge; 0; its {@code duration-beats} is a finite number &gt; 0; it does
     * not overlap a region accepted before it (the first in document order
     * is kept). The accepted regions are set at once, in document order —
     * never through {@link TakeComping#addCompRegion}, which would drop an
     * overlap in silence.
     */
    private void parseCompRegions(Element takesElem, TakeComping comping, Map<String, Integer> laneBySavedId,
                                  UnreadProjectItem.OnTrack location) {
        List<CompRegion> accepted = new ArrayList<>();
        List<Integer> acceptedNumbers = new ArrayList<>();
        List<Element> regionElements = getDirectChildElements(takesElem, "comp-region");
        for (int r = 0; r < regionElements.size(); r++) {
            Element regionElem = regionElements.get(r);
            String rawTake = regionElem.getAttribute("take-id");
            String rawStart = regionElem.getAttribute("start-beat");
            String rawDuration = regionElem.getAttribute("duration-beats");
            Integer laneIndex = rawTake.isEmpty() ? null : laneBySavedId.get(rawTake);
            OptionalDouble start = finiteNumber(rawStart);
            OptionalDouble duration = finiteNumber(rawDuration);
            String reason;
            if (laneIndex == null) {
                reason = "take '" + UnreadProjectItem.logText(rawTake) + "' is not a take on this track";
            } else if (start.isEmpty() || start.getAsDouble() < 0) {
                reason = "start-beat '" + UnreadProjectItem.logText(rawStart) + "' is not a beat position ≥ 0";
            } else if (duration.isEmpty() || !(duration.getAsDouble() > 0)) {
                reason = "duration-beats '" + UnreadProjectItem.logText(rawDuration)
                        + "' is not a positive number of beats";
            } else {
                CompRegion region = new CompRegion(laneIndex, start.getAsDouble(), duration.getAsDouble());
                int overlapped = firstOverlapped(accepted, acceptedNumbers, region);
                if (overlapped == 0) {
                    accepted.add(region);
                    acceptedNumbers.add(r + 1);
                    continue;
                }
                reason = "it overlaps comp region " + overlapped;
            }
            reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.COMP_REGION_SKIPPED, location, r,
                    reason + savedAs(regionElem, COMP_REGION), 1));
        }
        comping.setCompRegions(accepted);
    }

    /** The number of the first of {@code accepted} that {@code region} overlaps; 0 when it overlaps none. */
    private static int firstOverlapped(List<CompRegion> accepted, List<Integer> acceptedNumbers, CompRegion region) {
        for (int a = 0; a < accepted.size(); a++) {
            if (accepted.get(a).overlaps(region.startBeat(), region.endBeat())) {
                return acceptedNumbers.get(a);
            }
        }
        return 0;
    }

    /**
     * Adopts the main-lane clips the file saved as comp output
     * ({@code clip@comp-output}, keyed by their clip index) as the current
     * pass's output of the track's restored comping (story 334, studio
     * ruling E14), so the next compile replaces exactly them; the open never
     * compiles. On a track that has no takes the mark means nothing: each
     * marked clip stays an ordinary clip, with a WARNING, and no backup.
     */
    private void adoptSavedCompOutput(Track track, Map<Integer, AudioClip> savedAsCompOutput,
                                      UnreadProjectItem.OnTrack location) {
        TakeComping comping = track.getTakeComping();
        if (comping.isActive()) {
            savedAsCompOutput.values().forEach(comping::adoptCompOutput);
            return;
        }
        savedAsCompOutput.forEach((clipIndex, clip) -> LOG.log(Level.WARNING, location.describe() + ": "
                + describeClip(clipIndex, clip.getName())
                + " is saved as comp output, but the track has no takes; it is kept as an ordinary clip"));
    }

    /**
     * Fills {@code clip} in place from a {@code <midi-clip>} element: its lock
     * flag, its notes in file order and its CC lanes with their breakpoints.
     * Columns are read as stored (one column is a quarter of a transport
     * beat). A note, lane or event the model rejects — or whose value is
     * missing or not a number — is skipped and reported; nothing is clamped,
     * because a clamped note is a note the user never wrote. Duplicate or
     * overlapping notes are kept as saved.
     */
    private void parseMidiClip(Element clipElem, MidiClip clip, UnreadProjectItem.OnTrack location) {
        clip.setLocked(parseBooleanAttr(clipElem, "locked"));

        List<Element> notesContainers = getDirectChildElements(clipElem, "notes");
        if (!notesContainers.isEmpty()) {
            List<Element> noteElements = getDirectChildElements(notesContainers.getFirst(), "note");
            for (int i = 0; i < noteElements.size(); i++) {
                Element noteElem = noteElements.get(i);
                try {
                    clip.addNote(new MidiNoteData(
                            requiredIntAttr(noteElem, "note-number"),
                            requiredIntAttr(noteElem, "start-column"),
                            requiredIntAttr(noteElem, "duration-columns"),
                            requiredIntAttr(noteElem, "velocity"),
                            noteElem.hasAttribute("channel")
                                    ? requiredIntAttr(noteElem, "channel")
                                    : MidiNoteData.DEFAULT_CHANNEL));
                } catch (IllegalArgumentException e) {
                    reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED,
                            location, i, e.getMessage() + savedAs(noteElem, NOTE)
                                    + atTransportBeat(noteElem, "start-column"), 1));
                }
            }
        }

        List<Element> lanesContainers = getDirectChildElements(clipElem, "cc-lanes");
        if (!lanesContainers.isEmpty()) {
            List<Element> laneElements = getDirectChildElements(lanesContainers.getFirst(), "cc-lane");
            for (int i = 0; i < laneElements.size(); i++) {
                MidiCcLane lane = parseCcLane(laneElements.get(i), i, location);
                if (lane != null) {
                    clip.addCcLane(lane);
                }
            }
        }
    }

    /**
     * Reads the {@code laneIndex}-th CC lane, or skips and reports it —
     * counting as its saved events, none for a lane saved without events —
     * when its type, CC number, resolution or channel cannot be read or is
     * rejected by {@link MidiCcLane}: its values cannot be interpreted
     * without them. A preset lane type's CC number comes from the type. An
     * unusable {@code height-ratio} is view layout, not music: it is logged
     * and the default is kept. Each breakpoint the model rejects is skipped
     * and reported on its own.
     */
    private MidiCcLane parseCcLane(Element laneElem, int laneIndex, UnreadProjectItem.OnTrack location) {
        List<Element> eventElements = getDirectChildElements(laneElem, "event");
        MidiCcLane lane;
        try {
            String typeStr = laneElem.getAttribute("type");
            MidiCcLaneType type;
            try {
                type = MidiCcLaneType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "type '" + UnreadProjectItem.logText(typeStr) + "' is not a CC lane type");
            }
            int ccNumber = type == MidiCcLaneType.ARBITRARY_CC
                    ? requiredIntAttr(laneElem, "cc-number")
                    : type.defaultCcNumber();
            boolean highResolution = strictBooleanAttr(laneElem, "high-resolution");
            int channel = laneElem.hasAttribute("channel") ? requiredIntAttr(laneElem, "channel") : 0;
            lane = new MidiCcLane(type, ccNumber, highResolution, channel);
        } catch (IllegalArgumentException e) {
            reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.CC_LANE_SKIPPED, location,
                    laneIndex, e.getMessage() + savedAs(laneElem, CC_LANE), eventElements.size()));
            return null;
        }

        String heightRatio = laneElem.getAttribute("height-ratio");
        if (!heightRatio.isEmpty()) {
            try {
                lane.setHeightRatio(Double.parseDouble(heightRatio));
            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, location.describe() + ": CC lane " + (laneIndex + 1)
                        + " height-ratio '" + UnreadProjectItem.logText(heightRatio)
                        + "' is not a positive number; " + MidiCcLane.DEFAULT_HEIGHT_RATIO + " is used");
            }
        }

        UnreadProjectItem.EventLane eventLane =
                new UnreadProjectItem.EventLane(laneIndex, lane.getType(), lane.getCcNumber());
        for (int i = 0; i < eventElements.size(); i++) {
            Element eventElem = eventElements.get(i);
            try {
                lane.addEvent(new MidiCcEvent(
                        requiredIntAttr(eventElem, "column"),
                        requiredIntAttr(eventElem, "value")));
            } catch (IllegalArgumentException e) {
                reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.CC_EVENT_SKIPPED, location, i,
                        e.getMessage() + savedAs(eventElem, CC_EVENT)
                                + atTransportBeat(eventElem, "column"), 1, eventLane));
            }
        }
        return lane;
    }

    /**
     * {@code "; saved as <tag a="v" …/>"}: {@code elem} as {@code form}
     * writes it — its attributes in the writer's order, never the DOM's
     * attribute order — each value as saved and quoted by
     * {@link UnreadProjectItem#logText}; an attribute the element does not
     * carry is left out. The locator of a skipped note, lane or event for
     * whoever repairs the degraded backup by hand.
     */
    private static String savedAs(Element elem, WrittenForm form) {
        StringBuilder snippet = new StringBuilder("; saved as <").append(form.tag());
        for (String attribute : form.attributes()) {
            if (elem.hasAttribute(attribute)) {
                snippet.append(' ').append(attribute).append("=\"")
                        .append(UnreadProjectItem.logText(elem.getAttribute(attribute))).append('"');
            }
        }
        return snippet.append("/>").toString();
    }

    /**
     * {@code ", at transport beat <b>"} for the saved column in
     * {@code columnAttribute} when it is an integer &ge; 0 — the column in
     * transport beats from the project start, without trailing zeros
     * ({@code 17} gives {@code 4.25}) — else {@code ""}.
     */
    private static String atTransportBeat(Element elem, String columnAttribute) {
        int column;
        try {
            column = Integer.parseInt(elem.getAttribute(columnAttribute));
        } catch (NumberFormatException e) {
            return "";
        }
        if (column < 0) {
            return "";
        }
        BigDecimal beat = BigDecimal.valueOf(column * MidiRecorder.BEATS_PER_COLUMN).stripTrailingZeros();
        return ", at transport beat " + beat.toPlainString();
    }

    /**
     * Restores a track's MIDI-effect chain from its {@code <midi-effect>}
     * children, in chain order. Each entry resolves through a closed switch on
     * its stable {@code plugin-id} — never by loading a class named in the
     * file. An entry this build does not recognise (unknown or missing
     * {@code plugin-id}) is kept in its position as an
     * {@link UnresolvedMidiEffect} carrying its attributes, so no entry is
     * dropped and none shifts. Files written before schema version 2 keyed
     * the entry by class name; the 1→2 migration rewrites the known one.
     */
    private void parseMidiEffectChain(Element chainElem, List<MidiEffectPlugin> chain,
                                      UnreadProjectItem.OnTrack location) {
        List<Element> effectElements = getDirectChildElements(chainElem, "midi-effect");
        for (int i = 0; i < effectElements.size(); i++) {
            Element effectElem = effectElements.get(i);
            MidiEffectPlugin effect = switch (effectElem.getAttribute("plugin-id")) {
                case ArpeggiatorPlugin.PLUGIN_ID -> parseArpeggiator(effectElem, i, location);
                default -> unresolvedMidiEffect(effectElem, i, location);
            };
            chain.add(effect);
        }
    }

    /**
     * Builds an arpeggiator from its saved settings. An absent setting keeps
     * the plugin's default. A setting that is present but invalid is adjusted
     * and reported: a finite number outside the plugin's range is clamped to
     * it; an unknown name, an unparseable or non-finite number takes the
     * plugin's default.
     */
    private ArpeggiatorPlugin parseArpeggiator(Element elem, int chainIndex,
                                               UnreadProjectItem.OnTrack location) {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        if (elem.hasAttribute("rate")) {
            String raw = elem.getAttribute("rate");
            try {
                arp.setRate(ArpeggiatorPlugin.Rate.valueOf(raw));
            } catch (IllegalArgumentException e) {
                reportArpeggiatorSetting(location, chainIndex, "rate '" + UnreadProjectItem.logText(raw)
                        + "' is not a rate; " + arp.getRate() + " is used");
            }
        }
        if (elem.hasAttribute("pattern")) {
            String raw = elem.getAttribute("pattern");
            try {
                arp.setPattern(ArpeggiatorPlugin.Pattern.valueOf(raw));
            } catch (IllegalArgumentException e) {
                reportArpeggiatorSetting(location, chainIndex, "pattern '" + UnreadProjectItem.logText(raw)
                        + "' is not a pattern; " + arp.getPattern() + " is used");
            }
        }
        if (elem.hasAttribute("octave-range")) {
            String raw = elem.getAttribute("octave-range");
            try {
                int octaves = Integer.parseInt(raw);
                int clamped = Math.clamp(octaves, ArpeggiatorPlugin.MIN_OCTAVE, ArpeggiatorPlugin.MAX_OCTAVE);
                arp.setOctaveRange(clamped);
                if (clamped != octaves) {
                    reportArpeggiatorSetting(location, chainIndex, "octave-range " + octaves
                            + " is outside " + ArpeggiatorPlugin.MIN_OCTAVE + "–"
                            + ArpeggiatorPlugin.MAX_OCTAVE + "; " + clamped + " is used");
                }
            } catch (NumberFormatException e) {
                reportArpeggiatorSetting(location, chainIndex, "octave-range '"
                        + UnreadProjectItem.logText(raw) + "' is not an integer; " + arp.getOctaveRange() + " is used");
            }
        }
        if (elem.hasAttribute("gate")) {
            arp.setGate(parseArpeggiatorPercent(elem, "gate", arp.getGate(),
                    ArpeggiatorPlugin.MIN_GATE, ArpeggiatorPlugin.MAX_GATE, chainIndex, location));
        }
        if (elem.hasAttribute("swing")) {
            arp.setSwing(parseArpeggiatorPercent(elem, "swing", arp.getSwing(),
                    ArpeggiatorPlugin.MIN_SWING, ArpeggiatorPlugin.MAX_SWING, chainIndex, location));
        }
        if (elem.hasAttribute("latch")) {
            arp.setLatch(Boolean.parseBoolean(elem.getAttribute("latch")));
        }
        return arp;
    }

    /**
     * Reads a saved arpeggiator percentage: {@code defaultValue} for an
     * unparseable or non-finite value ({@code Double.parseDouble} accepts
     * {@code NaN} and {@code Infinity}, which the plugin's setters would let
     * through), the nearest bound for a finite value outside
     * {@code [min, max]} — each reported.
     */
    private double parseArpeggiatorPercent(Element elem, String attr, double defaultValue,
                                           double min, double max, int chainIndex,
                                           UnreadProjectItem.OnTrack location) {
        String raw = elem.getAttribute(attr);
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            value = Double.NaN;
        }
        if (!Double.isFinite(value)) {
            reportArpeggiatorSetting(location, chainIndex, attr + " '" + UnreadProjectItem.logText(raw)
                    + "' is not a finite number; " + defaultValue + " is used");
            return defaultValue;
        }
        double clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            reportArpeggiatorSetting(location, chainIndex, attr + " " + UnreadProjectItem.logText(raw)
                    + " is outside " + min + "–" + max + "; " + clamped + " is used");
        }
        return clamped;
    }

    private void reportArpeggiatorSetting(UnreadProjectItem.OnTrack location, int chainIndex, String detail) {
        reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.MIDI_EFFECT_SETTING_ADJUSTED,
                location, chainIndex, "arpeggiator " + detail, 1));
    }

    /**
     * Keeps a {@code <midi-effect>} this build does not recognise as a
     * pass-through placeholder with an immutable copy of its attributes, and
     * reports it. Child elements and non-blank text (or CDATA) of such an
     * entry are logged and dropped: no writer produces them, and the next
     * save cannot write them back ({@link #losesSavedContent()}).
     */
    private UnresolvedMidiEffect unresolvedMidiEffect(Element elem, int chainIndex,
                                                      UnreadProjectItem.OnTrack location) {
        SequencedMap<String, String> attributes = savedAttributes(elem);
        dropUnwrittenContent(location.describe() + ": MIDI effect " + (chainIndex + 1), elem, 0);
        UnresolvedMidiEffect placeholder = new UnresolvedMidiEffect(attributes);
        String pluginId = placeholder.pluginId();
        reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED, location,
                chainIndex, pluginId.isEmpty()
                        ? "the entry has no plugin-id " + UnreadProjectItem.logText(attributes.toString())
                        : "plugin-id '" + UnreadProjectItem.logText(pluginId)
                                + "' is not a MIDI effect this build knows",
                1));
        return placeholder;
    }

    /**
     * Every attribute of {@code elem}, name to value, in the order the DOM
     * holds them (the JDK's DOM keeps an element's attributes sorted by name):
     * what a placeholder — an unrecognised MIDI effect, an offline insert and
     * its kept children — writes back as saved.
     */
    private static SequencedMap<String, String> savedAttributes(Element elem) {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        NamedNodeMap attributeNodes = elem.getAttributes();
        for (int i = 0; i < attributeNodes.getLength(); i++) {
            Node attribute = attributeNodes.item(i);
            attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return attributes;
    }

    /**
     * Logs and drops the content of a placeholder's element that no writer
     * produces, and that the next save therefore cannot write back: its child
     * elements beyond the {@code keptElements} the placeholder keeps, and its
     * non-blank text (or CDATA) — {@code <subject> has 1 child element no
     * writer produces; it is dropped} (story 330 precedent; {@link #losesSavedContent()}).
     *
     * @param subject      the placeholder as the log names it, e.g. {@code Track 2 'Vox': insert 2}
     * @param elem         the placeholder's element
     * @param keptElements how many of its child elements the placeholder keeps
     */
    private void dropUnwrittenContent(String subject, Element elem, int keptElements) {
        int childElements = -keptElements;
        boolean text = false;
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                childElements++;
            } else if (child instanceof Text content && !content.getData().isBlank()) {
                text = true;
            }
        }
        dropContent(subject, childElements, text);
    }

    /**
     * Logs {@code <subject> has <what> no writer produces; it is dropped} and
     * records that the next save loses saved content; nothing when there is
     * nothing to drop.
     *
     * @param subject       the element as the log names it
     * @param childElements how many of its child elements are dropped
     * @param text          whether its non-blank text is dropped
     */
    private void dropContent(String subject, int childElements, boolean text) {
        if (childElements > 0 || text) {
            String dropped = childElements == 0 ? "text"
                    : childElements + (childElements == 1 ? " child element" : " child elements")
                            + (text ? " and text" : "");
            boolean several = childElements + (text ? 1 : 0) > 1;
            LOG.log(Level.WARNING, subject + " has " + dropped + " no writer produces; "
                    + (several ? "they are" : "it is") + " dropped");
            droppedUnlistedContent = true;
        }
    }

    /**
     * Rebuilds the {@code clipIndex}-th {@code <clip>} saved on the track at
     * {@code location} — of its main lane, or of one of its take lanes; its
     * WARNINGs name the track, then {@code takeInLog} — empty for a
     * main-lane clip, {@code take <t> '<name>' } for a lane clip (story 334,
     * studio ruling G9), so a lane clip is never taken for the main lane's
     * clip of the same number — then the clip ({@link #describeClip}).
     */
    private AudioClip parseClip(Element elem, String takeInLog, int clipIndex, UnreadProjectItem.OnTrack location) {
        String name = elem.getAttribute("name");
        if (name.isEmpty()) {
            name = "Untitled Clip";
        }
        String clipInLog = takeInLog + describeClip(clipIndex, name);

        double startBeat = Math.max(0.0, parseDoubleAttr(elem, "start-beat", 0.0));
        double durationBeats = Math.max(0.001, parseDoubleAttr(elem, "duration-beats", 1.0));
        String sourceFile = elem.getAttribute("source-file");
        if (sourceFile.isEmpty()) {
            sourceFile = null;
        }
        // Resolves and checks each segment once, in order.
        List<String> segmentPaths = parseSourceSegments(elem, clipInLog, location);

        // The segment list is the authority (AudioClip head invariant): each
        // segment was checked once, and setSourceSegmentPaths below makes the
        // first segment the head, so a clip with segments never has its
        // source-file resolved, checked, warned about or reported.
        if (segmentPaths.isEmpty() && sourceFile != null) {
            sourceFile = resolveAndCheck(location, clipInLog + " source file", sourceFile);
        }

        AudioClip clip = new AudioClip(name, startBeat, durationBeats, sourceFile);
        if (!segmentPaths.isEmpty()) {
            clip.setSourceSegmentPaths(segmentPaths);
        }
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

    /**
     * Reads a recorded take's ordered {@code <source-segment path="…"/>}
     * children (story 323), each resolved and checked once, in order, by
     * {@link #resolveAndCheck}; empty for a single-file clip and for files
     * written before story 323. A child whose path is unresolvable against
     * the project directory stays in its place, as written, so the list
     * keeps its count, order and head. A child whose {@code path} is empty
     * or missing is skipped with a WARNING that names the track, the clip
     * ({@code clipInLog}) and the segment, numbered from 1 — the one element
     * the list cannot carry ({@code AudioClip.setSourceSegmentPaths} refuses
     * it).
     */
    private List<String> parseSourceSegments(Element clipElem, String clipInLog, UnreadProjectItem.OnTrack location) {
        List<Element> segmentElements = getDirectChildElements(clipElem, "source-segment");
        if (segmentElements.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>(segmentElements.size());
        for (int i = 0; i < segmentElements.size(); i++) {
            String segment = clipInLog + " source segment " + (i + 1) + " of " + segmentElements.size();
            String path = segmentElements.get(i).getAttribute("path");
            if (!path.isEmpty()) {
                paths.add(resolveAndCheck(location, segment, path));
            } else {
                // A segment that cannot be named cannot be loaded; say so
                // rather than shorten the take's segment order in silence.
                LOG.log(Level.WARNING, location.describe() + ": " + segment + " skipped — the path is missing");
            }
        }
        return paths;
    }

    /**
     * {@code clip <clipIndex + 1> '<name>'}: a clip as its WARNINGs name it —
     * its position among the saved {@code <clip>} elements of its lane (the
     * track's main lane, or a take), counted
     * from 1, since clip names repeat (a duplicate or a paste keeps the
     * name), and its name as restored, which the timeline shows, quoted by
     * {@link UnreadProjectItem#logText}.
     */
    private static String describeClip(int clipIndex, String name) {
        return "clip " + (clipIndex + 1) + " '" + UnreadProjectItem.logText(name) + "'";
    }

    /**
     * Resolves one clip reference through {@link ProjectPaths} and checks it
     * for {@link #missingFiles}. A reference that is
     * {@linkplain ProjectPaths#unresolvable unresolvable} against the project
     * directory — it escapes the directory or is the directory itself, it is
     * not a valid path on this platform, or it is drive- or root-relative on
     * this platform — is not resolved: it is kept as written, logged once at
     * WARNING naming the track, the clip, the reference (quoted by
     * {@link UnreadProjectItem#logText}) and the reason, and added to
     * {@link #missingFiles} unconditionally, never through
     * {@link #noteIfMissing}, whose existence check would judge a relative,
     * drive-relative or root-relative string from the JVM's working
     * directory or the current drive. Any other reference goes through
     * {@link ProjectPaths#resolve} and is added only when the result names no
     * existing file; with a directory that result is an absolute path.
     *
     * @param location  the track the clip is saved on, which the warning names
     * @param what      which reference of which clip this is, as the warning
     *                  names it ({@link #describeClip}, then the reference)
     * @param reference the persisted reference; not empty
     * @return the in-memory form
     */
    private String resolveAndCheck(UnreadProjectItem.OnTrack location, String what, String reference) {
        Optional<ProjectPaths.Unresolvable> unresolvable =
                ProjectPaths.unresolvable(projectDirectory, reference);
        if (unresolvable.isPresent()) {
            LOG.log(Level.WARNING, location.describe() + ": " + what + " not resolved — the path '"
                    + UnreadProjectItem.logText(reference) + "' " + unresolvable.get().reason()
                    + " (project directory " + projectDirectory + "); it is kept as written and"
                    + " reported missing");
            missingFiles.add(reference);
            return reference;
        }
        String resolved = ProjectPaths.resolve(projectDirectory, reference);
        noteIfMissing(resolved);
        return resolved;
    }

    /** Adds {@code reference} to {@link #missingFiles} unless it names an existing file. */
    private void noteIfMissing(String reference) {
        try {
            if (!Files.exists(Path.of(reference))) {
                missingFiles.add(reference);
            }
        } catch (java.nio.file.InvalidPathException ignored) {
            missingFiles.add(reference);
        }
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

    /**
     * Applies the {@code <mixer>} element. The id-keyed references in it — a
     * cue send's {@code track-id}, a VCA member's {@code channel-id}, a
     * channel link's two channel ids (a track's channel id equals its track
     * id) — resolve only to a track restored from this file
     * ({@code restoredTracks}); see {@link #resolveTrackReference}.
     */
    private void parseMixer(Element mixerElem, DawProject project,
                            Map<UUID, UnreadProjectItem.OnTrack> restoredTracks) {
        // Parse master channel
        List<Element> masterElements = getDirectChildElements(mixerElem, "master");
        if (!masterElements.isEmpty()) {
            applyMixerChannelAttrs(masterElements.getFirst(), project.getMixer().getMasterChannel(),
                    new UnreadProjectItem.InMixer("master"), project);
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
                // Numbered like a track: bus names repeat, and a bus the
                // default mixer already holds keeps its own name, not the file's.
                applyMixerChannelAttrs(rbElem, bus, new UnreadProjectItem.InMixer("return bus " + (i + 1)
                        + " '" + UnreadProjectItem.logText(bus.getName()) + "'"), project);
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
                MixerChannel channel = existingChannels.get(i);
                applyMixerChannelAttrs(channelElements.get(i), channel,
                        trackChannelStrip(channel, i, project), project);
            }
        }

        // Parse cue buses (Story 135). The element is optional: legacy
        // projects predating cue-bus support load with no cue buses because
        // the manager starts empty. Mirrors the serializer's <cue-buses>
        // section so cue-mix configurations round-trip across save/load.
        List<Element> cueBusesContainers = getDirectChildElements(mixerElem, "cue-buses");
        if (!cueBusesContainers.isEmpty()) {
            CueBusManager cueManager = project.getCueBusManager();
            for (Element busElem : getDirectChildElements(cueBusesContainers.getFirst(), "cue-bus")) {
                String idAttr = busElem.getAttribute("id");
                if (idAttr.isEmpty()) {
                    continue;
                }
                java.util.UUID id;
                try {
                    id = java.util.UUID.fromString(idAttr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String label = busElem.getAttribute("label");
                if (label.isEmpty()) {
                    label = "Cue";
                }
                int hardwareOutputIndex = Math.max(0,
                        parseIntAttr(busElem, "hardware-output-index", 0));
                double masterGain = clampDouble(
                        parseDoubleAttr(busElem, "master-gain", 1.0), 0.0, 1.0);
                UnreadProjectItem.InMixer busLocation =
                        new UnreadProjectItem.InMixer("cue bus '" + UnreadProjectItem.logText(label) + "'");
                List<CueSend> sends = new ArrayList<>();
                Set<UUID> sentTracks = new HashSet<>();
                List<Element> sendElements = getDirectChildElements(busElem, "cue-send");
                for (int i = 0; i < sendElements.size(); i++) {
                    Element sendElem = sendElements.get(i);
                    UUID trackId = resolveTrackReference(sendElem, "cue-send", "track-id",
                            restoredTracks, sentTracks, busLocation, i);
                    if (trackId == null) {
                        continue;
                    }
                    double gain = clampDouble(parseDoubleAttr(sendElem, "gain", 0.0), 0.0, 1.0);
                    double pan = clampDouble(parseDoubleAttr(sendElem, "pan", 0.0), -1.0, 1.0);
                    boolean preFader = parseBooleanAttr(sendElem, "pre-fader", true);
                    sends.add(new CueSend(trackId, gain, pan, preFader));
                }
                try {
                    cueManager.addCueBus(new CueBus(id, label, hardwareOutputIndex, sends, masterGain));
                } catch (IllegalArgumentException ignored) {
                    // A duplicate id or hardware-output-index in the file is
                    // malformed — skip the bus rather than failing the whole load.
                }
            }
        }

        // Parse VCA groups. The element is optional: legacy projects predating
        // VCA support load with no VCAs because the manager starts empty.
        List<Element> vcaGroupsContainers = getDirectChildElements(mixerElem, "vca-groups");
        if (!vcaGroupsContainers.isEmpty()) {
            VcaGroupManager vcaManager = project.getVcaGroupManager();
            // The label of each group read so far, by id: the first group in
            // document order keeps a repeated id.
            Map<UUID, String> groupLabels = new HashMap<>();
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
                String logged = "Mixer VCA group '" + UnreadProjectItem.logText(label) + "'";
                List<Element> memberElements = getDirectChildElements(groupElem, "vca-member");
                // A repeated id is skipped before its members are read, so the
                // open reports nothing for a group it does not keep. A
                // re-id'd copy would double its gain on the shared members.
                String firstLabel = groupLabels.putIfAbsent(id, label);
                if (firstLabel != null) {
                    int members = memberElements.size();
                    LOG.log(Level.WARNING, logged + ": id " + id + " is already used by VCA group '"
                            + UnreadProjectItem.logText(firstLabel) + "'; the group and its " + members
                            + (members == 1 ? " saved member are" : " saved members are") + " skipped");
                    droppedUnlistedContent = true;
                    continue;
                }
                double gainDb = 0.0;
                String rawGain = groupElem.getAttribute("master-gain-db");
                if (!rawGain.isEmpty()) {
                    try {
                        gainDb = Double.parseDouble(rawGain);
                    } catch (NumberFormatException e) {
                        gainDb = Double.NaN;
                    }
                    if (Double.isNaN(gainDb)) {
                        // No level information: unity, the attribute's default.
                        LOG.log(Level.WARNING, logged + ": master-gain-db '" + UnreadProjectItem.logText(rawGain)
                                + "' is not a number; 0 dB is used");
                        gainDb = 0.0;
                    }
                }
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
                // The group, its label and its gain are kept whatever happens
                // to its members.
                UnreadProjectItem.InMixer groupLocation =
                        new UnreadProjectItem.InMixer("VCA group '" + UnreadProjectItem.logText(label) + "'");
                List<UUID> members = new ArrayList<>();
                Set<UUID> memberSet = new HashSet<>();
                for (int i = 0; i < memberElements.size(); i++) {
                    UUID member = resolveTrackReference(memberElements.get(i), "vca-member", "channel-id",
                            restoredTracks, memberSet, groupLocation, i);
                    if (member != null) {
                        members.add(member);
                    }
                }
                // Neither the id (checked above) nor the gain (finite or
                // clamped) can make the group or the manager refuse it.
                vcaManager.addVcaGroup(new VcaGroup(id, label, gainDb, color, members));
            }
        }

        // Parse channel links. The element is optional: legacy projects predating
        // channel-link support load with no links because the manager starts empty.
        List<Element> channelLinksContainers = getDirectChildElements(mixerElem, "channel-links");
        if (!channelLinksContainers.isEmpty()) {
            ChannelLinkManager linkManager = project.getChannelLinkManager();
            UnreadProjectItem.InMixer linksLocation = new UnreadProjectItem.InMixer("channel links");
            // A channel belongs to at most one link: the first link that names
            // it wins.
            Set<UUID> linkedChannels = new HashSet<>();
            List<Element> linkElements = getDirectChildElements(channelLinksContainers.getFirst(), "channel-link");
            for (int i = 0; i < linkElements.size(); i++) {
                Element linkElem = linkElements.get(i);
                // A link with either side unresolved is dropped whole, and
                // reported once.
                TrackReference left = readTrackReference(linkElem, "channel-link", "left-channel-id",
                        restoredTracks, linkedChannels, LINK_REPEAT);
                TrackReference right = readTrackReference(linkElem, "channel-link", "right-channel-id",
                        restoredTracks, linkedChannels, LINK_REPEAT);
                String problem = left.resolved() && right.resolved() && left.id().equals(right.id())
                        ? "channel-link links " + restoredTracks.get(left.id()).describe() + " (" + left.id()
                                + ") to itself"
                        : TrackReference.problemOf(left, right);
                if (problem != null) {
                    reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED,
                            linksLocation, i, problem, 1));
                    continue;
                }
                UUID leftId = left.id();
                UUID rightId = right.id();
                linkedChannels.add(leftId);
                linkedChannels.add(rightId);
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
                // Story 322 removed "link-inserts"; a legacy attribute is
                // tolerated by simply not being read.
                boolean linkSends = parseBooleanAttr(linkElem, "link-sends", true);
                // Neither channel is linked yet (linkedChannels above), so the
                // manager accepts the link.
                linkManager.link(new ChannelLink(leftId, rightId, mode,
                        linkFaders, linkPans, linkMuteSolo, linkSends));
            }
        }
    }

    /** Why a cue send, VCA member or bed routing that repeats an earlier one is removed. */
    private static final String ASSIGNMENT_REPEAT = "repeats an earlier assignment of the same track";

    /** Why a channel-link side naming an already linked channel is removed. */
    private static final String LINK_REPEAT = "is already in an earlier channel link";

    /**
     * One id-keyed mixer reference as read: the track id it resolves to, or
     * why it does not resolve.
     *
     * @param id      the referenced track (and channel) id; {@code null} when unresolved
     * @param problem why the reference cannot be honoured; {@code null} when resolved
     */
    private record TrackReference(UUID id, String problem) {

        boolean resolved() {
            return problem == null;
        }

        /** The problems of the unresolved references among {@code references}, or {@code null}. */
        static String problemOf(TrackReference... references) {
            StringBuilder problems = new StringBuilder();
            for (TrackReference reference : references) {
                if (!reference.resolved()) {
                    problems.append(problems.isEmpty() ? "" : "; ").append(reference.problem());
                }
            }
            return problems.isEmpty() ? null : problems.toString();
        }
    }

    /**
     * Reads the {@code attr} reference of an {@code element}: it resolves when
     * it is a UUID naming a track restored from this file
     * ({@code restoredTracks} — a master or return-bus id, which the file
     * never carries, does not) that {@code alreadyReferenced} does not hold
     * yet (the first of duplicated references wins). A missing or malformed
     * value, an id the file holds no track for, or a repeat — said with
     * {@code repeat}, naming the track — is unresolved.
     */
    private static TrackReference readTrackReference(Element refElem, String element, String attr,
                                                     Map<UUID, UnreadProjectItem.OnTrack> restoredTracks,
                                                     Set<UUID> alreadyReferenced, String repeat) {
        String name = element + "@" + attr;
        String raw = refElem.getAttribute(attr);
        if (raw.isEmpty()) {
            return new TrackReference(null, name + " is missing");
        }
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return new TrackReference(null, name + " '" + UnreadProjectItem.logText(raw) + "' is not a track id");
        }
        UnreadProjectItem.OnTrack track = restoredTracks.get(id);
        if (track == null) {
            return new TrackReference(null, name + " " + id + " names no track in this file");
        }
        if (alreadyReferenced.contains(id)) {
            return new TrackReference(null, name + " " + id + " (" + track.describe() + ") " + repeat);
        }
        return new TrackReference(id, null);
    }

    /**
     * Resolves the {@code index}-th reference of a mixer container (a cue
     * send, a VCA member, a bed routing) through {@link #readTrackReference}.
     * A resolved id is added to {@code alreadyReferenced} and returned; an
     * unresolved reference is reported — the caller drops it and keeps the
     * container — and {@code null} is returned.
     */
    private UUID resolveTrackReference(Element refElem, String element, String attr,
                                       Map<UUID, UnreadProjectItem.OnTrack> restoredTracks,
                                       Set<UUID> alreadyReferenced,
                                       UnreadProjectItem.InMixer location, int index) {
        TrackReference reference = readTrackReference(refElem, element, attr,
                restoredTracks, alreadyReferenced, ASSIGNMENT_REPEAT);
        if (!reference.resolved()) {
            reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED,
                    location, index, reference.problem(), 1));
            return null;
        }
        alreadyReferenced.add(reference.id());
        return reference.id();
    }

    /**
     * The track channel {@code channel} — the file's
     * {@code channelIndex}-th {@code <channel>} — as the log names its strip:
     * the track that owns it, as {@link #parseTrack} named it, a track
     * restored under a new id ({@link #restoreTrackId}) included. Its number
     * is its position in the project's tracks, which the reader added in file
     * order to a project that had none, and its name is the one it was
     * restored with. A channel no track owns is named
     * {@code Mixer channel <channelIndex + 1>}.
     */
    private static UnreadProjectItem.Location trackChannelStrip(MixerChannel channel, int channelIndex,
                                                                DawProject project) {
        return project.getTrackForChannel(channel)
                .<UnreadProjectItem.Location>map(owner -> new UnreadProjectItem.OnTrack(
                        project.getTracks().indexOf(owner), owner.getName()))
                .orElseGet(() -> new UnreadProjectItem.InMixer("channel " + (channelIndex + 1)));
    }

    /**
     * Applies a {@code <master>}, {@code <return-bus>} or {@code <channel>}
     * element to {@code channel}; {@code strip} names the channel in the
     * WARNINGs and items of its inserts ({@link #parseInsertSlot}).
     */
    private void applyMixerChannelAttrs(Element elem, MixerChannel channel, UnreadProjectItem.Location strip,
                                        DawProject project) {
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

        // Story 322 removed the legacy scalar "send-level"; a legacy attribute
        // is tolerated by simply not being read — send state lives in <sends>.

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
            for (int i = 0; i < insertElements.size(); i++) {
                parseInsertSlot(insertElements.get(i), i, channel, strip, project);
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

    /**
     * The names a writer puts on an {@code <insert>}, read by name on every
     * saved insert before the reader decides what the insert is (story 334):
     * the write/read parity contract judges every insert on its own.
     *
     * @param name          {@code name}, empty when absent
     * @param bypassed      {@code bypassed}
     * @param expensive     {@code expensive}, empty when absent
     * @param sidechainKey  {@code sidechain-source}, empty when absent
     * @param analyzerId    {@code analyzer-plugin-id}, when present
     * @param pluginSource  {@code plugin-source}, when present
     * @param pluginId      {@code plugin-id}, empty when absent
     * @param pluginVersion {@code plugin-version}, empty when absent
     * @param effectType    {@code effect-type}, empty when absent
     */
    private record InsertNames(String name, boolean bypassed, String expensive, String sidechainKey,
                               Optional<String> analyzerId, Optional<String> pluginSource, String pluginId,
                               String pluginVersion, String effectType) {

        static InsertNames of(Element elem) {
            return new InsertNames(elem.getAttribute("name"), parseBooleanAttr(elem, "bypassed"),
                    elem.getAttribute("expensive"), elem.getAttribute("sidechain-source"),
                    elem.hasAttribute("analyzer-plugin-id")
                            ? Optional.of(elem.getAttribute("analyzer-plugin-id")) : Optional.empty(),
                    elem.hasAttribute("plugin-source")
                            ? Optional.of(elem.getAttribute("plugin-source")) : Optional.empty(),
                    elem.getAttribute("plugin-id"), elem.getAttribute("plugin-version"),
                    elem.getAttribute("effect-type"));
        }

        /**
         * The name an insert the open does not load shows and its lines quote
         * (studio rulings preamble): the saved name when it is not blank, else
         * the saved {@code effect-type}, plugin id or analyzer id, else
         * {@code Insert <insertIndex + 1>}.
         */
        String label(int insertIndex) {
            for (String saved : List.of(name, effectType, pluginId, analyzerId.orElse(""))) {
                if (!saved.isBlank()) {
                    return saved;
                }
            }
            return "Insert " + (insertIndex + 1);
        }
    }

    /** What the reader made of a saved {@code <insert>}: a working slot, or why the insert is offline. */
    private sealed interface InsertLoad permits InsertLoad.Working, InsertLoad.Offline {

        /**
         * A slot that loaded, its parameters applied.
         *
         * @param slot        the slot, not yet in its channel
         * @param adjustments the details of the parameters it could not apply
         *                    as saved, in document order
         */
        record Working(InsertSlot slot, List<String> adjustments) implements InsertLoad {
        }

        /**
         * An insert the open could not load; nothing of it is left constructed.
         *
         * @param cause why
         */
        record Offline(OfflineCause cause) implements InsertLoad {
        }
    }

    /**
     * Restores the {@code insertIndex}-th saved {@code <insert>} of
     * {@code channel} — counted over the elements, so one that is skipped
     * still takes its position — whose strip the log names as {@code strip}
     * (story 334; Persistence Integrity book §4.5). The open never fails
     * because of an insert and never shortens a chain silently: an insert it
     * cannot load is kept at its place, offline, and written back as saved;
     * a ninth or later insert, which a strip cannot hold, is skipped and
     * reported.
     */
    private void parseInsertSlot(Element elem, int insertIndex, MixerChannel channel,
                                 UnreadProjectItem.Location strip, DawProject project) {
        InsertNames names = InsertNames.of(elem);
        if (channel.getInsertSlots().size() >= MixerChannel.MAX_INSERT_SLOTS) {
            reportUnread(UnreadProjectItem.atInsert(UnreadProjectItem.Kind.INSERT_SKIPPED, strip, insertIndex,
                    names.label(insertIndex), "the strip already holds " + MixerChannel.MAX_INSERT_SLOTS + " inserts"));
            return;
        }
        switch (loadInsert(elem, names, insertIndex, strip, project)) {
            case InsertLoad.Working working -> addWorkingInsert(names, working, insertIndex, channel, strip, project);
            case InsertLoad.Offline offline ->
                    addOfflineInsert(elem, names, offline.cause(), insertIndex, channel, strip, project);
        }
    }

    /** Builds the slot an {@code <insert>} saves, by its identity: an analyzer, a plugin, or a built-in effect. */
    private InsertLoad loadInsert(Element elem, InsertNames names, int insertIndex,
                                  UnreadProjectItem.Location strip, DawProject project) {
        if (names.analyzerId().isPresent()) {
            String analyzerId = names.analyzerId().get();
            return AnalyzerInsertPersistence.knows(analyzerId)
                    ? new InsertLoad.Working(
                            AnalyzerInsertPersistence.read(elem, project.getFormat(), strip, insertIndex), List.of())
                    : new InsertLoad.Offline(new OfflineCause.UnknownAnalyzer(analyzerId));
        }
        if (names.pluginSource().isPresent()) {
            Optional<PluginSourceKind> kind = PluginSourceKind.ofToken(names.pluginSource().get());
            if (kind.isEmpty() || names.pluginId().isBlank()) {
                return new InsertLoad.Offline(new OfflineCause.NoIdentity());
            }
            PluginIdentity identity = new PluginIdentity(kind.get(), names.pluginId(), names.pluginVersion());
            return switch (identity.kind()) {
                case BUILT_IN -> loadFirstPartyPlugin(elem, identity, insertIndex, strip, project);
                case JAR, CLAP -> loadResolvedPlugin(elem, identity, insertIndex, strip, project);
            };
        }
        if (!names.effectType().isEmpty()) {
            return loadBuiltInEffect(elem, names.effectType(), project.getFormat());
        }
        return new InsertLoad.Offline(new OfflineCause.NoIdentity());
    }

    /** A built-in DSP insert from the rack's built-in list: its slot is named for its type (studio ruling R15). */
    private static InsertLoad loadBuiltInEffect(Element elem, String effectType, AudioFormat format) {
        InsertEffectType type;
        try {
            type = InsertEffectType.valueOf(effectType);
        } catch (IllegalArgumentException unknown) {
            return new InsertLoad.Offline(new OfflineCause.UnknownEffectType(effectType));
        }
        if (type == InsertEffectType.CLAP_PLUGIN) {
            return new InsertLoad.Offline(new OfflineCause.NoIdentity());
        }
        InsertSlot slot;
        try {
            slot = InsertEffectFactory.createSlot(type, format.channels(), format.sampleRate());
        } catch (IllegalArgumentException formatRejected) {
            return new InsertLoad.Offline(
                    new OfflineCause.FormatNotSupported(effectType, format.channels(), format.sampleRate()));
        }
        return new InsertLoad.Working(slot, restoreParameters(elem, slot));
    }

    /**
     * A first-party plugin insert: resolved from this build's own providers
     * by its id at every door, never through the resolver (studio ruling
     * E11); its version is the build's, so a difference logs nothing.
     */
    private InsertLoad loadFirstPartyPlugin(Element elem, PluginIdentity identity, int insertIndex,
                                            UnreadProjectItem.Location strip, DawProject project) {
        Optional<BuiltInDawPlugin> plugin = BuiltInDawPlugin.createById(identity.id());
        if (plugin.isEmpty()) {
            return new InsertLoad.Offline(new OfflineCause.UnknownBuiltInPlugin(identity.id()));
        }
        InsertSlot slot;
        try {
            slot = new BuiltInPluginGraph(ProcessorRegistry.getInstance()).createSlot(plugin.get(),
                    new AudioFormatPluginContext(project.getFormat()), project.getMetronome());
        } catch (RuntimeException | LinkageError failed) {
            // createSlot disposed the plugin. No first-party plugin rejects a
            // project's audio format when it is initialized today; one that
            // does is to be worded with the format cause instead — "built-in
            // plugin '<id>' cannot run in this project's audio format
            // (channels <c>, sample rate <r> Hz)" (studio ruling G20).
            return new InsertLoad.Offline(new OfflineCause.FailedToLoad(identity.kind(), identity.id(),
                    identity.version(), PluginResolution.FailedToLoad.of(failed).failure()));
        }
        return finishPluginInsert(elem, slot, identity, plugin.get().getDescriptor().version(), insertIndex, strip);
    }

    /**
     * An external JAR or CLAP plugin insert, through the
     * {@linkplain #setPluginResolver resolver}: loaded at its saved identity
     * whatever the installed version (studio ruling E4), which a difference
     * logs at INFO (E21). Anything the resolver throws but the virtual
     * machine's own error — a plugin's own code failing as it loads, a
     * {@link StackOverflowError} included ({@link PluginResolution.FailedToLoad#rethrowIfFatal})
     * — leaves the insert offline as failed to load (ruling-3 C3, finding
     * FX2-7); a resolver releases what it built before it throws.
     */
    private InsertLoad loadResolvedPlugin(Element elem, PluginIdentity identity, int insertIndex,
                                          UnreadProjectItem.Location strip, DawProject project) {
        PluginSourceKind kind = identity.kind();
        String id = identity.id();
        String version = identity.version();
        PluginResolution resolution;
        try {
            resolution = pluginResolver.resolve(identity, new AudioFormatPluginContext(project.getFormat()));
        } catch (Throwable failed) {
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return new InsertLoad.Offline(new OfflineCause.FailedToLoad(kind, id, version,
                    PluginResolution.FailedToLoad.of(failed).failure()));
        }
        return switch (resolution) {
            case PluginResolution.Resolved resolved -> loadPlugin(elem, identity, resolved.loaded(), insertIndex, strip);
            case PluginResolution.NotInstalled _ ->
                    new InsertLoad.Offline(new OfflineCause.NotInstalled(kind, id, version));
            case PluginResolution.InstalledAsOtherKind other ->
                    new InsertLoad.Offline(new OfflineCause.InstalledAsOtherKind(kind, id, version,
                            other.installedKind()));
            case PluginResolution.MissingAtStartup _ ->
                    new InsertLoad.Offline(new OfflineCause.MissingAtStartup(kind, id, version));
            case PluginResolution.NotLoadedBySnapshotRestore _ ->
                    new InsertLoad.Offline(new OfflineCause.NotLoadedBySnapshotRestore(kind, id, version));
            case PluginResolution.FailedToLoad failed ->
                    new InsertLoad.Offline(new OfflineCause.FailedToLoad(kind, id, version, failed.failure()));
        };
    }

    /**
     * Puts a resolved plugin in a slot, activates it and gives it what its
     * file saved; logs at INFO when it opens with another version than the
     * one saved (E21). Whatever the plugin's own code throws disposes it
     * first; then anything but the virtual machine's own error — a
     * {@link StackOverflowError} included — leaves the insert offline as
     * failed to load (ruling-3 C3, finding FX2-7), and the virtual machine's
     * own error propagates.
     */
    private InsertLoad loadPlugin(Element elem, PluginIdentity identity, LoadedPlugin loaded, int insertIndex,
                                  UnreadProjectItem.Location strip) {
        DawPlugin plugin = loaded.plugin();
        String installedVersion;
        Optional<InsertSlot> created;
        try {
            installedVersion = plugin.getDescriptor().version();
            created = InsertEffectFactory.createSlotFromPlugin(plugin);
            if (created.isPresent()) {
                plugin.activate();
            }
        } catch (Throwable failed) {
            disposeQuietly(loaded.disposal());
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return new InsertLoad.Offline(new OfflineCause.FailedToLoad(identity.kind(), identity.id(),
                    identity.version(), PluginResolution.FailedToLoad.of(failed).failure()));
        }
        if (created.isEmpty()) {
            disposeQuietly(loaded.disposal());
            // Studio ruling G12: no exception was thrown, so none is named.
            return new InsertLoad.Offline(new OfflineCause.FailedToLoad(identity.kind(), identity.id(),
                    identity.version(), "the installed version " + installedVersion + " has no audio processor"));
        }
        InsertSlot slot = created.get();
        slot.setDisposal(loaded.disposal());
        InsertLoad load = finishPluginInsert(elem, slot, identity, installedVersion, insertIndex, strip);
        if (load instanceof InsertLoad.Working && !installedVersion.equals(identity.version())) {
            LOG.log(Level.INFO, strip.describe() + ": insert " + (insertIndex + 1) + " '"
                    + UnreadProjectItem.logText(slot.getName()) + "' " + identity.kind().displayName()
                    + " plugin '" + UnreadProjectItem.logText(identity.id()) + "' was saved with version '"
                    + UnreadProjectItem.logText(identity.version()) + "' and opens with installed version "
                    + installedVersion);
        }
        return load;
    }

    /**
     * Gives a loaded plugin insert what its file saved: a CLAP plugin its
     * state (a state the plugin does not take leaves the insert offline —
     * never a plugin at its defaults, studio ruling E4), then the parameters
     * (§3.3); last, it asks the plugin once for its latency (ruling-4 Q8), so
     * the plugin is asked in the state the delay compensation will find it
     * in. A plugin that fails at any of these steps is disposed first,
     * whatever it threw; then anything but the virtual machine's own error —
     * a {@link StackOverflowError} included (ruling-3 C3, finding FX2-7) —
     * leaves the insert offline as failed to load, and the virtual machine's
     * own error propagates. A latency query that throws names the call:
     * "{@code <exception> when asked for its latency}"; a plugin that cannot
     * state its latency cannot be compensated. The answer is not kept: the
     * compensation asks the plugin itself (E1). A {@code <plugin-state>} the
     * plugin takes no state from is content no writer produces: logged and
     * dropped.
     */
    private InsertLoad finishPluginInsert(Element elem, InsertSlot slot, PluginIdentity identity,
                                          String installedVersion, int insertIndex,
                                          UnreadProjectItem.Location strip) {
        List<Element> states = getDirectChildElements(elem, "plugin-state");
        int unreadStates = states.size();
        if (identity.kind() == PluginSourceKind.CLAP && !states.isEmpty()) {
            Optional<OfflineCause> notTaken;
            try {
                notTaken = loadPluginState(states.getFirst(), slot, identity, installedVersion);
            } catch (Throwable fatal) {
                // Only the virtual machine's own error leaves loadPluginState.
                disposeQuietly(slot::disposeAfterQuiescence);
                throw fatal;
            }
            if (notTaken.isPresent()) {
                disposeQuietly(slot::disposeAfterQuiescence);
                return new InsertLoad.Offline(notTaken.get());
            }
            unreadStates--;
        }
        List<String> adjustments;
        try {
            adjustments = restoreParameters(elem, slot);
        } catch (Throwable failed) {
            disposeQuietly(slot::disposeAfterQuiescence);
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return new InsertLoad.Offline(new OfflineCause.FailedToLoad(identity.kind(), identity.id(),
                    identity.version(), PluginResolution.FailedToLoad.of(failed).failure()));
        }
        try {
            slot.getProcessor().getLatencySamples();
        } catch (Throwable failed) {
            disposeQuietly(slot::disposeAfterQuiescence);
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return new InsertLoad.Offline(new OfflineCause.FailedToLoad(identity.kind(), identity.id(),
                    identity.version(), PluginResolution.FailedToLoad.of(failed).failure() + LATENCY_QUERY_FAILED));
        }
        dropContent(strip.describe() + ": insert " + (insertIndex + 1), unreadStates, false);
        return new InsertLoad.Working(slot, adjustments);
    }

    /** What a failure to load names after the exception when the plugin's latency query threw (ruling-4 Q8). */
    private static final String LATENCY_QUERY_FAILED = " when asked for its latency";

    /**
     * Loads a CLAP plugin's saved state ({@code <plugin-state>}, Base64 text)
     * into it and remembers it on the slot. What the plugin throws as it
     * loads the state — a {@link StackOverflowError} included — is a state it
     * did not take; the virtual machine's own error propagates
     * ({@link PluginResolution.FailedToLoad#rethrowIfFatal}).
     *
     * @return why the plugin did not take it, or empty when it did (or the state is empty)
     */
    private static Optional<OfflineCause> loadPluginState(Element stateElem, InsertSlot slot, PluginIdentity identity,
                                                          String installedVersion) {
        byte[] state;
        try {
            state = Base64.getDecoder().decode(stateElem.getTextContent().strip());
        } catch (IllegalArgumentException notBase64) {
            // File damage: the plugin is never given it (studio ruling G7).
            return Optional.of(new OfflineCause.StateUnreadable(identity.kind(), identity.id(), identity.version()));
        }
        if (state.length == 0) {
            return Optional.empty();
        }
        if (!(slot.getPlugin() instanceof ExternalPluginHost host)) {
            return Optional.of(new OfflineCause.StateRejected(identity.kind(), identity.id(), identity.version(),
                    installedVersion, ""));
        }
        try {
            host.loadState(state);
        } catch (PluginStateRejectedException rejected) {
            return Optional.of(new OfflineCause.StateRejected(identity.kind(), identity.id(), identity.version(),
                    installedVersion, ""));
        } catch (Throwable failed) {
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return Optional.of(new OfflineCause.StateRejected(identity.kind(), identity.id(), identity.version(),
                    installedVersion, PluginResolution.FailedToLoad.of(failed).failure()));
        }
        slot.rememberPluginState(state);
        return Optional.empty();
    }

    /**
     * Runs the disposal of a plugin no channel holds; what the plugin's own
     * disposal throws is logged at FINE — all but the virtual machine's own
     * error ({@link PluginResolution.FailedToLoad#rethrowIfFatal}), which
     * propagates.
     */
    private static void disposeQuietly(Runnable disposal) {
        try {
            disposal.run();
        } catch (Throwable failed) {
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            LOG.log(Level.FINE, "Disposing a plugin the open did not keep failed", failed);
        }
    }

    /**
     * Applies the {@code <parameter>} children of a working insert, in
     * document order, and returns the detail of each one it could not apply
     * as saved — an {@link UnreadProjectItem.Kind#INSERT_PARAMETER_ADJUSTED}
     * item each; the parameters after it are still applied (studio rulings
     * E6 (e)-(g), E12 R6-R12). A named parameter goes through the
     * processor's {@code @ProcessorParam} accessors
     * ({@link ReflectivePresetSerializer#restoreEach}); an id-keyed one
     * through the effect's parameter handler for a built-in DSP insert, and
     * through the slot's parameter store for a plugin, applied to the plugin
     * now ({@link InsertSlot#applyParameterNow}, studio ruling G3). "{@code <v>
     * is used}" names the value the parameter holds after the open.
     *
     * @throws IllegalStateException when a built-in effect's setter fails
     *                               other than by refusing its value (a build
     *                               defect); a plugin's failure propagates as
     *                               it was thrown
     */
    private static List<String> restoreParameters(Element elem, InsertSlot slot) {
        List<Element> parameterElements = getDirectChildElements(elem, "parameter");
        if (parameterElements.isEmpty()) {
            return List.of();
        }
        AudioProcessor processor = slot.getProcessor();
        Map<String, Double> namedValues = ReflectivePresetSerializer.isSupported(processor)
                ? ReflectivePresetSerializer.snapshot(processor) : Map.of();
        // A built-in DSP insert holds no plugin; every slot with a plugin
        // keeps its id-keyed values in its parameter store.
        InsertEffectType builtInType = slot.getPlugin() == null ? slot.getEffectType() : null;
        Map<Integer, Double> builtInValues = builtInType == null
                ? Map.of() : InsertEffectFactory.getParameterValues(builtInType, processor);
        BiConsumer<Integer, Double> builtInHandler = builtInType == null
                ? null : InsertEffectFactory.createParameterHandler(builtInType, processor);
        PluginParameterStore store = slot.getParameterStore();
        Set<String> names = new HashSet<>();
        Set<Integer> ids = new HashSet<>();
        List<String> adjustments = new ArrayList<>();
        for (Element parameter : parameterElements) {
            String name = parameter.getAttribute("name");
            String rawId = parameter.getAttribute("id");
            String rawValue = parameter.getAttribute("value");
            String quotedValue = " value '" + UnreadProjectItem.logText(rawValue) + "'";
            if (!name.isEmpty()) {
                String subject = "parameter '" + UnreadProjectItem.logText(name) + "'" + quotedValue;
                Double inForce = namedValues.get(name);
                OptionalDouble value = finiteNumber(rawValue);
                if (!names.add(name)) {
                    adjustments.add(subject + REPEATS_AN_EARLIER_PARAMETER);
                } else if (inForce == null) {
                    adjustments.add(subject + NOT_A_PARAMETER_OF_THIS_INSERT);
                } else if (value.isEmpty()) {
                    adjustments.add(subject + " is not a finite number; " + inForce + " is used");
                } else {
                    SequencedMap<String, Double> saved = new LinkedHashMap<>();
                    saved.put(name, value.getAsDouble());
                    switch (ReflectivePresetSerializer.restoreEach(processor, saved).getFirst()) {
                        case ParameterRestore.Applied _ -> { }
                        case ParameterRestore.Clamped clamped -> adjustments.add(subject + " is outside "
                                + clamped.min() + "–" + clamped.max() + "; " + clamped.applied() + " is used");
                        case ParameterRestore.Rejected rejected ->
                                adjustments.add(subject + " is rejected by the insert; " + rejected.inForce() + " is used");
                        case ParameterRestore.Unknown _ -> adjustments.add(subject + NOT_A_PARAMETER_OF_THIS_INSERT);
                        case ParameterRestore.NotFinite _ ->
                                adjustments.add(subject + " is not a finite number; " + inForce + " is used");
                    }
                }
                continue;
            }
            OptionalInt id = wholeNumberFromZero(rawId);
            if (id.isEmpty()) {
                adjustments.add("a parameter with no name or id (value '" + UnreadProjectItem.logText(rawValue)
                        + "') is not applied");
                continue;
            }
            int parameterId = id.getAsInt();
            String subject = "parameter id " + parameterId + quotedValue;
            int storeIndex = builtInType == null ? storeIndexOf(store, parameterId) : -1;
            Double inForce = builtInType != null ? builtInValues.get(parameterId)
                    : storeIndex >= 0 ? Double.valueOf(store.value(storeIndex)) : null;
            OptionalDouble value = finiteNumber(rawValue);
            if (!ids.add(parameterId)) {
                adjustments.add(subject + REPEATS_AN_EARLIER_PARAMETER);
            } else if (inForce == null) {
                adjustments.add(subject + NOT_A_PARAMETER_OF_THIS_INSERT);
            } else if (value.isEmpty()) {
                adjustments.add(subject + " is not a finite number; " + inForce + " is used");
            } else if (builtInHandler != null) {
                if (!appliedByTheEffect(builtInHandler, parameterId, value.getAsDouble())) {
                    adjustments.add(subject + " is rejected by the insert; "
                            + InsertEffectFactory.getParameterValues(builtInType, processor).get(parameterId)
                            + " is used");
                }
            } else {
                PluginParameter declared = store.parameter(storeIndex);
                double applied = Math.max(declared.minValue(), Math.min(declared.maxValue(), value.getAsDouble()));
                try {
                    slot.applyParameterNow(parameterId, applied);
                    if (applied != value.getAsDouble()) {
                        adjustments.add(subject + " is outside " + declared.minValue() + "–" + declared.maxValue()
                                + "; " + applied + " is used");
                    }
                } catch (IllegalArgumentException refused) {
                    adjustments.add(subject + " is rejected by the insert; " + store.value(storeIndex) + " is used");
                }
            }
        }
        return adjustments;
    }

    private static final String REPEATS_AN_EARLIER_PARAMETER = " repeats an earlier parameter; it is not applied";
    private static final String NOT_A_PARAMETER_OF_THIS_INSERT = " is not a parameter of this insert; it is not applied";

    /**
     * Applies an id-keyed value through a built-in effect's parameter
     * handler. The handler of an annotated processor wraps what the setter
     * throws in an {@link IllegalStateException} whose cause is an
     * {@link InvocationTargetException}: a refusal like an
     * {@link IllegalArgumentException}.
     *
     * @return {@code false} when the effect refused the value
     */
    private static boolean appliedByTheEffect(BiConsumer<Integer, Double> handler, int parameterId, double value) {
        try {
            handler.accept(parameterId, value);
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        } catch (IllegalStateException failed) {
            if (failed.getCause() instanceof InvocationTargetException) {
                return false;
            }
            throw failed;
        }
    }

    /** The store index of {@code parameterId}, or {@code -1} when the store has no such parameter. */
    private static int storeIndexOf(PluginParameterStore store, int parameterId) {
        for (int index = 0; index < store.parameterCount(); index++) {
            if (store.parameterIdAt(index) == parameterId) {
                return index;
            }
        }
        return -1;
    }

    /** {@code raw} as a whole number &ge; 0, or empty. */
    private static OptionalInt wholeNumberFromZero(String raw) {
        try {
            int value = Integer.parseInt(raw.strip());
            return value >= 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }

    /**
     * Adds a working insert to its channel with its saved flags: the stored
     * bypass decision, the expensive flag and the sidechain source. A
     * {@code sidechain-source} that names no strip of the project is removed
     * and reported: the insert detects on its own input (studio ruling E12
     * R14).
     */
    private void addWorkingInsert(InsertNames names, InsertLoad.Working working, int insertIndex,
                                  MixerChannel channel, UnreadProjectItem.Location strip, DawProject project) {
        InsertSlot slot = working.slot();
        if (names.bypassed()) {
            slot.setBypassed(true);
        }
        applyExpensive(slot, names.expensive());
        for (String adjustment : working.adjustments()) {
            reportUnread(UnreadProjectItem.atInsert(UnreadProjectItem.Kind.INSERT_PARAMETER_ADJUSTED, strip,
                    insertIndex, slot.getName(), adjustment));
        }
        if (!names.sidechainKey().isEmpty()) {
            MixerChannel source = resolveSidechainSource(names.sidechainKey(), project.getMixer());
            if (source != null) {
                slot.setSidechainSource(source);
            } else {
                reportUnread(new UnreadProjectItem(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED, strip, insertIndex,
                        "insert " + (insertIndex + 1) + " '" + UnreadProjectItem.logText(slot.getName())
                                + "' sidechain-source '" + UnreadProjectItem.logText(names.sidechainKey())
                                + "' names no strip in this project; the insert detects on its own input", 1));
            }
        }
        channel.addInsert(slot);
    }

    /**
     * Adds an insert the open could not load to its channel, at its place:
     * offline, passing audio through, holding its element as saved for the
     * next save (Persistence Integrity book §4.5). Its child content no
     * writer produces is logged first; its sidechain key resolves like any
     * slot's and is never reported (studio ruling E1); then its line — an
     * unknown analyzer is logged only, every other cause is counted.
     */
    private void addOfflineInsert(Element elem, InsertNames names, OfflineCause cause, int insertIndex,
                                  MixerChannel channel, UnreadProjectItem.Location strip, DawProject project) {
        List<SavedInsertChild> children = new ArrayList<>();
        List<Element> parameters = getDirectChildElements(elem, "parameter");
        for (Element parameter : parameters) {
            children.add(new SavedInsertChild(SavedInsertChild.Tag.PARAMETER, savedAttributes(parameter), ""));
        }
        List<Element> states = getDirectChildElements(elem, "plugin-state");
        for (Element state : states) {
            children.add(new SavedInsertChild(SavedInsertChild.Tag.PLUGIN_STATE, savedAttributes(state),
                    state.getTextContent()));
        }
        dropUnwrittenContent(strip.describe() + ": insert " + (insertIndex + 1), elem,
                parameters.size() + states.size());
        SavedInsert saved = new SavedInsert(savedAttributes(elem), children);
        MixerChannel sidechain = names.sidechainKey().isEmpty()
                ? null : resolveSidechainSource(names.sidechainKey(), project.getMixer());
        String label = names.label(insertIndex);
        InsertSlot slot = InsertSlot.ofOffline(label, project.getFormat().channels(),
                new OfflineInsert(cause, saved, sidechain != null));
        slot.setBypassed(names.bypassed());
        applyExpensive(slot, names.expensive());
        slot.setSidechainSource(sidechain);
        if (cause instanceof OfflineCause.UnknownAnalyzer unknown) {
            AnalyzerInsertPersistence.logUnknown(strip, insertIndex, label, unknown.analyzerId());
        } else {
            reportUnread(UnreadProjectItem.atInsert(UnreadProjectItem.Kind.INSERT_NOT_LOADED, strip, insertIndex,
                    label, OfflineInsertDetails.detail(cause)));
        }
        channel.addInsert(slot);
    }

    /** Applies a saved {@code expensive} flag (story 129); an absent one keeps the slot's default. */
    private static void applyExpensive(InsertSlot slot, String expensive) {
        if (!expensive.isEmpty()) {
            slot.setExpensive(Boolean.parseBoolean(expensive));
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
        Send send = new Send(target, level, parseSendTap(sendElem));
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
     * Parses the tap point of a {@code <send>} element. The {@code tap}
     * attribute is the send's only stored fact. Legacy projects (saved before
     * per-send tap-point support) omit it and carry the lossy legacy
     * {@code mode} instead: only then is {@code mode} read, and the tap is
     * derived from it (falling back to {@link SendTap#POST_FADER} as the
     * spec mandates). When both are present the tap wins.
     *
     * @param sendElem the {@code <send>} element
     * @return the parsed or migrated tap point
     */
    private SendTap parseSendTap(Element sendElem) {
        String value = sendElem.getAttribute("tap");
        if (value.isEmpty()) {
            SendMode legacyMode = parseSendMode(sendElem.getAttribute("mode"));
            return legacyMode == SendMode.PRE_FADER ? SendTap.PRE_FADER : SendTap.POST_FADER;
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

    /**
     * Reads an integer attribute an item cannot be read without.
     *
     * @throws IllegalArgumentException naming the attribute when it is
     *                                  missing or not an integer
     */
    private static int requiredIntAttr(Element element, String attr) {
        String value = element.getAttribute(attr);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(attr + " is missing");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(attr + " '" + UnreadProjectItem.logText(value) + "' is not an integer");
        }
    }

    /**
     * Reads a boolean attribute that changes how an item's values are read:
     * absent is {@code false}; {@code true} / {@code false} in any case.
     *
     * @throws IllegalArgumentException naming the attribute for any other value
     */
    private static boolean strictBooleanAttr(Element element, String attr) {
        String value = element.getAttribute(attr);
        if (value.isEmpty() || "false".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        throw new IllegalArgumentException(attr + " '" + UnreadProjectItem.logText(value) + "' is not true or false");
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
        // The mixer (and its return buses) is parsed before the snapshots, so
        // snapshot send targets resolve against the loaded bus list.
        List<MixerChannel> returnBuses = project.getMixer().getReturnBuses();

        for (Element snapshotElem : getDirectChildElements(elem, "snapshot")) {
            MixerSnapshot snapshot = parseSnapshot(snapshotElem, returnBuses);
            if (snapshot != null && manager.getSnapshotCount() < MixerSnapshotManager.MAX_SNAPSHOTS) {
                manager.addSnapshot(snapshot);
            }
        }

        List<Element> slotA = getDirectChildElements(elem, "slot-a");
        if (!slotA.isEmpty()) {
            MixerSnapshot snap = parseSnapshot(slotA.getFirst(), returnBuses);
            if (snap != null) {
                manager.setSlot(MixerSnapshotManager.Slot.A, snap);
            }
        }
        List<Element> slotB = getDirectChildElements(elem, "slot-b");
        if (!slotB.isEmpty()) {
            MixerSnapshot snap = parseSnapshot(slotB.getFirst(), returnBuses);
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

    private MixerSnapshot parseSnapshot(Element elem, List<MixerChannel> mixerReturnBuses) {
        String name = elem.getAttribute("name");
        if (name.isEmpty()) {
            name = "Untitled";
        }
        Instant timestamp = parseInstant(elem.getAttribute("timestamp"), Instant.now());

        List<Element> masterElems = getDirectChildElements(elem, "master");
        if (masterElems.isEmpty()) {
            return null;
        }
        ChannelSnapshot master = parseChannelSnapshot(masterElems.getFirst(), mixerReturnBuses);
        if (master == null) {
            return null;
        }

        List<ChannelSnapshot> channels = new ArrayList<>();
        List<Element> channelContainers = getDirectChildElements(elem, "channels");
        if (!channelContainers.isEmpty()) {
            for (Element ce : getDirectChildElements(channelContainers.getFirst(), "channel")) {
                ChannelSnapshot cs = parseChannelSnapshot(ce, mixerReturnBuses);
                if (cs != null) {
                    channels.add(cs);
                }
            }
        }

        List<ChannelSnapshot> returnBuses = new ArrayList<>();
        List<Element> returnContainers = getDirectChildElements(elem, "return-buses");
        if (!returnContainers.isEmpty()) {
            for (Element re : getDirectChildElements(returnContainers.getFirst(), "return-bus")) {
                ChannelSnapshot cs = parseChannelSnapshot(re, mixerReturnBuses);
                if (cs != null) {
                    returnBuses.add(cs);
                }
            }
        }

        return new MixerSnapshot(name, timestamp, master, channels, returnBuses);
    }

    private ChannelSnapshot parseChannelSnapshot(Element elem, List<MixerChannel> mixerReturnBuses) {
        try {
            double volume = clampDouble(parseDoubleAttr(elem, "volume", 1.0), 0.0, 1.0);
            double pan = clampDouble(parseDoubleAttr(elem, "pan", 0.0), -1.0, 1.0);
            boolean muted = parseBooleanAttr(elem, "muted");
            boolean solo = parseBooleanAttr(elem, "solo");
            boolean phaseInverted = parseBooleanAttr(elem, "phase-inverted");
            // Story 322 removed the legacy scalar "send-level" from snapshots;
            // a legacy attribute is tolerated by simply not being read.

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
                    UUID targetId = resolveSnapshotSendTarget(se, mixerReturnBuses);
                    if (targetId == null) {
                        continue;
                    }
                    double level = clampDouble(parseDoubleAttr(se, "level", 0.0), 0.0, 1.0);
                    sends.add(new SendSnapshot(targetId, level, parseSendTap(se)));
                }
            }

            TrackCpuBudget cpuBudget = null;
            List<Element> budgetElements = getDirectChildElements(elem, "cpu-budget");
            if (!budgetElements.isEmpty()) {
                cpuBudget = parseCpuBudget(budgetElements.getFirst());
            }

            return new ChannelSnapshot(volume, pan, muted, solo, phaseInverted,
                    routing, inserts, sends, cpuBudget);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Story 322 — resolves a snapshot send's target bus id. The bus's index
     * in the loaded mixer's return-bus list ({@code target-index}) is
     * authoritative: return-bus ids are regenerated when a project is opened,
     * so the persisted {@code target} id only matches within the session that
     * wrote it. The id is the fallback for a file whose index no longer
     * resolves; {@code null} when neither does (the send is skipped).
     */
    private UUID resolveSnapshotSendTarget(Element sendElem, List<MixerChannel> mixerReturnBuses) {
        int targetIndex = parseIntAttr(sendElem, "target-index", -1);
        if (targetIndex >= 0 && targetIndex < mixerReturnBuses.size()) {
            return mixerReturnBuses.get(targetIndex).getId();
        }
        String targetAttr = sendElem.getAttribute("target");
        if (targetAttr.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(targetAttr);
        } catch (IllegalArgumentException e) {
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

    /**
     * Applies the {@code <bed-bus>} element. A routing's {@code track-id}
     * resolves only to a track restored from this file
     * ({@code restoredTracks}; {@link #resolveTrackReference}): a routing
     * that names no such track, or the same track as an earlier routing, is
     * dropped and reported, and the bed bus is kept.
     */
    private void parseBedBus(Element elem, DawProject project,
                             Map<UUID, UnreadProjectItem.OnTrack> restoredTracks) {
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
        UnreadProjectItem.InMixer bedLocation = new UnreadProjectItem.InMixer("bed bus");
        Set<UUID> routedTracks = new HashSet<>();
        List<Element> routingElements = getDirectChildElements(routingsContainers.getFirst(), "bed-routing");
        for (int i = 0; i < routingElements.size(); i++) {
            Element re = routingElements.get(i);
            UUID trackId = resolveTrackReference(re, "bed-routing", "track-id",
                    restoredTracks, routedTracks, bedLocation, i);
            if (trackId == null) {
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
