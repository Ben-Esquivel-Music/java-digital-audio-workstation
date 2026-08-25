package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.BiPredicate;

/**
 * Internal helper that holds the mutable state every {@link AudioBackend}
 * implementation needs: the input-block publisher, the opened
 * {@link AudioFormat}, and the open/closed flag.
 *
 * <p>Used by composition — native backends ({@code AsioBackend},
 * {@code CoreAudioBackend}, {@code WasapiBackend}, {@code JackBackend}),
 * {@link JavaxSoundBackend}, and {@link MockAudioBackend} all delegate
 * their common state management here. Kept package-private because the
 * {@link AudioBackend} interface is the public surface.</p>
 */
final class AudioBackendSupport implements AutoCloseable {

    /**
     * Drop handler shared by every
     * {@link #publishInput(SubmissionPublisher, AudioBlock)} call — and so by
     * {@link #publishInput(AudioBlock)}, which delegates there. Hoisted into a
     * constant so no lambda is allocated per published block
     * (story 311). Returning {@code false} tells
     * {@link SubmissionPublisher#offer(Object, java.util.function.BiPredicate)}
     * to drop the item rather than retry.
     */
    private static final BiPredicate<Flow.Subscriber<? super AudioBlock>, AudioBlock>
            DROP_INPUT_BLOCK = (subscriber, droppedBlock) -> false;

    /**
     * Drop handler shared by every {@link #publishDeviceEvent(AudioDeviceEvent)}
     * call; hoisted for the same reason as {@link #DROP_INPUT_BLOCK}.
     */
    private static final BiPredicate<Flow.Subscriber<? super AudioDeviceEvent>, AudioDeviceEvent>
            DROP_DEVICE_EVENT = (subscriber, droppedEvent) -> false;

    private volatile SubmissionPublisher<AudioBlock> publisher;
    private volatile SubmissionPublisher<AudioDeviceEvent> devicePublisher;
    private volatile boolean open;
    private volatile AudioFormat format;
    private volatile int bufferFrames;

    AudioBackendSupport() {
        this(new SubmissionPublisher<>(), new SubmissionPublisher<>());
    }

    /** Test seam for deterministically exercising publisher-close races. */
    AudioBackendSupport(SubmissionPublisher<AudioBlock> publisher,
                        SubmissionPublisher<AudioDeviceEvent> devicePublisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.devicePublisher = Objects.requireNonNull(
                devicePublisher, "devicePublisher must not be null");
    }

