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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 315 review — the engine owns the transport's RT-clock claim, and the
 * claim is true only while a backend stream is genuinely calling — or, for the
 * instant between the claim and the backend's start call, about to call —
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
        assertThat(engine.isStreamOpen())
                .as("output is stopped and not resumable, so the engine does not report it open"
                        + " — a settings apply must not restart output on this answer")
                .isFalse();
        assertThat(engine.isStreamPaused())
                .as("a retained handle is stopped but not resumable, so it is not paused")
                .isFalse();
        assertThat(backend.delegate.isStreamOpen())
                .as("the backend still owns the unclosed handle")
                .isTrue();
        assertThat(transport.isRealTimeClockActive())
                .as("nothing will drain the queue, so the claim must be released")
                .isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the queued seek is drained by the release, not stranded")
                .isEqualTo(24.0);

        engine.stopAudioOutput();
        assertThat(backend.closeAttempts)
                .as("the engine still tracks the retained handle: the next stop retries the close,"
                        + " which a closed engine would never do")
                .isEqualTo(2);
    }

    // ── Finding A: a retained handle must be released before anything else ─

    @Test
    void aRetainedHandleIsReleasedByALaterStop() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained
        assertThat(backend.delegate.isStreamOpen())
                .as("precondition: the backend still owns the handle")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("precondition: a retained handle is not reported open")
                .isFalse();

        backend.failClose = false;
        assertThatCode(engine::stopAudioOutput).doesNotThrowAnyException();

        assertThat(backend.closeAttempts)
                .as("the second stop retries the close instead of returning early")
                .isEqualTo(2);
        assertThat(backend.delegate.isStreamOpen())
                .as("the retry released the backend's handle")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void startingWithARetainedHandleThatStillCannotBeReleasedFailsWithoutASecondOpen() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained

        assertThatThrownBy(engine::startAudioOutput)
                .as("the retained handle blocks a fresh open; the caller must be told")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released")
                .cause().hasMessageContaining("close refused by the driver");

        assertThat(backend.closeAttempts)
                .as("the start retried the close")
                .isEqualTo(2);
        assertThat(backend.openAttempts)
                .as("no second openStream while the backend still owns a handle")
                .isEqualTo(1);
        assertThat(backend.startAttempts).isEqualTo(1);
        assertThat(backend.delegate.isStreamOpen())
                .as("the state is unchanged: the backend still owns the handle")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("a retained handle is stopped and not resumable, so it is not reported open")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        // Still tracked: the next stop retries the close once more.
        engine.stopAudioOutput();
        assertThat(backend.closeAttempts).isEqualTo(3);
    }

    @Test
    void startingWithARetainedHandleReleasesItAndOpensAFreshStream() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained

        backend.failClose = false;
        engine.startAudioOutput();

        assertThat(backend.closeAttempts)
                .as("the retained handle is released before the fresh open")
                .isEqualTo(2);
        assertThat(backend.openAttempts)
                .as("a fresh stream is opened once the handle is gone")
                .isEqualTo(2);
        assertThat(backend.startAttempts).isEqualTo(2);
        assertThat(backend.delegate.isStreamActive()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("the fresh stream drives the transport again")
                .isTrue();
    }

    @Test
    void startingFullDuplexWithARetainedHandleThatCannotBeReleasedFailsWithoutASecondOpen() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained

        assertThatThrownBy(() -> engine.startAudioInputOutput(0))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be closed");

        assertThat(backend.openAttempts)
                .as("no second openStream while the backend still owns a handle")
                .isEqualTo(1);
        assertThat(backend.closeAttempts)
                .as("the full-duplex start retried the close first")
                .isEqualTo(2);
        assertThat(backend.delegate.isStreamOpen())
                .as("the backend still owns the handle")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("a retained handle is not reported open")
                .isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void startingFullDuplexWithARetainedHandleReleasesItAndOpensAFreshStream() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained

        backend.failClose = false;
        engine.startAudioInputOutput(0);

        assertThat(backend.closeAttempts)
                .as("the retained handle is released before the full-duplex open")
                .isEqualTo(2);
        assertThat(backend.openAttempts)
                .as("a fresh full-duplex stream is opened once the handle is gone")
                .isEqualTo(2);
        assertThat(backend.delegate.isStreamActive()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("the fresh stream drives the transport again")
                .isTrue();
    }

    @Test
    void startingFullDuplexFromARunningStreamWhoseCloseFailsWithTheCallbackActivePreservesTheClaim() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        backend.failStop = true;  // driver wedged: the callback keeps running…
        backend.failClose = true; // …and the stream cannot be released either

        assertThatThrownBy(() -> engine.startAudioInputOutput(0))
                .as("the previous stream could not be closed, so no second open is attempted")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be closed");

        assertThat(backend.openAttempts)
                .as("no second openStream while the backend still owns an active handle")
                .isEqualTo(1);
        assertThat(backend.delegate.isStreamActive())
                .as("precondition: the callback is still running")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("the running state is preserved for a later retry")
                .isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("the still-running callback keeps the clock; seeks must stay queued")
                .isTrue();
    }

    @Test
    void aFullDuplexStartFailureReleasesTheClaimAndClosesTheStream() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStart = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        assertThatThrownBy(() -> engine.startAudioInputOutput(0))
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("start refused by the driver");

        assertThat(transport.isRealTimeClockActive())
                .as("a full-duplex stream that never started drives nothing")
                .isFalse();
        assertThat(backend.delegate.isStreamOpen())
                .as("the opened handle was given back")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
    }

    @Test
    void pausingIsANoOpWhileAHandleIsRetained() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained
        int stopsBefore = backend.stopAttempts;

        engine.pauseAudioOutput();

        assertThat(backend.stopAttempts)
                .as("a retained handle is already stopped; pause must not touch it")
                .isEqualTo(stopsBefore);
        assertThat(engine.isStreamPaused())
                .as("a retained handle is not resumable, so it is never 'paused'")
                .isFalse();
        assertThat(engine.isStreamOpen())
                .as("a retained handle is not reported open either")
                .isFalse();
        assertThat(backend.delegate.isStreamOpen())
                .as("the backend still owns the handle")
                .isTrue();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void rebindingWhileAHandleIsRetainedLeavesTheIncomingTransportUnclaimed() {
        FaultyBackend backend = new FaultyBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained

        Transport incoming = new Transport();
        engine.setGraph(incoming, null, null);

        assertThat(incoming.isRealTimeClockActive())
                .as("no callback drives a retained, stopped handle")
                .isFalse();
        assertThat(outgoing.isRealTimeClockActive()).isFalse();
    }

    // ── Re-pointing the engine: a tracked handle belongs to the outgoing backend ─

    @Test
    void switchingBackendsWithARetainedHandleRetriesTheCloseOnTheOutgoingBackendThenAbandonsIt() {
        FaultyBackend outgoing = new FaultyBackend();
        outgoing.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(outgoing);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // idle stream, handle retained
        engine.stop();            // setAudioBackend requires a stopped engine
        HeadlessAudioBackend incoming = new HeadlessAudioBackend();

        assertThatCode(() -> engine.setAudioBackend(incoming))
                .as("re-pointing is best-effort about the old handle and must not throw")
                .doesNotThrowAnyException();

        assertThat(outgoing.closeAttempts)
                .as("the close is retried on the backend that actually holds the handle")
                .isEqualTo(2);
        assertThat(outgoing.delegate.isStreamOpen())
                .as("the close still failed: the old handle is abandoned, not leaked into the"
                        + " new backend's bookkeeping")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("nothing tracked any more — a retry on the new backend could never reach it")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        assertThatCode(engine::startAudioOutput)
                .as("the new backend opens a fresh stream; no close retry is aimed at it")
                .doesNotThrowAnyException();
        assertThat(incoming.isStreamOpen()).isTrue();
        assertThat(incoming.isStreamActive()).isTrue();
        assertThat(outgoing.closeAttempts)
                .as("no further close was aimed at the old backend")
                .isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    @Test
    void switchingBackendsWithARunningStreamClosesItOnTheOutgoingBackendAndReleasesTheClaim() {
        FaultyBackend outgoing = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(outgoing);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        transport.play();
        transport.setPositionInBeats(5.0); // queued — the stream owns the clock
        engine.stop();

        engine.setAudioBackend(new HeadlessAudioBackend());

        assertThat(outgoing.closeAttempts)
                .as("the outgoing backend's stream is closed before it is forgotten")
                .isEqualTo(1);
        assertThat(outgoing.delegate.isStreamOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the release drains the queued seek rather than stranding it")
                .isEqualTo(5.0);
    }

    @Test
    void reSettingTheSameBackendWhilePausedLeavesTheStreamPausedAndResumable() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        engine.stop();

        engine.setAudioBackend(backend); // same instance

        assertThat(backend.closeAttempts)
                .as("the same backend keeps its handle; nothing is closed")
                .isZero();
        assertThat(engine.isStreamPaused()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        engine.startAudioOutput(); // resumes, no re-open
        assertThat(backend.openAttempts).isEqualTo(1);
        assertThat(backend.startAttempts).isEqualTo(2);
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    // ── Finding B: the claim is taken before the backend start call ───────

    @Test
    void theClaimIsAlreadyHeldWhenTheBackendStartsTheOutputStream() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        List<Boolean> claimedInsideStart = new ArrayList<>();
        backend.onStart = () -> claimedInsideStart.add(transport.isRealTimeClockActive());

        engine.startAudioOutput();

        assertThat(claimedInsideStart)
                .as("startStream may run the first callback block before it returns,"
                        + " so the claim must already be held inside it")
                .containsExactly(true);
    }

    @Test
    void theClaimIsAlreadyHeldWhenTheBackendStartsTheFullDuplexStream() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        List<Boolean> claimedInsideStart = new ArrayList<>();
        backend.onStart = () -> claimedInsideStart.add(transport.isRealTimeClockActive());

        engine.startAudioInputOutput(0);

        assertThat(claimedInsideStart).containsExactly(true);
    }

    @Test
    void theClaimIsAlreadyHeldWhenTheBackendResumesAPausedStream() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        List<Boolean> claimedInsideStart = new ArrayList<>();
        backend.onStart = () -> claimedInsideStart.add(transport.isRealTimeClockActive());

        engine.startAudioOutput(); // resumes the paused stream

        assertThat(claimedInsideStart).containsExactly(true);
    }

    @Test
    void aSeekIssuedDuringAFailedStartIsDrainedByTheReleaseAndTheStreamIsClosed() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStart = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        transport.play();
        List<Boolean> claimedInsideStart = new ArrayList<>();
        List<Double> positionInsideStart = new ArrayList<>();
        backend.onStart = () -> {
            transport.setPositionInBeats(7.0); // UI seek in the window
            claimedInsideStart.add(transport.isRealTimeClockActive());
            positionInsideStart.add(transport.getPositionInBeats());
        };

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("start refused by the driver");

        assertThat(claimedInsideStart)
                .as("the claim was held while the seek was issued")
                .containsExactly(true);
        assertThat(positionInsideStart)
                .as("so the seek was queued behind the claim, not applied inline")
                .containsExactly(0.0);
        assertThat(transport.isRealTimeClockActive())
                .as("a stream that never started drives nothing")
                .isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the seek queued in the window is applied by the release, not stranded")
                .isEqualTo(7.0);
        assertThat(backend.delegate.isStreamOpen())
                .as("the opened handle was given back")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void anAbruptStartFailureIsUnwoundLikeARefusedStartAndRethrownAsIs() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStartAbruptly = true; // IllegalStateException, not AudioBackendException
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        assertThatThrownBy(engine::startAudioOutput)
                .as("the backend's own exception propagates unchanged")
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("line failed to start");

        assertThat(transport.isRealTimeClockActive())
                .as("the claim taken before the start call is given back")
                .isFalse();
        assertThat(engine.isStreamOpen())
                .as("the engine must not be left RUNNING with nothing driving the callback")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(backend.delegate.isStreamOpen())
                .as("the opened handle was given back")
                .isFalse();

        // Not wedged: a later start opens and starts a fresh stream normally.
        backend.failStartAbruptly = false;
        engine.startAudioOutput();
        assertThat(backend.openAttempts).isEqualTo(2);
        assertThat(backend.startAttempts).isEqualTo(2);
        assertThat(backend.delegate.isStreamActive()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    @Test
    void anAbruptResumeFailureLeavesTheStreamPausedAndRethrownAsIs() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        backend.failStartAbruptly = true;

        assertThatThrownBy(engine::startAudioOutput)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("line failed to start");

        assertThat(engine.isStreamPaused())
                .as("the resume is retryable — the engine is not wedged RUNNING")
                .isTrue();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        backend.failStartAbruptly = false;
        engine.startAudioOutput();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(backend.openAttempts).isEqualTo(1);
        assertThat(transport.isRealTimeClockActive()).isTrue();
    }

    @Test
    void aStartFailureWhoseCloseAlsoFailsRetainsTheHandleAndKeepsTheStartFailure() {
        FaultyBackend backend = new FaultyBackend();
        backend.failStart = true;
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        assertThatThrownBy(engine::startAudioOutput)
                .as("the close failure must never mask the start failure")
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("start refused by the driver")
                .hasSuppressedException(new AudioBackendException("close refused by the driver"))
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .as("the close failure travels as the one suppressed exception")
                        .hasSize(1));

        assertThat(backend.delegate.isStreamOpen())
                .as("the backend still owns the handle it could not close")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("a retained handle is stopped and not resumable, so it is not reported open")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        // A later start with the close still failing retries the close — the
        // engine still tracks the handle — but must not open a second stream.
        backend.failStart = false;
        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released");
        assertThat(backend.closeAttempts).isEqualTo(2);
        assertThat(backend.openAttempts).isEqualTo(1);

        // A later stop retries the close and, once it succeeds, the engine is closed.
        backend.failClose = false;
        engine.stopAudioOutput();
        assertThat(backend.closeAttempts).isEqualTo(3);
        assertThat(backend.delegate.isStreamOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void aFailedResumeLeavesTheStreamPausedAndReleasesTheClaim() {
        FaultyBackend backend = new FaultyBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setAudioBackend(backend);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        backend.failStart = true;
        List<Boolean> claimedInsideStart = new ArrayList<>();
        backend.onStart = () -> claimedInsideStart.add(transport.isRealTimeClockActive());

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("start refused by the driver");

        assertThat(claimedInsideStart)
                .as("the claim was held inside the refused resume start")
                .containsExactly(true);
        assertThat(engine.isStreamOpen())
                .as("the stream stays open — the resume is retryable")
                .isTrue();
        assertThat(engine.isStreamPaused()).isTrue();
        assertThat(backend.closeAttempts)
                .as("a failed resume does not close the paused stream")
                .isZero();
        assertThat(transport.isRealTimeClockActive())
                .as("the claim taken for the resume is given back")
                .isFalse();

        backend.failStart = false;
        engine.startAudioOutput();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isTrue();
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
     * {@link HeadlessAudioBackend} decorator with opt-in start/stop/close
     * faults. A refused start leaves the delegate's stream open but inactive
     * (the handle is held, the callback never ran); a refused stop leaves the
     * delegate's stream active (the callback keeps running, exactly like a
     * wedged driver); a refused close leaves the delegate untouched, so
     * {@link #isStreamActive()} reports the real post-failure state: still
     * active when stop was refused too, idle when stop succeeded. A refused
     * activity probe hides that state entirely.
     *
     * <p>Like both production backends, {@link #openStream} throws
     * {@link IllegalStateException} while the delegate still holds a stream,
     * so a test that expects "no second open" fails for the real reason if
     * the engine ever regresses into double-opening. {@link #failStartAbruptly}
     * models the unguarded failures those backends can raise from
     * {@code startStream()} (Java Sound's {@code line.start()} / thread spawn,
     * PortAudio's {@code ensureStreamOpen}): a plain
     * {@link IllegalStateException}, not an {@link AudioBackendException}.</p>
     *
     * <p>{@link #onStart} runs inside {@link #startStream()} before the
     * delegate is started (and before a refused start throws), standing in
     * for a backend whose first callback block — or a UI seek — lands while
     * the start call is still on the stack.</p>
     */
    private static final class FaultyBackend implements NativeAudioBackend {

        final HeadlessAudioBackend delegate = new HeadlessAudioBackend();
        boolean failStart;
        boolean failStartAbruptly;
        boolean failStop;
        boolean failClose;
        boolean failActiveProbe;
        Runnable onStart = () -> { };
        int openAttempts;
        int startAttempts;
        int stopAttempts;
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
            if (delegate.isStreamOpen()) {
                throw new IllegalStateException("A stream is already open; close it first");
            }
            openAttempts++;
            delegate.openStream(config, callback);
        }

        @Override
        public void startStream() {
            startAttempts++;
            onStart.run();
            if (failStart) {
                throw new AudioBackendException("start refused by the driver");
            }
            if (failStartAbruptly) {
                throw new IllegalStateException("line failed to start");
            }
            delegate.startStream();
        }

        @Override
        public void stopStream() {
            stopAttempts++;
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
