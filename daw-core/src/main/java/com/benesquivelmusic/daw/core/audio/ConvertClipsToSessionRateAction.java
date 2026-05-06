package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Undoable maintenance action that walks every clip whose
 * {@link SourceRateMetadata#nativeRateHz()} differs from the session
 * rate, renders the converted copy via the configured
 * {@link SampleRateConverter} quality tier, replaces the clip's source
 * data, and updates {@link SourceRateMetadata} so the rate-mismatch
 * badge disappears. Story 126.
 *
 * <p>Backed by an {@link UndoableAction} so the user can revert: undo
 * restores the original {@code float[][]} audio data and the original
 * {@link SourceRateMetadata} for every converted clip.</p>
 *
 * <p>This is a non-realtime action — it allocates new buffers via
 * {@link SampleRateConverter#process(float[][], int, int)} and is
 * intended to be invoked from the UI thread (typically via the
 * "Project → Audio → Convert all clips to session rate" menu item).</p>
 */
public final class ConvertClipsToSessionRateAction implements UndoableAction {

    private final List<Track> tracks;
    private final int sessionRateHz;
    private final QualityTier tier;
    private final SampleRateConversionCache cache;

    /** Snapshot of the per-clip state we need to restore on undo. */
    private final List<Snapshot> snapshots = new ArrayList<>();

    private record Snapshot(AudioClip clip, float[][] previousAudio,
                            SourceRateMetadata previousMetadata) { }

    /**
     * Creates a new convert-all-clips action.
     *
     * @param tracks         the project's tracks; every {@link AudioClip}
     *                       on every track is inspected
     * @param sessionRateHz  the active session sample rate in Hz (positive)
     * @param tier           the SRC quality tier to render with
     * @param cache          optional cache to invalidate after conversion;
     *                       may be {@code null}
     */
    public ConvertClipsToSessionRateAction(List<Track> tracks,
                                           int sessionRateHz,
                                           QualityTier tier,
                                           SampleRateConversionCache cache) {
        this.tracks = Objects.requireNonNull(tracks, "tracks must not be null");
        if (sessionRateHz <= 0) {
            throw new IllegalArgumentException(
                    "sessionRateHz must be positive: " + sessionRateHz);
        }
        this.sessionRateHz = sessionRateHz;
        this.tier = Objects.requireNonNull(tier, "tier must not be null");
        this.cache = cache;
    }

    @Override
    public String description() {
        return "Convert Clips to Session Rate";
    }

    @Override
    public void execute() {
        snapshots.clear();
        SampleRateConverter converter = SampleRateConverter.of(tier);
        for (Track track : tracks) {
            for (AudioClip clip : track.getClips()) {
                SourceRateMetadata meta = clip.getSourceRateMetadata();
                float[][] data = clip.getAudioData();
                if (meta == null || data == null || data.length == 0) {
                    continue;
                }
                if (!meta.requiresConversion(sessionRateHz)) {
                    continue;
                }
                snapshots.add(new Snapshot(clip, data, meta));
                float[][] converted = converter.process(
                        data, meta.nativeRateHz(), sessionRateHz);
                clip.setAudioData(converted);
                int channels = converted.length;
                int frames = (channels > 0 && converted[0] != null)
                        ? converted[0].length : 0;
                clip.setSourceRateMetadata(new SourceRateMetadata(
                        sessionRateHz, channels, frames));
            }
        }
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    @Override
    public void undo() {
        // Restore in reverse so cache invalidation lands once at the end.
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            Snapshot s = snapshots.get(i);
            s.clip.setAudioData(s.previousAudio);
            s.clip.setSourceRateMetadata(s.previousMetadata);
        }
        if (cache != null) {
            cache.invalidateAll();
        }
        snapshots.clear();
    }

    /** Number of clips converted by the most recent {@link #execute()} call. */
    public int convertedClipCount() {
        return snapshots.size();
    }
}
