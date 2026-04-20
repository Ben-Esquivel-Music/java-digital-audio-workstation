package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Identifies which class of room boundary is responsible for a Speaker
 * Boundary Interference Response (SBIR) prediction.
 *
 * <p>SBIR is the comb-filtering pattern produced when a speaker's direct
 * sound combines at the listening position with a delayed reflection from
 * a nearby boundary — typically the wall behind the speaker, the side
 * wall, the floor (&quot;desk bounce&quot;), or the ceiling. The boundary
 * kind determines which surface the predicted notch is attributed to and
 * which mitigation suggestion the engine emits (&quot;move speaker
 * 0.3&nbsp;m from front wall&quot;, etc.).</p>
 */
public enum BoundaryKind {

    /** Wall behind the speaker (front wall in this project's convention). */
    FRONT_WALL,

    /** Wall behind the listener. */
    BACK_WALL,

    /** Nearest side wall (left or right). */
    SIDE_WALL,

    /** Floor (desk-bounce / floor-bounce notch). */
    FLOOR,

    /** Ceiling. */
    CEILING
}
