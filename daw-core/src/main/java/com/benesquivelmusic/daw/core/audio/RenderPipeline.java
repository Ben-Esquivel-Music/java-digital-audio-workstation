package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.PluginParameterTarget;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified per-block audio render pipeline shared by live playback and
 * offline export.
 *
 * <p>This class encapsulates the complete per-block processing chain:</p>
 * <ol>
 *   <li>Read clip audio (or synthesize MIDI) into per-track scratch buffers.</li>
 *   <li>Apply automation lane values to mixer channel parameters (volume,
 *       pan, mute, send level, and plugin-parameter automation) for tracks
 *       with {@link AutomationMode#READ} enabled.</li>
 *   <li>Mix the per-track buffers through the {@link Mixer} — applying
 *       per-channel insert effects, volume, pan, mute and solo, and routing
 *       sends to return buses that are summed into the main mix.</li>
 *   <li>Process the mixed result through the master effects chain.</li>
 *   <li>Render non-master channels to their direct hardware outputs.</li>
 *   <li>Advance the transport by the number of beats corresponding to the
 *       block size at the current tempo.</li>
 * </ol>
 *
 * <p>Two entry points are provided:</p>
 * <ul>
 *   <li>{@link #renderBlock(float[][], float[][], int, Transport, Mixer,
 *       List, MidiTrackRenderer, EffectsChain, AudioEngine.RecordingCallback,
 *       PerformanceMonitor)} — invoked from the audio callback on the live
 *       path. It is {@link RealTimeSafe}: all scratch buffers are
 *       pre-allocated by the constructor, no locks are acquired, and no
 *       heap allocations occur.</li>
 *   <li>{@link #renderOffline(Transport, Mixer, List, MidiTrackRenderer,
 *       EffectsChain, float[][], int, int)} — wraps {@code renderBlock} in a
 *       loop to render {@code totalFrames} of audio into a caller-supplied
 *       output buffer. This is the entry point for offline export code
 *       paths such as stem export, track bouncing, and master rendering.</li>
 * </ul>
 *
 * <p>Sharing a single rendering implementation between live playback and
 * offline export guarantees that exported audio is bit-identical to what
 * the user heard during playback — the "what you hear is what you get"
 * (WYHIWYG) principle. The only difference between live and offline is
 * the output destination and the pace of consumption: live rendering is
 * driven by the audio callback at real time, offline rendering runs as
 * fast as the CPU allows.</p>
 *
 * @see AudioEngine#processBlock(float[][], float[][], int)
 */
public final class RenderPipeline {

    private static final Logger LOG = Logger.getLogger(RenderPipeline.class.getName());

    /** Maximum number of tracks supported by this pipeline instance. */
    private final int maxTracks;

    /** Audio format describing channel count and sample rate. */
    private final AudioFormat format;

    // Pre-allocated mix buffer. [channel][frame]
    private final float[][] mixBuffer;

    // Pre-allocated per-track buffers: [track][channel][frame]
    private final float[][][] trackBuffers;

    // Pre-allocated per-return-bus buffers for send routing: [returnBus][channel][frame]
    private final float[][][] returnBuffers;

    // One-shot warning flag for exceeding return bus cap
    private boolean returnBusCapWarningLogged;

    /**
     * Creates a render pipeline with pre-allocated scratch buffers.
     *
     * @param format    the audio format describing channel count and sample rate
     * @param maxTracks the maximum number of tracks rendered per block
     * @param blockSize the maximum block size in frames
     */
    public RenderPipeline(AudioFormat format, int maxTracks, int blockSize) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        if (maxTracks <= 0) {
            throw new IllegalArgumentException("maxTracks must be positive: " + maxTracks);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.maxTracks = maxTracks;
        int channels = format.channels();
        this.mixBuffer = new float[channels][blockSize];
        this.trackBuffers = new float[maxTracks][channels][blockSize];
        this.returnBuffers = new float[Mixer.MAX_RETURN_BUSES][channels][blockSize];
    }

    /**
     * Returns the audio format this pipeline was configured with.
     *
     * @return the audio format
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Returns the pre-allocated per-track scratch buffers. Package-private
     * so that {@link AudioEngine} can invoke
     * {@link Mixer#renderDirectOutputs(float[][][], float[][], int)} on the
     * per-track buffers after {@link #renderBlock} has populated them.
     *
     * @return the track buffers as {@code [track][channel][frame]}
     */
    float[][][] getTrackBuffers() {
        return trackBuffers;
    }

