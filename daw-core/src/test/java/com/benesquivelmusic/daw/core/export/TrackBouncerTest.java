package com.benesquivelmusic.daw.core.export;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class TrackBouncerTest {

    private static final int SAMPLE_RATE = 44100;
    private static final double TEMPO = 120.0;

    @Test
    void shouldReturnNullWhenTrackHasNoClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 2);

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenClipsHaveNoAudioData() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        track.addClip(new AudioClip("clip1", 0.0, 4.0, null));

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 2);

        assertThat(result).isNull();
    }

    @Test
    void shouldBounceMonoClipAtBeatZero() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip1", 0.0, 1.0, null);
        // 1 beat at 120 BPM = 0.5 seconds = 22050 samples
        int expectedSamples = TrackBouncer.beatsToFrames(1.0, SAMPLE_RATE, TEMPO);
        float[][] data = new float[1][expectedSamples];
        for (int i = 0; i < expectedSamples; i++) {
            data[0][i] = 0.5f;
        }
        clip.setAudioData(data);
        track.addClip(clip);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 1);

        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(1);
        assertThat(result[0].length).isEqualTo(expectedSamples);
        assertThat(result[0][0]).isCloseTo(0.5f, offset(0.001f));
    }

    @Test
    void shouldBounceStereoClipAtBeatZero() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip1", 0.0, 1.0, null);
        int frames = TrackBouncer.beatsToFrames(1.0, SAMPLE_RATE, TEMPO);
        float[][] data = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            data[0][i] = 0.3f;
            data[1][i] = 0.7f;
        }
        clip.setAudioData(data);
        track.addClip(clip);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 2);

        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(2);
        assertThat(result[0][0]).isCloseTo(0.3f, offset(0.001f));
        assertThat(result[1][0]).isCloseTo(0.7f, offset(0.001f));
    }

    @Test
    void shouldPlaceClipAtCorrectBeatOffset() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Clip starts at beat 2
        AudioClip clip = new AudioClip("clip1", 2.0, 1.0, null);
        int clipFrames = TrackBouncer.beatsToFrames(1.0, SAMPLE_RATE, TEMPO);
        float[][] data = new float[1][clipFrames];
        for (int i = 0; i < clipFrames; i++) {
            data[0][i] = 0.8f;
        }
        clip.setAudioData(data);
        track.addClip(clip);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 1);

        assertThat(result).isNotNull();
        int startFrame = TrackBouncer.beatsToFrames(2.0, SAMPLE_RATE, TEMPO);
        // Silence before clip start
        assertThat(result[0][0]).isCloseTo(0.0f, offset(0.001f));
        // Audio at clip start
        assertThat(result[0][startFrame]).isCloseTo(0.8f, offset(0.001f));
    }

    @Test
    void shouldMixOverlappingClips() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        int frames = TrackBouncer.beatsToFrames(2.0, SAMPLE_RATE, TEMPO);
        float[][] data1 = new float[1][frames];
        float[][] data2 = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            data1[0][i] = 0.3f;
            data2[0][i] = 0.2f;
        }

        AudioClip clip1 = new AudioClip("clip1", 0.0, 2.0, null);
        clip1.setAudioData(data1);
        AudioClip clip2 = new AudioClip("clip2", 0.0, 2.0, null);
        clip2.setAudioData(data2);

        track.addClip(clip1);
        track.addClip(clip2);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 1);

        assertThat(result).isNotNull();
        // Overlapping clips should be summed: 0.3 + 0.2 = 0.5
        assertThat(result[0][0]).isCloseTo(0.5f, offset(0.001f));
    }

    @Test
    void shouldApplyClipGain() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        AudioClip clip = new AudioClip("clip1", 0.0, 1.0, null);
        clip.setGainDb(-6.0); // roughly halve the amplitude
        int frames = TrackBouncer.beatsToFrames(1.0, SAMPLE_RATE, TEMPO);
        float[][] data = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            data[0][i] = 1.0f;
        }
        clip.setAudioData(data);
        track.addClip(clip);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 1);

        assertThat(result).isNotNull();
        // -6 dB ≈ 0.501
        double expectedGain = Math.pow(10.0, -6.0 / 20.0);
        assertThat((double) result[0][0]).isCloseTo(expectedGain, offset(0.01));
    }

    @Test
    void shouldClampToUnitRange() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        int frames = 100;
        float[][] data1 = new float[1][frames];
        float[][] data2 = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            data1[0][i] = 0.8f;
            data2[0][i] = 0.8f;
        }

        AudioClip clip1 = new AudioClip("clip1", 0.0, 1.0, null);
        clip1.setAudioData(data1);
        AudioClip clip2 = new AudioClip("clip2", 0.0, 1.0, null);
        clip2.setAudioData(data2);

        track.addClip(clip1);
        track.addClip(clip2);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 1);

        assertThat(result).isNotNull();
        // Sum 0.8 + 0.8 = 1.6 → clamped to 1.0
        assertThat(result[0][0]).isCloseTo(1.0f, offset(0.001f));
    }

    @Test
    void shouldSkipClipsWithoutAudioData() {
        Track track = new Track("Audio 1", TrackType.AUDIO);
        // Clip without audio data
        AudioClip clipNoData = new AudioClip("empty", 0.0, 4.0, "/some/file.wav");
        track.addClip(clipNoData);

        // Clip with audio data
        AudioClip clipWithData = new AudioClip("real", 0.0, 1.0, null);
        int frames = TrackBouncer.beatsToFrames(1.0, SAMPLE_RATE, TEMPO);
        float[][] data = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            data[0][i] = 0.5f;
        }
        clipWithData.setAudioData(data);
        track.addClip(clipWithData);

        float[][] result = TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 1);

        assertThat(result).isNotNull();
        assertThat(result[0][0]).isCloseTo(0.5f, offset(0.001f));
    }

    @Test
    void shouldConvertBeatsToFramesCorrectly() {
        // 1 beat at 120 BPM = 0.5 seconds = 22050 frames at 44100 Hz
        assertThat(TrackBouncer.beatsToFrames(1.0, 44100, 120.0)).isEqualTo(22050);
        // 4 beats at 120 BPM = 2 seconds = 88200 frames
        assertThat(TrackBouncer.beatsToFrames(4.0, 44100, 120.0)).isEqualTo(88200);
        // 1 beat at 60 BPM = 1 second = 44100 frames
        assertThat(TrackBouncer.beatsToFrames(1.0, 44100, 60.0)).isEqualTo(44100);
    }

    @Test
    void shouldRejectNullTrack() {
        assertThatThrownBy(() -> TrackBouncer.bounce(null, SAMPLE_RATE, TEMPO, 2))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("track");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        assertThatThrownBy(() -> TrackBouncer.bounce(track, 0, TEMPO, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNonPositiveTempo() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        assertThatThrownBy(() -> TrackBouncer.bounce(track, SAMPLE_RATE, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo");
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        Track track = new Track("Audio 1", TrackType.AUDIO);

        assertThatThrownBy(() -> TrackBouncer.bounce(track, SAMPLE_RATE, TEMPO, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels");
    }
}
