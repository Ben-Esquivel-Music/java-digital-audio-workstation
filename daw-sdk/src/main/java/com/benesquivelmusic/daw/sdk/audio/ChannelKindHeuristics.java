package com.benesquivelmusic.daw.sdk.audio;

import java.util.regex.Pattern;

/**
 * Heuristic mapping from a driver-reported channel name to a
 * {@link ChannelKind}, used by backends (notably
 * {@link AsioBackend}) when the native API does not carry an explicit
 * kind tag — story 199.
 *
 * <p>The patterns mirror the small table called out by the user story:
 * <ul>
 *   <li>{@code /mic|line/i  → Mic | Line}</li>
 *   <li>{@code /inst|hi.?z/i → Instrument}</li>
 *   <li>{@code /spdif|adat|aes/i → Digital}</li>
 *   <li>{@code /main|monitor/i → Monitor}</li>
 *   <li>{@code /phone|headphone/i → Headphone}</li>
 *   <li>otherwise → {@link ChannelKind.Generic} with the raw name preserved</li>
 * </ul>
 *
 * <p>The class is deliberately tiny and stateless so it can be called
 * from any thread (including audio-callback context, although in
 * practice it is only invoked at device-open time).</p>
 */
public final class ChannelKindHeuristics {

    // "Mic" appears inside "Microphone" too, and "Line" inside "Line Out";
    // the order of probes matters, so we go from most specific to least.
    private static final Pattern HEADPHONE = Pattern.compile("(?i)\\b(head\\s*phones?|phones?)\\b");
    private static final Pattern MONITOR = Pattern.compile("(?i)\\b(main|monitor)\\b");
    private static final Pattern DIGITAL = Pattern.compile("(?i)\\b(s/?pdif|adat|aes(/?ebu)?)\\b");
    private static final Pattern INSTRUMENT = Pattern.compile("(?i)\\b(inst(rument)?|hi[\\s\\-_.]?z)\\b");
    private static final Pattern MIC = Pattern.compile("(?i)\\b(mic(rophone)?)\\b");
    private static final Pattern LINE = Pattern.compile("(?i)\\b(line)\\b");

    private ChannelKindHeuristics() {
    }

    /**
     * Classifies the given driver-reported channel name.
     *
     * @param displayName the name reported by the driver; must not be null
     * @return the inferred {@link ChannelKind}; never null. Returns
     *         {@link ChannelKind.Generic#INSTANCE} when no pattern matches.
     */
    public static ChannelKind infer(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("displayName must not be null");
        }
        if (HEADPHONE.matcher(displayName).find()) {
            return ChannelKind.Headphone.INSTANCE;
        }
        if (MONITOR.matcher(displayName).find()) {
            return ChannelKind.Monitor.INSTANCE;
        }
        if (DIGITAL.matcher(displayName).find()) {
            return ChannelKind.Digital.INSTANCE;
        }
        if (INSTRUMENT.matcher(displayName).find()) {
            return ChannelKind.Instrument.INSTANCE;
        }
        // "Mic/Line" should be classified as Mic (preamp can take either,
        // but the channel's primary role is microphone input).
        if (MIC.matcher(displayName).find()) {
            return ChannelKind.Mic.INSTANCE;
        }
        if (LINE.matcher(displayName).find()) {
            return ChannelKind.Line.INSTANCE;
        }
        return ChannelKind.Generic.INSTANCE;
    }
}
