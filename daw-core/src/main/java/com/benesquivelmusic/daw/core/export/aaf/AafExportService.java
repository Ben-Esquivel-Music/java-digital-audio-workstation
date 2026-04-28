package com.benesquivelmusic.daw.core.export.aaf;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Assembles an OMF / AAF export from a {@link DawProject} and writes it
 * via {@link AafWriter} (and optionally also via the OMF&nbsp;2.0
 * fallback writer for older workflows).
 *
 * <p>The service walks every audio track included by
 * {@link AafExportConfig#includedTrackIndices()} (or all audio tracks
 * if that list is empty), gathers each clip's position / length / fade
 * / gain, converts beat-based timing to samples using the project's
 * tempo and sample rate, and pre-composes the timeline at the user's
 * selected {@link AafFrameRate}. Source media is either embedded
 * (self-contained file) or referenced (smaller file with external
 * file paths).</p>
 */
public final class AafExportService {

    private final AafWriter writer;

    public AafExportService() {
        this(new AafWriter());
    }

    /** Constructor used by tests for dependency injection. */
    public AafExportService(AafWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    /**
     * Pre-composes the timeline from {@code project} at the export
     * config's frame rate and returns the AAF data model. Made public
     * so callers (and tests) can inspect the composition before it is
     * written.
     */
    public AafComposition composeTimeline(DawProject project, AafExportConfig config) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(config, "config must not be null");

        int sampleRate = (int) Math.round(project.getFormat().sampleRate());
        double tempo = project.getTransport().getTempo();
        if (tempo <= 0) tempo = 120.0;
        double samplesPerBeat = (60.0 / tempo) * sampleRate;

        List<Track> allTracks = project.getTracks();
        List<Integer> requested = config.includedTrackIndices();
        List<AafSourceClip> clips = new ArrayList<>();
        long maxEnd = 0;

        for (int idx = 0; idx < allTracks.size(); idx++) {
            if (!requested.isEmpty() && !requested.contains(idx)) continue;
            Track track = allTracks.get(idx);
            if (track.getType() != TrackType.AUDIO) continue;

            for (AudioClip clip : track.getClips()) {
                long startSample  = Math.round(clip.getStartBeat()      * samplesPerBeat);
                long lengthSample = Math.max(1L,
                                       Math.round(clip.getDurationBeats() * samplesPerBeat));
                long offsetSample = Math.round(clip.getSourceOffsetBeats() * samplesPerBeat);
                long fadeIn       = Math.round(clip.getFadeInBeats()      * samplesPerBeat);
                long fadeOut      = Math.round(clip.getFadeOutBeats()     * samplesPerBeat);
                // Defensive: combined fades must not exceed the clip.
                if (fadeIn + fadeOut > lengthSample) {
                    long over = (fadeIn + fadeOut) - lengthSample;
                    if (fadeOut >= over) fadeOut -= over;
                    else { fadeIn -= (over - fadeOut); fadeOut = 0; }
                    if (fadeIn < 0) fadeIn = 0;
                }

                UUID mobId = deterministicMobId(clip);
                clips.add(new AafSourceClip(
                        mobId,
                        clip.getSourceFilePath(),
                        clip.getName(),
                        idx,
                        track.getName(),
                        Math.max(0, startSample),
                        lengthSample,
                        Math.max(0, offsetSample),
                        clip.getGainDb(),
                        fadeIn,
                        AafFadeCurve.from(orDefault(clip.getFadeInCurveType())),
                        fadeOut,
                        AafFadeCurve.from(orDefault(clip.getFadeOutCurveType()))));
                maxEnd = Math.max(maxEnd, startSample + lengthSample);
            }
        }

        clips.sort(Comparator.<AafSourceClip>comparingInt(AafSourceClip::trackIndex)
                .thenComparingLong(AafSourceClip::startSample));

        // Composition length is from start-TC to last clip end.
        long startOffset = config.startTimecode().toSampleOffset(sampleRate);
        long total = startOffset + maxEnd;
        return new AafComposition(
                config.compositionName(),
                sampleRate,
                config.frameRate(),
                config.startTimecode(),
                total,
                clips);
    }

