package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.marker.MarkerType;
import com.benesquivelmusic.daw.core.midi.MidiCcEvent;
import com.benesquivelmusic.daw.core.midi.MidiCcLane;
import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.mixer.CueSend;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendTap;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.VcaGroupManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.spatial.BedBus;
import com.benesquivelmusic.daw.core.mixer.spatial.BedBusManager;
import com.benesquivelmusic.daw.core.mixer.spatial.BedChannelRouting;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
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
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.AudienceMember;
import com.benesquivelmusic.daw.sdk.telemetry.CeilingShape;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SourceDirectivity;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;

/**
 * The project family the write/read parity contract and the round-trip
 * inventory test share (Persistence Integrity book §4.4): built through the
 * public model API only, it puts every element and attribute the
 * {@link ProjectSerializer} can emit into some written document, under every
 * path the shared builders write it at — except the two items the
 * application never produces, named below.
 *
 * <p>The {@code "full"} variant holds everything that can coexist in one
 * project: tracks with a stable input identity and with only a legacy input
 * hint; audio clips (locked, multi-segment with a gain envelope, faded,
 * stretched); automation and object lanes; MIDI notes and CC lanes (an
 * ARBITRARY_CC lane among them); a MIDI-effect chain of two arpeggiators
 * around an unrecognised-effect placeholder; a non-{@code DISABLED}
 * pre/post-roll; a master, four track channels and two return buses, every
 * channel kind carrying inserts (built-ins with a parameter moved off its
 * default, spectrum and tuner analyzers, one of them inactive, and a
 * sidechained insert), sends and a CPU budget — every degradation policy,
 * every send tap, a sidechain from a track channel and from a return bus;
 * cue buses, VCA groups, a channel link and bed routings, all naming tracks
 * of the project; markers, ranges, track groups; a disabled metronome with a
 * non-default click output; reference tracks (one immersive, with trims); a
 * room with a flat ceiling, a non-omni source and both treatment locations;
 * a mixer snapshot and A/B slots, each holding sends to a return bus removed
 * after the capture; layout JSON, an HRTF profile, a loudness target, nudge
 * settings and ripple mode. The ceiling kinds are mutually exclusive, so
 * each other kind has a variant of its own. A channel carries one CPU budget,
 * so the master's second policy-specific attribute comes from the
 * {@code "kernel-substituting master"} variant, which also holds the
 * family's one non-stereo audio format and every snapshot path of that
 * master.</p>
 *
 * <p>Not produced: the id-keyed insert-parameter form ({@code parameter@id}
 * under a mixer insert), which no model state makes the serializer write —
 * it is written only for a built-in processor without
 * {@code @ProcessorParam} accessors, and the one such processor
 * ({@code ParametricEqProcessor}) has no id-keyed parameters; snapshot
 * inserts write that form and are covered — and the output routing of the
 * master and of a return bus, live or in a snapshot, written only off
 * {@code OutputRouting.MASTER}: the model accepts that routing on any
 * channel, but no UI path produces it, because MixerView offers the routing
 * selector on track strips only (the parity contract tolerates those
 * reads).</p>
 *
 * <p>When the serializer gains an item, extend {@link #full(Path)} (or add a
 * variant for a mutually exclusive branch) in the same change: the contract
 * test names every writer literal the family does not emit.</p>
 */
final class RoundTripInventoryProjects {

    /** One written project of the family, named for failure messages. */
    record Variant(String name, DawProject project) {
    }

    /** The audio format of every variant but one. */
    static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    /**
     * The {@code "kernel-substituting master"} variant's audio format: six
     * channels, away from the reader's stereo default, so the round trip of a
     * format discriminates.
     */
    static final AudioFormat SURROUND_FORMAT = new AudioFormat(96_000.0, 6, 32, 1024);

    /** The {@code plugin-id} of the MIDI effect no build recognises. */
    static final String UNRESOLVED_EFFECT_ID = "com.example.future.harmonizer";

    private RoundTripInventoryProjects() {
    }

