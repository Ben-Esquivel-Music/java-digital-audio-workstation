package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.metering.AnalysisConsumer;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Bounded streaming adapters for the story-318 analysis lane. Transforms and
 * window copies run only on its analysis thread; callbacks carry snapshots.
 * Sample-time hops are independent of render block size and JavaFX pulses.
 */
public final class AnalyzerProcessor implements AnalysisConsumer, AutoCloseable {
    public enum Kind { SPECTRUM, WAVEFORM, CORRELATION, LOUDNESS, PITCH }

    public static final int FFT_SIZE = 4096;
    public static final int FFT_HOP = 1024;
    public static final long IDLE_NANOS = 500_000_000L;
    private static final String[] NOTES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private final Kind kind;
    private final Consumer<AnalyzerSnapshot> publish;
    private final DoubleSupplier referencePitch;
    private final int fftSize;
    private final WindowType windowType;
    private final LongSupplier nanoTime;
    private double sampleRate;
    private int channels;
    private float[][] history;
    private float[][] window;
    private int cursor;
    private int filled;
    private int untilHop;
    private int hop;
    private long lastBlockNanos;
    private long droppedBlocks;
    private SpectrumAnalyzer spectrumLeft;
    private SpectrumAnalyzer spectrumRight;
    private PitchDetector pitch;
    private CorrelationMeter correlation;
    private LoudnessMeter loudness;
    private boolean mono;
    private int dominantChannel;
    private volatile boolean closed;

    public AnalyzerProcessor(Kind kind, Consumer<AnalyzerSnapshot> publish) {
        this(kind, publish, () -> 440.0, FFT_SIZE, WindowType.HANN);
    }

    public AnalyzerProcessor(Kind kind, Consumer<AnalyzerSnapshot> publish, LongSupplier nanoTime) {
        this(kind, publish, () -> 440.0, FFT_SIZE, WindowType.HANN, nanoTime);
    }

    public AnalyzerProcessor(Kind kind, Consumer<AnalyzerSnapshot> publish,
                             DoubleSupplier referencePitch, int fftSize, WindowType windowType) {
        this(kind, publish, referencePitch, fftSize, windowType, System::nanoTime);
    }

