package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Taxonomy of rectangular-room normal modes.
 *
 * <p>A room-mode index triple {@code (nx, ny, nz)} is classified by how
 * many of its indices are non-zero:
 * <ul>
 *   <li>{@link #AXIAL} — one non-zero index. The mode bounces back and
 *       forth between a single pair of opposing walls; these are the
 *       loudest and most problematic modes.</li>
 *   <li>{@link #TANGENTIAL} — two non-zero indices. The mode involves
 *       four walls; typically 3&nbsp;dB weaker than axial modes.</li>
 *   <li>{@link #OBLIQUE} — three non-zero indices. The mode involves all
 *       six surfaces; typically 6&nbsp;dB weaker than axial modes and
 *       generally not a practical concern.</li>
 * </ul>
 *
 * <p>Consumers render each kind with a distinct colour — axial red,
 * tangential orange, oblique yellow — on the Room Modes plot.</p>
 */
public enum ModeKind {
    /** One non-zero index — loudest modes, bounce between a single wall pair. */
    AXIAL,
    /** Two non-zero indices — four-wall modes. */
    TANGENTIAL,
    /** Three non-zero indices — six-surface modes. */
    OBLIQUE;

    /**
     * Classifies a mode index triple. At least one index must be
     * strictly positive — {@code (0, 0, 0)} is not a real mode.
     *
     * @param nx the X (width) index
     * @param ny the Y (length) index
     * @param nz the Z (height) index
     * @return the classification
     * @throws IllegalArgumentException if all three indices are zero
     *                                  or any is negative
     */
    public static ModeKind classify(int nx, int ny, int nz) {
        if (nx < 0 || ny < 0 || nz < 0) {
            throw new IllegalArgumentException(
                    "mode indices must be non-negative: (" + nx + ", " + ny + ", " + nz + ")");
        }
        int nonZero = (nx == 0 ? 0 : 1) + (ny == 0 ? 0 : 1) + (nz == 0 ? 0 : 1);
        return switch (nonZero) {
            case 1 -> AXIAL;
            case 2 -> TANGENTIAL;
            case 3 -> OBLIQUE;
            default -> throw new IllegalArgumentException(
                    "mode (0, 0, 0) is not a real mode");
        };
    }
}
