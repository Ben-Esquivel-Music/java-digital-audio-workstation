package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Test-only unity-gain passthrough that exists purely to REPORT plugin
 * latency, so a test can drive the render pipeline's delay compensation
 * without perturbing the audio it is asserting on.
 *
 * <p>Dropping one of these into an {@link com.benesquivelmusic.daw.core.mixer.InsertSlot}
 * on any channel raises {@code Mixer.getSystemLatencySamples()} to
 * {@code latency}, which makes {@code RenderPipeline.renderBlock} shift its
 * content cursor {@code latency} frames ahead of the raw transport cursor.
 * That PDC shift is the condition under which the content walk and the click
 * walk cross the loop end at different frames, so every loop-boundary
 * regression that depends on the shift uses this processor.</p>
 *
 * <p>The declared channel counts are nominal — nothing in the mixer's insert
 * path consumes them — while {@link #process} copies whatever channel count
 * the host hands it, so the same instance is safe in mono and stereo
 * fixtures alike.</p>
 *
 * <p>Package-private and top-level rather than nested in one test: it is
 * shared by {@code RenderPipelineLoopPrecisionTest} and
 * {@code AudioEngineMidiPlaybackTest}.</p>
 *
 * @param latency the latency in sample frames this processor reports
 */
record ReportedLatencyProcessor(int latency) implements AudioProcessor {

    @Override
    public void process(float[][] in, float[][] out, int frames) {
        for (int ch = 0; ch < in.length; ch++) {
            System.arraycopy(in[ch], 0, out[ch], 0, frames);
        }
    }

    @Override public void reset() {}
    @Override public int getInputChannelCount() { return 1; }
    @Override public int getOutputChannelCount() { return 1; }

    @Override
    public int getLatencySamples() {
        return latency;
    }
}
