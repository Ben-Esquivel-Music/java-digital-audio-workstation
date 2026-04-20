package com.benesquivelmusic.daw.core.mixer;

import java.util.Objects;
import java.util.UUID;

/**
 * A single per-track contribution to a {@link CueBus}.
 *
 * <p>Cue sends are the headphone/monitor equivalent of aux sends: each cue send
 * describes how loud and where a given mixer channel appears in a specific
 * performer's monitor mix. Unlike {@link Send}, a cue send targets a
 * dedicated {@link CueBus} (headphone output) rather than a return bus, and
 * supports independent {@code gain}, {@code pan}, and a {@code preFader} flag
 * that determines whether moves on the main fader are reflected in the cue
 * mix (post-fader) or ignored entirely (pre-fader — the usual choice while
 * tracking).</p>
 *
 * <p>Records are immutable; use {@link #withGain(double)}, {@link #withPan(double)},
 * or {@link #withPreFader(boolean)} to obtain modified copies.</p>
 *
 * @param trackId   the id of the track whose channel contributes to the cue mix
 * @param gain      the send level, in linear scale {@code [0.0, 1.0]}
 * @param pan       stereo pan, {@code -1.0} (hard left) to {@code +1.0} (hard right)
 * @param preFader  {@code true} if the send taps the channel audio before the
 *                  channel fader is applied; {@code false} for post-fader
 */
public record CueSend(UUID trackId, double gain, double pan, boolean preFader) {

    public CueSend {
        Objects.requireNonNull(trackId, "trackId must not be null");
        if (gain < 0.0 || gain > 1.0) {
            throw new IllegalArgumentException("gain must be between 0.0 and 1.0: " + gain);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
    }

    /** Returns a copy of this send with the given gain. */
    public CueSend withGain(double newGain) {
        return new CueSend(trackId, newGain, pan, preFader);
    }

    /** Returns a copy of this send with the given pan. */
    public CueSend withPan(double newPan) {
        return new CueSend(trackId, gain, newPan, preFader);
    }

    /** Returns a copy of this send with the given pre-fader flag. */
    public CueSend withPreFader(boolean newPreFader) {
        return new CueSend(trackId, gain, pan, newPreFader);
    }
}
