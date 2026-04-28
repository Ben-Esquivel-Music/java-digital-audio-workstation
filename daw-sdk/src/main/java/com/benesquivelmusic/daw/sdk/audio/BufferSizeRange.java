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
 *                    must be {@code >= 0})
 */
public record BufferSizeRange(int min, int max, int preferred, int granularity) {

    /**
     * A safe, OS-independent default used when a backend cannot report
     * buffer-size capabilities (for example because it is not yet wired
     * to its native driver, or because the device is not opened). The
     * traditional power-of-two ladder {@code 32..2048} is expressed as
     * a granular range; the dialog's {@link #expandedSizes()} call still
     * yields the historical menu so behaviour is unchanged for backends
     * that do not override the API.
     */
    public static final BufferSizeRange DEFAULT_RANGE =
            new BufferSizeRange(32, 2048, 256, 32);

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
        if (granularity < 0) {
            throw new IllegalArgumentException(
                    "granularity must not be negative: " + granularity);
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
     * <p>When {@code granularity == 0} the returned list contains only
     * {@code preferred}. Otherwise it contains every multiple of
     * {@code granularity} starting at {@code min} and ending at
     * {@code max} inclusive — exactly the menu a user expects from a
     * driver that reports e.g. {@code BufferSizeRange(64, 512, 128, 64)}.</p>
     *
     * @return an unmodifiable list of allowed buffer sizes (never empty)
     */
    public List<Integer> expandedSizes() {
        if (granularity == 0) {
            return List.of(preferred);
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
     * driver-allowed buffer sizes — i.e. it equals {@code preferred}
     * when the range is a singleton, or it falls within {@code [min, max]}
     * and is a multiple of {@code granularity} measured from {@code min}.
     *
     * @param frames a candidate buffer size in sample frames
     * @return true when the driver would accept {@code frames}
     */
    public boolean accepts(int frames) {
        if (granularity == 0) {
            return frames == preferred;
        }
        if (frames < min || frames > max) {
            return false;
        }
        return ((frames - min) % granularity) == 0;
    }
}
