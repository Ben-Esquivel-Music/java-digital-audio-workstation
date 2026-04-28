package com.benesquivelmusic.daw.core.export.aaf;

import java.util.Objects;

/**
 * SMPTE-style HH:MM:SS:FF timecode bound to a specific frame rate.
 *
 * <p>Used as the start-of-timeline ("session start") timecode that the
 * user configures in the export dialog. Conversion to and from a sample
 * offset is exposed via {@link #toSampleOffset(int)} and
 * {@link #fromSamples(long, int, AafFrameRate)} so the writer and
 * reader can round-trip start positions losslessly when the sample rate
 * is fixed.</p>
 *
 * <p>This implementation models <em>non-drop</em> arithmetic only; for
 * drop-frame rates ({@link AafFrameRate#FPS_29_97}) the textual
 * representation uses {@code ;} as the separator before the frames
 * field per SMPTE convention, but the numeric conversion treats the
 * drop-frame counts as the same wallclock-equivalent samples (the AAF
 * data model itself is sample-based, so drop-frame is purely a display
 * convention).</p>
 *
 * @param hours      hours component, 0–23 typical (no upper bound)
 * @param minutes    minutes component, 0–59
 * @param seconds    seconds component, 0–59
 * @param frames     frames component, 0 to {@code frameRate.nominalFps()-1}
 * @param frameRate  the frame rate this timecode is anchored to
 */
public record AafTimecode(int hours,
                          int minutes,
                          int seconds,
                          int frames,
                          AafFrameRate frameRate) {

    public AafTimecode {
        Objects.requireNonNull(frameRate, "frameRate must not be null");
        if (hours < 0)   throw new IllegalArgumentException("hours must be >= 0: " + hours);
        if (minutes < 0 || minutes > 59) {
            throw new IllegalArgumentException("minutes must be 0..59: " + minutes);
        }
        if (seconds < 0 || seconds > 59) {
            throw new IllegalArgumentException("seconds must be 0..59: " + seconds);
        }
        if (frames < 0 || frames >= frameRate.nominalFps()) {
            throw new IllegalArgumentException(
                    "frames must be 0.." + (frameRate.nominalFps() - 1) + ": " + frames);
        }
    }

    /** @return zero timecode at the given frame rate (00:00:00:00). */
    public static AafTimecode zero(AafFrameRate frameRate) {
        return new AafTimecode(0, 0, 0, 0, frameRate);
    }

    /**
     * Converts this timecode to an absolute sample offset at the given
     * sample rate.
     *
     * @param sampleRate the project sample rate in Hz
     * @return the equivalent sample count from the start of the day
     */
    public long toSampleOffset(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        double totalSeconds = hours * 3600.0
                + minutes * 60.0
                + seconds
                + frames / (double) frameRate.nominalFps();
        return Math.round(totalSeconds * sampleRate);
    }

    /**
     * Builds a timecode from a sample count at the given sample rate
     * and target frame rate.
     */
    public static AafTimecode fromSamples(long samples, int sampleRate, AafFrameRate frameRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (samples < 0) {
            throw new IllegalArgumentException("samples must be >= 0: " + samples);
        }
        Objects.requireNonNull(frameRate, "frameRate must not be null");
        double totalSeconds = samples / (double) sampleRate;
        int totalFrames = (int) Math.floor(totalSeconds * frameRate.nominalFps());
        int frames = totalFrames % frameRate.nominalFps();
        int totalWholeSeconds = totalFrames / frameRate.nominalFps();
        int seconds = totalWholeSeconds % 60;
        int totalMinutes = totalWholeSeconds / 60;
        int minutes = totalMinutes % 60;
        int hours = totalMinutes / 60;
        return new AafTimecode(hours, minutes, seconds, frames, frameRate);
    }

    /**
     * Parses an {@code HH:MM:SS:FF} (or {@code HH:MM:SS;FF} for drop-frame)
     * string into a timecode.
     */
    public static AafTimecode parse(String text, AafFrameRate frameRate) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(frameRate, "frameRate must not be null");
        String[] parts = text.split("[:;]");
        if (parts.length != 4) {
            throw new IllegalArgumentException("expected HH:MM:SS:FF, got: " + text);
        }
        try {
            return new AafTimecode(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    frameRate);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid timecode: " + text, e);
        }
    }

    /** @return canonical {@code HH:MM:SS:FF} representation. */
    @Override
    public String toString() {
        char sep = frameRate.dropFrame() ? ';' : ':';
        return String.format("%02d:%02d:%02d%c%02d", hours, minutes, seconds, sep, frames);
    }
}