    /**
     * The whole family: the {@code "full"} project (flat ceiling) and one
     * room-only project per other ceiling kind.
     *
     * @param assetRoot the absolute directory the clip, SoundFont and
     *                  reference-track paths are under
     */
    static List<Variant> variants(Path assetRoot) {
        return List.of(
                new Variant("full", full(assetRoot)),
                new Variant("domed ceiling", roomOnly("Domed Room", new CeilingShape.Domed(3.0, 6.5))),
                new Variant("barrel-vault ceiling",
                        roomOnly("Vaulted Room", new CeilingShape.BarrelVault(3.2, 5.8, CeilingShape.Axis.Y))),
                new Variant("cathedral ceiling",
                        roomOnly("Cathedral Room", new CeilingShape.Cathedral(2.8, 7.4, CeilingShape.Axis.X))),
                new Variant("angled ceiling",
                        roomOnly("Angled Room", new CeilingShape.Angled(2.4, 4.1, CeilingShape.Axis.Y))),
                new Variant("kernel-substituting master", kernelSubstitutingMaster()));
    }

    /**
     * The project that holds every coexisting item (see the class comment).
     *
     * @param assetRoot the absolute directory the clip, SoundFont and
     *                  reference-track paths are under
     */
    static DawProject full(Path assetRoot) {
        DawProject project = new DawProject("Inventory Session", FORMAT);
        transport(project.getTransport());

        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        Track keys = project.createMidiTrack("Keys");
        Track pad = project.createMidiTrack("Pad");
        drums(drums, assetRoot);
        bass(bass, assetRoot);
        keys(keys, assetRoot);
        pad.setColor(TrackColor.INDIGO);
        pad.setFoldState(new TrackFoldState(false, true, false, 0.0));
        pad.getMidiClip().setLocked(true);

        mixer(project, drums, bass, keys, pad);
        mixerReferences(project, drums, bass, keys, pad);

        project.getMarkerManager().addMarker(marker("Intro", 0.0, MarkerType.SECTION, "#FF0000"));
        project.getMarkerManager().addMarker(new Marker("Hook", 32.0, MarkerType.ARRANGEMENT));
        MarkerRange verse = new MarkerRange("Verse", 16.0, 32.0, MarkerType.REHEARSAL);
        verse.setColor("#00FF00");
        project.getMarkerManager().addMarkerRange(verse);
        project.getMarkerManager().addMarkerRange(new MarkerRange("Outro", 48.0, 64.0, MarkerType.SECTION));
        project.createTrackGroup("Rhythm", List.of(drums, bass));
        project.createTrackGroup("Synths", List.of(keys, pad));

        metronome(project.getMetronome());
        referenceTracks(project.getReferenceTrackManager(), assetRoot);
        project.setRoomConfiguration(room());
        mixerSnapshots(project, keys);

        project.setRippleMode(RippleMode.ALL_TRACKS);
        project.setNudgeSettings(new NudgeSettings(NudgeUnit.MILLISECONDS, 12.5));
        project.setLoudnessTarget(LoudnessTarget.APPLE_MUSIC);
        project.setActiveHrtfProfileName("KEMAR Large Pinna");
        project.setLayoutJson("{\"panels\":[{\"id\":\"mixer\",\"zone\":\"BOTTOM\"}],\"note\":\"a]]>b\"}");
        return project;
    }

    /**
     * A 5.1-format project whose master's CPU budget substitutes a simple
     * kernel, captured into a saved snapshot and both A/B slots: the master
     * paths of {@code cpu-budget@kernel-id} the full project's master
     * ({@code ReduceOversampling}) cannot hold.
     */
    private static DawProject kernelSubstitutingMaster() {
        DawProject project = new DawProject("Kernel Master", SURROUND_FORMAT);
        Mixer mixer = project.getMixer();
        mixer.getMasterChannel().setCpuBudget(new TrackCpuBudget(0.8,
                new DegradationPolicy.SubstituteSimpleKernel("simple-limiter")));
        MixerSnapshotManager snapshots = project.getMixerSnapshotManager();
        snapshots.addSnapshot(MixerSnapshot.capture(mixer, "Kernel"));
        snapshots.setSlot(MixerSnapshotManager.Slot.A, MixerSnapshot.capture(mixer, "Kernel A"));
        snapshots.setSlot(MixerSnapshotManager.Slot.B, MixerSnapshot.capture(mixer, "Kernel B"));
        return project;
    }

