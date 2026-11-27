package com.benesquivelmusic.daw.core.audio;

import java.util.Objects;

/**
 * One capture input of the open stream as {@link AudioEngine#captureInputs()}
 * read it (story 338).
 *
 * @param handle          the input's opaque handle: the same object for the
 *                        life of the input, the key a reader tracks it by and
 *                        the one {@link AudioEngine#declareInputStalled} takes
 * @param sourceName      the input device's name
 * @param deliveredBlocks the blocks the device has handed the engine on this
 *                        input so far, counted on arrival whatever their
 *                        content; it only grows for the same handle
 * @param streamInput     whether this is the stream's own input, rather than
 *                        an input opened beside the stream
 */
public record CaptureInputSnapshot(Object handle, String sourceName, long deliveredBlocks, boolean streamInput) {

    public CaptureInputSnapshot {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        if (deliveredBlocks < 0) {
            throw new IllegalArgumentException("deliveredBlocks must not be negative: " + deliveredBlocks);
        }
    }
}
