package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;

/**
 * Decodes one {@link AssetReference} for the clip rehydrator — the seam
 * between {@link ClipRehydrator}'s scheduling and the file readers.
 * Production uses {@link FileSourceDecoder#INSTANCE}; tests substitute a
 * decoder that records, holds or fails.
 *
 * <p>Called on decode threads, never on the owner thread; one call per
 * source per session. Implementations must not touch the project.</p>
 */
interface SourceDecoder {

    /**
     * Estimates the heap bytes decoding {@code source} needs at peak, for
     * the session's memory budget (decision D10). Never throws: a source
     * whose size cannot be judged costs what can be judged (0 at worst),
     * and {@link #decode} reports what is wrong with it.
     *
     * @param source            the source; {@linkplain AssetReference#isOpenable() openable}
     * @param projectSampleRate the project rate, in Hz
     * @return the estimated cost in bytes, not negative
     */
    long estimateCost(AssetReference source, int projectSampleRate);

    /**
     * Decodes {@code source}.
     *
     * @param source            the source; {@linkplain AssetReference#isOpenable() openable}
     * @param projectSampleRate the project rate, in Hz
     * @return the decoded audio
     * @throws IOException          if a file cannot be read
     * @throws InterruptedException if the decoding thread is interrupted
     */
    DecodedAudio decode(AssetReference source, int projectSampleRate) throws IOException, InterruptedException;
}
