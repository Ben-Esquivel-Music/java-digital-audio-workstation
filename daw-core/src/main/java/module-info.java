/**
 * {@code daw.core} — the DAW audio engine: mixer graph, DSP processors,
 * built-in plugins, persistence, transport, spatial audio and the native
 * (FFM / JEP 454) backends for PortAudio, FluidSynth, CLAP and the codec
 * exporters.
 *
 * <p>{@code daw.core} sits above {@code daw.sdk} (stable API floor) and
 * {@code daw.acoustics} (acoustic-modelling library) and below the
 * {@code daw.app} JavaFX shell, which is its only first-party consumer.
 *
 * <p>Unlike {@code daw.sdk}/{@code daw.acoustics}, {@code daw.core} is an
 * <em>internal engine</em> module rather than a semantically-versioned public
 * API, so it is not governed by {@code ModuleExportsAllowlistTest}. The export
 * list below is exactly the set of packages consumed by {@code daw.app}; the
 * implementation-only packages enumerated at the bottom are deliberately
 * <strong>not</strong> exported and remain strongly encapsulated.
 *
 * <h2>Built-in plugin discovery</h2>
 * Built-in plugins are discovered through the JPMS {@link java.util.ServiceLoader}
 * SPI: this module {@code provides} every concrete
 * {@link com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin} implementation
 * and also {@code uses} the same service so that
 * {@code BuiltInDawPlugin.discoverAll()} / {@code menuEntries()} resolve
 * providers from this module's own layer (replacing the previous
 * {@code Class.getPermittedSubclasses()} reflection enumeration).
 *
 * <h2>Native access</h2>
 * The PortAudio / FluidSynth / CLAP / codec bindings use the Foreign Function
 * &amp; Memory API. Restricted native access for this <em>named</em> module is
 * granted with {@code --enable-native-access=daw.core} (Surefire argLine and
 * the packaged launcher); the legacy {@code ALL-UNNAMED} grant no longer
 * applies once the module is named.
 */
module daw.core {
    requires transitive daw.sdk;
    requires daw.acoustics;

    // javax.xml.parsers / javax.xml.transform — DAWproject + session XML I/O.
    requires java.xml;
    requires java.logging;
    // java.lang.management (ThreadMXBean) + com.sun.management
    // (HotSpotDiagnosticMXBean / allocation counters) used by
    // RealtimeAllocationDetector.
    requires java.management;
    requires jdk.management;
    // java.util.prefs — RecentProjectsStore.
    requires java.prefs;
    // java.desktop (java.awt.geom, javax.sound.sampled) is supplied transitively
    // by daw.sdk (`requires transitive java.desktop`).

    // ---------------------------------------------------------------------
    // Exported packages — exactly the surface consumed by daw.app.
    // Implementation-only packages are listed (unexported) at the bottom.
    // ---------------------------------------------------------------------
    exports com.benesquivelmusic.daw.core.analysis;
    exports com.benesquivelmusic.daw.core.audio;
    exports com.benesquivelmusic.daw.core.audio.cache;
    exports com.benesquivelmusic.daw.core.audio.javasound;
    exports com.benesquivelmusic.daw.core.audio.performance;
    exports com.benesquivelmusic.daw.core.audio.portaudio;
    exports com.benesquivelmusic.daw.core.audioimport;
    exports com.benesquivelmusic.daw.core.automation;
    // Sample preview engine — surfaced to daw.app's BrowserPanel per-row
    // audition button (story 275). Single-channel auditioner.
    exports com.benesquivelmusic.daw.core.browser;
    // Story 281 Task 2 — Clip sealed-ish interface is referenced by
    // daw.app's ClipEditorFactory / WorkshopSelectionHostController as
    // the common type that dispatches AudioClip vs MidiClip into the
    // Workshop clip-detail slot.
    exports com.benesquivelmusic.daw.core.clip;
    exports com.benesquivelmusic.daw.core.comping;
    exports com.benesquivelmusic.daw.core.dsp;
    exports com.benesquivelmusic.daw.core.dsp.acoustics;
    exports com.benesquivelmusic.daw.core.dsp.dynamics;
    exports com.benesquivelmusic.daw.core.dsp.eq;
    exports com.benesquivelmusic.daw.core.dsp.mastering;
    exports com.benesquivelmusic.daw.core.dsp.reverb;
    exports com.benesquivelmusic.daw.core.dsp.saturation;
    exports com.benesquivelmusic.daw.core.export;
    exports com.benesquivelmusic.daw.core.export.aaf;
    exports com.benesquivelmusic.daw.core.marker;
    exports com.benesquivelmusic.daw.core.mastering;
    exports com.benesquivelmusic.daw.core.midi;
    exports com.benesquivelmusic.daw.core.mixer;
    exports com.benesquivelmusic.daw.core.mixer.snapshot;
    exports com.benesquivelmusic.daw.core.mixer.spatial;
    exports com.benesquivelmusic.daw.core.performance;
    exports com.benesquivelmusic.daw.core.persistence;
    exports com.benesquivelmusic.daw.core.persistence.archive;
    exports com.benesquivelmusic.daw.core.persistence.backup;
    exports com.benesquivelmusic.daw.core.persistence.migration;
    exports com.benesquivelmusic.daw.core.plugin;
    exports com.benesquivelmusic.daw.core.plugin.builtin.midi;
    exports com.benesquivelmusic.daw.core.plugin.clap;
    exports com.benesquivelmusic.daw.core.plugin.parameter;
    exports com.benesquivelmusic.daw.core.preset;
    exports com.benesquivelmusic.daw.core.project;
    exports com.benesquivelmusic.daw.core.project.edit;
    exports com.benesquivelmusic.daw.core.recording;
    exports com.benesquivelmusic.daw.core.reference;
    exports com.benesquivelmusic.daw.core.session.dawproject;
    exports com.benesquivelmusic.daw.core.snapshot;
    exports com.benesquivelmusic.daw.core.spatial.ambisonics;
    exports com.benesquivelmusic.daw.core.spatial.binaural;
    exports com.benesquivelmusic.daw.core.spatial.objectbased;
    exports com.benesquivelmusic.daw.core.spatial.panner;
    exports com.benesquivelmusic.daw.core.spatial.qc;
    exports com.benesquivelmusic.daw.core.spatial.room;
    exports com.benesquivelmusic.daw.core.telemetry;
    exports com.benesquivelmusic.daw.core.telemetry.acoustics;
    exports com.benesquivelmusic.daw.core.telemetry.advisor;
    exports com.benesquivelmusic.daw.core.template;
    exports com.benesquivelmusic.daw.core.track;
    exports com.benesquivelmusic.daw.core.transport;
    exports com.benesquivelmusic.daw.core.undo;

