package com.benesquivelmusic.daw.sdk.session;

import java.util.List;
import java.util.Objects;

/**
 * Format-neutral representation of a DAW session for export.
 *
 * <p>This record carries the essential session data (project name, tempo,
 * time signature, sample rate, tracks, and mixer settings) in a form that
 * any {@link SessionExporter} can serialize.</p>
 *
 * @param projectName            the project/session name
 * @param tempo                  tempo in beats per minute
 * @param timeSignatureNumerator beats per bar
 * @param timeSignatureDenominator note value per beat
 * @param sampleRate             sample rate in Hz
 * @param tracks                 the tracks in the session
 */
public record SessionData(
        String projectName,
        double tempo,
        int timeSignatureNumerator,
        int timeSignatureDenominator,
        double sampleRate,
        List<SessionTrack> tracks
) {
    public SessionData {
        Objects.requireNonNull(projectName, "projectName must not be null");
        Objects.requireNonNull(tracks, "tracks must not be null");
        tracks = List.copyOf(tracks);
    }

    /**
     * A single track within a session.
     *
     * @param name          the track display name
     * @param type          the track type name (e.g., "AUDIO", "MIDI", "AUX", "MASTER")
     * @param volume        volume level (0.0 – 1.0)
     * @param pan           pan position (−1.0 to 1.0)
     * @param muted         whether the track is muted
     * @param solo          whether the track is soloed
     * @param clips         audio clips on this track
     */
    public record SessionTrack(
            String name,
            String type,
            double volume,
            double pan,
            boolean muted,
            boolean solo,
            List<SessionClip> clips
    ) {
        public SessionTrack {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(clips, "clips must not be null");
            clips = List.copyOf(clips);
        }
    }

    /**
     * An audio clip within a track.
     *
     * @param name              the clip display name
     * @param startBeat         start position in beats
     * @param durationBeats     duration in beats
     * @param sourceOffsetBeats offset into the source audio in beats
     * @param sourceFilePath    path to the source audio file (may be {@code null})
     * @param gainDb            clip gain in dB
     */
    public record SessionClip(
            String name,
            double startBeat,
            double durationBeats,
            double sourceOffsetBeats,
            String sourceFilePath,
            double gainDb
    ) {
        public SessionClip {
            Objects.requireNonNull(name, "name must not be null");
        }
    }
}
