package com.benesquivelmusic.daw.sdk.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

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

    private final SubmissionPublisher<AudioBlock> publisher = new SubmissionPublisher<>();
    private final SubmissionPublisher<AudioDeviceEvent> devicePublisher = new SubmissionPublisher<>();
    private volatile boolean open;
    private volatile AudioFormat format;
    private volatile int bufferFrames;

    void markOpen(AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(format, "format must not be null");
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
        }
        if (open) {
            throw new IllegalStateException("backend already has an open stream");
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

    Flow.Publisher<AudioBlock> inputBlocks() {
        return publisher;
    }

    Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return devicePublisher;
    }

    void publishDeviceEvent(AudioDeviceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        devicePublisher.submit(event);
    }

    void publishInput(AudioBlock block) {
        Objects.requireNonNull(block, "block must not be null");
        if (open) {
            publisher.submit(block);
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
    public void close() {
        open = false;
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
