package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;

import java.util.List;
import java.util.Map;

/**
 * Built-in factory {@link TrackTemplate}s and {@link ChannelStripPreset}s that
 * ship with the DAW, covering the most common recording/mixing scenarios.
 *
 * <p>These defaults are always available regardless of what the user has saved
 * to disk. The application populates the "New Track from Template" menu with
 * the concatenation of {@link #factoryTemplates()} and any user-authored
 * templates loaded from {@link TrackTemplateStore}.</p>
 *
 * <p>Factory templates reference the default return bus name
 * {@code "Reverb Return"} that {@link com.benesquivelmusic.daw.core.mixer.Mixer}
 * creates automatically.</p>
 */
public final class TrackTemplateFactory {

    /** The name of the reverb return bus created by default in every mixer. */
    public static final String REVERB_RETURN_NAME = "Reverb Return";

    private TrackTemplateFactory() {
        // utility class
    }

    /**
     * Returns the list of built-in factory track templates.
     *
     * @return the factory templates in stable menu order
     */
    public static List<TrackTemplate> factoryTemplates() {
        return List.of(
                vocalTrack(),
                drumBus(),
                guitarTrack(),
                synthTrack());
    }

    /**
     * Returns the list of built-in factory channel strip presets, which mirror
     * the mixer side of the factory track templates.
     *
     * @return the factory presets in stable menu order
     */
    public static List<ChannelStripPreset> factoryPresets() {
        return List.of(
                new ChannelStripPreset("Vocal Channel", vocalTrack().inserts(), vocalTrack().sends(), 0.85, 0.0),
                new ChannelStripPreset("Drum Bus Channel", drumBus().inserts(), drumBus().sends(), 0.85, 0.0),
                new ChannelStripPreset("Guitar Channel", guitarTrack().inserts(), guitarTrack().sends(), 0.8, 0.0),
                new ChannelStripPreset("Synth Channel", synthTrack().inserts(), synthTrack().sends(), 0.8, 0.0));
    }

    // ── Factory templates ───────────────────────────────────────────────────

    /** Vocal track: compressor, EQ, and a reverb send. */
    public static TrackTemplate vocalTrack() {
        List<InsertEffectSpec> inserts = List.of(
                InsertEffectSpec.of(InsertEffectType.COMPRESSOR, Map.of(
                        0, -18.0, // threshold dB
                        1, 3.0,   // ratio
                        2, 5.0,   // attack ms
                        3, 80.0,  // release ms
                        5, 3.0    // makeup gain dB
                )),
                InsertEffectSpec.ofDefaults(InsertEffectType.PARAMETRIC_EQ));
        List<SendSpec> sends = List.of(
                new SendSpec(REVERB_RETURN_NAME, 0.25, SendMode.POST_FADER));
        return new TrackTemplate(
                "Vocal Track",
                TrackType.AUDIO,
                "Vocal",
                inserts,
                sends,
                0.85,
                0.0,
                TrackColor.PINK,
                InputRouting.DEFAULT_STEREO,
                OutputRouting.MASTER);
    }

    /** Drum bus: compressor, EQ, and limiter for cohesive glue. */
    public static TrackTemplate drumBus() {
        List<InsertEffectSpec> inserts = List.of(
                InsertEffectSpec.of(InsertEffectType.COMPRESSOR, Map.of(
                        0, -12.0,
                        1, 4.0,
                        2, 10.0,
                        3, 120.0,
                        5, 2.0
                )),
                InsertEffectSpec.ofDefaults(InsertEffectType.PARAMETRIC_EQ),
                InsertEffectSpec.of(InsertEffectType.LIMITER, Map.of(
                        0, -1.0,  // ceiling dB
                        1, 0.3,   // attack ms
                        2, 100.0  // release ms
                )));
        return new TrackTemplate(
                "Drum Bus",
                TrackType.AUDIO,
                "Drum Bus",
                inserts,
                List.of(),
                0.85,
                0.0,
                TrackColor.ORANGE,
                InputRouting.DEFAULT_STEREO,
                OutputRouting.MASTER);
    }

    /** Guitar track: EQ and a reverb send. */
    public static TrackTemplate guitarTrack() {
        List<InsertEffectSpec> inserts = List.of(
                InsertEffectSpec.ofDefaults(InsertEffectType.PARAMETRIC_EQ));
        List<SendSpec> sends = List.of(
                new SendSpec(REVERB_RETURN_NAME, 0.2, SendMode.POST_FADER));
        return new TrackTemplate(
                "Guitar Track",
                TrackType.AUDIO,
                "Guitar",
                inserts,
                sends,
                0.8,
                0.0,
                TrackColor.AMBER,
                InputRouting.DEFAULT_STEREO,
                OutputRouting.MASTER);
    }

    /** Synth track: EQ and chorus for width. */
    public static TrackTemplate synthTrack() {
        List<InsertEffectSpec> inserts = List.of(
                InsertEffectSpec.ofDefaults(InsertEffectType.PARAMETRIC_EQ),
                InsertEffectSpec.of(InsertEffectType.CHORUS, Map.of(
                        0, 1.2,  // rate Hz
                        1, 4.0,  // depth ms
                        2, 12.0, // base delay ms
                        3, 0.3   // mix
                )));
        return new TrackTemplate(
                "Synth Track",
                TrackType.MIDI,
                "Synth",
                inserts,
                List.of(),
                0.8,
                0.0,
                TrackColor.PURPLE,
                InputRouting.DEFAULT_STEREO,
                OutputRouting.MASTER);
    }
}
