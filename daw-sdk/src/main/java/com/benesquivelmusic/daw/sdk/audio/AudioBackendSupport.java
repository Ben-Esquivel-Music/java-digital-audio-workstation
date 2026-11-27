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
 * {@link AudioFormat}, the open/closed flag, and the device-event publisher,
 * which is the backend's rather than one stream's and outlives a stream's
 * {@link #close()} (story 327).
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
     * {@link #publishInput(AudioBlock)}, which delegates there:
     * {@link InputBlockLossListener#reportDrop}. It tells the subscriber that
     * dropped the block, when that subscriber is a listener (story 338, ruling
     * E208), and never asks
     * {@link SubmissionPublisher#offer(Object, java.util.function.BiPredicate)}
     * to retry. Hoisted as a non-capturing method reference, so no handler is
     * allocated per published block (story 311).
     */
    private static final BiPredicate<Flow.Subscriber<? super AudioBlock>, AudioBlock>
            DROP_INPUT_BLOCK = InputBlockLossListener::reportDrop;

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
        // close() completes the stream's input publisher, as required by
        // AudioBackend. A later close-then-open starts a fresh stream and must
        // therefore expose a fresh publisher rather than silently discard ASIO
        // callbacks on the already-completed instance. The device-event
        // publisher outlives a close (see close()); it is replaced only when
        // a test handed in one that was closed.
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

    /**
     * Clears stream state and keeps the input publisher, for a rollback that
     * started no thread publishing into it (Java Sound's). A rollback that did
     * must {@link #close()} instead (story 338, ruling E215).
     */
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
     * cycle. Only a caller that publishes synchronously on its own thread,
     * guarded by {@link #isOpen()}, may use it: {@code MockAudioBackend.pumpInput}
     * is the one. Every capture or drain thread can outlive its stream's close
     * when the bounded join of that close times out —
     * {@link JavaxSoundBackend}'s capture thread, blocked in a
     * non-interruptible read, and ASIO's {@code asio-input-drain}, whose
     * {@code shutDown} join is bounded by
     * {@code DRAIN_SHUTDOWN_TIMEOUT_MILLIS} — so each pins its stream's
     * publisher before it starts and publishes through
     * {@link #publishInput(SubmissionPublisher, AudioBlock)} (story 338,
     * ruling E215); see there.</p>
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
     * thread that will publish for that stream, as {@link JavaxSoundBackend}
     * does before it starts its capture thread and the
     * {@code AsioBufferSwitchShim} constructor does before it starts its
     * drain. Read at any other time it may name a publisher {@link #close()}
     * has already completed.</p>
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
     * callback. The offer also runs the drop handler under that lock, and so
     * the {@link InputBlockLossListener#inputBlockDropped} of each subscriber
     * that dropped the block.</p>
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
     *
     * @return what {@link SubmissionPublisher#offer} returned (negative when
     *         at least one subscriber dropped the item), or {@code 0} when a
     *         concurrent close won and nothing was offered
     */
    private static <T> int offerUnlessClosed(
            SubmissionPublisher<T> publisher,
            T item,
            BiPredicate<Flow.Subscriber<? super T>, T> dropHandler) {
        try {
            return publisher.offer(item, dropHandler);
        } catch (IllegalStateException closedRace) {
            if (!publisher.isClosed()) {
                throw closedRace;
            }
            return 0;
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

    /**
     * Closes the stream: clears its state and completes its input-block
     * publisher. The device-event publisher is deliberately left open — the
     * host's device events are a feed of the backend, not of one stream, and
     * the hot-unplug reaction depends on that (story 327): the loss of the
     * device of an open stream closes that stream, and the device's return is
     * published while no stream is open; a feed completed by that close could
     * never deliver it, and {@link #publishDeviceEvent} would drop it. The
     * device-event publisher is never completed: it is collected with this
     * support, and a subscriber that must stop before then cancels its
     * subscription, as {@code DefaultAudioEngineController} does on rebind
     * and shutdown.
     */
    @Override
    public synchronized void close() {
        markClosed();
        publisher.close();
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
