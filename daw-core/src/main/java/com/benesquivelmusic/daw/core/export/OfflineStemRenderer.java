package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EffectsChain;
import com.benesquivelmusic.daw.core.audio.RenderPipeline;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-track stem renderer that delegates to {@link RenderPipeline#renderOffline}.
 *
 * <p>Configures a transient render context against a project's existing
 * {@link Mixer}, mutes every channel except the target track's channel,
 * and renders {@code totalFrames} of audio with the master effects chain
 * intentionally bypassed. Each stem is therefore the single-channel
 * contribution to the master bus that the user would hear if they soloed
 * the track during live playback (story 102 — "Playback-Export Parity").</p>
 *
 * <p>The renderer is single-use and {@linkplain AutoCloseable closeable}: it
 * snapshots the project's mute and solo state plus the master channel's
 * volume/mute on construction, mutates them during {@link #render}, and
 * restores them inside {@link #close()}. Use it inside a try-with-resources
 * block so the project state is always restored — even if rendering
 * fails.</p>
 *
 * <p>This class is a package-private implementation detail shared between
 * {@link StemExporter} and {@link BundleExportService} so that both export
 * paths share a single rendering implementation.</p>
 */
final class OfflineStemRenderer implements AutoCloseable {

    /** Block size used by the per-export render pipeline. */
    static final int OFFLINE_BLOCK_SIZE = 1024;

    private final Mixer mixer;
    private final List<Track> allTracks;
    private final RenderPipeline pipeline;
    private final EffectsChain emptyMaster;
    private final List<MixerChannel> mixerChannels;
    private final boolean[] originalMutes;
    private final boolean[] originalSolos;
    private final MixerChannel masterChannel;
    private final boolean originalMasterMute;
    private final double originalMasterVolume;
    private final int audioChannels;
    private final int totalFrames;
    private final int blockSize;
    private final double tempo;
    private boolean closed;

    /**
     * Captures the project state needed to render stems and neutralizes the
     * master fader so it does not attenuate stem output.
     *
     * @param project     the DAW project
     * @param totalFrames the per-stem render length in sample frames
     *                    (must be non-negative; zero produces empty stems)
     */
    OfflineStemRenderer(DawProject project, int totalFrames) {
        Objects.requireNonNull(project, "project must not be null");
        if (totalFrames < 0) {
            throw new IllegalArgumentException(
                    "totalFrames must be non-negative: " + totalFrames);
        }
        this.totalFrames = totalFrames;
        this.audioChannels = project.getFormat().channels();
        int sampleRate = (int) project.getFormat().sampleRate();
        this.tempo = project.getTransport().getTempo();
        this.blockSize = Math.max(1, Math.min(OFFLINE_BLOCK_SIZE,
                Math.max(totalFrames, 1)));

        AudioFormat format = new AudioFormat(sampleRate, audioChannels,
                project.getFormat().bitDepth(), blockSize);
        this.mixer = project.getMixer();
        mixer.prepareForPlayback(audioChannels, blockSize);
        this.allTracks = project.getTracks();
        this.pipeline = new RenderPipeline(format,
                Math.max(1, allTracks.size()), blockSize);

        this.emptyMaster = new EffectsChain();
        emptyMaster.allocateIntermediateBuffers(audioChannels, blockSize);

        // NOTE: MidiTrackRenderer is package-private in
        // com.benesquivelmusic.daw.core.audio and cannot be instantiated
        // here. MIDI tracks with SoundFont assignments will render as
        // silence in stems until MidiTrackRenderer is made accessible
        // (tracked separately). For now we pass null to renderOffline,
        // which skips MIDI synthesis — matching the historical stem-export
        // behaviour that only supported audio tracks.

        // Snapshot mute and solo state into a stable copy so concurrent
        // channel-list mutations do not affect the restore on close.
        List<MixerChannel> liveChannels = mixer.getChannels();
        this.mixerChannels = new ArrayList<>(liveChannels);
        this.originalMutes = new boolean[mixerChannels.size()];
        this.originalSolos = new boolean[mixerChannels.size()];
        for (int j = 0; j < mixerChannels.size(); j++) {
            originalMutes[j] = mixerChannels.get(j).isMuted();
            originalSolos[j] = mixerChannels.get(j).isSolo();
        }
        this.masterChannel = mixer.getMasterChannel();
        this.originalMasterMute = masterChannel.isMuted();
        this.originalMasterVolume = masterChannel.getVolume();

        // Clear solo state so the mute-based isolation in render() is not
        // overridden by Mixer.mixDown's solo-gating logic.
        for (MixerChannel ch : mixerChannels) {
            ch.setSolo(false);
        }

        // Neutralize master so it does not affect the stems.
        masterChannel.setMuted(false);
        masterChannel.setVolume(1.0);
    }

    /**
     * Renders the supplied track's stem buffer using the unified render
     * pipeline. All other mixer channels are muted for the duration of
     * the call so only this track contributes to the bus.
     *
     * @param targetTrack   the track being rendered (must not be {@code null})
     * @param targetChannel the mixer channel for the track to render, or
     *                      {@code null} to render silence (no channel
     *                      contributes — every channel is muted)
     * @return the rendered stem as {@code [channels][totalFrames]}
     */
    float[][] render(Track targetTrack, MixerChannel targetChannel) {
        if (closed) {
            throw new IllegalStateException("renderer is closed");
        }
        Objects.requireNonNull(targetTrack, "targetTrack must not be null");

        // Mute every channel except the target's.
        for (MixerChannel ch : mixerChannels) {
            ch.setMuted(targetChannel == null || ch != targetChannel);
        }

        // Fresh transport per stem so each render starts at beat 0.
        Transport transport = new Transport();
        transport.setTempo(tempo);
        transport.play();

        float[][] stemBuffer = new float[audioChannels][totalFrames];
        if (totalFrames > 0) {
            pipeline.renderOffline(transport, mixer, allTracks, null,
                    emptyMaster, stemBuffer, totalFrames, blockSize);
        }
        return stemBuffer;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Restore mute / solo / master state regardless of whether rendering
        // succeeded so the project is left in its original configuration.
        masterChannel.setMuted(originalMasterMute);
        masterChannel.setVolume(originalMasterVolume);
        for (int j = 0; j < mixerChannels.size() && j < originalMutes.length; j++) {
            mixerChannels.get(j).setMuted(originalMutes[j]);
            mixerChannels.get(j).setSolo(originalSolos[j]);
        }
    }
}
