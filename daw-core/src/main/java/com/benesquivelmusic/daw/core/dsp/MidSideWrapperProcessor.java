package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * {@link AudioProcessor} that wraps two independent processor chains operating
 * on the Mid (M = (L+R)·0.5) and Side (S = (L−R)·0.5) representations of a
 * stereo signal.
 *
 * <p>Pipeline per audio block:</p>
 * <ol>
 *   <li>Encode L/R → M/S (see {@link MidSideEncoder}).</li>
 *   <li>Run the mid chain on M as a mono buffer (one input channel, one output).</li>
 *   <li>Run the side chain on S as a mono buffer.</li>
 *   <li>Decode M/S → L/R (see {@link MidSideDecoder}).</li>
 * </ol>
 *
 * <p>Each inner chain hosts any sequence of {@link AudioProcessor} instances.
 * Inner processors are unaware they are operating on M/S signals — they see a
 * single mono channel. This is a documented simplification that covers the
 * vast majority of practical M/S use cases (EQ, compression, gain, de-essing).</p>
 *
 * <p>When both chains are empty (or the wrapper is {@linkplain #setBypassed
 * bypassed}), processing is bit-exact with copying input to output, because
 * encoding immediately followed by decoding is mathematically identity:</p>
 * <pre>
 *   L' = M + S = (L+R)/2 + (L−R)/2 = L
 *   R' = M − S = (L+R)/2 − (L−R)/2 = R
 * </pre>
 *
 * <p>The processor pre-allocates its scratch buffers via {@link #process} (lazily
 * sized to the largest seen frame count) so that steady-state processing is
 * allocation-free and real-time safe.</p>
 *
 * <h2>Latency / PDC</h2>
 * <p>Sample-accurate plugin delay compensation across mid vs. side chains when
 * inner processors report different latencies is a future refinement; this
 * implementation aligns by zero-pad (i.e., simply trusts each chain to be
 * latency-equivalent or accepts a small alignment error).</p>
 */
public final class MidSideWrapperProcessor implements AudioProcessor {

    private final List<AudioProcessor> midChain = new ArrayList<>();
    private final List<AudioProcessor> sideChain = new ArrayList<>();

    private float[] midBuf;
    private float[] sideBuf;
    private float[][] monoIn;   // [1][numFrames] view
    private float[][] monoOut;  // [1][numFrames] view
    private float[] scratchMono;

    private volatile boolean bypassed;

    /** Creates an empty wrapper with no processors in either chain. */
    public MidSideWrapperProcessor() {
    }

    /** Returns a live, mutable view of the mid chain. */
    public List<AudioProcessor> getMidChain() {
        return midChain;
    }

    /** Returns a live, mutable view of the side chain. */
    public List<AudioProcessor> getSideChain() {
        return sideChain;
    }

    /** Returns an unmodifiable, live view of the mid chain (reflects future mutations). */
    public List<AudioProcessor> midChainView() {
        return Collections.unmodifiableList(midChain);
    }

    /** Returns an unmodifiable, live view of the side chain (reflects future mutations). */
    public List<AudioProcessor> sideChainView() {
        return Collections.unmodifiableList(sideChain);
    }

    /** Appends a processor to the mid chain. */
    public void addMidProcessor(AudioProcessor processor) {
        midChain.add(Objects.requireNonNull(processor, "processor must not be null"));
    }

    /** Appends a processor to the side chain. */
    public void addSideProcessor(AudioProcessor processor) {
        sideChain.add(Objects.requireNonNull(processor, "processor must not be null"));
    }

    /**
     * Returns whether this wrapper is bypassed. When bypassed, the wrapper
     * copies input to output without encoding/decoding or running either chain.
     */
    public boolean isBypassed() {
        return bypassed;
    }

    /** Sets the bypass state. */
    public void setBypassed(boolean bypassed) {
        this.bypassed = bypassed;
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (numFrames <= 0) {
            return;
        }
        // Bypass: bit-exact copy, channel-for-channel. Acts as the null test
        // baseline (see MidSideWrapperProcessorTest#bypass_isBitExact).
        if (bypassed || (midChain.isEmpty() && sideChain.isEmpty())) {
            // Even when not strictly bypassed, an empty wrapper is the
            // identity transform: encoding then decoding cancels out.
            // Doing a direct copy avoids floating-point round-off and
            // guarantees bit-exact output, satisfying the null test.
            int channels = Math.min(inputBuffer.length, outputBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        // Mono / fewer-than-two channels: just pass through. M/S only makes
        // sense for stereo signals; running it on mono would be a no-op.
        if (inputBuffer.length < 2 || outputBuffer.length < 2) {
            int channels = Math.min(inputBuffer.length, outputBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        ensureBuffers(numFrames);

        float[] left  = inputBuffer[0];
        float[] right = inputBuffer[1];

        MidSideEncoder.encode(left, right, midBuf, sideBuf, numFrames);

        runChain(midChain, midBuf, numFrames);
        runChain(sideChain, sideBuf, numFrames);

        MidSideDecoder.decode(midBuf, sideBuf, outputBuffer[0], outputBuffer[1], numFrames);

        // Pass any extra channels (e.g., a 5.1 host calling a stereo M/S
        // insert) straight through so we don't leak stale audio left in the
        // output buffer. Channels 0/1 were just written by decode above.
        int extra = Math.min(inputBuffer.length, outputBuffer.length);
        for (int ch = 2; ch < extra; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }
    }

    @Override
    public void reset() {
        for (AudioProcessor p : midChain)  p.reset();
        for (AudioProcessor p : sideChain) p.reset();
    }

    @Override
    public int getInputChannelCount() {
        return 2;
    }

    @Override
    public int getOutputChannelCount() {
        return 2;
    }

    @Override
    public int getLatencySamples() {
        // Aligns by zero-pad: report the larger of the two chains' total
        // latency. A future refinement may compensate the shorter chain.
        return Math.max(chainLatency(midChain), chainLatency(sideChain));
    }

    private static int chainLatency(List<AudioProcessor> chain) {
        int total = 0;
        for (AudioProcessor p : chain) {
            total += p.getLatencySamples();
        }
        return total;
    }

    private void runChain(List<AudioProcessor> chain, float[] monoBuf, int numFrames) {
        if (chain.isEmpty()) {
            return;
        }
        // Ping-pong between monoBuf and scratchMono to avoid an arraycopy
        // per processor: each iteration reads from `cur` and writes to
        // `alt`, then we swap. After the loop the latest result is in
        // `cur`; if that isn't the caller's monoBuf we copy back exactly
        // once so the decode step (and the next runChain call, which also
        // reuses scratchMono) sees the result in the expected place.
        float[] cur = monoBuf;
        float[] alt = scratchMono;
        for (AudioProcessor p : chain) {
            monoIn[0]  = cur;
            monoOut[0] = alt;
            p.process(monoIn, monoOut, numFrames);
            float[] tmp = cur;
            cur = alt;
            alt = tmp;
        }
        if (cur != monoBuf) {
            System.arraycopy(cur, 0, monoBuf, 0, numFrames);
        }
    }

    private void ensureBuffers(int numFrames) {
        if (midBuf == null || midBuf.length < numFrames) {
            midBuf = new float[numFrames];
            sideBuf = new float[numFrames];
            scratchMono = new float[numFrames];
            monoIn  = new float[1][];
            monoOut = new float[1][];
        }
    }
}
