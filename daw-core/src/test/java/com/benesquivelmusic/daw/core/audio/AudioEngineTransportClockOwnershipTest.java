package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.harness.HeadlessAudioBackend;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.LatencyInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story 315 review — the engine owns the transport's RT-clock claim, and the
 * claim is true only while a backend stream is genuinely calling
 * {@link AudioEngine#processBlock(float[][], float[][], int)}.
 *
 * <p>The regression this pins down: {@link AudioEngine#startAudioOutput()}
 * returns early with "No audio backend configured; playback without hardware
 * output", while the UI calls {@code transport.play()} regardless. The
 * transport was then {@code PLAYING} with nothing driving
 * {@link Transport#advancePosition(double)}, so every seek was queued behind a
 * drain that never came — the playhead and the time display froze on every
 * ruler click and skip, and the next stop discarded the queue. Making the
 * {@code PLAYING} state itself honest is story 317; owning the <em>clock</em>
 * is this story's §6.1 concern.</p>
 */
class AudioEngineTransportClockOwnershipTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    // ── No backend: nothing drives the transport ─────────────────────────

    @Test
    void startingOutputWithNoBackendLeavesTheTransportsClockUnclaimed() {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen())
                .as("no backend was configured, so no stream opened")
                .isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("nothing calls advancePosition, so the transport must not defer seeks")
                .isFalse();
    }

    @Test
    void seeksApplyImmediatelyWhenPlaybackStartedWithoutAStream() {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        transport.play(); // exactly what TransportController.start() does

        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);
        transport.setPositionInBeats(68.0); // ruler click on bar 17

        assertThat(transport.getPositionInBeats()).isEqualTo(68.0);
        assertThat(fired).containsExactly(ChangeKind.POSITION);
    }

    @Test
    void startingInputOutputWithNoBackendLeavesTheTransportsClockUnclaimed() {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioInputOutput(0);

        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    // ── A real stream: the claim follows the stream lifecycle ────────────

    @Test
    void startingOutputOnARunningStreamClaimsTheTransportsClock() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    @Test
    void startingFullDuplexInputOutputClaimsTheTransportsClock() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioInputOutput(0);

        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    @Test
    void stoppingOutputReleasesTheClaim() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.stopAudioOutput();

        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void pausingReleasesTheClaimAndResumingTakesItBack() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.pauseAudioOutput();
        assertThat(transport.isRealTimeClockActive())
                .as("a paused stream issues no callbacks")
                .isFalse();

        engine.startAudioOutput(); // resumes the paused stream
        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    @Test
    void closingTheStreamDrainsASeekQueuedMicrosecondsEarlier() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        transport.play();
        transport.setPositionInBeats(24.0); // queued — the stream owns the clock
        assertThat(transport.getPositionInBeats()).isZero();

        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);
        engine.stopAudioOutput();

        assertThat(transport.getPositionInBeats())
                .as("the seek must not die with the stream")
                .isEqualTo(24.0);
        assertThat(fired).containsExactly(ChangeKind.POSITION);
    }

    // ── Backend faults on stop: the claim follows the callback, not the call ─

    @Test
    void stopFailureStillClosesTheStreamAndReleasesTheClaim() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStop = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        assertThatCode(engine::stopAudioOutput)
                .as("stopAudioOutput is best-effort and must not throw")
                .doesNotThrowAnyException();

        assertThat(backend.closeAttempts)
                .as("close is attempted independently of the failed stop")
                .isEqualTo(1);
        assertThat(backend.delegate.isStreamOpen())
                .as("a normal close released the stream and its callback")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("no callback can run once close returned normally")
                .isFalse();
    }

    @Test
    void closeFailureWithTheCallbackStillActivePreservesTheClaim() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStop = true;  // driver wedged: the callback keeps running…
        backend.failClose = true; // …and the stream cannot be released either
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        assertThatCode(engine::stopAudioOutput).doesNotThrowAnyException();

        assertThat(backend.isStreamActive())
                .as("precondition: the backend reports the callback still running")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("callback shutdown is not guaranteed, so the stream stays open")
                .isTrue();
        assertThat(transport.isRealTimeClockActive())
                .as("the still-running callback keeps the clock; seeks must stay queued")
                .isTrue();
    }

    @Test
    void closeFailureIsRetriedByALaterStop() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStop = true;
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // preserved the claimed state

        backend.failStop = false;
        backend.failClose = false;
        engine.stopAudioOutput();

        assertThat(backend.closeAttempts).isEqualTo(2);
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void closeFailureWithTheCallbackGoneReleasesTheClaim() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true; // stop succeeded, so the hardware is idle
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        transport.play();
        transport.setPositionInBeats(24.0); // queued — the stream owns the clock
        assertThat(transport.getPositionInBeats()).isZero();

        assertThatCode(engine::stopAudioOutput).doesNotThrowAnyException();

        assertThat(backend.isStreamActive())
                .as("precondition: the backend reports no callback running")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("nothing will drain the queue, so the claim must be released")
                .isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the queued seek is drained by the release, not stranded")
                .isEqualTo(24.0);
    }

    @Test
    void closeFailureWithAnUnknownCallbackStatePreservesTheClaim() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;       // stop succeeded, so the delegate is idle…
        backend.failActiveProbe = true; // …but the driver refuses to say so
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        assertThatCode(engine::stopAudioOutput)
                .as("a failed activity probe is part of best-effort stop and must not throw")
                .doesNotThrowAnyException();

        assertThat(engine.isStreamOpen())
                .as("unknown callback state is treated as possibly still running")
                .isTrue();
        assertThat(transport.isRealTimeClockActive())
                .as("the claim is preserved for a later stop to retry, not released into a race")
                .isTrue();
    }

    // ── setGraph hands the claim over (story 314 rebind) ─────────────────

    @Test
    void rebindingMidPlaybackMovesTheClaimToTheIncomingTransport() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        assertThat(outgoing.isRealTimeClockActive()).isTrue();

        Transport incoming = new Transport();
        engine.setGraph(incoming, null, null);

        assertThat(outgoing.isRealTimeClockActive())
                .as("the old project's transport is no longer driven by the callback")
                .isFalse();
        assertThat(incoming.isRealTimeClockActive())
                .as("exactly one transport is claimed after a rebind")
                .isTrue();
    }

    @Test
    void rebindingDrainsASeekTheOutgoingTransportStillHadQueued() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        outgoing.play();
        outgoing.setPositionInBeats(12.0); // queued

        engine.setGraph(new Transport(), null, null);

        assertThat(outgoing.getPositionInBeats()).isEqualTo(12.0);
    }

    @Test
    void bindingWithNoStreamRunningLeavesTheIncomingTransportUnclaimed() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();

        engine.setGraph(transport, null, null); // no stream opened yet

        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void unbindingReleasesTheClaimOfTheOutgoingTransport() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.setGraph(null, null, null); // EngineBinder.unbind()

        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void theClaimSurvivesARebindOntoTheSameTransport() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.setGraph(transport, null, List.of()); // e.g. a track-list refresh

        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    // ── Test double ──────────────────────────────────────────────────────

    /**
     * {@link HeadlessAudioBackend} decorator with opt-in stop/close faults.
     * A refused stop leaves the delegate's stream active (the callback keeps
     * running, exactly like a wedged driver); a refused close leaves the
     * delegate untouched, so {@link #isStreamActive()} reports the real
     * post-failure state: still active when stop was refused too, idle when
     * stop succeeded. A refused activity probe hides that state entirely.
     */
    private static final class FaultyBackend implements NativeAudioBackend {

        final HeadlessAudioBackend delegate = new HeadlessAudioBackend();
        boolean failStop;
        boolean failClose;
        boolean failActiveProbe;
        int closeAttempts;

        @Override
        public void initialize() {
            delegate.initialize();
        }

        @Override
        public List<AudioDeviceInfo> getAvailableDevices() {
            return delegate.getAvailableDevices();
        }

        @Override
        public AudioDeviceInfo getDefaultInputDevice() {
            return delegate.getDefaultInputDevice();
        }

        @Override
        public AudioDeviceInfo getDefaultOutputDevice() {
            return delegate.getDefaultOutputDevice();
        }

        @Override
        public void openStream(AudioStreamConfig config, AudioStreamCallback callback) {
            delegate.openStream(config, callback);
        }

        @Override
        public void startStream() {
            delegate.startStream();
        }

        @Override
        public void stopStream() {
            if (failStop) {
                throw new AudioBackendException("stop refused by the driver");
            }
            delegate.stopStream();
        }

        @Override
        public void closeStream() {
            closeAttempts++;
            if (failClose) {
                throw new AudioBackendException("close refused by the driver");
            }
            delegate.closeStream();
        }

        @Override
        public LatencyInfo getLatencyInfo() {
            return delegate.getLatencyInfo();
        }

        @Override
        public boolean isStreamActive() {
            if (failActiveProbe) {
                throw new AudioBackendException("probe refused by the driver");
            }
            return delegate.isStreamActive();
        }

        @Override
        public String getBackendName() {
            return "Faulty(" + delegate.getBackendName() + ")";
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
