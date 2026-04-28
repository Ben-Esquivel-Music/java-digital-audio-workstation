package com.benesquivelmusic.daw.core.export.aaf;

/**
 * Standard SMPTE frame rates available for AAF / OMF timeline export.
 *
 * <p>The user picks a single frame rate when exporting; clip positions
 * (originally beat-based in the DAW) are converted to samples using the
 * project's tempo and sample rate, and the resulting timeline is then
 * labelled with this frame rate so that downstream post tools display
 * timecodes correctly.</p>
 *
 * <p>Drop-frame is supported only for the broadcast rates 29.97 fps;
 * 23.976 fps is non-drop, as is conventional in film post.</p>
 */
public enum AafFrameRate {

    /** 23.976 fps (24000/1001) — film transferred to NTSC video, non-drop. */
    FPS_23_976(24000.0 / 1001.0, 24, false, "23.976"),

    /** 24 fps — true cinema rate, non-drop. */
    FPS_24(24.0, 24, false, "24"),

    /** 25 fps — PAL television, non-drop. */
    FPS_25(25.0, 25, false, "25"),

    /** 29.97 fps (30000/1001) — NTSC broadcast, drop-frame counting. */
    FPS_29_97(30000.0 / 1001.0, 30, true, "29.97"),

    /** 30 fps — non-drop 30 (sometimes called "30 NDF"). */
    FPS_30(30.0, 30, false, "30");

    private final double fps;
    private final int nominalFps;
    private final boolean dropFrame;
    private final String label;

    AafFrameRate(double fps, int nominalFps, boolean dropFrame, String label) {
        this.fps = fps;
        this.nominalFps = nominalFps;
        this.dropFrame = dropFrame;
        this.label = label;
    }

    /** @return the exact frame rate in frames per second */
    public double fps() {
        return fps;
    }

    /**
     * @return the nominal (rounded) frame rate used for HH:MM:SS:FF
     *         decomposition (e.g. 24 for 23.976, 30 for 29.97)
     */
    public int nominalFps() {
        return nominalFps;
    }

    /** @return whether the rate uses drop-frame timecode counting */
    public boolean dropFrame() {
        return dropFrame;
    }

    /** @return short user-facing label, e.g. {@code "23.976"} */
    public String label() {
        return label;
    }
}