    /**
     * Renders a single block of audio into {@code outputBuffer}.
     *
     * <p>When the transport, mixer, and track list are non-null and the
     * transport is in {@link TransportState#PLAYING} or
     * {@link TransportState#RECORDING} state, the engine renders clips
     * through the mixer and master effects chain. Otherwise, the
     * {@code inputBuffer} is routed through the master effects chain
     * (pass-through).</p>
     *
     * <p>This method performs zero heap allocations and acquires no locks
     * on the live path — it is safe to invoke from the audio callback
     * thread.</p>
     *
     * @param inputBuffer       the input audio data {@code [channel][frame]}
     *                          (may be {@code null} when rendering offline)
     * @param outputBuffer      the output audio data {@code [channel][frame]}
     * @param numFrames         the number of sample frames to process
     * @param transport         the transport, or {@code null} for pass-through
     * @param mixer             the mixer, or {@code null} for pass-through
     * @param tracks            the tracks, or {@code null} for pass-through
     * @param midiRenderer      the MIDI track renderer, or {@code null}
     * @param masterChain       the master effects chain applied after mixdown
     * @param recordingCallback optional recording callback invoked with the
     *                          captured {@code inputBuffer} (may be {@code null})
     * @param performanceMonitor optional performance monitor (may be {@code null})
     */
    @RealTimeSafe
    public void renderBlock(float[][] inputBuffer,
                            float[][] outputBuffer,
                            int numFrames,
                            Transport transport,
                            Mixer mixer,
                            List<Track> tracks,
                            MidiTrackRenderer midiRenderer,
                            EffectsChain masterChain,
                            AudioEngine.RecordingCallback recordingCallback,
                            PerformanceMonitor performanceMonitor) {
        renderBlock(inputBuffer, outputBuffer, numFrames, transport, mixer,
                tracks, midiRenderer, masterChain, recordingCallback,
                performanceMonitor, null);
    }

