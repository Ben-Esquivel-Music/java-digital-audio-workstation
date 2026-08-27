package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.drag.AnimationProfile;
import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;
import com.benesquivelmusic.daw.app.ui.marshal.FxAnimationTimerAllowed;
import com.benesquivelmusic.daw.core.transport.TransportState;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;

import java.util.Objects;

/**
 * Coordinates all frame-by-frame and transition-based animations for the
 * main DAW window. Heavy lifting is delegated to focused collaborators:
 *
 * <ul>
 *   <li>{@link IdleVisualizationAnimator} — synthesized spectrum data while
 *       the engine is idle (story 318 removed its level-meter push: the Peak /
 *       RMS display is fed by the metering tap bus; story 319 deletes the
 *       spectrum arm).</li>
 *   <li>{@link TransportGlowAnimator} — pulsing play-button glow and
 *       blinking record-button glow.</li>
 *   <li>{@link ButtonPressAnimator} — scale-bounce press animations on
 *       toolbar buttons.</li>
 * </ul>
 *
 * <p>The former wall-clock {@code TimeTickerAnimator} was deleted by story
 * 315: the time display is now bound to the beats→time projection of
 * {@code TransportVM.playhead} by {@code TransportControlBinder.bindTimeDisplay}
 * — one clock, projected (Audio Engine Wiring Design Book §2.3).</p>
 *
 * <p>This controller is responsible only for owning the single
 * {@link AnimationTimer} that drives those collaborators each frame, plus
 * exposing the playhead-update callback hook used by
 * {@link MainController}. Issue: "Decompose Remaining God-Class
 * Controllers into Focused Services."</p>
 */
@FxAnimationTimerAllowed("Owns the single per-frame animation timer driving the idle "
        + "spectrum demo (story 319 removes it), transport glow, and the per-frame "
        + "playhead overlay tick (javafx-application-design §6 control-owns-timer); "
        + "level meters are NOT ticked here — they are MeterFeed pulse consumers "
        + "(story 318); not a cross-thread seam — story 289 sentinel.")
final class AnimationController {

    /**
     * Callback interface implemented by the host controller to provide
     * dynamic state that remains in the top-level controller.
     */
    interface Host {
        TransportState transportState();
    }

    private final IdleVisualizationAnimator idleVisualizationAnimator;
    private final TransportGlowAnimator transportGlowAnimator;
    private final ButtonPressAnimator buttonPressAnimator;
    private final Host host;

    /**
     * Single shared {@link AnimationProfile} used by every drag-related
     * animation (clip, plugin, sample) so timing feels cohesive across
     * the app — see user story 197.
     */
    private final AnimationProfile dragAnimationProfile = AnimationProfile.DEFAULT;

    /**
     * Single shared {@link DragVisualAdvisor} consulted by every drag
     * source (clips in {@code ClipInteractionController}, plugins in
     * {@code InsertEffectRack}, samples in {@code BrowserPanel}).
     */
    private final DragVisualAdvisor dragVisualAdvisor =
            new DragVisualAdvisor(dragAnimationProfile);

    private AnimationTimer mainAnimTimer;
    private double glowAnimPhase;
    private Runnable playheadUpdateCallback;

    AnimationController(SpectrumDisplay spectrumDisplay,
                        Button playButton,
                        Button recordButton,
                        Button[] animatedButtons,
                        Host host) {
        this.idleVisualizationAnimator = new IdleVisualizationAnimator(spectrumDisplay);
        this.transportGlowAnimator = new TransportGlowAnimator(playButton, recordButton);
        this.buttonPressAnimator = new ButtonPressAnimator(animatedButtons);
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Creates and starts the single {@link AnimationTimer} that drives
     * all continuous frame-by-frame animations: idle spectrum demo,
     * transport glow, and the per-frame playhead overlay tick.
     */
    void start() {
        mainAnimTimer = new AnimationTimer() {
            private long lastNanos = 0;

            @Override
            public void handle(long now) {
                if (lastNanos == 0) {
                    lastNanos = now;
                    return;
                }
                double delta = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;

                glowAnimPhase += delta;

                TransportState state = host.transportState();
                transportGlowAnimator.apply(state, glowAnimPhase);

                if (playheadUpdateCallback != null) {
                    playheadUpdateCallback.run();
                }

                idleVisualizationAnimator.tick(delta);
            }
        };
        mainAnimTimer.start();
    }

    /** Stops the animation timer. */
    void stop() {
        if (mainAnimTimer != null) {
            mainAnimTimer.stop();
        }
    }

    /**
     * Sets a callback invoked each animation frame to update the playhead
     * position in the arrangement view from the transport's current beat.
     *
     * @param callback the callback to invoke, or {@code null} to clear
     */
    void setPlayheadUpdateCallback(Runnable callback) {
        this.playheadUpdateCallback = callback;
    }

    /**
     * Returns the single shared {@link DragVisualAdvisor} instance — the
     * one point of consultation for ghost previews, drop-zone highlights,
     * snap indicators, and modifier-key cursor changes across every drag
     * source in the application (user story 197).
     */
    DragVisualAdvisor dragVisualAdvisor() {
        return dragVisualAdvisor;
    }

    /**
     * Returns the shared {@link AnimationProfile} so external animators
     * can match the drag-system timings (fade-in, cancel-revert, etc.).
     */
    AnimationProfile dragAnimationProfile() {
        return dragAnimationProfile;
    }

    // ── Button press animations ──────────────────────────────────────────────

    /**
     * Adds a scale-bounce press/release animation to every animated button so
     * clicks feel tactile and immediate.
     */
    void applyButtonPressAnimations() {
        buttonPressAnimator.install();
    }
}
