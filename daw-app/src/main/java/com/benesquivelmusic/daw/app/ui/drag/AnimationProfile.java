package com.benesquivelmusic.daw.app.ui.drag;

import java.time.Duration;
import java.util.Objects;

/**
 * Timings used by every drag-related animation, providing the "consistent
 * timing" requirement of user story 197.
 *
 * <p>A single instance of this profile is owned by
 * {@link com.benesquivelmusic.daw.app.ui.AnimationController} and shared
 * with {@link DragVisualAdvisor}. Centralising the durations here gives
 * every drag-related transition (ghost fade-in, drop-zone tint, snap
 * guide-line, cancel-revert) the same cohesive feel.</p>
 *
 * @param ghostFadeIn   how long the ghost preview fades in when a drag
 *                      starts
 * @param highlightFade how long a drop-target highlight tint takes to
 *                      fade in or out as the cursor enters or leaves
 * @param cancelRevert  how long the cancel-revert animation takes to
 *                      slide the source back to its origin on Esc
 * @param dropSettle    how long the drop-settle animation takes after
 *                      a successful drop
 * @param ghostOpacity  the resting opacity of a ghost preview, in the
 *                      range {@code [0.0, 1.0]}
 */
public record AnimationProfile(
        Duration ghostFadeIn,
        Duration highlightFade,
        Duration cancelRevert,
        Duration dropSettle,
        double ghostOpacity) {

    /** Default profile — short, snappy timings comparable to Ableton/Pro Tools. */
    public static final AnimationProfile DEFAULT = new AnimationProfile(
            Duration.ofMillis(90),
            Duration.ofMillis(120),
            Duration.ofMillis(180),
            Duration.ofMillis(140),
            0.55);

    public AnimationProfile {
        Objects.requireNonNull(ghostFadeIn, "ghostFadeIn");
        Objects.requireNonNull(highlightFade, "highlightFade");
        Objects.requireNonNull(cancelRevert, "cancelRevert");
        Objects.requireNonNull(dropSettle, "dropSettle");
        if (ghostFadeIn.isNegative() || highlightFade.isNegative()
                || cancelRevert.isNegative() || dropSettle.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
        if (!(ghostOpacity >= 0.0 && ghostOpacity <= 1.0)) {
            throw new IllegalArgumentException(
                    "ghostOpacity must be in [0,1]: " + ghostOpacity);
        }
    }
}
