package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.midi.MidiCcLane;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueSend;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.snapshot.ChannelSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.SendSnapshot;
import com.benesquivelmusic.daw.core.mixer.spatial.BedBus;
import com.benesquivelmusic.daw.core.mixer.spatial.BedChannelRouting;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.transport.Transport;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model round-trip family (Persistence Integrity book §4.4): the full
 * {@link RoundTripInventoryProjects} project is saved and reopened — once
 * through {@link ProjectSerializer} / {@link ProjectDeserializer} and once
 * through {@link ProjectManager} save and open on disk — and compared fact by
 * fact, one assertion group per §3.1 inventory row that round-trips today or
 * that story 330 owns, and every variant's audio format is compared as well.
 * Every fact names itself in its assertion message.
 *
 * <p>Where the parity contract ({@link ProjectSchemaParityContractTest})
 * proves every written name is read, this family proves every value comes
 * back as saved. When the serializer gains a fact, add its assertion here in
 * the same change. The §3.1 rows owned by story 334 — the tempo map,
 * plugin-backed inserts, take stacks and comp selections — get their groups
 * here when that story makes them round-trip.</p>
 */
class ProjectRoundTripInventoryTest {

    @TempDir
    static Path tempDir;

    private static List<Reopened> reopenings;

    /**
     * One way of saving and reopening the inventory project.
     *
     * @param path     how it was saved and reopened, for the assertion messages
     * @param original the project as saved
     * @param reopened the project as reopened
     * @param unread   what the open could not honour as written
     */
    record Reopened(String path, DawProject original, DawProject reopened, List<UnreadProjectItem> unread) {

        @Override
        public String toString() {
            return path;
        }
    }

    @BeforeAll
    static void saveAndReopenTheInventoryProject() throws IOException {
        DawProject original = RoundTripInventoryProjects.full(tempDir.resolve("assets"));
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(original));
        Reopened viaSerializer = new Reopened("ProjectSerializer → ProjectDeserializer",
                original, reopened, deserializer.getUnreadItems());

        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        ProjectMetadata metadata = manager.createProject("Inventory Session", tempDir);
        DawProject saved = RoundTripInventoryProjects.full(metadata.projectPath());
        manager.saveDawProject(saved);
        manager.abandonProject();
        manager.openProject(metadata.projectPath());
        Reopened viaManager = new Reopened("ProjectManager save → open",
                saved, manager.getCurrentDawProject(), manager.getLastUnreadItems());
        manager.abandonProject();

