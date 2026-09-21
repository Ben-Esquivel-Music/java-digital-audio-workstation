package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audioimport.AudioReadResult;
import com.benesquivelmusic.daw.core.audioimport.ReferenceFileLoader;
import com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor;
import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import com.benesquivelmusic.daw.core.export.SampleRateConverter;
import com.benesquivelmusic.daw.core.plugin.editor.MatchEqEditor;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in spectrum-matched ("match EQ") effect plugin.
 *
 * <p>Wraps the DAW's {@link MatchEqProcessor} as a first-class plugin so it
 * appears in the Plugins menu alongside external plugins. Match EQ analyses
 * the long-term-average spectrum of a reference track (story 041) and the
 * current source, computes the tonal difference, and applies it as either
 * a minimum-phase IIR cascade or a linear-phase FIR.</p>
 *
 * <p>Exposed generic parameters cover the parameters that map naturally to
 * sliders in the stock {@code PluginParameterEditorPanel}. Reference loading
 * and spectrum capture are driven by dedicated API calls and the
 * {@code MatchEqPluginView} UI — not through numeric parameters.</p>
 */
@BuiltInPlugin(label = "Match EQ", icon = "eq", category = BuiltInPluginCategory.EFFECT)
public final class MatchEqPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.builtin.match-eq";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Match EQ",
            "1.0.0",
            "DAW Built-in",
            PluginType.EFFECT,
            PluginCategory.EQ_AND_FILTER,
            "eq"
    );

    private static final MatchEqProcessor.FftSize[] FFT_SIZES = MatchEqProcessor.FftSize.values();
    private static final MatchEqProcessor.Smoothing[] SMOOTHING_MODES = MatchEqProcessor.Smoothing.values();
    private static final MatchEqProcessor.PhaseMode[] PHASE_MODES = MatchEqProcessor.PhaseMode.values();
    private volatile MatchEqProcessor processor;
    private final AudioProcessor signalPath = new MatchSignalPath();
    private ProgressiveDspPreparation<MatchEqProcessor> preparation;
    private volatile int fftSizeIndex = MatchEqProcessor.FftSize.SIZE_2048.ordinal();
    private volatile int smoothingIndex = MatchEqProcessor.Smoothing.THIRD_OCTAVE.ordinal();
    private volatile double amount = 1.0;
    private volatile int phaseIndex = MatchEqProcessor.PhaseMode.MINIMUM_PHASE.ordinal();
    private boolean active;

    public MatchEqPlugin() {
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        processor = new MatchEqProcessor(context.getAudioChannels(), context.getSampleRate());
        preparation = new ProgressiveDspPreparation<>(
                () -> prepareMatch(processor), next -> processor = next);
    }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        if (processor != null) {
            processor.reset();
        }
    }

    @Override
    public void dispose() {
        active = false;
        if (preparation != null) preparation.close();
        processor = null;
    }

    @Override
    public Optional<AudioProcessor> asAudioProcessor() {
        return processor == null ? Optional.empty() : Optional.of(signalPath);
    }

    /**
     * Returns the underlying {@link MatchEqProcessor}, or {@code null} if
     * the plugin has not been initialized or has been disposed.
     */
    public MatchEqProcessor getProcessor() {
        return processor;
    }

    /**
     * Returns the parameter descriptors for this plugin.
     *
     * <p>Parameter ids correspond to: 0 = FFT size (enum ordinal), 1 = smoothing
     * (enum ordinal), 2 = amount (0–1), 3 = phase mode (enum ordinal).</p>
     */
    @Override
    public List<PluginParameter> getParameters() {
        int fftMax = MatchEqProcessor.FftSize.values().length - 1;
        int smoothingMax = MatchEqProcessor.Smoothing.values().length - 1;
        int phaseMax = MatchEqProcessor.PhaseMode.values().length - 1;
        return List.of(
                new PluginParameter(0, "FFT Size", 0.0, fftMax,
                        MatchEqProcessor.FftSize.SIZE_2048.ordinal()),
                new PluginParameter(1, "Smoothing", 0.0, smoothingMax,
                        MatchEqProcessor.Smoothing.THIRD_OCTAVE.ordinal()),
                new PluginParameter(2, "Amount", 0.0, 1.0, 1.0),
                new PluginParameter(3, "Phase Mode", 0.0, phaseMax,
                        MatchEqProcessor.PhaseMode.MINIMUM_PHASE.ordinal()));
    }

    @Override
    @RealTimeSafe
    public void setAutomatableParameter(int parameterId, double value) {
        if (!Double.isFinite(value)) return;
        switch (parameterId) {
            case 0 -> {
                int next = (int) Math.round(Math.clamp(value, 0.0, FFT_SIZES.length - 1.0));
                if (fftSizeIndex == next) return;
                fftSizeIndex = next;
            }
            case 1 -> {
                int next = (int) Math.round(Math.clamp(value, 0.0, SMOOTHING_MODES.length - 1.0));
                if (smoothingIndex == next) return;
                smoothingIndex = next;
            }
            case 2 -> {
                double next = Math.clamp(value, 0.0, 1.0);
                if (amount == next) return;
                amount = next;
            }
            case 3 -> {
                int next = (int) Math.round(Math.clamp(value, 0.0, PHASE_MODES.length - 1.0));
                if (phaseIndex == next) return;
                phaseIndex = next;
            }
            default -> { return; }
        }
        if (preparation != null) preparation.request();
    }

    private MatchEqProcessor prepareMatch(MatchEqProcessor current) {
        var fftSize = FFT_SIZES[fftSizeIndex];
        var smoothing = SMOOTHING_MODES[smoothingIndex];
        double matchAmount = amount;
        var phase = PHASE_MODES[phaseIndex];
        var next = new MatchEqProcessor(current.getChannelCount(), current.getSampleRate());
        next.setFftSize(fftSize);
        next.setSmoothing(smoothing);
        next.setAmount(matchAmount);
        next.setPhaseMode(phase);
        next.setFirOrder(current.getFirOrder());
        int bins = next.getFftSize().value() / 2 + 1;
        double[] source = remapSpectrum(current.getSourceSpectrum(), bins);
        double[] reference = remapSpectrum(current.getReferenceSpectrum(), bins);
        if (source != null) next.setSourceSpectrum(source);
        if (reference != null) next.setReferenceSpectrum(reference);
        if (source != null && reference != null) next.updateMatch();
        return next;
    }

    /** Retains captured spectra when the FFT grid changes; the sample rate stays fixed. */
    private static double[] remapSpectrum(double[] captured, int bins) {
        if (captured == null || captured.length == bins) return captured;
        double[] remapped = new double[bins];
        for (int bin = 0; bin < bins; bin++) {
            double position = (double) bin * (captured.length - 1) / (bins - 1);
            int lower = (int) position;
            int upper = Math.min(lower + 1, captured.length - 1);
            double fraction = position - lower;
            remapped[bin] = captured[lower] + fraction * (captured[upper] - captured[lower]);
        }
        return remapped;
    }

    private final class MatchSignalPath implements AudioProcessor, PreparedParameterProcessor, DynamicLatencyProcessor {
        private boolean hostPreparesParameters;

        @Override public void enableRealtimeParameterPreparation() { hostPreparesParameters = true; }
        @Override @RealTimeSafe public void applyPreparedParameters() { if (preparation != null) preparation.apply(); }
        @Override public void awaitParameterPreparation() { if (preparation != null) preparation.await(); }
        @Override public void closeParameterPreparation() { if (preparation != null) preparation.close(); }
        @Override @RealTimeSafe
        public void process(float[][] input, float[][] output, int frames) {
            if (!hostPreparesParameters) applyPreparedParameters();
            MatchEqProcessor current = processor;
            if (current != null) current.process(input, output, frames);
        }
        @Override public void reset() { if (processor != null) processor.reset(); }
        @Override public int getInputChannelCount() { return processor == null ? 0 : processor.getChannelCount(); }
        @Override public int getOutputChannelCount() { return processor == null ? 0 : processor.getChannelCount(); }
        @Override public int getLatencySamples() {
            MatchEqProcessor current = processor;
            return current == null ? 0 : current.getLatencySamples();
        }
    }

    /**
     * Loads an audio file from disk and analyzes it as the reference spectrum.
     *
     * <p>Provides the one-off direct audio-file loading path required by the
     * Match EQ plugin's UI (outside the story 041 {@link ReferenceTrack}
     * workflow). The file is decoded via the shared audio-import readers
     * ({@link ReferenceFileLoader}), wrapped in a transient
     * {@link ReferenceTrack}, and passed to
     * {@link MatchEqProcessor#analyzeReference(ReferenceTrack)}.</p>
     *
     * @param file the audio file to load (WAV/FLAC/AIFF/OGG/MP3)
     * @return the populated {@link ReferenceTrack} for further UI display
     * @throws IllegalStateException    if the plugin has not been initialized
     * @throws IllegalArgumentException if the file format is unsupported
     * @throws IOException              if the file cannot be read
     */
    public ReferenceTrack loadReferenceFile(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (processor == null) {
            throw new IllegalStateException("plugin has not been initialized");
        }
        AudioReadResult read = ReferenceFileLoader.read(file);
        float[][] audio = read.audioData();
        int processorRate = (int) Math.round(processor.getSampleRate());
        if (read.sampleRate() != processorRate) {
            // Resample to the processor's sample rate so FFT bins map to the
            // correct frequencies. Uses the same windowed-sinc converter as
            // AudioFileImporter to keep import and reference paths consistent.
            float[][] converted = new float[audio.length][];
            for (int ch = 0; ch < audio.length; ch++) {
                converted[ch] = SampleRateConverter.convert(
                        audio[ch], read.sampleRate(), processorRate);
            }
            audio = converted;
        }
        ReferenceTrack track = new ReferenceTrack(
                file.getFileName().toString(), file.toString());
        track.setAudioData(audio);
        processor.analyzeReference(track);
        return track;
    }

    /** Returns whether the plugin is currently active (between activate/deactivate). */
    public boolean isActive() {
        return active;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Story 302 (Plugin View Design Book §8.3 item 5): returns the
     * immersive {@link MatchEqEditor} canvas — the source/reference/target
     * spectrum plot — replacing this plugin's previous implicit
     * {@code Declarative} default as its visual surface. The four
     * {@link #getParameters()} entries remain the declarative data automation
     * binds to; the canvas is the visual surface.</p>
     */
    @Override
    public PluginEditorFactory editorFactory() {
        return new MatchEqEditor(this);
    }
}
