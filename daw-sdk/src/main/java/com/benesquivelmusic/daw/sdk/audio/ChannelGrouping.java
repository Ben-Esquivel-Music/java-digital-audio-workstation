package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helper that converts a flat list of driver-reported
 * {@link AudioChannelInfo}s into the routing options shown in the
 * per-track I/O routing dropdowns (story 199).
 *
 * <p>Two pieces of logic live here:</p>
 * <ol>
 *   <li><b>Stereo-pair grouping.</b> Consecutive channels that share a
 *       stem name suffixed {@code "L"} / {@code "R"} (e.g.
 *       {@code "Mic 1 L"} + {@code "Mic 1 R"}) are auto-grouped into a
 *       single stereo entry rendered as {@code "Mic 1 (Stereo)"}. The
 *       individual mono variants stay in the list — the user must
 *       explicitly pick one to record mono.</li>
 *   <li><b>Inactive marker.</b> Channels whose
 *       {@link AudioChannelInfo#active() active} flag is {@code false}
 *       (ASIO {@code isActive=false}, e.g. disabled in the driver's own
 *       panel) are returned with {@link Option#active()} = false; the
 *       UI greys those entries and tooltips "Disabled in driver".</li>
 * </ol>
 *
 * <p>Per the user story's non-goals, this heuristic <em>wins</em> over
 * any stereo-pair grouping the driver itself reports — the L/R suffix
 * convention is the single source of truth for now.</p>
 */
public final class ChannelGrouping {

    /** Matches a trailing standalone "L" or "R" suffix (case-insensitive). */
    private static final Pattern LR_SUFFIX =
            Pattern.compile("(?i)^(.*?)\\s*[\\s\\-_]\\s*([LR])\\s*$");

    private ChannelGrouping() {
    }

    /**
     * One entry in a routing dropdown.
     *
     * @param firstChannel zero-based index of the first hardware channel
     * @param channelCount {@code 1} for mono, {@code 2} for an
     *                     L/R-suffixed stereo pair
     * @param displayName  the label rendered in the dropdown — the
     *                     driver name verbatim for mono, or
     *                     {@code "<stem> (Stereo)"} for an auto-grouped pair
     * @param kind         the {@link ChannelKind} of the first channel of
     *                     the pair (consecutive L/R channels share a
     *                     kind in practice)
     * @param active       {@code false} when the driver reports this
     *                     channel (or any channel of the pair) as disabled
     */
    public record Option(int firstChannel, int channelCount, String displayName,
                         ChannelKind kind, boolean active) {
        public Option {
            Objects.requireNonNull(displayName, "displayName must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            if (channelCount < 1 || channelCount > 2) {
                throw new IllegalArgumentException(
                        "channelCount must be 1 or 2: " + channelCount);
            }
            if (firstChannel < 0) {
                throw new IllegalArgumentException(
                        "firstChannel must be >= 0: " + firstChannel);
            }
        }
    }

    /**
     * Builds the dropdown options for the given driver-reported channels.
     *
     * <p>The returned list always preserves the underlying mono entries
     * (so the user can pick a single side of an L/R pair); the stereo
     * pair entry is inserted directly after its second channel for the
     * conventional ordering: {@code "Mic 1 L"}, {@code "Mic 1 R"},
     * {@code "Mic 1 (Stereo)"}.</p>
     *
     * @param channels driver-reported channel infos in their native order;
     *                 must not be null (may be empty)
     * @return an unmodifiable list of dropdown options; never null
     */
    public static List<Option> buildOptions(List<AudioChannelInfo> channels) {
        Objects.requireNonNull(channels, "channels must not be null");
        List<Option> out = new ArrayList<>();
        int i = 0;
        while (i < channels.size()) {
            AudioChannelInfo a = channels.get(i);
            // Always emit the mono option first.
            out.add(new Option(a.index(), 1, a.displayName(), a.kind(), a.active()));

            // Look ahead for an L/R-suffixed neighbour that shares a stem
            // and a consecutive index — that is the only condition for
            // auto-grouping per the user story.
            if (i + 1 < channels.size()) {
                AudioChannelInfo b = channels.get(i + 1);
                if (b.index() == a.index() + 1) {
                    String stem = matchedStereoStem(a.displayName(), b.displayName());
                    if (stem != null) {
                        // Emit the mono right-channel option too.
                        out.add(new Option(b.index(), 1, b.displayName(), b.kind(), b.active()));
                        // Then the auto-grouped stereo entry.
                        out.add(new Option(
                                a.index(),
                                2,
                                stem + " (Stereo)",
                                a.kind(),
                                a.active() && b.active()));
                        i += 2;
                        continue;
                    }
                }
            }
            i++;
        }
        return List.copyOf(out);
    }

    /**
     * Returns the shared stem when {@code aName} ends in "L" and
     * {@code bName} ends in "R" (case-insensitive) <em>and</em> the
     * stems match exactly. Returns {@code null} otherwise.
     */
    private static String matchedStereoStem(String aName, String bName) {
        Matcher ma = LR_SUFFIX.matcher(aName);
        Matcher mb = LR_SUFFIX.matcher(bName);
        if (!ma.matches() || !mb.matches()) {
            return null;
        }
        String stemA = ma.group(1).trim();
        String stemB = mb.group(1).trim();
        String sideA = ma.group(2).toUpperCase();
        String sideB = mb.group(2).toUpperCase();
        if (!stemA.equals(stemB) || stemA.isEmpty()) {
            return null;
        }
        if (!sideA.equals("L") || !sideB.equals("R")) {
            return null;
        }
        return stemA;
    }
}
