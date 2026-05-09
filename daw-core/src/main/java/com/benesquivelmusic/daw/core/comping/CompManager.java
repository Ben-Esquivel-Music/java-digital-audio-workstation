package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Renders the composite audio for a set of {@link CompRegion}s on a
 * {@link TakeComping} stack.
 *
 * <p>For each comp region, the corresponding audio samples on the referenced
 * take lane's first overlapping {@link AudioClip} are stitched into a single
 * composite buffer. Adjacent (touching) region boundaries are smoothed with
 * an optional <em>equal-power crossfade</em>
 * (default {@value #DEFAULT_CROSSFADE_MS}&nbsp;ms) so segments from
 * different takes blend without clicks.</p>
 *
 * <p>The renderer is sample-accurate: the composite for a region
 * {@code [s, s+d)} (in beats) at sample-rate {@code sr} and tempo
 * {@code bpm} occupies exactly
 * <code>round(d&nbsp;*&nbsp;60&nbsp;/&nbsp;bpm&nbsp;*&nbsp;sr)</code>
 * samples relative to the composite origin (the earliest selected region).</p>
 *
 * <p>This class is the headless renderer used by both the playback path
 * (composite preview) and {@link CompileCompAction} (compile to clip). UI
 * code should not depend on it directly — instead route operations through
 * the action classes so they are undoable.</p>
 */
public final class CompManager {

    /** Default crossfade duration applied at region boundaries, in milliseconds. */
    public static final double DEFAULT_CROSSFADE_MS = 5.0;

    private final TakeComping comping;
    private double crossfadeMs = DEFAULT_CROSSFADE_MS;

    /**
     * Creates a comp manager bound to the given {@link TakeComping}.
     *
     * @param comping the take stack to render; must not be {@code null}
     */
    public CompManager(TakeComping comping) {
        this.comping = Objects.requireNonNull(comping, "comping must not be null");
    }

    /** Returns the underlying {@link TakeComping}. */
    public TakeComping getTakeComping() {
        return comping;
    }

    /** Returns the equal-power crossfade duration in milliseconds. */
    public double getCrossfadeMs() {
        return crossfadeMs;
    }

    /**
     * Sets the equal-power crossfade duration applied at region boundaries.
     *
     * @param crossfadeMs duration in milliseconds (must be {@code >= 0})
     */
    public void setCrossfadeMs(double crossfadeMs) {
        if (crossfadeMs < 0 || Double.isNaN(crossfadeMs)) {
            throw new IllegalArgumentException("crossfadeMs must be >= 0: " + crossfadeMs);
        }
        this.crossfadeMs = crossfadeMs;
    }

    /**
     * Renders the composite audio for the current comp regions.
     *
     * <p>If no comp regions are set, returns an empty 2D array. Otherwise the
     * composite spans from the earliest region start to the latest region end
     * and uses the channel count of the first source clip carrying audio
     * data.</p>
     *
     * @param sampleRate the sample-rate to render at, in Hz (must be {@code > 0})
     * @param tempoBpm   the tempo to convert beats &harr; samples (must be {@code > 0})
     * @return {@code float[channel][sample]} composite buffer; never {@code null}
     */
    public float[][] renderComposite(double sampleRate, double tempoBpm) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (tempoBpm <= 0) {
            throw new IllegalArgumentException("tempoBpm must be > 0: " + tempoBpm);
        }
        List<CompRegion> regions = new ArrayList<>(comping.getCompRegions());
        if (regions.isEmpty()) {
            return new float[0][0];
        }
        regions.sort((a, b) -> Double.compare(a.startBeat(), b.startBeat()));

        double originBeat = regions.getFirst().startBeat();
        double endBeat = 0.0;
        int channels = 0;
        for (CompRegion r : regions) {
            endBeat = Math.max(endBeat, r.endBeat());
            if (channels == 0) {
                AudioClip src = findSourceClip(r);
                if (src != null && src.getAudioData() != null && src.getAudioData().length > 0) {
                    channels = src.getAudioData().length;
                }
            }
        }
        if (channels == 0) {
            return new float[0][0];
        }
        int totalSamples = beatsToSamples(endBeat - originBeat, tempoBpm, sampleRate);
        if (totalSamples <= 0) {
            return new float[channels][0];
        }
        float[][] out = new float[channels][totalSamples];
        int crossfadeSamples = Math.max(0, (int) Math.round(crossfadeMs / 1000.0 * sampleRate));

        // Track the natural end-sample of the previously written region so we
        // can detect touching boundaries that warrant an equal-power crossfade.
        int previousEnd = Integer.MIN_VALUE;
        for (int rIdx = 0; rIdx < regions.size(); rIdx++) {
            CompRegion region = regions.get(rIdx);
            AudioClip src = findSourceClip(region);
            if (src == null) {
                continue;
            }
            float[][] data = src.getAudioData();
            if (data == null || data.length == 0) {
                continue;
            }
            int regionStart = beatsToSamples(region.startBeat() - originBeat, tempoBpm, sampleRate);
            int regionEnd = beatsToSamples(region.endBeat() - originBeat, tempoBpm, sampleRate);
            regionEnd = Math.min(regionEnd, totalSamples);
            if (regionEnd <= regionStart) {
                continue;
            }
            double srcOffsetBeats = region.startBeat() - src.getStartBeat()
                    + src.getSourceOffsetBeats();
            int srcStart = beatsToSamples(srcOffsetBeats, tempoBpm, sampleRate);
            int regionLen = regionEnd - regionStart;

            int xfade = 0;
            if (crossfadeSamples > 0 && regionStart == previousEnd) {
                xfade = Math.min(crossfadeSamples, regionLen / 2);
            }

            for (int ch = 0; ch < out.length; ch++) {
                float[] srcCh = data[Math.min(ch, data.length - 1)];
                float[] dstCh = out[ch];
                // Save the previous region's data at positions that the body
                // loop is about to overwrite — needed for crossfade blending.
                float[] prevTail = null;
                if (xfade > 0) {
                    prevTail = new float[xfade];
                    for (int i = 0; i < xfade; i++) {
                        int idx = regionStart + i;
                        if (idx >= 0 && idx < dstCh.length) {
                            prevTail[i] = dstCh[idx];
                        }
                    }
                }
                // Body: write the full region at its natural position.
                for (int i = 0; i < regionLen; i++) {
                    int dstIdx = regionStart + i;
                    int si = srcStart + i;
                    if (dstIdx < 0 || dstIdx >= dstCh.length) {
                        continue;
                    }
                    if (si < 0 || si >= srcCh.length) {
                        dstCh[dstIdx] = 0f;
                    } else {
                        dstCh[dstIdx] = srcCh[si];
                    }
                }
                // Crossfade: equal-power blend at the touching boundary.
                // The zone is [regionStart, regionStart + xfade): the previous
                // region's trailing samples (saved in prevTail, which may
                // include samples just past the previous region's selected end)
                // are faded out while the incoming region's leading samples
                // (just written by the body loop) are faded in.
                if (xfade > 0 && prevTail != null) {
                    for (int i = 0; i < xfade; i++) {
                        int dstIdx = regionStart + i;
                        if (dstIdx < 0 || dstIdx >= dstCh.length) {
                            continue;
                        }
                        float prev = prevTail[i];
                        float incoming = dstCh[dstIdx];
                        double t = (i + 1) / (double) xfade;
                        double angle = t * Math.PI / 2.0;
                        dstCh[dstIdx] = (float) (prev * Math.cos(angle)
                                + incoming * Math.sin(angle));
                    }
                }
                // Extend: write xfade samples past the region end from the
                // source clip so the *next* region's crossfade has data to
                // blend with. Only extend when the next region starts exactly
                // at this region's end (touching boundary); otherwise, leave
                // the gap as silence to avoid leaking audio between regions.
                if (crossfadeSamples > 0 && rIdx + 1 < regions.size()) {
                    CompRegion nextRegion = regions.get(rIdx + 1);
                    int nextStart = beatsToSamples(nextRegion.startBeat() - originBeat, tempoBpm, sampleRate);
                    if (nextStart == regionEnd) {
                        for (int i = 0; i < crossfadeSamples; i++) {
                            int dstIdx = regionEnd + i;
                            int si = srcStart + regionLen + i;
                            if (dstIdx < 0 || dstIdx >= dstCh.length) {
                                continue;
                            }
                            if (si >= 0 && si < srcCh.length) {
                                dstCh[dstIdx] = srcCh[si];
                            }
                        }
                    }
                }
            }
            previousEnd = regionEnd;
        }
        return out;
    }

    /**
     * Compiles the current comp into a single {@link AudioClip} suitable for
     * placement on the main lane. The clip starts at the earliest selected
     * region's start beat and carries the rendered composite as its in-memory
     * audio data.
     *
     * @param sampleRate the sample-rate to render at, in Hz
     * @param tempoBpm   the tempo to convert beats &harr; samples
     * @return the compiled composite clip, or {@code null} if there is
     *     nothing to compile
     */
    public AudioClip compileToClip(double sampleRate, double tempoBpm) {
        List<CompRegion> regions = new ArrayList<>(comping.getCompRegions());
        if (regions.isEmpty()) {
            return null;
        }
        float[][] audio = renderComposite(sampleRate, tempoBpm);
        if (audio.length == 0 || audio[0].length == 0) {
            return null;
        }
        regions.sort((a, b) -> Double.compare(a.startBeat(), b.startBeat()));
        double originBeat = regions.getFirst().startBeat();
        double endBeat = regions.stream().mapToDouble(CompRegion::endBeat).max().orElse(originBeat);
        double duration = endBeat - originBeat;
        AudioClip clip = new AudioClip("Comp", originBeat, duration, null);
        clip.setAudioData(audio);
        return clip;
    }

    /**
     * Returns the take lanes that should be rendered as solo'd, mirroring the
     * UX where Alt+Click solos a single take lane (audition). Returns an
     * empty list when no lane is solo'd, in which case the composite is
     * audible.
     *
     * @return the list of soloed lanes
     */
    public List<TakeLane> getSoloedLanes() {
        List<TakeLane> soloed = new ArrayList<>();
        for (TakeLane lane : comping.getTakeLanes()) {
            if (lane.isSoloed()) {
                soloed.add(lane);
            }
        }
        return Collections.unmodifiableList(soloed);
    }

    /**
     * Solos the take lane at the given index, unsoloing every other lane.
     * Pass {@code -1} to clear all solos and return to composite playback.
     *
     * @param index the lane index to solo, or {@code -1} to clear
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public void soloLane(int index) {
        List<TakeLane> lanes = comping.getTakeLanes();
        if (index < -1 || index >= lanes.size()) {
            throw new IndexOutOfBoundsException("index out of range: " + index);
        }
        for (int i = 0; i < lanes.size(); i++) {
            lanes.get(i).setSoloed(i == index);
        }
    }

    private AudioClip findSourceClip(CompRegion region) {
        if (region.takeIndex() < 0 || region.takeIndex() >= comping.getTakeLaneCount()) {
            return null;
        }
        TakeLane lane = comping.getTakeLane(region.takeIndex());
        for (AudioClip clip : lane.getClips()) {
            if (clip.getStartBeat() < region.endBeat()
                    && clip.getEndBeat() > region.startBeat()) {
                return clip;
            }
        }
        return null;
    }

    private static int beatsToSamples(double beats, double tempoBpm, double sampleRate) {
        double seconds = beats * 60.0 / tempoBpm;
        return (int) Math.round(seconds * sampleRate);
    }
}
