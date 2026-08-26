package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.harness.HeadlessAudioBackend;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Story 315 review, retargeted onto the story-316 consolidated seam — the
 * engine owns the transport's RT-clock claim, and the claim is true only
 * while the engine-owned render pump is (or, for the instant between the
 * claim and the pump start, is about to be) calling
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
 *
 * <h2>What changed with story 316 (deliberate)</h2>
 * <p>The render drive is now the engine's own pump, not a backend callback,
 * so after {@code pump.stop()} joins, the engine <em>knows</em> nothing can
 * drive the transport any more — {@link AudioEngine#stopAudioOutput()}
 * therefore releases the claim unconditionally after stopping the pump, and
 * the old "close failed with the callback possibly still active → preserve
 * the claim" matrix rows no longer exist (there is no independent callback
 * whose liveness could be unknown). A refused backend close still yields
 * {@code RELEASE_PENDING} with the handle retained and retried — on every
 * engine path that closes the TRACKED stream handle, the provision swap
 * included. That swap
 * used to be the exception: it abandoned an unreleasable handle, which threw
 * away the engine's only retry path and let the next open put a second
 * backend on the same device. It now refuses the whole swap instead, keeps
 * tracking the outgoing backend, and releases the RT-clock claim on that
 * refusal path too — the claim is owed from the moment the pump's exit is
 * CONFIRMED, not from the moment the handle is finally freed.</p>
 *
 * <p>A failed ladder HOP is not one of those paths, and must not be read as
 * one: {@code closeFailedHop} closes a rung whose handle was never tracked,
 * so a close that fails there is REPORTED rather than retained — the walk is
 * abandoned instead, and the instance is the app layer's to close.</p>
 *
 * <h2>Determinism</h2>
 * <p>Tests that queue a seek behind the claim first stall the pump inside
 * {@code sink} ({@link FaultySdkBackend#stallSink}): the pump's single first
 * block renders while the transport is stopped (no advance, no drain), then
 * blocks — so a subsequently queued seek deterministically stays queued
 * until the release drains it. Awaits use one generous guard budget
 * ({@value #GUARD_BUDGET_MILLIS}&nbsp;ms) on conditions, never sleeps.</p>
 */
class AudioEngineTransportClockOwnershipTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    /** Guard budget for pump-related waits — generous, never inner-inflated. */
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    private static StreamingProvision provisionOf(AudioBackend backend) {
        return new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name()))));
    }

    // ── No provision: nothing drives the transport ───────────────────────

    @Test
    void startingOutputWithNoProvisionLeavesTheTransportsClockUnclaimed() {
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen())
                .as("no provision was installed, so no stream opened")
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
    void startingInputOutputWithNoProvisionThrowsAndLeavesTheTransportsClockUnclaimed() {
        // Story 316 review: the record path used to return normally here, so
        // this test only had to prove the claim was never taken. It now
        // THROWS — with no provision the engine has no capture device at all
        // — and the claim must still be untaken AFTER that throw. The
        // ordering matters: a refusal that had already claimed the clock
        // would leave the transport driven by a stream that does not exist,
        // and every later seek queued behind an owner that never advances.
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        assertThatThrownBy(engine::startAudioInputOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no audio backend is configured");

        assertThat(transport.isRealTimeClockActive())
                .as("the refused record open must not leave the RT clock claimed")
                .isFalse();
    }

    @Test
    void startingOutputWithNoProvisionStillSucceedsWithTheClockUnclaimed() {
        // The DISCRIMINATING sibling of the test above (story 316 review):
        // refusing a capture-less RECORD open must not have made PLAYBACK
        // stricter. Playback without hardware output is legitimate, so this
        // returns normally, and the claim is untaken for the original reason
        // — no stream drives the transport.
        AudioEngine engine = new AudioEngine(FORMAT);
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    // ── A real stream: the claim follows the stream lifecycle ────────────

    @Test
    void startingOutputOnARunningStreamClaimsTheTransportsClock() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void startingInputOutputClaimsTheTransportsClock() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        engine.startAudioInputOutput();

        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void stoppingOutputReleasesTheClaim() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.stopAudioOutput();

        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void pausingReleasesTheClaimAndResumingTakesItBack() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.pauseAudioOutput();
        assertThat(transport.isRealTimeClockActive())
                .as("a paused stream renders nothing")
                .isFalse();

        engine.startAudioOutput(); // resumes the paused stream
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void closingTheStreamDrainsASeekQueuedMicrosecondsEarlier() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.stallSink = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(backend);
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

    // ── Backend close faults on stop: the RELEASE_PENDING matrix ─────────

    @Test
    void stopWithACloseFailureReleasesTheClaimAndRetainsTheHandle() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        assertThatCode(engine::stopAudioOutput)
                .as("stopAudioOutput is best-effort and must not throw")
                .doesNotThrowAnyException();

        assertThat(backend.delegate.isOpen())
                .as("the backend still owns the unclosed handle")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("output is stopped and not resumable, so the engine does not report it open"
                        + " — a settings apply must not restart output on this answer")
                .isFalse();
        assertThat(engine.isStreamPaused())
                .as("a retained handle is stopped but not resumable, so it is not paused")
                .isFalse();
        assertThat(engine.openStreamBackendName())
                .as("a retained handle is not an open stream")
                .isEmpty();
        assertThat(transport.isRealTimeClockActive())
                .as("the pump is provably stopped, so the claim is released — nothing"
                        + " would ever drain the queue otherwise")
                .isFalse();

        engine.stopAudioOutput();
        assertThat(backend.closeAttempts)
                .as("the engine still tracks the retained handle: the next stop retries the"
                        + " close, which a closed engine would never do")
                .isEqualTo(2);
    }

    @Test
    void aCloseFailureWithAQueuedSeekDrainsTheSeekOnRelease() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.stallSink = true;
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(backend);
        transport.play();
        transport.setPositionInBeats(24.0); // queued — the stream owns the clock
        assertThat(transport.getPositionInBeats()).isZero();

        assertThatCode(engine::stopAudioOutput).doesNotThrowAnyException();

        assertThat(transport.isRealTimeClockActive()).isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the queued seek is drained by the release, not stranded")
                .isEqualTo(24.0);
        assertThat(backend.delegate.isOpen())
                .as("the backend still owns the unclosed handle")
                .isTrue();
    }

    @Test
    void aRetainedHandleIsReleasedByALaterStop() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained
        assertThat(backend.delegate.isOpen())
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
        assertThat(backend.delegate.isOpen())
                .as("the retry released the backend's handle")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void startingWithARetainedHandleThatStillCannotBeReleasedFailsWithoutASecondOpen() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained

        assertThatThrownBy(engine::startAudioOutput)
                .as("the retained handle blocks a fresh open; the caller must be told")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released")
                .cause().hasMessageContaining("close refused by the driver");

        assertThat(backend.closeAttempts)
                .as("the start retried the close")
                .isEqualTo(2);
        assertThat(backend.openAttempts)
                .as("no second open while the backend still owns a handle")
                .isEqualTo(1);
        assertThat(backend.delegate.isOpen())
                .as("the state is unchanged: the backend still owns the handle")
                .isTrue();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        // Still tracked: the next stop retries the close once more.
        engine.stopAudioOutput();
        assertThat(backend.closeAttempts).isEqualTo(3);
    }

    @Test
    void startingWithARetainedHandleReleasesItAndOpensAFreshStream() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained

        backend.failClose = false;
        engine.startAudioOutput();

        assertThat(backend.closeAttempts)
                .as("the retained handle is released before the fresh open")
                .isEqualTo(2);
        assertThat(backend.openAttempts)
                .as("a fresh stream is opened once the handle is gone")
                .isEqualTo(2);
        assertThat(backend.pumpStartAttempts).isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("the fresh stream drives the transport again")
                .isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void startingInputOutputWithARetainedHandleThatCannotBeReleasedFailsWithoutASecondOpen() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained

        assertThatThrownBy(engine::startAudioInputOutput)
                .as("record shares the one seam and hits the same refusal")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released");

        assertThat(backend.openAttempts)
                .as("no second open while the backend still owns a handle")
                .isEqualTo(1);
        assertThat(backend.closeAttempts).isEqualTo(2);
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void startingInputOutputWithARetainedHandleReleasesItAndOpensAFreshStream() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained

        backend.failClose = false;
        engine.startAudioInputOutput();

        assertThat(backend.closeAttempts).isEqualTo(2);
        assertThat(backend.openAttempts).isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void pausingIsANoOpWhileAHandleIsRetained() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained

        engine.pauseAudioOutput();

        assertThat(engine.isStreamPaused())
                .as("a retained handle is not resumable, so it is never 'paused'")
                .isFalse();
        assertThat(engine.isStreamOpen())
                .as("a retained handle is not reported open either")
                .isFalse();
        assertThat(backend.delegate.isOpen())
                .as("the backend still owns the handle")
                .isTrue();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void rebindingWhileAHandleIsRetainedLeavesTheIncomingTransportUnclaimed() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained

        Transport incoming = new Transport();
        engine.setGraph(incoming, null, null);

        assertThat(incoming.isRealTimeClockActive())
                .as("nothing drives a retained, stopped handle")
                .isFalse();
        assertThat(outgoing.isRealTimeClockActive()).isFalse();
    }

    // ── Re-provisioning: a tracked handle belongs to the outgoing rungs ──

    @Test
    void settingAProvisionWhileTheEngineRunsIsRejected() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        engine.start();

        assertThatThrownBy(() -> engine.setStreamingProvision(provisionOf(new FaultySdkBackend())))
                .isInstanceOf(IllegalStateException.class);
        engine.stop();
    }

    @Test
    void swappingProvisionsWithAnUnreleasableHandleIsRefusedAndKeepsTheRetryPath() {
        FaultySdkBackend outgoing = new FaultySdkBackend();
        outgoing.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(outgoing));
        StreamingProvision outgoingProvision = engine.getStreamingProvision();
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.stopAudioOutput(); // pump stopped, handle retained
        engine.stop();            // setStreamingProvision requires a stopped engine
        FaultySdkBackend incoming = new FaultySdkBackend();
        StreamingProvision incomingProvision = provisionOf(incoming);

        assertThatThrownBy(() -> engine.setStreamingProvision(incomingProvision))
                .as("re-pointing the engine at another provision would DISCARD the only"
                        + " path that could ever release this handle, and the next open"
                        + " would then put a second backend on the same device")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released");

        assertThat(outgoing.closeAttempts)
                .as("the close is retried on the backend that actually holds the handle")
                .isEqualTo(2);
        assertThat(outgoing.delegate.isOpen())
                .as("the close still failed, so the backend still owns the handle")
                .isTrue();
        assertThat(engine.getStreamingProvision())
                .as("the swap is refused WHOLE — the engine still points at the outgoing"
                        + " provision, so the outgoing backend stays reachable")
                .isSameAs(outgoingProvision);
        assertThat(engine.isStreamOpen())
                .as("RELEASE_PENDING is not open: output is stopped and not resumable")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive())
                .as("the pump's exit was CONFIRMED, so the claim is released on the"
                        + " refusal path too — not stranded behind the throw")
                .isFalse();

        // The retry path the refusal preserved: the next open aims its close
        // at the OUTGOING backend, not at the incoming provision's.
        assertThatThrownBy(engine::startAudioOutput)
                .as("the open is refused while the retained handle is still held")
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released");
        assertThat(outgoing.closeAttempts)
                .as("that retry actually REACHED the outgoing backend")
                .isEqualTo(3);
        assertThat(incoming.openAttempts)
                .as("no stream was opened beside the backend that still holds the device")
                .isZero();

        // Once the handle can be released, the same swap succeeds.
        outgoing.failClose = false;
        engine.setStreamingProvision(incomingProvision);
        assertThat(engine.getStreamingProvision()).isSameAs(incomingProvision);
        assertThat(outgoing.closeAttempts)
                .as("the successful release is the fourth close aimed at the old backend")
                .isEqualTo(4);
        assertThat(outgoing.delegate.isOpen()).isFalse();

        engine.startAudioOutput();
        assertThat(incoming.delegate.isOpen()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void swappingProvisionsWithARunningStreamClosesItOnTheOutgoingBackendAndReleasesTheClaim() {
        FaultySdkBackend outgoing = new FaultySdkBackend();
        outgoing.stallSink = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(outgoing));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(outgoing);
        transport.play();
        transport.setPositionInBeats(5.0); // queued — the stream owns the clock
        engine.stop();

        engine.setStreamingProvision(provisionOf(new FaultySdkBackend()));

        assertThat(outgoing.closeAttempts)
                .as("the outgoing backend's stream is closed before it is forgotten")
                .isEqualTo(1);
        assertThat(outgoing.delegate.isOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the release drains the queued seek rather than stranding it")
                .isEqualTo(5.0);
    }

    @Test
    void reinstallingAProvisionCarryingTheSameOpenBackendKeepsThePausedStreamResumable() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        engine.stop();

        engine.setStreamingProvision(provisionOf(backend)); // same backend instance

        assertThat(backend.closeAttempts)
                .as("the same backend keeps its handle; nothing is closed")
                .isZero();
        assertThat(engine.isStreamPaused()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        engine.startAudioOutput(); // resumes, no re-open
        assertThat(backend.openAttempts).isEqualTo(1);
        assertThat(backend.pumpStartAttempts).isEqualTo(2);
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    // ── Finding B: the claim is taken before the pump start ──────────────

    @Test
    void theClaimIsAlreadyHeldWhenThePumpIsStarted() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        List<Boolean> claimedInsidePumpStart = new ArrayList<>();
        backend.onPumpStart = () -> claimedInsidePumpStart.add(transport.isRealTimeClockActive());

        engine.startAudioOutput();

        assertThat(claimedInsidePumpStart)
                .as("the pump may render its first block before the start call returns,"
                        + " so the claim must already be held when the pump starts")
                .containsExactly(true);
        engine.stopAudioOutput();
    }

    @Test
    void theClaimIsAlreadyHeldWhenTheInputOutputPumpIsStarted() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        List<Boolean> claimedInsidePumpStart = new ArrayList<>();
        backend.onPumpStart = () -> claimedInsidePumpStart.add(transport.isRealTimeClockActive());

        engine.startAudioInputOutput();

        assertThat(claimedInsidePumpStart).containsExactly(true);
        engine.stopAudioOutput();
    }

    @Test
    void theClaimIsAlreadyHeldWhenAPausedStreamIsResumed() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        List<Boolean> claimedInsidePumpStart = new ArrayList<>();
        backend.onPumpStart = () -> claimedInsidePumpStart.add(transport.isRealTimeClockActive());

        engine.startAudioOutput(); // resumes the paused stream

        assertThat(claimedInsidePumpStart).containsExactly(true);
        engine.stopAudioOutput();
    }

    @Test
    void aSeekIssuedDuringAFailedPumpStartIsDrainedByTheReleaseAndTheStreamIsClosed() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failPumpStart = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        transport.play();
        List<Boolean> claimedInsidePumpStart = new ArrayList<>();
        List<Double> positionInsidePumpStart = new ArrayList<>();
        backend.onPumpStart = () -> {
            transport.setPositionInBeats(7.0); // UI seek in the window
            claimedInsidePumpStart.add(transport.isRealTimeClockActive());
            positionInsidePumpStart.add(transport.getPositionInBeats());
        };

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("pump start refused by the driver");

        assertThat(claimedInsidePumpStart)
                .as("the claim was held while the seek was issued")
                .containsExactly(true);
        assertThat(positionInsidePumpStart)
                .as("so the seek was queued behind the claim, not applied inline")
                .containsExactly(0.0);
        assertThat(transport.isRealTimeClockActive())
                .as("a stream whose pump never started drives nothing")
                .isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the seek queued in the window is applied by the release, not stranded")
                .isEqualTo(7.0);
        assertThat(backend.delegate.isOpen())
                .as("the opened handle was given back")
                .isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void anAbruptPumpStartFailureIsUnwoundLikeARefusedStartAndRethrownAsIs() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failPumpStartAbruptly = true; // IllegalStateException, not AudioBackendException
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        assertThatThrownBy(engine::startAudioOutput)
                .as("the backend's own exception propagates unchanged")
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("pump failed to start");

        assertThat(transport.isRealTimeClockActive())
                .as("the claim taken before the pump start is given back")
                .isFalse();
        assertThat(engine.isStreamOpen())
                .as("the engine must not be left RUNNING with nothing driving the render")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(backend.delegate.isOpen())
                .as("the opened handle was given back")
                .isFalse();

        // Not wedged: a later start opens and starts a fresh stream normally.
        backend.failPumpStartAbruptly = false;
        engine.startAudioOutput();
        assertThat(backend.openAttempts).isEqualTo(2);
        assertThat(backend.pumpStartAttempts).isEqualTo(2);
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void anAbruptResumeFailureLeavesTheStreamPausedAndRethrownAsIs() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        backend.failPumpStartAbruptly = true;

        assertThatThrownBy(engine::startAudioOutput)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("pump failed to start");

        assertThat(engine.isStreamPaused())
                .as("the resume is retryable — the engine is not wedged RUNNING")
                .isTrue();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        backend.failPumpStartAbruptly = false;
        engine.startAudioOutput();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(backend.openAttempts).isEqualTo(1);
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void aPumpStartFailureWhoseCloseAlsoFailsRetainsTheHandleAndKeepsTheStartFailure() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.failPumpStart = true;
        backend.failClose = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);

        assertThatThrownBy(engine::startAudioOutput)
                .as("the close failure must never mask the start failure")
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("pump start refused by the driver")
                .hasSuppressedException(new AudioBackendException("close refused by the driver"))
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .as("the close failure travels as the one suppressed exception")
                        .hasSize(1));

        assertThat(backend.delegate.isOpen())
                .as("the backend still owns the handle it could not close")
                .isTrue();
        assertThat(engine.isStreamOpen())
                .as("a retained handle is stopped and not resumable, so it is not reported open")
                .isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();

        // A later start with the close still failing retries the close — the
        // engine still tracks the handle — but must not open a second stream.
        backend.failPumpStart = false;
        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("could not be released");
        assertThat(backend.closeAttempts).isEqualTo(2);
        assertThat(backend.openAttempts).isEqualTo(1);

        // A later stop retries the close and, once it succeeds, the engine is closed.
        backend.failClose = false;
        engine.stopAudioOutput();
        assertThat(backend.closeAttempts).isEqualTo(3);
        assertThat(backend.delegate.isOpen()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void aFailedResumeLeavesTheStreamPausedAndReleasesTheClaim() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        backend.failPumpStart = true;
        List<Boolean> claimedInsidePumpStart = new ArrayList<>();
        backend.onPumpStart = () -> claimedInsidePumpStart.add(transport.isRealTimeClockActive());

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("pump start refused by the driver");

        assertThat(claimedInsidePumpStart)
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

        backend.failPumpStart = false;
        engine.startAudioOutput();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    // ── setGraph hands the claim over (story 314 rebind) ─────────────────

    @Test
    void rebindingMidPlaybackMovesTheClaimToTheIncomingTransport() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        assertThat(outgoing.isRealTimeClockActive()).isTrue();

        Transport incoming = new Transport();
        engine.setGraph(incoming, null, null);

        assertThat(outgoing.isRealTimeClockActive())
                .as("the old project's transport is no longer driven by the pump")
                .isFalse();
        assertThat(incoming.isRealTimeClockActive())
                .as("exactly one transport is claimed after a rebind")
                .isTrue();
        engine.stopAudioOutput();
    }

    @Test
    void rebindingDrainsASeekTheOutgoingTransportStillHadQueued() {
        FaultySdkBackend backend = new FaultySdkBackend();
        backend.stallSink = true;
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport outgoing = new Transport();
        engine.setGraph(outgoing, null, null);
        engine.startAudioOutput();
        awaitPumpStalledInSink(backend);
        outgoing.play();
        outgoing.setPositionInBeats(12.0); // queued

        engine.setGraph(new Transport(), null, null);

        assertThat(outgoing.getPositionInBeats()).isEqualTo(12.0);
        engine.stopAudioOutput();
    }

    @Test
    void bindingWithNoStreamRunningLeavesTheIncomingTransportUnclaimed() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();

        engine.setGraph(transport, null, null); // no stream opened yet

        assertThat(transport.isRealTimeClockActive()).isFalse();
    }

    @Test
    void unbindingReleasesTheClaimOfTheOutgoingTransport() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.setGraph(null, null, null); // EngineBinder.unbind()

        assertThat(transport.isRealTimeClockActive()).isFalse();
        engine.stopAudioOutput();
    }

    @Test
    void theClaimSurvivesARebindOntoTheSameTransport() {
        FaultySdkBackend backend = new FaultySdkBackend();
        AudioEngine engine = new AudioEngine(FORMAT);
        engine.setStreamingProvision(provisionOf(backend));
        Transport transport = new Transport();
        engine.setGraph(transport, null, null);
        engine.startAudioOutput();

        engine.setGraph(transport, null, List.of()); // e.g. a track-list refresh

        assertThat(transport.isRealTimeClockActive()).isTrue();
        engine.stopAudioOutput();
    }

    // ── Await support ────────────────────────────────────────────────────

    /**
     * Awaits the pump's first (and only) sink call, after which the pump is
     * deterministically blocked and no further {@code processBlock} can run
     * until the engine stops it. Guard budget only — no inner waits.
     */
    private static void awaitPumpStalledInSink(FaultySdkBackend backend) {
        try {
            if (!backend.sinkEntered.await(GUARD_BUDGET_MILLIS, TimeUnit.MILLISECONDS)) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS
                        + " ms awaiting the pump's first sink call");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted awaiting the pump's first sink call");
        }
    }

    // ── Test double ──────────────────────────────────────────────────────

    /**
     * {@link HeadlessAudioBackend} decorator with opt-in open / pump-start /
     * close faults, the story-316 analogue of the old
     * {@code NativeAudioBackend} FaultyBackend. A refused pump start
     * ({@link #failPumpStart} / {@link #failPumpStartAbruptly}, thrown from
     * {@link #inputBlocks()} — the first thing the pump touches) leaves the
     * delegate's stream open but undriven, exactly like a wedged driver
     * start; a refused close leaves the delegate untouched so
     * {@code delegate.isOpen()} reports the real post-failure state.
     *
     * <p>{@link #onPumpStart} runs inside {@link #inputBlocks()} before any
     * failure throw — i.e. after the engine's claim but before the pump
     * thread exists — standing in for a UI seek landing while the start call
     * is still on the stack.</p>
     *
     * <p>{@link #stallSink} makes the first {@code sink} call signal
     * {@link #sinkEntered} and then block until interrupted (which
     * {@code pump.stop()} does), so tests can queue a seek with the claim
     * held and no render block racing the queue.</p>
     *
     * <p>{@link #awaitSinkCapacity(long)} parks briefly instead of the
     * delegate's no-op so a free-running pump never busy-spins a CPU core
     * during a test.</p>
     */
    private static final class FaultySdkBackend implements AudioBackend {

        final HeadlessAudioBackend delegate = new HeadlessAudioBackend();
        volatile boolean failOpen;
        volatile boolean failPumpStart;
        volatile boolean failPumpStartAbruptly;
        volatile boolean failClose;
        volatile boolean stallSink;
        volatile Runnable onPumpStart = () -> { };
        volatile int openAttempts;
        volatile int pumpStartAttempts;
        volatile int closeAttempts;
        final CountDownLatch sinkEntered = new CountDownLatch(1);
        private final CountDownLatch neverReleased = new CountDownLatch(1);

        @Override
        public String name() {
            return "Faulty(" + delegate.name() + ")";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return delegate.listDevices();
        }

        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            if (delegate.isOpen()) {
                throw new IllegalStateException("A stream is already open; close it first");
            }
            openAttempts++;
            if (failOpen) {
                throw new AudioBackendException("open refused by the driver");
            }
            delegate.open(device, format, bufferFrames);
        }

        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            pumpStartAttempts++;
            onPumpStart.run();
            if (failPumpStart) {
                throw new AudioBackendException("pump start refused by the driver");
            }
            if (failPumpStartAbruptly) {
                throw new IllegalStateException("pump failed to start");
            }
            return delegate.inputBlocks();
        }

        @Override
        public void sink(AudioBlock block) {
            if (stallSink) {
                sinkEntered.countDown();
                try {
                    neverReleased.await(); // until pump.stop() interrupts
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            delegate.sink(block);
        }

        @Override
        public void awaitSinkCapacity(long timeoutNanos) {
            // Never busy-spin a free-running pump in tests.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /**
         * Story 316 review: capture truth comes from the delegate, which is
         * a full-duplex headless stand-in. Without this override the
         * interface default ({@code 0}) would make every record-path test
         * here fail the engine's REQUIRED-open verification, which is not
         * what any of them is about.
         */
        @Override
        public int openedInputChannels() {
            return delegate.openedInputChannels();
        }

        @Override
        public void close() {
            closeAttempts++;
            if (failClose) {
                throw new AudioBackendException("close refused by the driver");
            }
            delegate.close();
        }
    }
}