    /**
     * Renders a single block of audio into {@code outputBuffer}, optionally
     * feeding per-track CPU measurements to a {@link TrackCpuBudgetEnforcer}.
     *
     * <p>This overload adds per-track CPU timing around the mixer's insert
     * processing. When {@code cpuBudgetEnforcer} is non-null, each track's
     * mixer processing time is measured and reported to the enforcer, which
     * evaluates per-track and master budgets and publishes degradation or
     * restoration events.</p>
     *
     * <p><strong>RT-safety note:</strong> When {@code cpuBudgetEnforcer} is
     * non-null, this method acquires a {@link java.util.concurrent.locks.ReentrantLock}
     * inside the enforcer for each {@code recordTrackCpu} call and for the
     * {@code evaluateMasterBudget} cascade. The enforcer pre-allocates its
     * internal snapshot/sort buffers to minimize GC pressure, but the lock
     * acquisitions mean this path is not fully lock-free. When the enforcer
     * is {@code null}, the method remains allocation-free and lock-free.</p>
     *
     * @param inputBuffer        the input audio data {@code [channel][frame]}
     *                           (may be {@code null} when rendering offline)
     * @param outputBuffer       the output audio data {@code [channel][frame]}
     * @param numFrames          the number of sample frames to process
     * @param transport          the transport, or {@code null} for pass-through
     * @param mixer              the mixer, or {@code null} for pass-through
     * @param tracks             the tracks, or {@code null} for pass-through
     * @param midiRenderer       the MIDI track renderer, or {@code null}
     * @param masterChain        the master effects chain applied after mixdown
     * @param recordingCallback  optional recording callback (may be {@code null})
     * @param performanceMonitor optional performance monitor (may be {@code null})
     * @param cpuBudgetEnforcer  optional per-track CPU budget enforcer
     *                           (may be {@code null}); when non-null, per-track
     *                           timing and lock acquisition occur on this path
     */
    @RealTimeSafe
    public void renderBlock(float[][] inputBuffer,
                            float[][] outputBuffer,
                            int numFrames,
                            Transport transport,
                            Mixer mixer,
                            List<Track> tracks,
                            MidiTrackRenderer midiRenderer,
                            EffectsChain masterChain,
                            AudioEngine.RecordingCallback recordingCallback,
                            PerformanceMonitor performanceMonitor,
                            TrackCpuBudgetEnforcer cpuBudgetEnforcer) {
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
        Objects.requireNonNull(masterChain, "masterChain must not be null");

        long startNanos = (performanceMonitor != null) ? System.nanoTime() : 0L;

        // Clear the mix buffer
        for (float[] channel : mixBuffer) {
            Arrays.fill(channel, 0, numFrames, 0.0f);
        }

        boolean playbackActive = transport != null
                && mixer != null
                && tracks != null
                && (transport.getState() == TransportState.PLAYING
                    || transport.getState() == TransportState.RECORDING);

        if (playbackActive) {
            int trackCount = Math.min(tracks.size(), maxTracks);

            // Compute the render offset so that PDC delays align output with
            // the displayed transport position. We render audio slightly
            // ahead of the transport cursor; the compensation delays then
            // push the audio back, so beat-1 arrives at the output exactly
            // on time.
            int systemLatency = mixer.getSystemLatencySamples();
            double samplesPerBeatForOffset =
                    format.sampleRate() * 60.0 / transport.getTempo();
            double renderOffsetBeats = systemLatency / samplesPerBeatForOffset;

            // Render clip audio (or synthesized MIDI) for each track
            renderTracks(tracks, trackCount, transport, renderOffsetBeats,
                    midiRenderer, numFrames);

            // Apply automation lane values to mixer channel parameters
            List<MixerChannel> mixerChannels = mixer.getChannels();
            applyAutomation(tracks, trackCount, mixerChannels, transport, mixer);

            // Warn once if the mixer has more return buses than pre-allocated
            if (!returnBusCapWarningLogged
                    && mixer.getReturnBusCount() > Mixer.MAX_RETURN_BUSES) {
                returnBusCapWarningLogged = true;
                final Mixer m = mixer;
                LOG.log(Level.WARNING,
                        () -> "Mixer has " + m.getReturnBusCount()
                                + " return buses but only " + Mixer.MAX_RETURN_BUSES
                                + " are supported; extra buses will not receive send audio");
            }

            // Per-track CPU timing for budget enforcement. The enforcer
            // measures around each track's mixer processing (insert chain
            // application, delay compensation, and summing). When not
            // present, the mixer processes all channels in one call with
            // no instrumentation overhead.
            if (cpuBudgetEnforcer != null) {
                mixer.mixDownInstrumented(trackBuffers, mixBuffer, returnBuffers,
                        numFrames, tracks, cpuBudgetEnforcer);
            } else {
                // Mix through the mixer into the mix buffer, routing sends to
                // return buses which are summed into the main output.
                mixer.mixDown(trackBuffers, mixBuffer, returnBuffers, numFrames);
            }
        } else if (inputBuffer != null) {
            // Fallback: copy input into the mix buffer (pass-through)
            int channels = Math.min(inputBuffer.length, mixBuffer.length);
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(inputBuffer[ch], 0, mixBuffer[ch], 0, numFrames);
            }
        }

        // Notify recording callback with the captured input
        if (recordingCallback != null && inputBuffer != null) {
            recordingCallback.onAudioCaptured(inputBuffer, numFrames);
        }

        // Process through the master effects chain
        masterChain.process(mixBuffer, outputBuffer, numFrames);

        // Write non-master channels to their direct hardware outputs.
        // This runs AFTER the master chain so that its overwrite of
        // outputBuffer (channels 0..N) does not clobber direct-output data
        // on higher channels.
        if (playbackActive) {
            mixer.renderDirectOutputs(trackBuffers, outputBuffer, numFrames);
        }

        // Advance the transport position
        if (playbackActive) {
            double samplesPerBeat = format.sampleRate() * 60.0 / transport.getTempo();
            double deltaBeats = numFrames / samplesPerBeat;
            transport.advancePosition(deltaBeats);
        }

        // Record processing time
        if (performanceMonitor != null) {
            long elapsedNanos = System.nanoTime() - startNanos;
            performanceMonitor.recordProcessingTime(elapsedNanos);
        }

