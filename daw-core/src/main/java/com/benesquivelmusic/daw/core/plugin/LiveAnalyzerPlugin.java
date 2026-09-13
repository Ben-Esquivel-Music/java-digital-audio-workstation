package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.util.Optional;
import java.util.function.Consumer;

/** A hosted analyzer: transforms run in the tap lane, never in its insert processor. */
public interface LiveAnalyzerPlugin extends DawPlugin {
    AnalyzerProcessor createAnalysisConsumer(Consumer<AnalyzerSnapshot> publish);
    void acceptAnalysis(AnalyzerSnapshot snapshot);
    boolean isActive();
    default long analysisRevision() { return 0; }

    @Override
    default Optional<AudioProcessor> asAudioProcessor() {
        return Optional.of(new TransparentProcessor());
    }

    /** Transparent in both engine precisions; no transform or allocation on RT. */
    final class TransparentProcessor implements AudioProcessor {
        @Override @RealTimeSafe
        public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < Math.min(input.length, output.length); channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
        }
        @Override @RealTimeSafe
        public void processDouble(double[][] input, double[][] output, int frames) {
            for (int channel = 0; channel < Math.min(input.length, output.length); channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
        }
        @Override public boolean supportsDouble() { return true; }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