    private static DawProject roomOnly(String name, CeilingShape ceiling) {
        DawProject project = new DawProject(name, FORMAT);
        project.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(8.0, 6.0, ceiling), WallMaterial.WOOD));
        return project;
    }

    private static void transport(Transport transport) {
        transport.setTempo(97.5);
        transport.setTimeSignature(7, 8);
        transport.setLoopWindow(true, 8.0, 24.0);
        transport.setPositionInBeats(13.25);
        transport.setPunchRegion(new PunchRegion(96_000L, 288_000L, true));
        // Bars with the flag off: the reader must restore the exact triple.
        transport.setPreRollPostRoll(new PreRollPostRoll(2, 3, false));
    }

    private static void drums(Track drums, Path assetRoot) {
        drums.setColor(TrackColor.ORANGE);
        drums.setVolume(0.8);
        drums.setPan(-0.25);
        drums.setSolo(true);
        drums.setCollapsed(true);
        drums.setAutomationMode(AutomationMode.LATCH);
        drums.setInputMonitoring(InputMonitoringMode.TAPE);
        drums.setInputDevice(Optional.of(new DeviceId("ASIO", "Studio Interface ASIO")));
        drums.setInputRouting(new InputRouting(2, 2));
        drums.setInputRoutingDisplayName("In 3-4");
        drums.setFoldState(new TrackFoldState(true, false, true, 96.0));

        AudioClip kick = new AudioClip("Kick Loop", 0.0, 8.0, asset(assetRoot, "audio/kick.wav"));
        kick.setGainDb(-2.5);
        kick.setReversed(true);
        kick.setLocked(true);
        kick.setFadeInBeats(0.5);
        kick.setFadeOutBeats(1.0);
        kick.setFadeInCurveType(FadeCurveType.EQUAL_POWER);
        kick.setFadeOutCurveType(FadeCurveType.S_CURVE);
        kick.setTimeStretchRatio(1.25);
        kick.setPitchShiftSemitones(-3.0);
        kick.setStretchQuality(StretchQuality.HIGH);
        kick.setSourceOffsetBeats(0.75);
        drums.addClip(kick);

        AudioClip take = new AudioClip("Take 3", 8.0, 16.0, null);
        take.setSourceSegmentPaths(List.of(
                asset(assetRoot, "audio/take3-0.wav"),
                asset(assetRoot, "audio/take3-1.wav"),
                asset(assetRoot, "audio/take3-2.wav")));
        take.setGainEnvelope(new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, -6.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(24_000L, 0.0, CurveShape.EXPONENTIAL),
                new ClipGainEnvelope.BreakpointDb(96_000L, -12.0, CurveShape.S_CURVE))));
        drums.addClip(take);

        AutomationLane volume = drums.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME);
        volume.setVisible(true);
        volume.addPoint(new AutomationPoint(0.0, 0.2, InterpolationMode.LINEAR));
        volume.addPoint(new AutomationPoint(4.0, 0.9, InterpolationMode.CURVED));
        AutomationLane pan = drums.getAutomationData().getOrCreateLane(AutomationParameter.PAN);
        pan.setVisible(false);
        pan.addPoint(new AutomationPoint(2.0, -0.5, InterpolationMode.CURVED));
        AutomationLane objectX = drums.getAutomationData().getOrCreateObjectLane(
                new ObjectParameterTarget("drums-object", ObjectParameter.X));
        objectX.setVisible(true);
        objectX.addPoint(new AutomationPoint(1.0, -0.75, InterpolationMode.LINEAR));
        objectX.addPoint(new AutomationPoint(3.0, 0.5, InterpolationMode.CURVED));
    }

    private static void bass(Track bass, Path assetRoot) {
        bass.setColor(TrackColor.BLUE);
        bass.setVolume(0.65);
        bass.setPan(0.1);
        bass.setMuted(true);
        bass.setArmed(true);
        bass.setPhaseInverted(true);
        bass.setInputMonitoring(InputMonitoringMode.OFF);
        // An unmigrated track: no stable identity, only the enumeration-index hint.
        bass.setLegacyInputDeviceIndexHint(3);
        bass.addClip(new AudioClip("Bass DI", 0.0, 32.0, asset(assetRoot, "audio/bass.wav")));
    }

    private static void keys(Track keys, Path assetRoot) {
        keys.setColor(TrackColor.PURPLE);
        keys.setAutomationMode(AutomationMode.WRITE);
        keys.setMidiInputDeviceName("USB MIDI Keyboard");
        keys.setSoundFontAssignment(new SoundFontAssignment(
                assetRoot.resolve("soundfonts/grand.sf2"), 0, 5, "Electric Piano"));

        MidiClip clip = keys.getMidiClip();
        clip.addNote(new MidiNoteData(60, 0, 4, 100, 0));
        clip.addNote(new MidiNoteData(64, 4, 2, 0, 15));
        clip.addNote(new MidiNoteData(127, 6, 1, 127, 9));
        clip.addNote(new MidiNoteData(60, 0, 4, 100, 0));
        clip.addCcLane(MidiCcLane.velocity());
        MidiCcLane filter = new MidiCcLane(MidiCcLaneType.ARBITRARY_CC, 74, false, 2);
        filter.setHeightRatio(0.75);
        filter.addEvent(new MidiCcEvent(0, 10));
        filter.addEvent(new MidiCcEvent(8, 120));
        clip.addCcLane(filter);
        MidiCcLane bend = new MidiCcLane(MidiCcLaneType.PITCH_BEND, -1, true, 1);
        bend.addEvent(new MidiCcEvent(0, MidiCcEvent.PITCH_BEND_CENTRE));
        bend.addEvent(new MidiCcEvent(4, 16383));
        clip.addCcLane(bend);
        MidiCcLane sustain = MidiCcLane.preset(MidiCcLaneType.SUSTAIN, false);
        sustain.setHeightRatio(0.5);
        sustain.addEvent(new MidiCcEvent(0, 127));
        sustain.addEvent(new MidiCcEvent(12, 0));
        clip.addCcLane(sustain);

        ArpeggiatorPlugin upDown = new ArpeggiatorPlugin();
        upDown.setRate(ArpeggiatorPlugin.Rate.EIGHTH);
        upDown.setPattern(ArpeggiatorPlugin.Pattern.UP_DOWN);
        upDown.setOctaveRange(3);
        upDown.setGate(75.0);
        upDown.setSwing(20.0);
        upDown.setLatch(true);
        keys.getMidiEffectChain().add(upDown);
        // Saved by a build that knew this effect; kept as a placeholder, in place.
        SequencedMap<String, String> unknown = new LinkedHashMap<>();
        unknown.put("mix", "0.5");
        unknown.put("plugin-id", UNRESOLVED_EFFECT_ID);
        unknown.put("voices", "3");
        keys.getMidiEffectChain().add(new UnresolvedMidiEffect(unknown));
        ArpeggiatorPlugin asPlayed = new ArpeggiatorPlugin();
        asPlayed.setRate(ArpeggiatorPlugin.Rate.SIXTEENTH_TRIPLET);
        asPlayed.setPattern(ArpeggiatorPlugin.Pattern.AS_PLAYED);
        asPlayed.setOctaveRange(2);
        asPlayed.setGate(150.0);
        asPlayed.setSwing(50.0);
        asPlayed.setLatch(false);
        keys.getMidiEffectChain().add(asPlayed);
    }

    /**
     * Every mixer channel kind carries inserts, sends and a CPU budget, so
     * every path the shared channel builder writes them at is emitted.
     */
    private static void mixer(DawProject project, Track drums, Track bass, Track keys, Track pad) {
        Mixer mixer = project.getMixer();
        MixerChannel master = mixer.getMasterChannel();
        MixerChannel reverb = mixer.getReturnBuses().getFirst();
        MixerChannel delay = mixer.addReturnBus("Delay Return");
        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);
        MixerChannel bassChannel = project.getMixerChannelForTrack(bass);
        MixerChannel keysChannel = project.getMixerChannelForTrack(keys);
        MixerChannel padChannel = project.getMixerChannelForTrack(pad);

        master.setVolume(0.9);
        master.setPan(0.05);
        master.addInsert(spectrum("Master Spectrum", 2048, WindowType.HAMMING));
        master.addInsert(tuner("Master Tuner", 442.0));
        InsertSlot limiter = builtIn(InsertEffectType.LIMITER);
        limiter.setBypassed(true);
        limiter.setExpensive(true);
        limiter.setSidechainSource(drumsChannel);
        master.addInsert(limiter);
        master.addSend(new Send(reverb, 0.1, SendTap.PRE_FADER));
        master.setCpuBudget(new TrackCpuBudget(0.75, new DegradationPolicy.ReduceOversampling(3)));

        drumsChannel.setVolume(0.7);
        drumsChannel.setPan(-0.3);
        drumsChannel.setSolo(true);
        drumsChannel.setSoloSafe(true);
        drumsChannel.setPhaseInverted(true);
        drumsChannel.setOutputRouting(new OutputRouting(4, 2));
        drumsChannel.setOutputRoutingDisplayName("Out 5-6");
        InsertSlot gate = builtIn(InsertEffectType.NOISE_GATE);
        gate.setSidechainSource(bassChannel);
        drumsChannel.addInsert(gate);
        drumsChannel.addInsert(spectrum("Drums Spectrum", 1024, WindowType.HANN));
        drumsChannel.addInsert(tuner("Drums Tuner", 432.0));
        drumsChannel.addSend(new Send(reverb, 0.35, SendTap.POST_FADER));
        drumsChannel.addSend(new Send(delay, 0.2, SendTap.PRE_INSERTS));
        drumsChannel.setCpuBudget(new TrackCpuBudget(0.4, new DegradationPolicy.ReduceOversampling(2)));

        bassChannel.setVolume(0.55);
        bassChannel.setMuted(true);
        bassChannel.setOutputRouting(new OutputRouting(0, 1));
        InsertSlot compressor = builtIn(InsertEffectType.COMPRESSOR);
        compressor.setSidechainSource(delay);
        bassChannel.addInsert(compressor);
        bassChannel.addSend(new Send(reverb, 0.5, SendTap.PRE_FADER));
        bassChannel.setCpuBudget(new TrackCpuBudget(0.3,
                new DegradationPolicy.SubstituteSimpleKernel("simple-compressor")));

        keysChannel.setVolume(0.6);
        keysChannel.setPan(0.4);
        keysChannel.addInsert(builtIn(InsertEffectType.CHORUS));
        keysChannel.addSend(new Send(delay, 0.6, SendTap.POST_FADER));
        keysChannel.setCpuBudget(new TrackCpuBudget(0.9, new DegradationPolicy.DoNothing()));

        padChannel.setVolume(0.45);
        padChannel.setCpuBudget(new TrackCpuBudget(0.5, new DegradationPolicy.BypassExpensive()));

        reverb.setVolume(0.8);
        reverb.addInsert(builtIn(InsertEffectType.REVERB));
        reverb.addInsert(spectrum("Reverb Spectrum", 4096, WindowType.BLACKMAN_HARRIS));
        // Saved inactive: the reader must not re-activate it.
        reverb.addInsert(tuner("Reverb Tuner", 440.0, false));
        reverb.setCpuBudget(new TrackCpuBudget(0.6,
                new DegradationPolicy.SubstituteSimpleKernel("simple-reverb")));

        delay.setVolume(0.65);
        delay.setSoloSafe(false);
        // A ducking delay: keyed by the Keys channel.
        InsertSlot ducked = builtIn(InsertEffectType.DELAY);
        ducked.setSidechainSource(keysChannel);
        delay.addInsert(ducked);
        // A return bus parsed after the default bus may send into it.
        delay.addSend(new Send(reverb, 0.25, SendTap.POST_FADER));
        delay.setCpuBudget(new TrackCpuBudget(0.5, new DegradationPolicy.ReduceOversampling(4)));
    }

    /** Cue sends, VCA members, a channel link and bed routings — all naming tracks of this project. */
    private static void mixerReferences(DawProject project, Track drums, Track bass, Track keys, Track pad) {
        CueBusManager cues = project.getCueBusManager();
        CueBus singer = cues.createCueBus("Singer", 1);
        cues.replace(singer
                .withSend(new CueSend(id(drums), 0.9, -0.5, true))
                .withSend(new CueSend(id(keys), 0.4, 0.6, false))
                .withMasterGain(0.85));
        CueBus drummer = cues.createCueBus("Drummer", 2);
        cues.replace(drummer.withSend(new CueSend(id(bass), 0.7, 0.0, true)));

        VcaGroupManager vcas = project.getVcaGroupManager();
        VcaGroup rhythm = vcas.createVcaGroup("Rhythm VCA", List.of(id(drums), id(bass)));
        vcas.replace(new VcaGroup(rhythm.id(), "Rhythm VCA", -4.5, TrackColor.TEAL, rhythm.memberChannelIds()));
        VcaGroup synths = vcas.createVcaGroup("Synth VCA", List.of(id(keys)));
        vcas.setMasterGainDb(synths.id(), 2.0);

        project.getChannelLinkManager().link(
                new ChannelLink(id(keys), id(pad), LinkMode.ABSOLUTE, false, true, false, false));

        BedBusManager bed = project.getBedBusManager();
        bed.setBedBus(new BedBus(UUID.fromString("00000000-0000-0000-0000-00000000bed1"),
                ImmersiveFormat.FORMAT_5_1, new double[] {0.0, -1.5, 0.0, -3.0, -6.0, -6.0}));
        bed.setRouting(BedChannelRouting.silent(id(keys), ImmersiveFormat.FORMAT_5_1)
                .withChannelGain(0, -3.0).withChannelGain(2, -1.5));
        bed.setRouting(BedChannelRouting.silent(id(pad), ImmersiveFormat.FORMAT_5_1)
                .withChannelGain(4, -9.0));
    }

    private static void metronome(Metronome metronome) {
        // Away from the model's default (enabled), so its round trip discriminates.
        metronome.setEnabled(false);
        metronome.setVolume(0.6f);
        metronome.setClickSound(ClickSound.COWBELL);
        metronome.setSubdivision(Subdivision.EIGHTH);
        metronome.setClickOutput(new ClickOutput(3, 0.75, false, true));
    }

    private static void referenceTracks(ReferenceTrackManager manager, Path assetRoot) {
        ReferenceTrack commercial = new ReferenceTrack("Commercial Mix", asset(assetRoot, "refs/commercial.wav"));
        commercial.setGainOffsetDb(-2.0);
        commercial.setLoopEnabled(true);
        commercial.setLoopRegion(4.0, 12.0);
        commercial.setIntegratedLufs(-9.5);
        manager.addReferenceTrack(commercial);
        ReferenceTrack atmos = new ReferenceTrack("Atmos Reference", asset(assetRoot, "refs/atmos.wav"));
        atmos.setImmersiveFormat(ImmersiveFormat.FORMAT_5_1);
        atmos.setPerChannelTrimDb(new double[] {0.0, -0.5, 1.0, 0.0, -2.0, -2.5});
        atmos.setIntegratedLufs(-18.0);
        manager.addReferenceTrack(atmos);
        manager.setActiveIndex(1);
        manager.setReferenceActive(true);
    }

    private static RoomConfiguration room() {
        RoomConfiguration room = new RoomConfiguration(new RoomDimensions(9.5, 7.25, 3.4),
                new SurfaceMaterialMap(WallMaterial.WOOD, WallMaterial.DRYWALL, WallMaterial.ACOUSTIC_FOAM,
                        WallMaterial.BRICK, WallMaterial.CURTAINS, WallMaterial.ACOUSTIC_TILE));
        room.addSoundSource(new SoundSource("Guitar Amp", new Position3D(2.0, 3.0, 1.0), 92.0));
        room.setSourceDirectivity("Guitar Amp", SourceDirectivity.CARDIOID);
        room.addSoundSource(new SoundSource("Vocalist", new Position3D(4.5, 2.0, 1.6), 80.0));
        room.addMicrophone(new MicrophonePlacement("Room L", new Position3D(1.0, 6.0, 2.0), 30.0, -5.0));
        room.addAudienceMember(new AudienceMember("Producer", new Position3D(4.75, 6.0, 1.2)));
        room.addAppliedTreatment(new AcousticTreatment(TreatmentKind.ABSORBER_BROADBAND,
                new WallAttachment.OnSurface(RoomSurface.LEFT_WALL, 2.5, 1.2),
                new Rectangle2D.Double(-0.3, -0.6, 0.6, 1.2), 1.5));
        room.addAppliedTreatment(new AcousticTreatment(TreatmentKind.ABSORBER_LF_TRAP,
                new WallAttachment.InCorner(RoomSurface.FRONT_WALL, RoomSurface.LEFT_WALL, 1.25),
                new Rectangle2D.Double(-0.3, -0.9, 0.6, 1.8), 2.1));
        return room;
    }

    /**
     * A saved snapshot and both A/B slots, each captured from a mixer whose
     * every channel kind carries inserts, sends and a CPU budget (see
     * {@link #captureWithRemovedBus}).
     */
    private static void mixerSnapshots(DawProject project, Track keys) {
        MixerSnapshotManager snapshots = project.getMixerSnapshotManager();
        snapshots.addSnapshot(captureWithRemovedBus(project, keys, "Verse"));
        snapshots.setSlot(MixerSnapshotManager.Slot.A, captureWithRemovedBus(project, keys, "Mix A"));
        snapshots.setSlot(MixerSnapshotManager.Slot.B, captureWithRemovedBus(project, keys, "Mix B"));
        snapshots.setActiveSlot(MixerSnapshotManager.Slot.B);
    }

    /**
     * Captures the mixer while the master, the Keys channel and the delay
     * return bus also send to an extra return bus, which is then removed —
     * its sends first, as a user empties a bus before deleting it. On those
     * sends the snapshot keeps the removed bus's id ({@code target}) and has
     * no bus index to write ({@code target-index}): the one state in which
     * the reader needs {@code target}, here under each channel kind.
     */
    private static MixerSnapshot captureWithRemovedBus(DawProject project, Track keys, String name) {
        Mixer mixer = project.getMixer();
        MixerChannel extra = mixer.addReturnBus(name + " FX");
        List<MixerChannel> senders = List.of(mixer.getMasterChannel(),
                project.getMixerChannelForTrack(keys), mixer.getReturnBuses().get(1));
        for (MixerChannel sender : senders) {
            sender.addSend(new Send(extra, 0.3, SendTap.PRE_FADER));
        }
        MixerSnapshot snapshot = MixerSnapshot.capture(mixer, name);
        for (MixerChannel sender : senders) {
            sender.removeSend(sender.getSendForTarget(extra));
        }
        mixer.removeReturnBus(extra);
        return snapshot;
    }

    /** A built-in insert with its first parameter moved off its default. */
    private static InsertSlot builtIn(InsertEffectType type) {
        InsertSlot slot = InsertEffectFactory.createSlot(type, FORMAT.channels(), FORMAT.sampleRate());
        List<PluginParameter> parameters = InsertEffectFactory.getParameterDescriptors(type);
        if (!parameters.isEmpty()) {
            PluginParameter first = parameters.getFirst();
            InsertEffectFactory.createParameterHandler(type, slot.getProcessor())
                    .accept(first.id(), (first.minValue() + first.maxValue()) / 2.0);
        }
        return slot;
    }

    private static InsertSlot spectrum(String name, int fftSize, WindowType window) {
        SpectrumAnalyzerPlugin spectrum = new SpectrumAnalyzerPlugin();
        initialize(spectrum);
        spectrum.reconfigure(fftSize, window);
        return new InsertSlot(name, spectrum.asAudioProcessor().orElseThrow(), null, spectrum);
    }

    private static InsertSlot tuner(String name, double referencePitchHz) {
        return tuner(name, referencePitchHz, true);
    }

    private static InsertSlot tuner(String name, double referencePitchHz, boolean active) {
        TunerPlugin tuner = new TunerPlugin();
        initialize(tuner);
        tuner.setReferencePitchHz(referencePitchHz);
        if (!active) {
            tuner.deactivate();
        }
        return new InsertSlot(name, tuner.asAudioProcessor().orElseThrow(), null, tuner);
    }

    private static void initialize(LiveAnalyzerPlugin plugin) {
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return FORMAT.sampleRate(); }
            @Override public int getBufferSize() { return FORMAT.bufferSize(); }
            @Override public int getAudioChannels() { return FORMAT.channels(); }
            @Override public void log(String message) { }
        });
        plugin.activate();
    }

    private static Marker marker(String name, double position, MarkerType type, String color) {
        Marker marker = new Marker(name, position, type);
        marker.setColor(color);
        return marker;
    }

    private static String asset(Path assetRoot, String relative) {
        return assetRoot.resolve(relative).toString();
    }

    static UUID id(Track track) {
        return UUID.fromString(track.getId());
    }
}