    synchronized void markOpen(AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(format, "format must not be null");
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
        }
        if (open) {
            throw new IllegalStateException("backend already has an open stream");
        }
        // close() completes a stream's publishers, as required by
        // AudioBackend. A later close-then-open starts a fresh stream and must
        // therefore expose fresh publishers rather than silently discard ASIO
        // callbacks on the already-completed instances.
        if (publisher.isClosed()) {
            publisher = new SubmissionPublisher<>();
        }
        if (devicePublisher.isClosed()) {
            devicePublisher = new SubmissionPublisher<>();
        }
        this.format = format;
        this.bufferFrames = bufferFrames;
        this.open = true;
    }

    boolean isOpen() {
        return open;
    }

    AudioFormat format() {
        return format;
    }

    int bufferFrames() {
        return bufferFrames;
    }

    /** Clears stream state after a native open rolled back. */
    void markClosed() {
        open = false;
        format = null;
        bufferFrames = 0;
    }

    Flow.Publisher<AudioBlock> inputBlocks() {
        return publisher;
    }

    Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return devicePublisher;
    }

    /**
     * Publishes a device event without ever blocking the caller.
     *
     * <p>Deliberately <em>not</em> gated on the open flag: story 218's
     * driver-initiated reset and format-change notifications must still reach
     * subscribers while the backend is opening or tearing down, which is
     * exactly when a driver sends them.</p>
     */
    void publishDeviceEvent(AudioDeviceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // Capture the swappable publisher reference exactly once: markOpen()
        // may replace it concurrently after a close-then-open cycle.
        SubmissionPublisher<AudioDeviceEvent> current = devicePublisher;
        if (current.isClosed()) {
            return;
        }
        // Use offer() with a drop handler instead of submit() to avoid
        // blocking under backpressure — this may be called from a native
        // callback thread (e.g. ASIO's asioMessage) that must not stall.
        offerUnlessClosed(current, event, DROP_DEVICE_EVENT);
    }

    /**
     * Publishes a captured input block without ever blocking the caller.
     *
     * <p>Story 311: for the ASIO backend this runs on the dedicated
     * {@code asio-input-drain} thread rather than the driver's real-time
     * thread, but it must still never stall — a stalled drain thread backs up
     * into the callback's capture ring. The previous
     * {@code publisher.submit(...)} blocks under back-pressure and was
     * therefore unusable. {@code offer(...)} with a drop handler applies
     * exactly the rationale already documented on
     * {@link #publishDeviceEvent(AudioDeviceEvent)}: a slow subscriber loses
     * a block instead of stalling capture.</p>
     *
     * <p>The guard checks both the open flag <em>and</em> the publisher's
     * closed state as a cheap fast path. Because {@link #close()} can still win
     * between that check and {@link SubmissionPublisher#offer},
     * {@link #offerUnlessClosed(SubmissionPublisher, Object, BiPredicate)}
     * handles the documented close exception at the operation itself.</p>
     *
     * <p>Publishes into whatever the CURRENT publisher is, read exactly once:
     * {@link #markOpen} may replace it concurrently after a close-then-open
     * cycle. That is the right target for a caller that its own stream's
     * teardown stops and joins before this support's {@link #close()} runs.
     * The ASIO drain thread is one: it is per-stream, not per-backend —
     * {@code AsioBackend.open} constructs the {@code AsioBufferSwitchShim}
     * whose constructor starts {@code asio-input-drain}, and
     * {@code AsioBackend.close()} calls {@code bridge.close()}, whose
     * {@code shutDown} unparks and joins that thread (bounded by
     * {@code DRAIN_SHUTDOWN_TIMEOUT_MILLIS}), BEFORE it calls
     * {@code support.close()}. The overload's other production caller,
     * {@code MockAudioBackend.pumpInput}, needs no such guarantee: it
     * publishes synchronously on its caller's thread, guarded by
     * {@link #isOpen()}. A thread that may still be publishing after its
     * stream's close has completed — {@link JavaxSoundBackend}'s capture
     * thread, blocked in a non-interruptible read — must instead pin that
     * stream's publisher and go through
     * {@link #publishInput(SubmissionPublisher, AudioBlock)}; see there.</p>
     *
     * @param block the captured block; must not be null
     */
    void publishInput(AudioBlock block) {
        publishInput(publisher, block);
    }

    /**
     * The CURRENT stream's input publisher, for a capture thread to pin
     * before it starts and publish into through
     * {@link #publishInput(SubmissionPublisher, AudioBlock)} for the rest of
     * its life (story 316 review).
     *
     * <p>Meant to be read between {@link #markOpen} and the start of the
     * thread that will publish for that stream. Read at any other time it may
     * name a publisher {@link #close()} has already completed.</p>
     *
     * @return the publisher {@link #inputBlocks()} currently hands out
     */
    SubmissionPublisher<AudioBlock> currentInputPublisher() {
        return publisher;
    }

    /**
     * Publishes a captured input block into ONE specific stream's publisher,
     * without ever blocking the caller (story 316 review).
     *
     * <p>This is the stream-isolation half of the capture-thread contract.
     * {@link JavaxSoundBackend}'s capture thread sits in
     * {@link javax.sound.sampled.TargetDataLine#read}, which is not
     * interruptible and can return one last partial block once its line is
     * closed under it. If that thread published into the swappable
     * {@code publisher} field, a close-then-open that raced ahead of it would
     * hand it the NEXT stream's publisher, and the stale block would land in
     * the new recording. So the thread pins the instance
     * {@link #currentInputPublisher()} returned when it started and publishes
     * only into that.</p>
     *
     * <p>Why the pinned instance can never reach a later stream:
     * {@link #close()} is {@code synchronized} and calls
     * {@link SubmissionPublisher#close()} on the instance the field held; a
     * closed {@code SubmissionPublisher} stays closed for good.
     * {@link #markOpen} is {@code synchronized} on the same monitor and only
     * ever installs a brand-new instance once it finds the field's instance
     * closed. A thread still holding the OLD instance can therefore only ever
     * offer into a publisher that is already closed, which the
     * {@code stream.isClosed()} fast path drops and — should {@code close()}
     * win between that check and the offer —
     * {@link #offerUnlessClosed(SubmissionPublisher, Object, BiPredicate)}
     * drops at the operation itself. Nothing in THIS class serializes that
     * offer against {@link #markOpen} or {@link #close()}: the publish path
     * never enters the support's monitor. This class puts no lock on it;
     * {@link SubmissionPublisher#offer} takes its own internal lock and may
     * dispatch to its executor, exactly as on the pre-existing
     * single-argument path — which is why publishing
     * already lives on a drain thread rather than in the real-time
     * callback.</p>
     *
     * <p>Same drop-instead-of-block {@code offer} and the same open-flag guard
     * as {@link #publishInput(AudioBlock)}, which delegates here with the
     * current field.</p>
     *
     * @param stream the publisher of the stream the block belongs to; must
     *               not be null
     * @param block  the captured block; must not be null
     */
    void publishInput(SubmissionPublisher<AudioBlock> stream, AudioBlock block) {
        Objects.requireNonNull(stream, "stream must not be null");
        Objects.requireNonNull(block, "block must not be null");
        if (!open || stream.isClosed()) {
            return;
        }
        offerUnlessClosed(stream, block, DROP_INPUT_BLOCK);
    }

    /**
     * Offers an item without allowing a concurrent {@link #close()} to leak
     * {@link IllegalStateException} onto a backend callback thread.
     *
     * <p>The earlier {@link SubmissionPublisher#isClosed()} checks remain a
     * cheap fast path, but they cannot make the subsequent offer atomic with
     * close. Serializing the operations would block the real-time caller, so
     * the close race is handled at the non-blocking operation itself. An
     * {@code IllegalStateException} from an open publisher is rethrown rather
     * than hidden.</p>
     */
    private static <T> void offerUnlessClosed(
            SubmissionPublisher<T> publisher,
            T item,
            BiPredicate<Flow.Subscriber<? super T>, T> dropHandler) {
        try {
            publisher.offer(item, dropHandler);
        } catch (IllegalStateException closedRace) {
            if (!publisher.isClosed()) {
                throw closedRace;
            }
        }
    }

    void validateOutgoing(AudioBlock block) {
        Objects.requireNonNull(block, "block must not be null");
        AudioFormat currentFormat = format;
        if (currentFormat != null && block.channels() != currentFormat.channels()) {
            throw new IllegalArgumentException(
                    "block channels (" + block.channels()
                            + ") does not match opened channels ("
                            + currentFormat.channels() + ")");
        }
    }

    @Override
    public synchronized void close() {
        markClosed();
        publisher.close();
        devicePublisher.close();
    }

    /**
     * Checks whether a native shared library is discoverable via FFM's
     * {@link SymbolLookup}. Used by native backends to probe availability
     * without actually invoking any symbol — a safe, side-effect-free check.
     *
     * @param libraryNames candidate library names to try in order
     *                     (for example {@code "jack", "libjack.so.0"})
     * @return true if any candidate loads successfully
     */
    static boolean nativeLibraryAvailable(String... libraryNames) {
        for (String candidate : libraryNames) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup.libraryLookup(candidate, arena);
                return true;
            } catch (UnsatisfiedLinkError | RuntimeException probeFailure) {
                // Not found — try the next candidate.
            }
        }
        return false;
    }
}