        reopenings = List.of(viaSerializer, viaManager);
    }

    static Stream<Reopened> reopenings() {
        return reopenings.stream();
    }

    /** The open honours every saved item; the one it reports is the effect no build recognises, kept in place. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void theOpenReportsOnlyTheUnrecognisedMidiEffect(Reopened r) {
        assertThat(r.unread()).as("%s: unread items", r.path()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED);
            assertThat(item.location()).isEqualTo(new UnreadProjectItem.OnTrack(2, "Keys"));
            assertThat(item.index()).isEqualTo(1);
            assertThat(item.detail()).contains(RoundTripInventoryProjects.UNRESOLVED_EFFECT_ID);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void projectMetadata(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        fact(softly, r, "project name", r.reopened().getName(), r.original().getName());
        fact(softly, r, "created-at", r.reopened().getMetadata().createdAt(), r.original().getMetadata().createdAt());
        fact(softly, r, "last-modified",
                r.reopened().getMetadata().lastModified(), r.original().getMetadata().lastModified());
        fact(softly, r, "audio format", r.reopened().getFormat(), r.original().getFormat());
        softly.assertAll();
    }

    /**
     * Every variant's audio format comes back as saved — among them a
     * non-stereo one, so the channel count is compared away from the
     * reader's default of two.
     */
    @Test
    void everyVariantsAudioFormatRoundTrips() throws IOException {
        List<RoundTripInventoryProjects.Variant> variants = RoundTripInventoryProjects.variants(tempDir.resolve("assets"));
        SoftAssertions softly = new SoftAssertions();
        for (RoundTripInventoryProjects.Variant variant : variants) {
            DawProject reopened = new ProjectDeserializer().deserialize(
                    new ProjectSerializer().serialize(variant.project()));
            softly.assertThat(reopened.getFormat()).as("variant \"%s\": audio format", variant.name())
                    .isEqualTo(variant.project().getFormat());
        }
        softly.assertAll();
        assertThat(variants).as("fixture: some variant is not stereo")
                .anySatisfy(variant -> assertThat(variant.project().getFormat().channels()).isNotEqualTo(2));
    }

    /** §3.1 "Track properties (name/type/colour/volume/pan/mute/solo/arm, groups, fold state)" — and input identity. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void trackProperties(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        List<Track> saved = r.original().getTracks();
        List<Track> reopened = r.reopened().getTracks();
        fact(softly, r, "track count", reopened.size(), saved.size());
        for (int i = 0; i < Math.min(saved.size(), reopened.size()); i++) {
            Track expected = saved.get(i);
            Track actual = reopened.get(i);
            String track = "track " + i + " (" + expected.getName() + ") ";
            fact(softly, r, track + "name", actual.getName(), expected.getName());
            fact(softly, r, track + "type", actual.getType(), expected.getType());
            fact(softly, r, track + "colour", actual.getColor().getHexColor(), expected.getColor().getHexColor());
            fact(softly, r, track + "volume", actual.getVolume(), expected.getVolume());
            fact(softly, r, track + "pan", actual.getPan(), expected.getPan());
            fact(softly, r, track + "mute", actual.isMuted(), expected.isMuted());
            fact(softly, r, track + "solo", actual.isSolo(), expected.isSolo());
            fact(softly, r, track + "arm", actual.isArmed(), expected.isArmed());
            fact(softly, r, track + "phase invert", actual.isPhaseInverted(), expected.isPhaseInverted());
            fact(softly, r, track + "collapsed", actual.isCollapsed(), expected.isCollapsed());
            fact(softly, r, track + "fold state", actual.getFoldState(), expected.getFoldState());
            fact(softly, r, track + "automation mode", actual.getAutomationMode(), expected.getAutomationMode());
            fact(softly, r, track + "input monitoring", actual.getInputMonitoring(), expected.getInputMonitoring());
            fact(softly, r, track + "input identity", actual.getInputDevice(), expected.getInputDevice());
            fact(softly, r, track + "legacy input hint",
                    actual.getLegacyInputDeviceIndexHint(), expected.getLegacyInputDeviceIndexHint());
            fact(softly, r, track + "input routing", actual.getInputRouting(), expected.getInputRouting());
            fact(softly, r, track + "input routing name",
                    actual.getInputRoutingDisplayName(), expected.getInputRoutingDisplayName());
            fact(softly, r, track + "MIDI input device", actual.getMidiInputDeviceName(), expected.getMidiInputDeviceName());
        }
        fact(softly, r, "track groups", groups(r.reopened()), groups(r.original()));
        softly.assertAll();
    }

    /** Story 330 (R1): every track keeps its saved id, so its channel and its MIDI clip are keyed by it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void trackIdentity(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        List<Track> saved = r.original().getTracks();
        List<Track> reopened = r.reopened().getTracks();
        for (int i = 0; i < Math.min(saved.size(), reopened.size()); i++) {
            Track actual = reopened.get(i);
            String track = "track " + i + " (" + actual.getName() + ") ";
            fact(softly, r, track + "id", actual.getId(), saved.get(i).getId());
            fact(softly, r, track + "channel id == track id",
                    r.reopened().getMixerChannelForTrack(actual).getId(), UUID.fromString(actual.getId()));
            fact(softly, r, track + "MIDI clip owner", actual.getMidiClip().getOwningTrackId(),
                    UUID.fromString(actual.getId()));
        }
        softly.assertAll();
    }

    /** §3.1 "Audio clip metadata (name, start, duration, source-file reference)". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void audioClipMetadata(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        forEachTrack(r, (track, expected, actual) -> {
            fact(softly, r, track + "clip count", actual.getClips().size(), expected.getClips().size());
            for (int c = 0; c < Math.min(expected.getClips().size(), actual.getClips().size()); c++) {
                AudioClip saved = expected.getClips().get(c);
                AudioClip clip = actual.getClips().get(c);
                String name = track + "clip " + c + " (" + saved.getName() + ") ";
                fact(softly, r, name + "name", clip.getName(), saved.getName());
                fact(softly, r, name + "start", clip.getStartBeat(), saved.getStartBeat());
                fact(softly, r, name + "duration", clip.getDurationBeats(), saved.getDurationBeats());
                fact(softly, r, name + "source offset", clip.getSourceOffsetBeats(), saved.getSourceOffsetBeats());
                fact(softly, r, name + "gain", clip.getGainDb(), saved.getGainDb());
                fact(softly, r, name + "reversed", clip.isReversed(), saved.isReversed());
                fact(softly, r, name + "locked", clip.isLocked(), saved.isLocked());
                fact(softly, r, name + "fade in", clip.getFadeInBeats(), saved.getFadeInBeats());
                fact(softly, r, name + "fade out", clip.getFadeOutBeats(), saved.getFadeOutBeats());
                fact(softly, r, name + "fade-in curve", clip.getFadeInCurveType(), saved.getFadeInCurveType());
                fact(softly, r, name + "fade-out curve", clip.getFadeOutCurveType(), saved.getFadeOutCurveType());
                fact(softly, r, name + "time stretch", clip.getTimeStretchRatio(), saved.getTimeStretchRatio());
                fact(softly, r, name + "pitch shift", clip.getPitchShiftSemitones(), saved.getPitchShiftSemitones());
                fact(softly, r, name + "stretch quality", clip.getStretchQuality(), saved.getStretchQuality());
                fact(softly, r, name + "source file", clip.getSourceFilePath(), saved.getSourceFilePath());
                fact(softly, r, name + "segments", clip.getSourceSegmentPaths(), saved.getSourceSegmentPaths());
                fact(softly, r, name + "gain envelope", clip.gainEnvelope(), saved.gainEnvelope());
            }
        });
        softly.assertAll();
    }

    /** §3.1 "MIDI notes + CC lanes (Track.midiClip)" — story 330. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void midiNotesAndCcLanes(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        forEachTrack(r, (track, expected, actual) -> {
            MidiClip saved = expected.getMidiClip();
            MidiClip clip = actual.getMidiClip();
            fact(softly, r, track + "MIDI clip locked", clip.isLocked(), saved.isLocked());
            fact(softly, r, track + "MIDI notes (every field, in order)", clip.getNotes(), saved.getNotes());
            fact(softly, r, track + "CC lane count", clip.getCcLanes().size(), saved.getCcLanes().size());
            for (int l = 0; l < Math.min(saved.getCcLanes().size(), clip.getCcLanes().size()); l++) {
                MidiCcLane expectedLane = saved.getCcLanes().get(l);
                MidiCcLane lane = clip.getCcLanes().get(l);
                String name = track + "CC lane " + l + " ";
                fact(softly, r, name + "type", lane.getType(), expectedLane.getType());
                fact(softly, r, name + "CC number", lane.getCcNumber(), expectedLane.getCcNumber());
                fact(softly, r, name + "14-bit", lane.isHighResolution(), expectedLane.isHighResolution());
                fact(softly, r, name + "channel", lane.getChannel(), expectedLane.getChannel());
                fact(softly, r, name + "height ratio", lane.getHeightRatio(), expectedLane.getHeightRatio());
                fact(softly, r, name + "events", lane.getEvents(), expectedLane.getEvents());
            }
        });
        softly.assertAll();
    }

    /** §3.1 "MIDI effect chains (arpeggiator settings)" — story 330. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void midiEffectChain(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        forEachTrack(r, (track, expected, actual) -> {
            List<MidiEffectPlugin> saved = expected.getMidiEffectChain();
            List<MidiEffectPlugin> chain = actual.getMidiEffectChain();
            fact(softly, r, track + "MIDI-effect chain length", chain.size(), saved.size());
            for (int e = 0; e < Math.min(saved.size(), chain.size()); e++) {
                String name = track + "MIDI effect " + e + " ";
                fact(softly, r, name + "kind", chain.get(e).getClass(), saved.get(e).getClass());
                if (saved.get(e) instanceof ArpeggiatorPlugin arp && chain.get(e) instanceof ArpeggiatorPlugin restored) {
                    fact(softly, r, name + "rate", restored.getRate(), arp.getRate());
                    fact(softly, r, name + "pattern", restored.getPattern(), arp.getPattern());
                    fact(softly, r, name + "octave range", restored.getOctaveRange(), arp.getOctaveRange());
                    fact(softly, r, name + "gate", restored.getGate(), arp.getGate());
                    fact(softly, r, name + "swing", restored.getSwing(), arp.getSwing());
                    fact(softly, r, name + "latch", restored.isLatch(), arp.isLatch());
                }
                if (saved.get(e) instanceof UnresolvedMidiEffect placeholder
                        && chain.get(e) instanceof UnresolvedMidiEffect restored) {
                    fact(softly, r, name + "placeholder attributes (verbatim, in order)",
                            List.copyOf(restored.attributes().entrySet()),
                            List.copyOf(placeholder.attributes().entrySet()));
                }
            }
        });
        softly.assertAll();
    }

    /** §3.1 "Pre-roll / post-roll configuration" — story 330: the exact (pre, post, enabled) triple. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void preRollPostRoll(Reopened r) {
        assertThat(r.reopened().getTransport().getPreRollPostRoll())
                .as("%s: pre/post-roll (pre bars, post bars, enabled)", r.path())
                .isEqualTo(r.original().getTransport().getPreRollPostRoll());
    }

    /** §3.1 "Loop region + punch region". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void loopAndPunch(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        Transport saved = r.original().getTransport();
        Transport transport = r.reopened().getTransport();
        fact(softly, r, "loop enabled", transport.isLoopEnabled(), saved.isLoopEnabled());
        fact(softly, r, "loop start", transport.getLoopStartInBeats(), saved.getLoopStartInBeats());
        fact(softly, r, "loop end", transport.getLoopEndInBeats(), saved.getLoopEndInBeats());
        fact(softly, r, "punch region", transport.getPunchRegion(), saved.getPunchRegion());
        softly.assertAll();
    }

    /** §3.1 "Tempo + time signature (beat 0)" — the tempo map is story 334's row. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void tempoAndTimeSignature(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        Transport saved = r.original().getTransport();
        Transport transport = r.reopened().getTransport();
        fact(softly, r, "tempo", transport.getTempo(), saved.getTempo());
        fact(softly, r, "time signature numerator",
                transport.getTimeSignatureNumerator(), saved.getTimeSignatureNumerator());
        fact(softly, r, "time signature denominator",
                transport.getTimeSignatureDenominator(), saved.getTimeSignatureDenominator());
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void playheadPosition(Reopened r) {
        assertThat(r.reopened().getTransport().getPositionInBeats())
                .as("%s: playhead position", r.path())
                .isEqualTo(r.original().getTransport().getPositionInBeats());
    }

    /** §3.1 "Automation breakpoints" — mixer-parameter lanes and object-parameter lanes. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void automationBreakpoints(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        forEachTrack(r, (track, expected, actual) -> {
            Map<AutomationParameter, AutomationLane> savedLanes = expected.getAutomationData().getLanes();
            Map<AutomationParameter, AutomationLane> lanes = actual.getAutomationData().getLanes();
            fact(softly, r, track + "automation lanes", lanes.keySet(), savedLanes.keySet());
            savedLanes.forEach((parameter, lane) -> sameLane(softly, r, track + parameter + " lane ",
                    lanes.get(parameter), lane));
            Map<ObjectParameterTarget, AutomationLane> savedObjects = expected.getAutomationData().getObjectLanes();
            Map<ObjectParameterTarget, AutomationLane> objects = actual.getAutomationData().getObjectLanes();
            fact(softly, r, track + "object lanes", objects.keySet(), savedObjects.keySet());
            savedObjects.forEach((target, lane) -> sameLane(softly, r, track + target.displayName() + " lane ",
                    objects.get(target), lane));
        });
        softly.assertAll();
    }

    private static void sameLane(SoftAssertions softly, Reopened r, String name,
                                 AutomationLane actual, AutomationLane expected) {
        if (actual == null) {
            softly.fail(r.path() + ": " + name + "is missing");
            return;
        }
        fact(softly, r, name + "visible", actual.isVisible(), expected.isVisible());
        fact(softly, r, name + "breakpoints (time, value, interpolation)", points(actual), points(expected));
    }

    /** §3.1 "Built-in insert slots + parameters" — built-ins and hosted analyzers, on every channel kind. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void builtInInsertsAndParameters(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        forEachChannel(r, (channel, expected, actual) -> {
            List<InsertSlot> saved = expected.getInsertSlots();
            List<InsertSlot> slots = actual.getInsertSlots();
            fact(softly, r, channel + "insert count", slots.size(), saved.size());
            for (int s = 0; s < Math.min(saved.size(), slots.size()); s++) {
                InsertSlot expectedSlot = saved.get(s);
                InsertSlot slot = slots.get(s);
                String name = channel + "insert " + s + " (" + expectedSlot.getName() + ") ";
                fact(softly, r, name + "name", slot.getName(), expectedSlot.getName());
                fact(softly, r, name + "effect type", slot.getEffectType(), expectedSlot.getEffectType());
                fact(softly, r, name + "bypassed", slot.isBypassed(), expectedSlot.isBypassed());
                fact(softly, r, name + "expensive", slot.isExpensive(), expectedSlot.isExpensive());
                fact(softly, r, name + "sidechain source", sidechainOf(r.reopened(), slot),
                        sidechainOf(r.original(), expectedSlot));
                if (expectedSlot.getEffectType() != null) {
                    fact(softly, r, name + "parameters by id", slot.snapshotParameterValues(),
                            expectedSlot.snapshotParameterValues());
                    if (ReflectivePresetSerializer.isSupported(expectedSlot.getProcessor())) {
                        fact(softly, r, name + "parameters by name",
                                ReflectivePresetSerializer.snapshot(slot.getProcessor()),
                                ReflectivePresetSerializer.snapshot(expectedSlot.getProcessor()));
                    }
                }
                if (expectedSlot.getPlugin() instanceof LiveAnalyzerPlugin analyzer) {
                    fact(softly, r, name + "analyzer", analyzerFacts(slot), analyzerFacts(expectedSlot));
                    fact(softly, r, name + "analyzer id", slot.getPlugin().getDescriptor().id(),
                            analyzer.getDescriptor().id());
                }
            }
        });
        softly.assertAll();
    }

    /** §3.1 "Mixer channel volume/pan/sends, VCA/return structure" — every channel's strip and sends. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void mixerChannelsAndSends(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        fact(softly, r, "return buses", busNames(r.reopened()), busNames(r.original()));
        forEachChannel(r, (channel, expected, actual) -> {
            fact(softly, r, channel + "volume", actual.getVolume(), expected.getVolume());
            fact(softly, r, channel + "pan", actual.getPan(), expected.getPan());
            fact(softly, r, channel + "mute", actual.isMuted(), expected.isMuted());
            fact(softly, r, channel + "solo", actual.isSolo(), expected.isSolo());
            fact(softly, r, channel + "solo safe", actual.isSoloSafe(), expected.isSoloSafe());
            fact(softly, r, channel + "phase invert", actual.isPhaseInverted(), expected.isPhaseInverted());
            fact(softly, r, channel + "output routing", actual.getOutputRouting(), expected.getOutputRouting());
            fact(softly, r, channel + "output routing name",
                    actual.getOutputRoutingDisplayName(), expected.getOutputRoutingDisplayName());
            fact(softly, r, channel + "CPU budget", actual.getCpuBudget(), expected.getCpuBudget());
            fact(softly, r, channel + "sends (target bus, level, tap)",
                    sends(r.reopened(), actual), sends(r.original(), expected));
        });
        softly.assertAll();
    }

    /** VCA membership resolves to the reopened channels and gives every track the same composite gain. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void vcaMembership(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        List<VcaGroup> saved = r.original().getVcaGroupManager().getVcaGroups();
        List<VcaGroup> groups = r.reopened().getVcaGroupManager().getVcaGroups();
        fact(softly, r, "VCA groups (id, label, gain, members)",
                groups.stream().map(ProjectRoundTripInventoryTest::vcaFacts).toList(),
                saved.stream().map(ProjectRoundTripInventoryTest::vcaFacts).toList());
        fact(softly, r, "composite track gain (fader × VCA)", compositeGains(r.reopened()), compositeGains(r.original()));
        for (VcaGroup group : groups) {
            for (UUID member : group.memberChannelIds()) {
                fact(softly, r, "VCA '" + group.label() + "' member " + member + " resolves to a reopened channel",
                        r.reopened().getMixer().getChannels().stream().anyMatch(c -> c.getId().equals(member)), true);
            }
        }
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void channelLinks(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        fact(softly, r, "channel links (channels, mode, flags)",
                r.reopened().getChannelLinkManager().getLinks(), r.original().getChannelLinkManager().getLinks());
        for (Track track : r.reopened().getTracks()) {
            UUID channel = r.reopened().getMixerChannelForTrack(track).getId();
            UUID partner = r.reopened().getChannelLinkManager().partnerOf(channel);
            if (partner != null) {
                fact(softly, r, "link partner of '" + track.getName() + "' resolves to a reopened channel",
                        r.reopened().getMixer().getChannels().stream().anyMatch(c -> c.getId().equals(partner)), true);
            }
        }
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void cueSends(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        List<CueBus> saved = r.original().getCueBusManager().getCueBuses();
        List<CueBus> buses = r.reopened().getCueBusManager().getCueBuses();
        fact(softly, r, "cue buses (id, label, output, sends: gain/pan/pre-fader, master gain)", buses, saved);
        for (CueBus bus : buses) {
            for (CueSend send : bus.sends()) {
                fact(softly, r, "cue bus '" + bus.label() + "' send resolves to a reopened track",
                        trackIds(r.reopened()).contains(send.trackId()), true);
            }
        }
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void bedRouting(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        BedBus saved = r.original().getBedBusManager().getBedBus();
        BedBus bus = r.reopened().getBedBusManager().getBedBus();
        fact(softly, r, "bed bus id", bus.id(), saved.id());
        fact(softly, r, "bed bus format", bus.format(), saved.format());
        fact(softly, r, "bed bus channel gains", list(bus.channelGainsDb()), list(saved.channelGainsDb()));
        fact(softly, r, "bed routings", routings(r.reopened()), routings(r.original()));
        for (UUID track : r.reopened().getBedBusManager().getRoutings().keySet()) {
            fact(softly, r, "bed routing " + track + " resolves to a reopened track",
                    trackIds(r.reopened()).contains(track), true);
        }
        softly.assertAll();
    }

    /** §3.1 "Markers, metronome settings, soundfont assignments". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void markers(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        fact(softly, r, "markers (name, position, type, colour)",
                markers(r.reopened().getMarkerManager().getMarkers()),
                markers(r.original().getMarkerManager().getMarkers()));
        fact(softly, r, "marker ranges (name, start, end, type, colour)",
                ranges(r.reopened().getMarkerManager().getMarkerRanges()),
                ranges(r.original().getMarkerManager().getMarkerRanges()));
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void metronomeAndClickOutput(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        Metronome saved = r.original().getMetronome();
        Metronome metronome = r.reopened().getMetronome();
        fact(softly, r, "metronome enabled", metronome.isEnabled(), saved.isEnabled());
        fact(softly, r, "metronome volume", metronome.getVolume(), saved.getVolume());
        fact(softly, r, "click sound", metronome.getClickSound(), saved.getClickSound());
        fact(softly, r, "subdivision", metronome.getSubdivision(), saved.getSubdivision());
        fact(softly, r, "click output", metronome.getClickOutput(), saved.getClickOutput());
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void soundFontAssignment(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        forEachTrack(r, (track, expected, actual) -> fact(softly, r, track + "SoundFont assignment",
                actual.getSoundFontAssignment(), expected.getSoundFontAssignment()));
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void referenceTracks(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        ReferenceTrackManager saved = r.original().getReferenceTrackManager();
        ReferenceTrackManager manager = r.reopened().getReferenceTrackManager();
        fact(softly, r, "reference tracks", manager.getReferenceTracks().stream()
                        .map(ProjectRoundTripInventoryTest::referenceFacts).toList(),
                saved.getReferenceTracks().stream().map(ProjectRoundTripInventoryTest::referenceFacts).toList());
        fact(softly, r, "active reference index", manager.getActiveIndex(), saved.getActiveIndex());
        fact(softly, r, "reference active", manager.isReferenceActive(), saved.isReferenceActive());
        softly.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void roomConfiguration(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        RoomConfiguration saved = r.original().getRoomConfiguration();
        RoomConfiguration room = r.reopened().getRoomConfiguration();
        fact(softly, r, "room dimensions and ceiling", room.getDimensions(), saved.getDimensions());
        fact(softly, r, "surface materials", room.getMaterialMap(), saved.getMaterialMap());
        fact(softly, r, "wall material", room.getWallMaterial(), saved.getWallMaterial());
        fact(softly, r, "sound sources", room.getSoundSources(), saved.getSoundSources());
        fact(softly, r, "source directivities", room.getSourceDirectivities(), saved.getSourceDirectivities());
        fact(softly, r, "microphones", room.getMicrophones(), saved.getMicrophones());
        fact(softly, r, "audience", room.getAudienceMembers(), saved.getAudienceMembers());
        fact(softly, r, "applied treatments", room.getAppliedTreatments(), saved.getAppliedTreatments());
        softly.assertAll();
    }

    /**
     * Mixer snapshots and the A/B slots. A send's target bus is compared by
     * its index in the return-bus list (return-bus ids are fresh on every
     * open), or by its id when the bus was removed after the capture.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void mixerSnapshots(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        MixerSnapshotManager saved = r.original().getMixerSnapshotManager();
        MixerSnapshotManager manager = r.reopened().getMixerSnapshotManager();
        fact(softly, r, "snapshots", manager.getSnapshots().stream().map(s -> snapshotFacts(r.reopened(), s)).toList(),
                saved.getSnapshots().stream().map(s -> snapshotFacts(r.original(), s)).toList());
        fact(softly, r, "slot A", snapshotFacts(r.reopened(), manager.getSlotA()),
                snapshotFacts(r.original(), saved.getSlotA()));
        fact(softly, r, "slot B", snapshotFacts(r.reopened(), manager.getSlotB()),
                snapshotFacts(r.original(), saved.getSlotB()));
        fact(softly, r, "active slot", manager.getActiveSlot(), saved.getActiveSlot());
        softly.assertAll();
    }

    /** §3.1 "Dock/window layout JSON (story 282)" and the other per-project preferences. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reopenings")
    void layoutAndProjectPreferences(Reopened r) {
        SoftAssertions softly = new SoftAssertions();
        fact(softly, r, "layout JSON", r.reopened().getLayoutJson(), r.original().getLayoutJson());
        fact(softly, r, "HRTF profile", r.reopened().getActiveHrtfProfileName(), r.original().getActiveHrtfProfileName());
        fact(softly, r, "loudness target", r.reopened().getLoudnessTarget(), r.original().getLoudnessTarget());
        fact(softly, r, "nudge settings", r.reopened().getNudgeSettings(), r.original().getNudgeSettings());
        fact(softly, r, "ripple mode", r.reopened().getRippleMode(), r.original().getRippleMode());
        softly.assertAll();
    }

    private static void fact(SoftAssertions softly, Reopened r, String fact, Object actual, Object expected) {
        softly.assertThat(actual).as("%s: %s", r.path(), fact).isEqualTo(expected);
    }

    /** A per-track comparison: the track's label, the saved track and the reopened one. */
    private interface TrackFacts {
        void compare(String track, Track expected, Track actual);
    }

    private static void forEachTrack(Reopened r, TrackFacts facts) {
        List<Track> saved = r.original().getTracks();
        List<Track> reopened = r.reopened().getTracks();
        for (int i = 0; i < Math.min(saved.size(), reopened.size()); i++) {
            facts.compare("track " + i + " (" + saved.get(i).getName() + ") ", saved.get(i), reopened.get(i));
        }
    }

    /** A per-channel comparison: the channel's label, the saved channel and the reopened one. */
    private interface ChannelFacts {
        void compare(String channel, MixerChannel expected, MixerChannel actual);
    }

    /** The master, every track channel and every return bus, matched by position. */
    private static void forEachChannel(Reopened r, ChannelFacts facts) {
        facts.compare("master ", r.original().getMixer().getMasterChannel(), r.reopened().getMixer().getMasterChannel());
        List<MixerChannel> saved = r.original().getMixer().getChannels();
        List<MixerChannel> channels = r.reopened().getMixer().getChannels();
        for (int i = 0; i < Math.min(saved.size(), channels.size()); i++) {
            facts.compare("channel " + i + " ", saved.get(i), channels.get(i));
        }
        List<MixerChannel> savedBuses = r.original().getMixer().getReturnBuses();
        List<MixerChannel> buses = r.reopened().getMixer().getReturnBuses();
        for (int i = 0; i < Math.min(savedBuses.size(), buses.size()); i++) {
            facts.compare("return bus " + i + " (" + savedBuses.get(i).getName() + ") ", savedBuses.get(i), buses.get(i));
        }
    }

    private static List<String> groups(DawProject project) {
        List<String> groups = new ArrayList<>();
        for (TrackGroup group : project.getTrackGroups()) {
            groups.add(group.getName() + " " + group.getTracks().stream().map(Track::getName).toList());
        }
        return groups;
    }

    private static List<String> points(AutomationLane lane) {
        List<String> points = new ArrayList<>();
        for (AutomationPoint point : lane.getPoints()) {
            points.add(point.getTimeInBeats() + "=" + point.getValue() + " " + point.getInterpolationMode());
        }
        return points;
    }

    /** Where a slot's sidechain comes from, by mixer position: {@code channel:1}, {@code return:0}. */
    private static String sidechainOf(DawProject project, InsertSlot slot) {
        MixerChannel source = slot.getSidechainSource();
        if (source == null) {
            return "none";
        }
        int channel = project.getMixer().getChannels().indexOf(source);
        return channel >= 0 ? "channel:" + channel : "return:" + project.getMixer().getReturnBuses().indexOf(source);
    }

    private static List<Object> analyzerFacts(InsertSlot slot) {
        List<Object> facts = new ArrayList<>();
        if (slot.getPlugin() instanceof LiveAnalyzerPlugin analyzer) {
            facts.add(analyzer.isActive());
        }
        if (slot.getPlugin() instanceof SpectrumAnalyzerPlugin spectrum) {
            facts.add(spectrum.getAnalyzer().getFftSize());
            facts.add(spectrum.getAnalyzer().getWindowType());
        }
        if (slot.getPlugin() instanceof TunerPlugin tuner) {
            facts.add(tuner.getReferencePitchHz());
        }
        return facts;
    }

    private static List<String> busNames(DawProject project) {
        return project.getMixer().getReturnBuses().stream().map(MixerChannel::getName).toList();
    }

    /** A channel's sends as (target bus index, level, tap). */
    private static List<String> sends(DawProject project, MixerChannel channel) {
        List<String> sends = new ArrayList<>();
        for (Send send : channel.getSends()) {
            sends.add("bus " + project.getMixer().getReturnBuses().indexOf(send.getTarget())
                    + " level " + send.getLevel() + " " + send.getTap());
        }
        return sends;
    }

    private static List<Object> vcaFacts(VcaGroup group) {
        return List.of(group.id(), group.label(), group.masterGainDb(),
                group.color() == null ? "no colour" : group.color().getHexColor(), group.memberChannelIds());
    }

    /** Each track's composite render gain: its channel fader times its VCA multiplier (as {@code Mixer} renders it). */
    private static Map<String, Double> compositeGains(DawProject project) {
        Map<String, Double> gains = new LinkedHashMap<>();
        for (Track track : project.getTracks()) {
            MixerChannel channel = project.getMixerChannelForTrack(track);
            gains.put(track.getName(), channel.getVolume()
                    * project.getVcaGroupManager().effectiveLinearMultiplier(channel.getId()));
        }
        return gains;
    }

    private static List<UUID> trackIds(DawProject project) {
        return project.getTracks().stream().map(track -> UUID.fromString(track.getId())).toList();
    }

    private static Map<UUID, List<Object>> routings(DawProject project) {
        Map<UUID, List<Object>> routings = new LinkedHashMap<>();
        for (BedChannelRouting routing : project.getBedBusManager().getRoutings().values()) {
            routings.put(routing.trackId(), List.of(routing.format(), list(routing.channelGainsDb())));
        }
        return routings;
    }

    private static List<Double> list(double[] values) {
        return values == null ? null : Arrays.stream(values).boxed().toList();
    }

    private static List<String> markers(List<Marker> markers) {
        return markers.stream()
                .map(m -> m.getName() + " @" + m.getPositionInBeats() + " " + m.getType() + " " + m.getColor())
                .toList();
    }

    private static List<String> ranges(List<MarkerRange> ranges) {
        return ranges.stream()
                .map(m -> m.getName() + " " + m.getStartPositionInBeats() + "–" + m.getEndPositionInBeats()
                        + " " + m.getType() + " " + m.getColor())
                .toList();
    }

    private static List<Object> referenceFacts(ReferenceTrack reference) {
        List<Object> facts = new ArrayList<>();
        facts.add(reference.getName());
        facts.add(reference.getSourceFilePath());
        facts.add(reference.getGainOffsetDb());
        facts.add(reference.isLoopEnabled());
        facts.add(reference.getLoopStartInBeats());
        facts.add(reference.getLoopEndInBeats());
        facts.add(reference.getIntegratedLufs());
        facts.add(String.valueOf(reference.getImmersiveFormat()));
        facts.add(String.valueOf(list(reference.getPerChannelTrimDb())));
        return facts;
    }

    private static List<Object> snapshotFacts(DawProject project, MixerSnapshot snapshot) {
        if (snapshot == null) {
            return List.of("none");
        }
        List<Object> facts = new ArrayList<>();
        facts.add(snapshot.name());
        facts.add(snapshot.timestamp());
        facts.add(channelSnapshotFacts(project, snapshot.master()));
        facts.add(snapshot.channels().stream().map(c -> channelSnapshotFacts(project, c)).toList());
        facts.add(snapshot.returnBuses().stream().map(c -> channelSnapshotFacts(project, c)).toList());
        return facts;
    }

    private static List<Object> channelSnapshotFacts(DawProject project, ChannelSnapshot channel) {
        List<Object> facts = new ArrayList<>(List.of(channel.volume(), channel.pan(), channel.muted(),
                channel.solo(), channel.phaseInverted(), channel.outputRouting(), channel.inserts(),
                String.valueOf(channel.cpuBudget())));
        List<MixerChannel> buses = project.getMixer().getReturnBuses();
        for (SendSnapshot send : channel.sends()) {
            int index = -1;
            for (int i = 0; i < buses.size(); i++) {
                if (buses.get(i).getId().equals(send.targetId())) {
                    index = i;
                }
            }
            facts.add((index >= 0 ? "bus " + index : "removed bus " + send.targetId())
                    + " level " + send.level() + " " + send.tap());
        }
        return facts;
    }
}
