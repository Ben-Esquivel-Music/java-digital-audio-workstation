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
 * sealed {@link AudioBackend} hierarchy is the public surface.</p>
 */
final class AudioBackendSupport implements AutoCloseable {

    /**
     * Drop handler shared by every {@link #publishInput(AudioBlock)} call.
     * Hoisted into a constant so no lambda is allocated per published block
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

    private volatile SubmissionPublisher<AudioBlock> publisher = new SubmissionPublisher<>();
    private volatile SubmissionPublisher<AudioDeviceEvent> devicePublisher =
            new SubmissionPublisher<>();
    private volatile boolean open;
    private volatile AudioFormat format;
    private volatile int bufferFrames;

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
        current.offer(event, DROP_DEVICE_EVENT);
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
     * closed state: {@link SubmissionPublisher#offer} throws
     * {@code IllegalStateException("Closed")} on a closed publisher with no
     * subscribers, so a publish racing {@link #close()} must be filtered out
     * here rather than construct an exception.</p>
     *
     * @param block the captured block; must not be null
     */
    void publishInput(AudioBlock block) {
        Objects.requireNonNull(block, "block must not be null");
        // Capture the swappable publisher reference exactly once: markOpen()
        // may replace it concurrently after a close-then-open cycle.
        SubmissionPublisher<AudioBlock> current = publisher;
        if (!open || current.isClosed()) {
            return;
        }
        current.offer(block, DROP_INPUT_BLOCK);
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
