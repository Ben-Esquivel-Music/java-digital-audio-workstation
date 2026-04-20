package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Routes a generated metronome click to the main mix bus, the direct-to-hardware
 * side output, and any enabled cue bus contributions — applying the gates and
 * gains defined by a {@link ClickOutput} configuration.
 *
 * <p>Introduced for story 136. The router is agnostic of scheduling: a caller
 * (the audio engine for main-bus playback, the count-in driver for pre-roll,
 * a unit test for validation) generates the click via
 * {@link Metronome#generateClick(boolean)} and hands the buffer to
 * {@link #route(float[][], AudioBackend, CueBusManager)}. All three destinations
 * share the same source buffer, so timing across them is inherently sample-accurate.</p>
 *
 * <h2>Destinations</h2>
 * <ol>
 *   <li><b>Main mix</b> — returned as a separate buffer from
 *       {@link RoutedClick#mainMixBuffer()}, or {@code null} when the metronome
 *       is disabled or {@link ClickOutput#mainMixEnabled()} is {@code false}.
 *       The audio engine sums this buffer into the master bus at the scheduled
 *       beat position.</li>
 *   <li><b>Side output</b> — written directly to
 *       {@link AudioBackend#writeToChannel(int, float[])} on
 *       {@link ClickOutput#hardwareChannelIndex()} at
 *       {@link ClickOutput#gain()}, bypassing every track and bus. Gated by
 *       {@link ClickOutput#sideOutputEnabled()}.</li>
 *   <li><b>Cue-bus contributions</b> — returned as a
 *       {@code Map<UUID, float[]>} keyed by cue bus id, each entry carrying a
 *       gained-down mono click that the cue-bus renderer adds into the cue
 *       mix at the same scheduled beat.</li>
 * </ol>
 */
public final class MetronomeSideOutputRouter {

    private final Map<UUID, Double> cueBusLevels = new LinkedHashMap<>();

    /**
     * Sets the click contribution level for a cue bus.
     *
     * @param cueBusId id of the cue bus; must not be null
     * @param level    linear gain in {@code [0.0, 1.0]}; zero removes the contribution
     * @throws IllegalArgumentException if {@code level} is outside {@code [0.0, 1.0]}
     */
    public void setCueBusLevel(UUID cueBusId, double level) {
        Objects.requireNonNull(cueBusId, "cueBusId must not be null");
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be between 0.0 and 1.0: " + level);
        }
        if (level == 0.0) {
            cueBusLevels.remove(cueBusId);
        } else {
            cueBusLevels.put(cueBusId, level);
        }
    }

    /**
     * Returns the click level for the given cue bus, or {@code 0.0} when the bus
     * has no click contribution configured.
     *
     * @param cueBusId id of the cue bus
     * @return linear gain in {@code [0.0, 1.0]}
     */
    public double getCueBusLevel(UUID cueBusId) {
        Objects.requireNonNull(cueBusId, "cueBusId must not be null");
        return cueBusLevels.getOrDefault(cueBusId, 0.0);
    }

    /**
     * Removes every cue-bus click contribution.
     */
    public void clearCueBusLevels() {
        cueBusLevels.clear();
    }

    /**
     * Returns an unmodifiable snapshot of the current cue-bus click levels.
     *
     * @return map of cue bus id → click gain
     */
    public Map<UUID, Double> cueBusLevels() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cueBusLevels));
    }

    /**
     * Routes a click sample to every destination defined by the metronome's
     * current {@link ClickOutput} configuration and per-cue-bus levels.
     *
     * <p>The metronome's {@link Metronome#isEnabled()} flag is the master
     * gate: a disabled metronome produces silence on <em>every</em>
     * destination, honouring count-in and pre-roll expectations because the
     * same gate is read here.</p>
     *
     * @param metronome     the metronome whose {@link Metronome#getClickOutput()}
     *                      configuration governs routing; must not be null
     * @param click         the generated click as {@code [channel][sample]}
     *                      produced by {@link Metronome#generateClick(boolean)};
     *                      must not be null
     * @param backend       the active audio backend for the side output; may be
     *                      {@code null} when no backend is attached (side
     *                      output is then silently dropped)
     * @param cueBusManager cue bus manager used to honour
     *                      {@link #setCueBusLevel(UUID, double) per-bus click
     *                      levels}; may be {@code null} to skip cue routing
     * @return the result carrying the main-mix buffer and per-cue-bus buffers
     */
    public RoutedClick route(Metronome metronome,
                             float[][] click,
                             AudioBackend backend,
                             CueBusManager cueBusManager) {
        Objects.requireNonNull(metronome, "metronome must not be null");
        Objects.requireNonNull(click, "click must not be null");

        if (!metronome.isEnabled()) {
            return RoutedClick.SILENT;
        }
        ClickOutput cfg = metronome.getClickOutput();

        float[][] mainMix = cfg.mainMixEnabled() ? click : null;

        if (cfg.sideOutputEnabled() && backend != null) {
            float[] mono = monoMix(click);
            float sideGain = (float) cfg.gain();
            if (sideGain != 1.0f) {
                for (int i = 0; i < mono.length; i++) {
                    mono[i] = mono[i] * sideGain;
                }
            }
            backend.writeToChannel(cfg.hardwareChannelIndex(), mono);
        }

        Map<UUID, float[]> cueContributions = Map.of();
        if (cueBusManager != null && !cueBusLevels.isEmpty()) {
            cueContributions = new LinkedHashMap<>(cueBusLevels.size());
            float[] mono = monoMix(click);
            for (Map.Entry<UUID, Double> entry : cueBusLevels.entrySet()) {
                CueBus bus = cueBusManager.getById(entry.getKey());
                if (bus == null) {
                    continue;
                }
                float g = entry.getValue().floatValue();
                float[] scaled = new float[mono.length];
                for (int i = 0; i < scaled.length; i++) {
                    scaled[i] = mono[i] * g;
                }
                cueContributions.put(entry.getKey(), scaled);
            }
            cueContributions = java.util.Collections.unmodifiableMap(cueContributions);
        }

        return new RoutedClick(mainMix, cueContributions);
    }

    private static float[] monoMix(float[][] click) {
        if (click.length == 0) {
            return new float[0];
        }
        int frames = click[0].length;
        if (click.length == 1) {
            return click[0].clone();
        }
        float[] mono = new float[frames];
        float scale = 1.0f / click.length;
        for (int ch = 0; ch < click.length; ch++) {
            float[] src = click[ch];
            for (int s = 0; s < frames; s++) {
                mono[s] += src[s] * scale;
            }
        }
        return mono;
    }

    /**
     * Result of routing one click: the optional main-mix buffer and a
     * per-cue-bus contribution map. Immutable.
     *
     * @param mainMixBuffer click samples destined for the main mix bus, or
     *                      {@code null} when the click is muted from the main
     *                      mix (either the metronome is disabled or
     *                      {@link ClickOutput#mainMixEnabled()} is false)
     * @param cueBusBuffers per-cue-bus mono click buffers scaled by each
     *                      bus's click level; empty when no cue bus has a
     *                      click contribution configured
     */
    public record RoutedClick(float[][] mainMixBuffer,
                              Map<UUID, float[]> cueBusBuffers) {

        /** Silent result — nothing on any destination. */
        public static final RoutedClick SILENT = new RoutedClick(null, Map.of());

        public RoutedClick {
            Objects.requireNonNull(cueBusBuffers, "cueBusBuffers must not be null");
        }

        /**
         * Convenience accessor indicating that the click should be summed into
         * the main mix.
         *
         * @return {@code true} when {@link #mainMixBuffer()} is non-null
         */
        public boolean hasMainMix() {
            return mainMixBuffer != null;
        }
    }
}
