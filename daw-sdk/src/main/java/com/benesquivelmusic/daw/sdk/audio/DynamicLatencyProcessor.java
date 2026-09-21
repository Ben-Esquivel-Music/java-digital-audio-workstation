package com.benesquivelmusic.daw.sdk.audio;

/**
 * Opts into live host delay compensation when parameters change processor latency.
 *
 * <p>{@link #getLatencySamples()} must be safe to read from any thread while
 * processing continues, without invoking thread-confined native APIs. Publish
 * the current latency through a volatile field or equivalent visibility mechanism.
 * Hosts may poll it off the audio thread to prepare compensation buffers.</p>
 */
public interface DynamicLatencyProcessor extends AudioProcessor {
    @Override
    int getLatencySamples();
}
