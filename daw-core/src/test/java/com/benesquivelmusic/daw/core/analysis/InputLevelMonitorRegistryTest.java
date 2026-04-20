package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputLevelMonitorRegistryTest {

    @Test
    void getOrCreateReturnsSameInstanceForSameTrackId() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor a = registry.getOrCreate("track-1");
        InputLevelMonitor b = registry.getOrCreate("track-1");
        assertThat(a).isSameAs(b);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void getReturnsNullForUnknownTrack() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        assertThat(registry.get("nope")).isNull();
        assertThat(registry.get(null)).isNull();
    }

    @Test
    void getOrCreateFromTrackKeysByTrackId() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        Track track = new Track("Vocals", TrackType.AUDIO);
        InputLevelMonitor m1 = registry.getOrCreate(track);
        InputLevelMonitor m2 = registry.get(track.getId());
        assertThat(m1).isSameAs(m2);
    }

    @Test
    void resetAllClearsLatchOnEveryMonitor() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor a = registry.getOrCreate("a");
        InputLevelMonitor b = registry.getOrCreate("b");

        float[] clipping = {1.5f, -1.5f, 1.5f, -1.5f, 1.5f, -1.5f, 1.5f, -1.5f};
        a.process(clipping);
        b.process(clipping);

        assertThat(a.isClippedSinceReset()).isTrue();
        assertThat(b.isClippedSinceReset()).isTrue();

        registry.resetAll();

        assertThat(a.isClippedSinceReset()).isFalse();
        assertThat(b.isClippedSinceReset()).isFalse();
    }

    @Test
    void removeDiscardsMonitor() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor m = registry.getOrCreate("x");
        assertThat(registry.remove("x")).isSameAs(m);
        assertThat(registry.get("x")).isNull();
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void clearDiscardsAllMonitors() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        registry.getOrCreate("a");
        registry.getOrCreate("b");
        registry.clear();
        assertThat(registry.size()).isEqualTo(0);
    }
}
