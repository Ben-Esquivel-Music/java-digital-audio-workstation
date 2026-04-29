package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Driver-reported information about a single hardware audio channel,
 * surfaced to the per-track input / output routing dropdowns
 * (story 199 — "Driver-Reported Channel Names in I/O Routing Dropdowns").
 *
 * <p>Multi-channel hardware drivers report semantic channel names that are
 * far more useful than generic {@code "Input N"}:
 * {@code "Mic/Line 1"}, {@code "Hi-Z Inst 3"}, {@code "S/PDIF L"},
 * {@code "Main Out L"}, {@code "Phones 1 L"}. When the user assigns a kick
 * mic to {@code "Mic 3"} rather than {@code "Input 3"}, routing mistakes
 * drop sharply and headphone-cue / S/PDIF passthrough conventions become
 * discoverable from the dropdown rather than the printed manual.</p>
 *
 * <p>Each backend has a structured source for these names:
 * <ul>
 *   <li>ASIO — {@code ASIOGetChannelInfo(channel, isInput) → ASIOChannelInfo{
 *       name, type, isActive }}</li>
 *   <li>CoreAudio — {@code kAudioObjectPropertyElementName} per channel</li>
 *   <li>WASAPI — per-channel {@code IPart::GetName} for capture and render
 *       endpoints</li>
 *   <li>JACK — port aliases</li>
 * </ul>
 *
 * @param index       zero-based index of this channel within the device's
 *                    input or output channel list (must be {@code &ge; 0})
 * @param displayName the driver-reported human-readable name; never null,
 *                    never blank — backends must substitute a generic
 *                    {@code "Input N"} / {@code "Output N"} when the
 *                    driver returns an empty string
 * @param kind        the semantic kind of the channel; never null —
 *                    use {@link ChannelKind.Generic} when no better
 *                    classification is available
 * @param active      {@code true} when the channel is enabled in the
 *                    driver (ASIO {@code isActive}); {@code false} when
 *                    the driver has reported the channel as disabled
 *                    (e.g. turned off in the driver's own panel) — the
 *                    UI greys these entries out and tooltips
 *                    "Disabled in driver"
 */
public record AudioChannelInfo(int index, String displayName, ChannelKind kind, boolean active) {

    public AudioChannelInfo {
        if (index < 0) {
            throw new IllegalArgumentException(
                    "index must be >= 0: " + index);
        }
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        Objects.requireNonNull(kind, "kind must not be null");
    }

    /**
     * Convenience constructor for active channels with kind inferred from
     * the display name via {@link ChannelKindHeuristics#infer(String)}.
     *
     * @param index       zero-based channel index
     * @param displayName driver-reported name
     */
    public AudioChannelInfo(int index, String displayName) {
        this(index, displayName, ChannelKindHeuristics.infer(displayName), true);
    }

    /**
     * Convenience constructor that only specifies activity, inferring the
     * kind from the display name.
     *
     * @param index       zero-based channel index
     * @param displayName driver-reported name
     * @param active      whether the driver reports this channel as enabled
     */
    public AudioChannelInfo(int index, String displayName, boolean active) {
        this(index, displayName, ChannelKindHeuristics.infer(displayName), active);
    }
}
