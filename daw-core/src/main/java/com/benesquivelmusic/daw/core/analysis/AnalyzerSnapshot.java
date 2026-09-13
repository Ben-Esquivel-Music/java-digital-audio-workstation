package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

/** Analysis-thread snapshots. A null payload explicitly means no signal. */
public sealed interface AnalyzerSnapshot {
    record Spectrum(SpectrumData data) implements AnalyzerSnapshot {
        public Spectrum { data = copy(data); }
        @Override public SpectrumData data() { return copy(data); }
        private static SpectrumData copy(SpectrumData data) {
            return data == null ? null : new SpectrumData(data.magnitudesDb(), data.peakHoldDb(), data.fftSize(), data.sampleRate());
        }
    }
    record Waveform(WaveformData data) implements AnalyzerSnapshot {
        public Waveform { data = copy(data); }
        @Override public WaveformData data() { return copy(data); }
        private static WaveformData copy(WaveformData data) {
            return data == null ? null : new WaveformData(data.minValues(), data.maxValues(), data.rmsValues(), data.columns());
        }
    }
    record Correlation(CorrelationData data) implements AnalyzerSnapshot { }
    record Loudness(LoudnessData data) implements AnalyzerSnapshot { }
    record Pitch(TuningResult data) implements AnalyzerSnapshot { }
}