    /**
     * Performs a complete export: composes the timeline, builds embedded
     * media when requested, and writes the AAF file.
     *
     * @return the composition that was written (useful for status display)
     */
    public AafComposition export(DawProject project,
                                 AafExportConfig config,
                                 Path outputPath) throws IOException {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        AafComposition comp = composeTimeline(project, config);
        Map<UUID, AafWriter.EmbeddedMedia> embedded = config.embedMedia()
                ? buildEmbeddedMedia(project, config, comp)
                : Map.of();
        writer.write(comp, embedded, outputPath);
        return comp;
    }

    /**
     * Builds the embedded-media map for a composition by encoding each
     * clip's in-memory {@code float[][]} audio data as 24-bit
     * little-endian PCM keyed by source-mob id.
     */
    private Map<UUID, AafWriter.EmbeddedMedia> buildEmbeddedMedia(DawProject project,
                                                                  AafExportConfig config,
                                                                  AafComposition comp) {
        int sampleRate = (int) Math.round(project.getFormat().sampleRate());
        // Group clips by source-mob id so each piece of source media is
        // embedded only once even if referenced by multiple clips.
        Map<UUID, AudioClip> uniqueSources = new LinkedHashMap<>();
        List<Track> tracks = project.getTracks();
        for (AafSourceClip sc : comp.clips()) {
            Track t = tracks.get(sc.trackIndex());
            for (AudioClip ac : t.getClips()) {
                if (deterministicMobId(ac).equals(sc.sourceMobId())) {
                    uniqueSources.putIfAbsent(sc.sourceMobId(), ac);
                    break;
                }
            }
        }

        Map<UUID, AafWriter.EmbeddedMedia> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, AudioClip> e : uniqueSources.entrySet()) {
            AudioClip ac = e.getValue();
            float[][] data = ac.getAudioData();
            if (data == null || data.length == 0 || data[0].length == 0) {
                continue;  // nothing to embed for this source
            }
            int channels = data.length;
            int frames = data[0].length;
            byte[] pcm = encodePcm24LE(data, channels, frames);
            String name = ac.getName();
            out.put(e.getKey(), new AafWriter.EmbeddedMedia(
                    name, sampleRate, channels, 24, frames, pcm));
        }
        // Suppress unused warning on config in environments where the
        // method may evolve; keep the parameter for future per-source
        // resampling decisions.
        Objects.requireNonNull(config);
        return out;
    }

    /**
     * Encodes interleaved 24-bit signed little-endian PCM from a planar
     * float buffer in [-1, 1].
     */
    private static byte[] encodePcm24LE(float[][] planar, int channels, int frames) {
        ByteBuffer buf = ByteBuffer.allocate(frames * channels * 3).order(ByteOrder.LITTLE_ENDIAN);
        final int max = (1 << 23) - 1;
        for (int f = 0; f < frames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                float s = planar[ch][f];
                if (s > 1f) s = 1f;
                if (s < -1f) s = -1f;
                int v = Math.round(s * max);
                buf.put((byte) (v & 0xFF));
                buf.put((byte) ((v >> 8) & 0xFF));
                buf.put((byte) ((v >> 16) & 0xFF));
            }
        }
        return buf.array();
    }

    /**
     * Maps an {@link AudioClip}'s id (a UUID string) to a deterministic
     * mob UUID so that re-exporting the same project produces stable
     * source-mob ids — important for downstream tools that cache by
     * mob id.
     */
    private static UUID deterministicMobId(AudioClip clip) {
        try {
            return UUID.fromString(clip.getId());
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(clip.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static FadeCurveType orDefault(FadeCurveType c) {
        return c == null ? FadeCurveType.LINEAR : c;
    }
}
