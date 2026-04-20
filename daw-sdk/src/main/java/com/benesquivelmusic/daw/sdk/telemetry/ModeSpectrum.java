package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable spectrum of rectangular-room normal modes plus the
 * Schroeder transition frequency.
 *
 * <p>Below the Schroeder frequency a small room is dominated by a
 * sparse set of discrete resonances — the modes captured in the
 * {@link #modes()} list. Above the Schroeder frequency the modal
 * density is high enough that the room behaves statistically (the
 * &quot;diffuse field&quot;) and reverberation-time metrics such as
 * RT60 become meaningful. The Schroeder frequency is estimated as
 * <i>f<sub>s</sub></i>&nbsp;≈&nbsp;2000&nbsp;·&nbsp;&radic;(<i>T60</i>&nbsp;/&nbsp;<i>V</i>)
 * with <i>T60</i> in seconds and <i>V</i> in cubic metres.</p>
 *
 * <p>Consumers — the &quot;Room Modes&quot; plot in
 * {@link ModeKind}&#39;s companion UI and the mode-density heatmap on
 * {@code RoomTelemetryDisplay} — render one vertical line per mode
 * (colour-coded by {@link RoomMode#kind()}) plus a dashed vertical at
 * {@code schroederHz}.</p>
 *
 * @param modes        the modes, typically ordered by frequency
 *                     (defensively copied into an unmodifiable list)
 * @param schroederHz  the Schroeder transition frequency in Hz
 */
public record ModeSpectrum(List<RoomMode> modes, double schroederHz) {

    public ModeSpectrum {
        Objects.requireNonNull(modes, "modes must not be null");
        if (!(schroederHz > 0) || Double.isNaN(schroederHz) || Double.isInfinite(schroederHz)) {
            throw new IllegalArgumentException(
                    "schroederHz must be a finite positive number: " + schroederHz);
        }
        for (RoomMode m : modes) {
            Objects.requireNonNull(m, "modes must not contain null elements");
        }
        modes = Collections.unmodifiableList(new ArrayList<>(modes));
    }

    /**
     * Returns only the {@linkplain ModeKind#AXIAL axial} modes.
     * Convenience method for the plot — axial modes are the loudest
     * and most problematic.
     */
    public List<RoomMode> axialModes() {
        return modes.stream().filter(m -> m.kind() == ModeKind.AXIAL).toList();
    }

    /**
     * Returns only the {@linkplain ModeKind#TANGENTIAL tangential} modes.
     */
    public List<RoomMode> tangentialModes() {
        return modes.stream().filter(m -> m.kind() == ModeKind.TANGENTIAL).toList();
    }

    /**
     * Returns only the {@linkplain ModeKind#OBLIQUE oblique} modes.
     */
    public List<RoomMode> obliqueModes() {
        return modes.stream().filter(m -> m.kind() == ModeKind.OBLIQUE).toList();
    }
}
