package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConvertClipsToSessionRateAction} — the bulk
 * "Convert all clips to session rate" maintenance action introduced in
 * story 126.
 */
class ConvertClipsToSessionRateActionTest {

    private static AudioClip makeClip(String id, int nativeRate, int frames) {
        AudioClip clip = new AudioClip(id, 0.0, 1.0, null);
        float[][] data = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            data[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / nativeRate);
        }
        clip.setAudioData(data);
        clip.setSourceRateMetadata(new SourceRateMetadata(nativeRate, 1, frames));
        return clip;
    }

    @Test
    void convertsRateMismatchedClipsAndRestoresOnUndo() {
        Track track = new Track("T", TrackType.AUDIO);
        AudioClip mismatched = makeClip("c-44k", 44_100, 44_100);
        AudioClip matched = makeClip("c-48k", 48_000, 48_000);
        track.addClip(mismatched);
        track.addClip(matched);

        float[][] originalMismatchedData = mismatched.getAudioData();
        SourceRateMetadata originalMismatchedMeta = mismatched.getSourceRateMetadata();
        float[][] originalMatchedData = matched.getAudioData();

        SampleRateConversionCache cache = new SampleRateConversionCache();
        ConvertClipsToSessionRateAction action = new ConvertClipsToSessionRateAction(
                List.of(track), 48_000, QualityTier.MEDIUM, cache);

        action.execute();

        // Only the rate-mismatched clip is touched.
        assertThat(action.convertedClipCount()).isEqualTo(1);
        assertThat(mismatched.getAudioData()).isNotSameAs(originalMismatchedData);
        assertThat(mismatched.getSourceRateMetadata().nativeRateHz()).isEqualTo(48_000);
        // Now matches the session rate, so the badge no longer applies.
        assertThat(mismatched.getSourceRateMetadata().requiresConversion(48_000)).isFalse();

        // The matched clip is left untouched.
        assertThat(matched.getAudioData()).isSameAs(originalMatchedData);

        action.undo();

        assertThat(mismatched.getAudioData()).isSameAs(originalMismatchedData);
        assertThat(mismatched.getSourceRateMetadata()).isEqualTo(originalMismatchedMeta);
    }

    @Test
    void convertedClipCountIsZeroWhenNoMismatch() {
        Track track = new Track("T", TrackType.AUDIO);
        track.addClip(makeClip("c-48k", 48_000, 48_000));

        ConvertClipsToSessionRateAction action = new ConvertClipsToSessionRateAction(
                List.of(track), 48_000, QualityTier.HIGH, null);

        action.execute();

        assertThat(action.convertedClipCount()).isZero();
    }
}
