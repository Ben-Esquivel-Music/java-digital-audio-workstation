package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerFeedsMonitoringTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 512);

    @Test
    void autoYieldsToActuallyMonitoredTrackExceptDuringRecording() {
        try (var fixture = new Fixture(InputMonitoringMode.AUTO)) {
            fixture.assertSelected(fixture.second);
            fixture.project.getTransport().play();
            fixture.assertSelected(fixture.second);
            fixture.project.getTransport().record();
            fixture.assertSelected(fixture.first);
            fixture.project.getTransport().pause();
            fixture.assertSelected(fixture.second);
            fixture.project.getTransport().stop();
            fixture.assertSelected(fixture.second);
        }
    }

    @Test
    void tapeFollowsPlaybackAndHalfOpenPunchRegionOnEverySelection() {
        try (var fixture = new Fixture(InputMonitoringMode.TAPE)) {
            var transport = fixture.project.getTransport();
            transport.setTempo(120);
            transport.setPunchRegion(PunchRegion.enabled(48_000, 96_000));
            fixture.assertSelected(fixture.first);
            transport.play();
            fixture.assertSelected(fixture.second);
            transport.record();
            fixture.assertSelected(fixture.second);
            transport.setPositionInBeats(2);
            fixture.assertSelected(fixture.first);
            transport.setPositionInBeats(4);
            fixture.assertSelected(fixture.second);
            transport.setPunchRegion(transport.getPunchRegion().withEnabled(false));
            fixture.assertSelected(fixture.first);
            transport.pause();
            fixture.assertSelected(fixture.first);
            transport.stop();
            fixture.assertSelected(fixture.first);
            transport.clearPunchRegion();
            transport.record();
            fixture.assertSelected(fixture.first);
        }
    }

    @Test
    void fallsBackToFirstArmedTrackAndNeverToTheMaster() {
        try (var fixture = new Fixture(InputMonitoringMode.OFF)) {
            fixture.assertSelected(fixture.second);
            fixture.second.setInputMonitoringMode(InputMonitoringMode.OFF);
            fixture.assertSelected(fixture.first);
            fixture.first.setArmed(false);
            fixture.assertSelected(fixture.second);
            fixture.second.setArmed(false);
            assertThat(fixture.feeds.tunerPoint()).isNull();
        }
        var bus = new MeteringTapBus();
        var dispatcher = new FxDispatcher();
        try (var feeds = new AnalyzerFeeds(bus, dispatcher, () -> null)) {
            assertThat(feeds.tunerPoint()).isNull();
        } finally {
            bus.close();
            dispatcher.dispose();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DawProject project = new DawProject("Tuner monitoring", FORMAT);
        final Track first = project.createAudioTrack("First");
        final Track second = project.createAudioTrack("Second");
        final MeteringTapBus bus = new MeteringTapBus();
        final FxDispatcher dispatcher = new FxDispatcher();
        final AnalyzerFeeds feeds = new AnalyzerFeeds(bus, dispatcher, () -> project);

        Fixture(InputMonitoringMode firstMode) {
            first.setArmed(true);
            first.setInputMonitoringMode(firstMode);
            second.setArmed(true);
            second.setInputMonitoringMode(InputMonitoringMode.ALWAYS);
        }

        void assertSelected(Track track) {
            assertThat(feeds.tunerPoint()).isEqualTo(
                    new MeterTapPoint.ChannelPost(project.getMixerChannelForTrack(track).getId()));
        }

        @Override public void close() {
            feeds.close();
            bus.close();
            dispatcher.dispose();
        }
    }
}
