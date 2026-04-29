package com.benesquivelmusic.daw.sdk.audio;

/**
 * Semantic classification of a hardware audio channel as reported by the
 * driver, used to drive the icon shown next to a channel name in the I/O
 * routing dropdowns (story 199).
 *
 * <p>Multi-channel hardware drivers report semantic channel names that are
 * far more useful than generic {@code "Input N"}: {@code "Mic/Line 1"},
 * {@code "Hi-Z Inst 3"}, {@code "S/PDIF L"}, {@code "Main Out L"},
 * {@code "Phones 1 L"}. {@code ChannelKind} is the small enum-shaped
 * sealed hierarchy the rendering code dispatches on to pick the right
 * glyph.</p>
 *
 * <p>Kinds are inferred from the driver-reported display name by
 * {@link ChannelKindHeuristics#infer(String)} when the backend does not
 * report the kind explicitly; backends that <em>do</em> know the kind
 * (for example a future binding to ASIO's {@code ASIOChannelInfo.type})
 * can construct {@code AudioChannelInfo} with a specific kind directly.</p>
 *
 * <p>Permitted variants:</p>
 * <ul>
 *   <li>{@link Mic} — microphone-level input (XLR, Mic/Line preamp).</li>
 *   <li>{@link Line} — line-level analog input or output.</li>
 *   <li>{@link Instrument} — Hi-Z / instrument-level input (1/4" guitar).</li>
 *   <li>{@link Digital} — S/PDIF, ADAT, AES, Word Clock, or other
 *       digital pass-through.</li>
 *   <li>{@link Monitor} — main monitor / control-room output.</li>
 *   <li>{@link Headphone} — headphone / cue / phones output.</li>
 *   <li>{@link Generic} — anything that does not match the heuristic
 *       table; the raw name is preserved verbatim in
 *       {@link AudioChannelInfo#displayName()}.</li>
 * </ul>
 */
public sealed interface ChannelKind {

    /** Microphone-level input. */
    record Mic() implements ChannelKind { public static final Mic INSTANCE = new Mic(); }

    /** Line-level analog input or output. */
    record Line() implements ChannelKind { public static final Line INSTANCE = new Line(); }

    /** Hi-Z / instrument-level input. */
    record Instrument() implements ChannelKind {
        public static final Instrument INSTANCE = new Instrument();
    }

    /** S/PDIF, ADAT, AES, or other digital pass-through. */
    record Digital() implements ChannelKind {
        public static final Digital INSTANCE = new Digital();
    }

    /** Main monitor / control-room output. */
    record Monitor() implements ChannelKind {
        public static final Monitor INSTANCE = new Monitor();
    }

    /** Headphone / cue / phones output. */
    record Headphone() implements ChannelKind {
        public static final Headphone INSTANCE = new Headphone();
    }

    /** Anything that does not match the heuristic table. */
    record Generic() implements ChannelKind {
        public static final Generic INSTANCE = new Generic();
    }
}
