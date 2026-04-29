package com.benesquivelmusic.daw.sdk.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackMixerPluginEventTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000abc");

    @Test
    void mixerChannelAddedRejectsNullId() {
        assertThatThrownBy(() -> new MixerEvent.ChannelAdded(null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void mixerMuteChangedExposesFlag() {
        MixerEvent.MuteChanged e = new MixerEvent.MuteChanged(ID, true, T0);
        assertThat(e.muted()).isTrue();
        assertThat(e.channelId()).isEqualTo(ID);
        assertThat(e.timestamp()).isEqualTo(T0);
    }

    @Test
    void trackArmedExposesFlagAndId() {
        TrackEvent.Armed e = new TrackEvent.Armed(ID, true, T0);
        assertThat(e.armed()).isTrue();
        assertThat(e.trackId()).isEqualTo(ID);
    }

    @Test
    void trackRemovedRejectsNullId() {
        assertThatThrownBy(() -> new TrackEvent.Removed(null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void clipAddedExposesIds() {
        UUID trackId = UUID.randomUUID();
        UUID clipId = UUID.randomUUID();
        ClipEvent.Added e = new ClipEvent.Added(trackId, clipId, T0);
        assertThat(e.trackId()).isEqualTo(trackId);
        assertThat(e.clipId()).isEqualTo(clipId);
    }

    @Test
    void pluginParameterChangedRejectsBlankId() {
        assertThatThrownBy(() -> new PluginEvent.ParameterChanged(ID, "  ", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pluginCrashedCarriesReason() {
        PluginEvent.Crashed e = new PluginEvent.Crashed(ID, "segfault", T0);
        assertThat(e.reason()).isEqualTo("segfault");
    }

    @Test
    void automationLaneAddedExposesId() {
        AutomationEvent.LaneAdded e = new AutomationEvent.LaneAdded(ID, T0);
        assertThat(e.laneId()).isEqualTo(ID);
    }

    @Test
    void projectSavedExposesLocation() {
        ProjectEvent.Saved e = new ProjectEvent.Saved(ID, java.nio.file.Path.of("/tmp/p.daw"), T0);
        assertThat(e.location().toString()).contains("p.daw");
        assertThat(e.projectId()).isEqualTo(ID);
    }
}