    private AnalyzerProcessor(Kind kind, Consumer<AnalyzerSnapshot> publish,
                              DoubleSupplier referencePitch, int fftSize, WindowType windowType,
                              LongSupplier nanoTime) {
        this.kind = Objects.requireNonNull(kind);
        this.publish = Objects.requireNonNull(publish);
        this.referencePitch = Objects.requireNonNull(referencePitch);
        this.fftSize = fftSize;
        this.windowType = Objects.requireNonNull(windowType);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override
    public void onBlock(float[][] samples, int channelCount, int numFrames, double rate) {
        if (closed || rate <= 0 || channelCount < 1 || numFrames < 1) return;
        long now = nanoTime.getAsLong();
        if (history == null || rate != sampleRate || channels != channelCount) {
            configure(rate);
            channels = channelCount;
        } else if (now - lastBlockNanos > IDLE_NANOS) {
            resetWindow();
        }
        mono = channelCount == 1;
        lastBlockNanos = now;
        for (int frame = 0; frame < numFrames; frame++) {
            history[0][cursor] = samples[0][frame];
            history[1][cursor] = samples[channelCount > 1 ? 1 : 0][frame];
            cursor = (cursor + 1) % history[0].length;
            filled = Math.min(filled + 1, history[0].length);
            if (--untilHop == 0) {
                untilHop = hop;
                if (filled == history[0].length) analyze();
            }
        }
    }

    private void configure(double rate) {
        if (loudness != null) loudness.close();
        sampleRate = rate;
        int size = switch (kind) {
            case SPECTRUM -> fftSize;
            case PITCH -> FFT_SIZE;
            case WAVEFORM -> FFT_HOP;
            case CORRELATION -> Math.max(1, (int) Math.round(rate / 15));
            case LOUDNESS -> Math.max(1, (int) Math.round(rate / 10));
        };
        hop = switch (kind) {
            case SPECTRUM -> Math.min(FFT_HOP, fftSize);
            case PITCH -> Math.max(1, (int) Math.round(rate / 15));
            default -> size;
        };
        history = new float[2][size];
        window = new float[2][size];
        cursor = filled = 0;
        untilHop = size;
        switch (kind) {
            case SPECTRUM -> {
                spectrumLeft = new SpectrumAnalyzer(size, rate, 0, windowType, false, 0);
                spectrumRight = new SpectrumAnalyzer(size, rate, 0, windowType, false, 0);
            }
            case PITCH -> pitch = new PitchDetector(size, rate);
            case CORRELATION -> correlation = new CorrelationMeter(0);
            case LOUDNESS -> loudness = new LoudnessMeter(rate, size);
            case WAVEFORM -> { }
        }
    }

    private void analyze() {
        int size = window[0].length;
        double energy = 0;
        double leftEnergy = 0;
        for (int channel = 0; channel < 2; channel++) {
            int tail = size - cursor;
            System.arraycopy(history[channel], cursor, window[channel], 0, tail);
            System.arraycopy(history[channel], 0, window[channel], tail, cursor);
            for (float value : window[channel]) energy += value * value;
            if (channel == 0) leftEnergy = energy;
        }
        dominantChannel = energy - leftEnergy > leftEnergy ? 1 : 0;
        boolean signal = energy / (2 * size) > 1e-12;
        // Pattern switch is final since Java 21 (JEP 441); no preview needed.
        publish.accept(switch (kind) {
            case SPECTRUM -> new AnalyzerSnapshot.Spectrum(signal ? spectrum() : null);
            case WAVEFORM -> new AnalyzerSnapshot.Waveform(signal ? waveform() : null);
            case CORRELATION -> {
                correlation.process(window[0], window[1], size);
                yield new AnalyzerSnapshot.Correlation(signal ? correlation.getLatestData() : null);
            }
            case LOUDNESS -> {
                if (mono) loudness.processMono(window[0], size);
                else loudness.process(window[0], window[1], size);
                yield new AnalyzerSnapshot.Loudness(signal ? loudness.getLatestData() : null);
            }
            case PITCH -> new AnalyzerSnapshot.Pitch(signal ? tuning() : null);
        });
    }

    private SpectrumData spectrum() {
        spectrumLeft.process(window[0]);
        spectrumRight.process(window[1]);
        float[] left = spectrumLeft.getLatestData().magnitudesDb();
        float[] right = spectrumRight.getLatestData().magnitudesDb();
        float[] power = new float[left.length];
        for (int i = 0; i < power.length; i++) {
            // Combine channel powers so polarity inversion cannot erase a spectrum.
            power[i] = (float) (10 * Math.log10((Math.pow(10, left[i] / 10.0)
                    + Math.pow(10, right[i] / 10.0)) / 2));
        }
        return new SpectrumData(power, fftSize, sampleRate);
    }

    private WaveformData waveform() {
        float[] values = window[dominantChannel];
        float[] rms = new float[values.length];
        for (int i = 0; i < rms.length; i++) rms[i] = Math.abs(values[i]);
        return new WaveformData(values, values, rms, values.length);
    }

    private TuningResult tuning() {
        var detected = pitch.detect(window[dominantChannel]);
        if (!detected.pitched()) return null;
        double semitones = 12 * Math.log(detected.frequencyHz() / referencePitch.getAsDouble()) / Math.log(2);
        int nearest = (int) Math.round(semitones);
        double cents = (semitones - nearest) * 100;
        int midi = 69 + nearest;
        return new TuningResult(NOTES[Math.floorMod(midi, 12)], Math.floorDiv(midi, 12) - 1,
                detected.frequencyHz(), cents, Math.abs(cents) <= 3);
    }

    @Override
    public void onOverrun(long count) {
        droppedBlocks = count;
        resetWindow();
    }

    private void resetWindow() {
        // A gap must not join unrelated samples into an FFT or pitch window.
        // Keep the loudness meter's integrated and LRA program history.
        if (history != null) {
            for (float[] channel : history) Arrays.fill(channel, 0);
            filled = cursor = 0;
            untilHop = history[0].length;
        }
    }

    public long droppedBlocks() { return droppedBlocks; }

    @Override
    public void close() {
        closed = true;
        if (loudness != null) loudness.close();
    }
}