        // Evaluate master budget after all per-track recordings for this block
        if (cpuBudgetEnforcer != null && playbackActive) {
            cpuBudgetEnforcer.evaluateMasterBudget();
        }
    }

    /**
     * Renders {@code totalFrames} of audio offline by invoking
     * {@link #renderBlock} in a loop. The caller-supplied
     * {@code outputBuffer} must have dimensions
     * {@code [format.channels()][totalFrames]}.
     *
     * <p>The transport is assumed to already be positioned at the render
     * start and in {@link TransportState#PLAYING} state. The pipeline will
     * advance the transport as it renders, just as it does on the live
     * path. This guarantees that live and offline rendering produce
     * bit-identical output for the same project state.</p>
     *
     * <p>Unlike {@link #renderBlock}, this method is <b>not</b> real-time
     * safe — it is intended for offline export contexts where allocations
     * and blocking I/O are acceptable.</p>
     *
     * @param transport     the transport positioned at the render start
     * @param mixer         the mixer (non-null)
     * @param tracks        the tracks to render (non-null)
     * @param midiRenderer  the MIDI track renderer, or {@code null}
     * @param masterChain   the master effects chain (non-null)
     * @param outputBuffer  the destination buffer
     *                      {@code [channels][totalFrames]}
     * @param totalFrames   the number of frames to render
     * @param blockSize     the per-block render size (must be &le; the
     *                      {@code blockSize} this pipeline was constructed
     *                      with)
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if {@code totalFrames} or
     *                                  {@code blockSize} is non-positive, or
     *                                  if the output buffer dimensions do
     *                                  not match the pipeline format
     */
    public void renderOffline(Transport transport,
                              Mixer mixer,
                              List<Track> tracks,
                              MidiTrackRenderer midiRenderer,
                              EffectsChain masterChain,
                              float[][] outputBuffer,
                              int totalFrames,
                              int blockSize) {
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(mixer, "mixer must not be null");
        Objects.requireNonNull(tracks, "tracks must not be null");
        Objects.requireNonNull(masterChain, "masterChain must not be null");
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
        if (totalFrames <= 0) {
            throw new IllegalArgumentException(
                    "totalFrames must be positive: " + totalFrames);
        }
        if (blockSize <= 0 || blockSize > mixBuffer[0].length) {
            throw new IllegalArgumentException(
                    "blockSize must be in (0, " + mixBuffer[0].length + "]: " + blockSize);
        }
        int channels = format.channels();
        if (outputBuffer.length < channels) {
            throw new IllegalArgumentException(
                    "outputBuffer must have at least " + channels + " channels");
        }
        for (int ch = 0; ch < channels; ch++) {
            if (outputBuffer[ch].length < totalFrames) {
                throw new IllegalArgumentException(
                        "outputBuffer channel " + ch + " shorter than totalFrames");
            }
        }

        // Scratch block-sized output that the pipeline writes into; we copy
        // each block into the correct offset of the caller's buffer.
        float[][] blockOut = new float[channels][blockSize];

        int framesRendered = 0;
        while (framesRendered < totalFrames) {
            int framesThisBlock = Math.min(blockSize, totalFrames - framesRendered);

            // Clear blockOut so master chain writes land on a zero scratch
            for (int ch = 0; ch < channels; ch++) {
                Arrays.fill(blockOut[ch], 0, framesThisBlock, 0.0f);
            }

            renderBlock(null, blockOut, framesThisBlock,
                    transport, mixer, tracks, midiRenderer, masterChain,
                    null, null);

            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(blockOut[ch], 0,
                        outputBuffer[ch], framesRendered, framesThisBlock);
            }
            framesRendered += framesThisBlock;
        }
    }

    // ------------------------------------------------------------------
    // Internal rendering helpers (moved verbatim from AudioEngine so that
    // live and offline paths share identical clip-to-buffer logic).
    // ------------------------------------------------------------------

    @RealTimeSafe
    private void renderTracks(List<Track> tracks, int trackCount, Transport transport,
                              double renderOffsetBeats, MidiTrackRenderer midiRenderer,
                              int numFrames) {
        int audioChannels = format.channels();
        for (int t = 0; t < trackCount; t++) {
            for (int ch = 0; ch < audioChannels; ch++) {
                Arrays.fill(trackBuffers[t][ch], 0, numFrames, 0.0f);
            }
        }

        double tempo = transport.getTempo();
        double sampleRate = format.sampleRate();
        double samplesPerBeat = sampleRate * 60.0 / tempo;
        // Offset ahead by the PDC system latency so that after compensation
        // delays, the output aligns with the transport cursor.
        double currentBeat = transport.getPositionInBeats() + renderOffsetBeats;
        boolean loopEnabled = transport.isLoopEnabled();
        double loopStart = transport.getLoopStartInBeats();
        double loopEnd = transport.getLoopEndInBeats();
        double loopLength = loopEnd - loopStart;

        int framesProcessed = 0;

        while (framesProcessed < numFrames) {
            int framesToProcess = numFrames - framesProcessed;

            if (loopEnabled && loopLength > 0.0 && currentBeat < loopEnd) {
                double beatsUntilLoopEnd = loopEnd - currentBeat;
                int framesUntilLoopEnd = (int) Math.ceil(beatsUntilLoopEnd * samplesPerBeat);
                if (framesUntilLoopEnd > 0) {
                    framesToProcess = Math.min(framesToProcess, framesUntilLoopEnd);
                }
            }

            renderSegment(tracks, trackCount, currentBeat, samplesPerBeat,
                    midiRenderer, framesProcessed, framesToProcess);

            framesProcessed += framesToProcess;
            currentBeat += framesToProcess / samplesPerBeat;

            if (loopEnabled && loopLength > 0.0 && currentBeat >= loopEnd) {
                currentBeat = loopStart + (currentBeat - loopEnd);
                if (midiRenderer != null) {
                    midiRenderer.allNotesOff();
                }
            }
        }
    }

    @RealTimeSafe
    private void renderSegment(List<Track> tracks, int trackCount,
                               double startBeat, double samplesPerBeat,
                               MidiTrackRenderer midiRenderer,
                               int frameOffset, int framesToProcess) {
        double endBeat = startBeat + framesToProcess / samplesPerBeat;

        for (int t = 0; t < trackCount; t++) {
            Track track = tracks.get(t);

            if (track.getType() == TrackType.MIDI
                    && track.getSoundFontAssignment() != null
                    && midiRenderer != null) {
                midiRenderer.renderMidiTrack(track, trackBuffers[t],
                        startBeat, endBeat, samplesPerBeat,
                        frameOffset, framesToProcess);
                continue;
            }

            List<AudioClip> clips = track.getClips();

            for (int c = 0; c < clips.size(); c++) {
                AudioClip clip = clips.get(c);
                float[][] audioData = clip.getAudioData();
                if (audioData == null || audioData.length == 0) {
                    continue;
                }

                double clipStart = clip.getStartBeat();
                double clipEnd = clip.getEndBeat();

                if (endBeat <= clipStart || startBeat >= clipEnd) {
                    continue;
                }

                double overlapStart = Math.max(startBeat, clipStart);
                double overlapEnd = Math.min(endBeat, clipEnd);

                int outStart = frameOffset + (int) Math.round((overlapStart - startBeat) * samplesPerBeat);
                int outEnd = frameOffset + (int) Math.round((overlapEnd - startBeat) * samplesPerBeat);
                outEnd = Math.min(outEnd, frameOffset + framesToProcess);

                double beatInClip = overlapStart - clipStart + clip.getSourceOffsetBeats();
                int srcStart = (int) Math.round(beatInClip * samplesPerBeat);
                int audioLength = audioData[0].length;

                if (srcStart < 0) {
                    outStart += -srcStart;
                    srcStart = 0;
                }

                int copyLength = Math.min(outEnd - outStart, audioLength - srcStart);
                if (copyLength <= 0) {
                    continue;
                }

                int audioChannels = Math.min(audioData.length, trackBuffers[t].length);
                // Resolve the per-sample gain: if the clip has a gain envelope,
                // evaluate it per source-frame; otherwise use the scalar clip-gain.
                ClipGainEnvelope envelope = clip.gainEnvelope().orElse(null);
                double scalarGain = (envelope == null) ? Math.pow(10.0, clip.getGainDb() / 20.0) : 1.0;
                if (envelope == null && scalarGain == 1.0) {
                    for (int ch = 0; ch < audioChannels; ch++) {
                        for (int f = 0; f < copyLength; f++) {
                            trackBuffers[t][ch][outStart + f] += audioData[ch][srcStart + f];
                        }
                    }
                } else if (envelope == null) {
                    float g = (float) scalarGain;
                    for (int ch = 0; ch < audioChannels; ch++) {
                        for (int f = 0; f < copyLength; f++) {
                            trackBuffers[t][ch][outStart + f] += audioData[ch][srcStart + f] * g;
                        }
                    }
                } else {
                    // Precompute the per-frame linear gain once for this copy
                    // range so audio-thread work stays O(frames + points) and
                    // each sample application is a single multiply.
                    float[] gains = new float[copyLength];
                    for (int f = 0; f < copyLength; f++) {
                        gains[f] = (float) envelope.linearAtFrame((long) srcStart + f);
                    }
                    for (int ch = 0; ch < audioChannels; ch++) {
                        for (int f = 0; f < copyLength; f++) {
                            trackBuffers[t][ch][outStart + f]
                                    += audioData[ch][srcStart + f] * gains[f];
                        }
                    }
                }
            }
        }
    }

    @RealTimeSafe
    private void applyAutomation(List<Track> tracks, int trackCount,
                                 List<MixerChannel> channels, Transport transport,
                                 Mixer mixer) {
        int channelCount = channels.size();
        double currentBeat = transport.getPositionInBeats();

        for (int t = 0; t < trackCount && t < channelCount; t++) {
            Track track = tracks.get(t);
            if (!track.getAutomationMode().readsAutomation()) {
                continue;
            }

            AutomationData automation = track.getAutomationData();
            MixerChannel channel = channels.get(t);

            if (automation.hasActiveAutomation(AutomationParameter.VOLUME)) {
                channel.setVolume(Math.clamp(
                        automation.getValueAtTime(AutomationParameter.VOLUME, currentBeat),
                        0.0, 1.0));
            }

            if (automation.hasActiveAutomation(AutomationParameter.PAN)) {
                channel.setPan(Math.clamp(
                        automation.getValueAtTime(AutomationParameter.PAN, currentBeat),
                        -1.0, 1.0));
            }

            if (automation.hasActiveAutomation(AutomationParameter.MUTE)) {
                channel.setMuted(
                        automation.getValueAtTime(AutomationParameter.MUTE, currentBeat) > 0.5);
            }

            if (automation.hasActiveAutomation(AutomationParameter.SEND_LEVEL)) {
                channel.setSendLevel(Math.clamp(
                        automation.getValueAtTime(AutomationParameter.SEND_LEVEL, currentBeat),
                        0.0, 1.0));
            }

            applyPluginParameterAutomation(automation, channel, currentBeat);

            // Apply reflective @ProcessorParam automation for built-in DSP inserts.
            // Bindings are pre-computed in Mixer.prepareForPlayback (and re-computed
            // when inserts change), so this call is allocation-free on the audio thread.
            mixer.getReflectiveParameterBinder().apply(channel, automation, currentBeat);
        }
    }

    @RealTimeSafe
    private void applyPluginParameterAutomation(AutomationData automation,
                                                MixerChannel channel,
                                                double currentBeat) {
        Map<PluginParameterTarget, ?> pluginLanes = automation.getPluginLanes();
        if (pluginLanes.isEmpty()) {
            return;
        }
        List<InsertSlot> inserts = channel.getInsertSlots();
        for (PluginParameterTarget target : pluginLanes.keySet()) {
            if (!automation.hasActiveAutomation(target)) {
                continue;
            }
            DawPlugin plugin = findPluginByInstanceId(inserts, target.pluginInstanceId());
            if (plugin == null) {
                continue;
            }
            double value = Math.clamp(
                    automation.getValueAtTime(target, currentBeat),
                    target.getMinValue(), target.getMaxValue());
            plugin.setAutomatableParameter(target.parameterId(), value);
        }
    }

    @RealTimeSafe
    private static DawPlugin findPluginByInstanceId(List<InsertSlot> inserts,
                                                    String instanceId) {
        for (int i = 0, n = inserts.size(); i < n; i++) {
            InsertSlot slot = inserts.get(i);
            DawPlugin plugin = slot.getPlugin();
            if (plugin != null
                    && instanceId.equals(plugin.getDescriptor().id())) {
                return plugin;
            }
        }
        return null;
    }
}
