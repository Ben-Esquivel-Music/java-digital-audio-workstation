package com.benesquivelmusic.daw.sdk.event;

import com.benesquivelmusic.daw.sdk.audio.XrunEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the closed shape of the {@link DawEvent} sealed hierarchy and
 * proves &mdash; via an exhaustive {@code switch} expression with no
 * {@code default} branch &mdash; that adding or removing a permitted
 * sub-interface breaks compilation. This is the compile-time safety
 * guarantee called out in the issue: removing an event variant breaks
 * every exhaustive switch, caught by {@code javac}, not at runtime.
 */
class DawEventExhaustiveSwitchTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Returns a short textual classification of the event.
     *
     * <p>This switch has <strong>no {@code default} branch</strong> and
     * covers every permitted sub-type of {@link DawEvent}. If a new
     * permitted sub-type is added to {@link DawEvent} without updating
     * this method, compilation fails with "the switch expression does
     * not cover all possible input values" &mdash; which is the
     * compile-time safety property the issue asks the test suite to
     * enforce.</p>
     */
    private static String classify(DawEvent event) {
        return switch (event) {
            case TransportEvent ignored  -> "transport";
            case MixerEvent ignored      -> "mixer";
            case TrackEvent ignored      -> "track";
            case ClipEvent ignored       -> "clip";
            case ProjectEvent ignored    -> "project";
            case AutomationEvent ignored -> "automation";
            case PluginEvent ignored     -> "plugin";
            case XrunEvent ignored       -> "xrun";
        };
    }

    @Test
    void exhaustiveSwitchCoversTransportEvents() {
        assertThat(classify(new TransportEvent.Started(0L, T0))).isEqualTo("transport");
        assertThat(classify(new TransportEvent.Stopped(0L, T0))).isEqualTo("transport");
        assertThat(classify(new TransportEvent.Seeked(0L, 1L, T0))).isEqualTo("transport");
        assertThat(classify(new TransportEvent.TempoChanged(120.0, 140.0, T0))).isEqualTo("transport");
        assertThat(classify(new TransportEvent.LoopChanged(true, 0L, 1L, T0))).isEqualTo("transport");
    }

    @Test
    void exhaustiveSwitchCoversMixerEvents() {
        assertThat(classify(new MixerEvent.ChannelAdded(ID, T0))).isEqualTo("mixer");
        assertThat(classify(new MixerEvent.ChannelRemoved(ID, T0))).isEqualTo("mixer");
        assertThat(classify(new MixerEvent.GainChanged(ID, T0))).isEqualTo("mixer");
        assertThat(classify(new MixerEvent.PanChanged(ID, T0))).isEqualTo("mixer");
        assertThat(classify(new MixerEvent.MuteChanged(ID, true, T0))).isEqualTo("mixer");
        assertThat(classify(new MixerEvent.SoloChanged(ID, false, T0))).isEqualTo("mixer");
    }

    @Test
    void exhaustiveSwitchCoversTrackEvents() {
        assertThat(classify(new TrackEvent.Added(ID, T0))).isEqualTo("track");
        assertThat(classify(new TrackEvent.Removed(ID, T0))).isEqualTo("track");
        assertThat(classify(new TrackEvent.Renamed(ID, T0))).isEqualTo("track");
        assertThat(classify(new TrackEvent.Muted(ID, true, T0))).isEqualTo("track");
        assertThat(classify(new TrackEvent.Soloed(ID, true, T0))).isEqualTo("track");
        assertThat(classify(new TrackEvent.Armed(ID, true, T0))).isEqualTo("track");
    }

    @Test
    void exhaustiveSwitchCoversClipEvents() {
        UUID trackId = UUID.randomUUID();
        UUID clipId = UUID.randomUUID();
        assertThat(classify(new ClipEvent.Added(trackId, clipId, T0))).isEqualTo("clip");
        assertThat(classify(new ClipEvent.Removed(trackId, clipId, T0))).isEqualTo("clip");
        assertThat(classify(new ClipEvent.Moved(trackId, clipId, T0))).isEqualTo("clip");
        assertThat(classify(new ClipEvent.Trimmed(trackId, clipId, T0))).isEqualTo("clip");
        assertThat(classify(new ClipEvent.Renamed(trackId, clipId, T0))).isEqualTo("clip");
    }

    @Test
    void exhaustiveSwitchCoversProjectEvents() {
        Path location = Path.of("/tmp/proj.daw");
        assertThat(classify(new ProjectEvent.Opened(ID, location, T0))).isEqualTo("project");
        assertThat(classify(new ProjectEvent.Closed(ID, T0))).isEqualTo("project");
        assertThat(classify(new ProjectEvent.Saved(ID, location, T0))).isEqualTo("project");
        assertThat(classify(new ProjectEvent.Created(ID, T0))).isEqualTo("project");
        assertThat(classify(new ProjectEvent.Undone(ID, T0))).isEqualTo("project");
        assertThat(classify(new ProjectEvent.Redone(ID, T0))).isEqualTo("project");
    }

    @Test
    void exhaustiveSwitchCoversAutomationEvents() {
        assertThat(classify(new AutomationEvent.LaneAdded(ID, T0))).isEqualTo("automation");
        assertThat(classify(new AutomationEvent.LaneRemoved(ID, T0))).isEqualTo("automation");
        assertThat(classify(new AutomationEvent.PointAdded(ID, T0))).isEqualTo("automation");
        assertThat(classify(new AutomationEvent.PointRemoved(ID, T0))).isEqualTo("automation");
        assertThat(classify(new AutomationEvent.PointMoved(ID, T0))).isEqualTo("automation");
    }

    @Test
    void exhaustiveSwitchCoversPluginEvents() {
        assertThat(classify(new PluginEvent.Loaded(ID, T0))).isEqualTo("plugin");
        assertThat(classify(new PluginEvent.Unloaded(ID, T0))).isEqualTo("plugin");
        assertThat(classify(new PluginEvent.Bypassed(ID, true, T0))).isEqualTo("plugin");
        assertThat(classify(new PluginEvent.ParameterChanged(ID, "cutoff", T0))).isEqualTo("plugin");
        assertThat(classify(new PluginEvent.Crashed(ID, "stack overflow", T0))).isEqualTo("plugin");
    }

    @Test
    void exhaustiveSwitchCoversXrunEvent() {
        assertThat(classify(new XrunEvent.BufferLate(1L, Duration.ofMillis(2)))).isEqualTo("xrun");
        assertThat(classify(new XrunEvent.BufferDropped(1L))).isEqualTo("xrun");
        assertThat(classify(new XrunEvent.GraphOverload("node-1", 1.5))).isEqualTo("xrun");
    }

    @Test
    void xrunEventIsRecognisedAsADawEvent() {
        DawEvent xrun = new XrunEvent.BufferDropped(7L);
        assertThat(xrun).isInstanceOf(DawEvent.class);
    }

    @Test
    void dawEventDefaultTimestampForXrunIsEpoch() {
        DawEvent xrun = new XrunEvent.BufferDropped(7L);
        assertThat(xrun.timestamp()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void timestampedEventsExposeTheirTimestamp() {
        DawEvent e = new TransportEvent.Started(0L, T0);
        assertThat(e.timestamp()).isEqualTo(T0);
    }
}
