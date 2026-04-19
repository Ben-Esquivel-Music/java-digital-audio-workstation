package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Identifies one of the six interior surfaces of a rectangular room.
 *
 * <p>Used as per-reflection metadata on {@link SoundWavePath} so the UI
 * can highlight which surface produced a given reflection, and as the
 * key for {@link SurfaceMaterialMap} per-surface material lookups.</p>
 */
public enum RoomSurface {

    /** Bottom surface of the room (z = 0). */
    FLOOR,

    /** Wall at y = 0 (the &quot;front&quot; of the room as viewed from above). */
    FRONT_WALL,

    /** Wall at y = length. */
    BACK_WALL,

    /** Wall at x = 0. */
    LEFT_WALL,

    /** Wall at x = width. */
    RIGHT_WALL,

    /** Top surface of the room (z = ceiling height). */
    CEILING
}
