package com.benesquivelmusic.daw.sdk.audio;

/**
 * Whether an {@link AudioBackend#open(DeviceId, AudioFormat, int,
 * CaptureRequirement) open} may degrade to output-only, or must produce a live
 * capture stream (story 316 review).
 *
 * <h2>Why this exists: the silent take</h2>
 * <p>Every backend in this tree treats capture as OPTIONAL-DEGRADE, and for a
 * playback open that is exactly right &mdash; a playback-only interface, or an
 * ASIO4ALL configured with only speakers enabled, must still open.
 * {@link JavaxSoundBackend#open(DeviceId, AudioFormat, int)} swallows a capture
 * line it could not take and carries on with playback; {@link AsioBackend}'s
 * channel negotiation deliberately has no symmetric guard against a driver
 * reporting zero inputs; and {@code daw-core}'s {@code CallbackBackendAdapter}
 * retries a refused duplex stream OUTPUT-ONLY. All three are correct for
 * playback.</p>
 *
 * <p>The RECORDING entry point walked that same ladder, and inherited all three
 * degradations. The open returned successfully, the recording pipeline
 * subscribed to {@link AudioBackend#inputBlocks()}, and that publisher never
 * emitted a block &mdash; so the take was silent, the take was saved, and
 * nothing anywhere reported a failure. A silent take discovered after the
 * performance is the most expensive failure this application can produce; it is
 * worth a refused open every time.</p>
 *
 * <h2>Division of labour: directive here, verification at the caller</h2>
 * <p>This enum is only HALF of the mechanism, and deliberately the weaker
 * half:</p>
 *
 * <ul>
 *   <li>The enum is a <em>directive to the backend</em>. A backend that can see
 *       it is about to degrade &mdash; {@link JavaxSoundBackend}, whose capture
 *       line was refused, or {@code CallbackBackendAdapter}, about to re-open
 *       output-only (that one lands with daw-core's half of the same review)
 *       &mdash; honours {@link #REQUIRED} by FAILING the open, with the precise
 *       native cause attached. That is strictly better than being
 *       rejected after the fact, because it never grabs the device output-only
 *       just to have the open refused and closed again.</li>
 *   <li>{@link AudioBackend#openedInputChannels()} is the <em>verifiable
 *       result</em>, and the caller's check of it is what actually makes the
 *       invariant hold. {@link AudioBackend#open(DeviceId, AudioFormat, int,
 *       CaptureRequirement)} has a DEFAULT body that ignores this enum
 *       entirely, so a backend that never overrides it honours nothing &mdash;
 *       and that is safe only because the engine, after a successful
 *       {@link #REQUIRED} open, reads {@code openedInputChannels()} and turns a
 *       zero into an ordinary failed ladder hop.</li>
 * </ul>
 *
 * <p>So: never read a {@link #REQUIRED} open returning normally as proof that
 * capture exists. The proof is {@code openedInputChannels() > 0}, and the
 * engine is the single place that enforces it.</p>
 *
 * @see AudioBackend#open(DeviceId, AudioFormat, int, CaptureRequirement)
 * @see AudioBackend#openedInputChannels()
 */
public enum CaptureRequirement {

    /**
     * Capture may degrade silently: an open that produces no capture stream at
     * all is still a SUCCESS.
     *
     * <p>The playback contract, and the historical behaviour of every backend
     * in this tree. It is what {@code AudioEngine.startAudioOutput} asks for
     * (wired in the same review), so nothing about playback changed when
     * {@link #REQUIRED} was introduced: a playback-only interface has no inputs
     * to offer and must still open, and an input line the mixer refuses only
     * disables capture.</p>
     */
    OPTIONAL,

    /**
     * The open must produce a live capture stream: any output-only degradation
     * must FAIL the open instead of succeeding into a stream that can never
     * record.
     *
     * <p>The recording contract. It is what
     * {@code AudioEngine.startAudioInputOutput} asks for (wired in the same
     * review), because a recording open that quietly became output-only
     * produces a silent take &mdash; see the class javadoc. A backend that
     * cannot detect the degradation is still caught: the caller verifies
     * {@link AudioBackend#openedInputChannels()} afterwards and refuses the
     * rung.</p>
     */
    REQUIRED
}
