package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes the set of audio buffer sizes (in sample frames) a driver
 * actually accepts on a particular device.
 *
 * <p>The shape mirrors Steinberg's
 * {@code ASIOGetBufferSize(min, max, preferred, granularity)} four-tuple,
 * which is the most expressive of the four professional audio host APIs
 * the DAW targets:</p>
 * <ul>
 *   <li><b>ASIO</b> — reports the four values directly.</li>
 *   <li><b>WASAPI</b> — exclusive mode picks a power-of-two range from
 *       {@code IAudioClient::GetDevicePeriod}; shared mode is fixed at
 *       the OS mixer's period (min == max == preferred,
 *       granularity == 0).</li>
 *   <li><b>CoreAudio</b> — exposes
 *       {@code kAudioDevicePropertyBufferFrameSizeRange} (min/max) and a
 *       preferred value via {@code kAudioDevicePropertyBufferFrameSize}.
 *       Granularity is treated as {@code 1}.</li>
 *   <li><b>JACK</b> — the JACK server picks one server-wide buffer
 *       size; min == max == preferred, granularity == 0.</li>
 * </ul>
 *
 * <p>The Audio Settings dialog uses {@link #expandedSizes()} to enumerate
 * the discrete dropdown items the user can pick from. When
 * {@code granularity > 0} the dropdown contains
 * {@code min, min+granularity, min+2*granularity, …, max}. When
 * {@code granularity == 0} only the singleton {@code preferred} is
 * returned (matching JACK / WASAPI shared semantics).</p>
 *
 * @param min         smallest buffer size the driver will accept (frames; must be positive)
 * @param max         largest buffer size the driver will accept (frames; must be {@code >= min})
 * @param preferred   the driver's preferred / current buffer size (frames; must be in {@code [min, max]})
 * @param granularity step size between successive accepted buffer sizes
 *                    ({@code 0} means the singleton {@code preferred} is the only allowed value;
 *                    {@code -1} is the ASIO power-of-two sentinel — the driver accepts every
 *                    power of two between {@code min} and {@code max} inclusive;
 *                    all other values must be {@code >= 0})
 */
public record BufferSizeRange(int min, int max, int preferred, int granularity) {

    /**
     * Sentinel granularity value used by ASIO drivers to indicate that
     * the accepted buffer sizes are powers of two between {@code min}
     * and {@code max}. {@link #expandedSizes()} and {@link #accepts(int)}
     * expand this into an explicit power-of-two ladder.
     */
    public static final int POWER_OF_TWO_GRANULARITY = -1;

    /**
     * The historical power-of-two buffer-size ladder that the dialog
     * showed before story 213 introduced driver-reported ranges. Used
     * by {@link #DEFAULT_RANGE} to preserve backwards compatibility for
     * backends that have not yet overridden
     * {@link AudioBackend#bufferSizeRange(DeviceId)}.
     */
    private static final List<Integer> HISTORICAL_POWER_OF_TWO =
            List.of(32, 64, 128, 256, 512, 1024, 2048);

    /**
     * A safe, OS-independent default used when a backend cannot report
     * buffer-size capabilities (for example because it is not yet wired
     * to its native driver, or because the device is not opened).
     * {@link #expandedSizes()} returns exactly the historical
     * power-of-two ladder {@code {32, 64, 128, 256, 512, 1024, 2048}}
     * so persisted settings and any code still using
     * {@link BufferSize#fromFrames(int)} continue to work.
     */
    public static final BufferSizeRange DEFAULT_RANGE =
            new BufferSizeRange(32, 2048, 256, 0);

    /**
     * Compact constructor that validates the four-tuple invariants
     * enforced by every native driver.
     *
     * @throws IllegalArgumentException if any field violates its constraint
     */
    public BufferSizeRange {
        if (min <= 0) {
            throw new IllegalArgumentException("min must be positive: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "max (" + max + ") must be >= min (" + min + ")");
        }
        if (preferred < min || preferred > max) {
            throw new IllegalArgumentException(
                    "preferred (" + preferred + ") must be in [" + min + ", " + max + "]");
        }
        if (granularity < POWER_OF_TWO_GRANULARITY) {
            throw new IllegalArgumentException(
                    "granularity must be >= " + POWER_OF_TWO_GRANULARITY + ": " + granularity);
        }
    }

    /**
     * Convenience factory for backends that only support a single,
     * fixed buffer size (JACK, WASAPI shared mode).
     *
     * @param fixed the only buffer size the device accepts (frames)
     * @return a singleton range with {@code min == max == preferred == fixed}
     *         and {@code granularity == 0}
     */
    public static BufferSizeRange singleton(int fixed) {
        return new BufferSizeRange(fixed, fixed, fixed, 0);
    }

    /**
     * Returns the discrete set of buffer sizes the dropdown should
     * present to the user, in ascending order.
     *
     * <p>When this is the {@link #DEFAULT_RANGE} the returned list is
     * the historical power-of-two ladder
     * {@code {32, 64, 128, 256, 512, 1024, 2048}} so persisted
     * settings and code using {@link BufferSize#fromFrames(int)}
     * continue to work.</p>
     *
     * <p>When {@code granularity == 0} the returned list contains only
     * {@code preferred}. Otherwise it contains every multiple of
     * {@code granularity} starting at {@code min} and ending at
     * {@code max} inclusive — exactly the menu a user expects from a
     * driver that reports e.g. {@code BufferSizeRange(64, 512, 128, 64)}.
     * If {@code max} is not on the regular ladder it is appended so the
     * user can always reach the maximum the driver supports.</p>
     *
     * @return an unmodifiable list of allowed buffer sizes (never empty)
     */
    public List<Integer> expandedSizes() {
        // The DEFAULT_RANGE uses granularity=0 but returns the historical
        // power-of-two ladder instead of a singleton, because multiple
        // values are valid yet they don't follow a uniform step.
        if (this.equals(DEFAULT_RANGE)) {
            return HISTORICAL_POWER_OF_TWO;
        }
        if (granularity == 0) {
            return List.of(preferred);
        }
        if (granularity == POWER_OF_TWO_GRANULARITY) {
            List<Integer> out = new ArrayList<>();
            for (int n = Integer.highestOneBit(min); n <= max; n <<= 1) {
                if (n >= min) {
                    out.add(n);
                }
            }
            if (out.isEmpty()) {
                out.add(preferred);
            }
            return Collections.unmodifiableList(out);
        }
        List<Integer> out = new ArrayList<>();
        for (int n = min; n <= max; n += granularity) {
            out.add(n);
        }
        // Guarantee max is in the list when (max - min) is not a multiple of granularity.
        if (out.isEmpty() || out.get(out.size() - 1) != max) {
            out.add(max);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns {@code true} if the given frame count is one of the
     * driver-allowed buffer sizes — i.e. it appears in the list
     * returned by {@link #expandedSizes()}. For granular ranges this
     * means the value falls within {@code [min, max]} and is either on
     * the regular {@code min + n*granularity} ladder <b>or</b> equals
     * {@code max} (which {@link #expandedSizes()} includes even when
     * it is not on the regular ladder).
     *
     * @param frames a candidate buffer size in sample frames
     * @return true when the driver would accept {@code frames}
     */
    public boolean accepts(int frames) {
        if (this.equals(DEFAULT_RANGE)) {
            return HISTORICAL_POWER_OF_TWO.contains(frames);
        }
        if (granularity == 0) {
            return frames == preferred;
        }
        if (frames < min || frames > max) {
            return false;
        }
        if (granularity == POWER_OF_TWO_GRANULARITY) {
            return frames > 0 && (frames & (frames - 1)) == 0;
        }
        // Accept both the regular ladder and the max value (which
        // expandedSizes() appends when max is not on the ladder).
        return ((frames - min) % granularity) == 0 || frames == max;
    }
}
