package com.benesquivelmusic.daw.app.ui.marshal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sentinel marker for a UI class that legitimately constructs its own
 * {@link javafx.animation.AnimationTimer} — a per-frame, FX-thread animation
 * loop a control or plugin view owns — and is therefore <em>not</em> the
 * cross-thread marshalling seam {@link FxDispatcher} consolidates (story 289;
 * Control Synchronization Design Book §4.5).
 *
 * <p>{@code RunLaterConsolidationTest} scans the {@code
 * com.benesquivelmusic.daw.app} source tree and asserts that, outside
 * {@link FxDispatcher}, no source constructs an {@code AnimationTimer} unless it
 * carries this annotation with a non-blank reason. The annotation makes a
 * sanctioned timer <strong>visible</strong> and prevents drift: a new class
 * cannot spin up a frame loop — or, worse, smuggle in a second cross-thread hop
 * — without consciously recording why its timer is a legitimate
 * control-owns-its-own-loop case and not a marshalling seam.</p>
 *
 * <p>Why a sentinel rather than folding the eleven existing timers into the one
 * {@code FxDispatcher} pulse? Those are per-frame meter / parameter / glyph
 * animations each owned by a single control or plugin view
 * ({@code javafx-application-design} §6: "a control owns its own timer"). They
 * are not cross-thread seams — none of them hops a non-FX signal onto the FX
 * thread — and merging them into a shared pulse would change their behaviour
 * (start/stop lifecycle, per-control framing). Story 289's mandate is a
 * no-behaviour-change consolidation of the <em>marshalling</em> seam, so these
 * stay where they are and are tracked here instead of refactored.</p>
 *
 * <p>This is the direct sibling of
 * {@link com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed} (story
 * 277) and {@link com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog} (story
 * 276): same pattern (mandatory non-blank {@code value()} reason,
 * {@code @Documented}, type-targeted), same intent (a green gate that tracks a
 * sanctioned exception without forcing a risky bulk refactor).</p>
 *
 * <p>Retention is {@link RetentionPolicy#SOURCE}: the audit is a source-file
 * text scan (no module / reflection concerns), so the marker need not survive
 * compilation. {@link FxDispatcher} itself owns the one legitimate seam timer
 * and is excluded from the scan by name — it must <em>not</em> carry this
 * annotation.</p>
 *
 * <pre>{@code
 * @FxAnimationTimerAllowed("Per-frame meter animation owned by this view "
 *         + "(javafx-application-design §6 control-owns-timer); not a "
 *         + "cross-thread seam — story 289 sentinel.")
 * public final class FooMeterView { ... }
 * }</pre>
 *
 * @see com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed
 * @see com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog
 * @see FxDispatcher
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface FxAnimationTimerAllowed {

    /**
     * Mandatory reason explaining why this class owns its own
     * {@link javafx.animation.AnimationTimer} and why that timer is a
     * legitimate per-frame control / view animation loop rather than a
     * cross-thread marshalling seam.
     *
     * @return the rationale; must be non-blank
     */
    String value();
}