    // ---------------------------------------------------------------------
    // Built-in plugin discovery via ServiceLoader (replaces the sealed-type
    // getPermittedSubclasses() enumeration in BuiltInDawPlugin.discoverAll()).
    // ---------------------------------------------------------------------
    uses com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;

    // Behaviour-preserving provider set: this is exactly the set of plugins
    // the previous BuiltInDawPlugin.discoverAll()/menuEntries() enumerated.
    // The old sealed `permits` clause additionally listed the MidiEffectPlugin
    // *marker interface* (skipped at runtime via `clazz.isInterface()`), so
    // ArpeggiatorPlugin — permitted by MidiEffectPlugin, not directly by
    // BuiltInDawPlugin — was never returned by discoverAll(). It is therefore
    // intentionally NOT registered here so the discoverable set stays the
    // same 24 concrete plugins.
    provides com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin with
            com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin,
            com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin,
            com.benesquivelmusic.daw.core.plugin.GraphicEqPlugin,
            com.benesquivelmusic.daw.core.plugin.CompressorPlugin,
            com.benesquivelmusic.daw.core.plugin.BusCompressorPlugin,
            com.benesquivelmusic.daw.core.plugin.MultibandCompressorPlugin,
            com.benesquivelmusic.daw.core.plugin.ReverbPlugin,
            com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin,
            com.benesquivelmusic.daw.core.plugin.TunerPlugin,
            com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin,
            com.benesquivelmusic.daw.core.plugin.SignalGeneratorPlugin,
            com.benesquivelmusic.daw.core.plugin.MetronomePlugin,
            com.benesquivelmusic.daw.core.plugin.AcousticReverbPlugin,
            com.benesquivelmusic.daw.core.plugin.BinauralMonitorPlugin,
            com.benesquivelmusic.daw.core.plugin.WaveshaperPlugin,
            com.benesquivelmusic.daw.core.plugin.MatchEqPlugin,
            com.benesquivelmusic.daw.core.plugin.DeEsserPlugin,
            com.benesquivelmusic.daw.core.plugin.TruePeakLimiterPlugin,
            com.benesquivelmusic.daw.core.plugin.TransientShaperPlugin,
            com.benesquivelmusic.daw.core.plugin.NoiseGatePlugin,
            com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin,
            com.benesquivelmusic.daw.core.plugin.ConvolutionReverbPlugin,
            com.benesquivelmusic.daw.core.plugin.ExciterPlugin,
            com.benesquivelmusic.daw.core.plugin.DitherPlugin;

    // ---------------------------------------------------------------------
    // NOT exported — implementation-only packages, strongly encapsulated:
    //   com.benesquivelmusic.daw.core.analysis.quality
    //   com.benesquivelmusic.daw.core.audio.processing
    //   com.benesquivelmusic.daw.core.audio.ringbuffer
    //   com.benesquivelmusic.daw.core.concurrent
    //   com.benesquivelmusic.daw.core.event
    //   com.benesquivelmusic.daw.core.export.omf
    //   com.benesquivelmusic.daw.core.midi.fluidsynth
    //   com.benesquivelmusic.daw.core.midi.javasound
    //   com.benesquivelmusic.daw.core.spatial
    // ---------------------------------------------------------------------
}
