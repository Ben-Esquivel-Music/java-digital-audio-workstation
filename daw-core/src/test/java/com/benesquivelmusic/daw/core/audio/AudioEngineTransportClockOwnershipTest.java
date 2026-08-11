package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.harness.HeadlessAudioBackend;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
