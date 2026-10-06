package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@ExtendWith(CaptureFlushThreadLeakGuard.class)
class CaptureDeviceCalibrationTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"Interface, 360", "Interface, 0", "Interface [MME], 360", "Interface [MME], 0",
            "Interface [WASAPI], 360", "Interface [WASAPI], 0"})
    void calibrationRequiresAnUnambiguousDeviceAcrossEnumerationAndArmedOrdering(String watched, int frames) throws Exception {
        AudioDeviceInfo mme = device(0, "MME"), wasapi = device(1, "WASAPI");
        for (boolean reversed : new boolean[]{false, true}) {
            var backend = spy(new MultichannelInputCaptureStory326Test.PatternBackend());
            List<AudioDeviceInfo> devices = reversed ? List.of(wasapi, mme) : List.of(mme, wasapi);
            doReturn(devices).when(backend).listDevices();
            AudioEngine engine = new AudioEngine(FORMAT);
            engine.setStreamingProvision(new StreamingProvision(backend.name(), List.of(
                    new BackendStreamRung(backend, new DeviceId(backend.name(), mme.qualifiedName())))));
            Track mmeTrack = track("MME", mme.index()), wasapiTrack = track("WASAPI", wasapi.index());
            List<Track> tracks = reversed ? List.of(wasapiTrack, mmeTrack) : List.of(mmeTrack, wasapiTrack);
            RecordingPipeline pipeline = new RecordingPipeline(engine, new Transport(), FORMAT,
                    directory.resolve(Boolean.toString(reversed)), tracks);
            try {
                engine.startAudioInputOutput(tracks);
                engine.pauseAudioOutput();
                backend.primaryLatency = latency(backend.input);
                backend.sibling.primaryLatency = latency(backend.sibling.input);
                doThrow(new AssertionError("Take preparation must use frozen source aliases")).when(backend).listDevices();
                pipeline.setReportedLatency(new DeviceId(backend.name(), watched), new RoundTripLatency(frames, 0, 0));
                pipeline.prepare().toCompletableFuture().get(5, TimeUnit.SECONDS);
                pipeline.beginCapture();

                assertThat(pipeline.getSession(mmeTrack).getCompensationFrames())
                        .isEqualTo(watched.equals(mme.qualifiedName()) ? frames : 208);
                assertThat(pipeline.getSession(wasapiTrack).getCompensationFrames())
                        .isEqualTo(watched.equals(wasapi.qualifiedName()) ? frames : 12);
            } finally {
                pipeline.requestStop().toCompletableFuture().get(5, TimeUnit.SECONDS);
                pipeline.completeStop();
                engine.stopAudioOutput();
                engine.shutdown();
            }
        }
    }

    private static AudioDeviceInfo device(int index, String hostApi) {
        return new AudioDeviceInfo(index, "Interface", hostApi, 8, 2, 48_000, List.of(), 0, 0);
    }

    private static Track track(String name, int device) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setArmed(true);
        track.setInputDeviceIndex(device);
        track.setInputRouting(new InputRouting(0, 1));
        return track;
    }

    private static RoundTripLatency latency(String deviceName) {
        return deviceName.endsWith("[MME]") ? new RoundTripLatency(64, 128, 16) : new RoundTripLatency(12, 0, 0);
    }
}
