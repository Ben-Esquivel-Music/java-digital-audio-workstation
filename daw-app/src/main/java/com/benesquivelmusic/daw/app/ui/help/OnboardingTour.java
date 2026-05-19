package com.benesquivelmusic.daw.app.ui.help;

import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * Lightweight, deterministic onboarding tour that walks the user through a
 * sequence of registered controls, highlighting each one and opening its
 * help topic in the {@link HelpOverlay}.
 *
 * <p>The tour is intentionally non-blocking — it does not modify focus,
 * does not steal input, and can be advanced or cancelled at any time.
 * Persistence of the "first launch" flag is delegated to
 * {@link OnboardingState}.</p>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class OnboardingTour {

    /** A single step in the tour. */
    public record Step(String slug, Node target) {
        public Step {
            Objects.requireNonNull(slug, "slug");
            // target may legitimately be null — represents an info-only step.
        }
    }

    private static final Effect HIGHLIGHT_EFFECT = buildHighlight();

    private final HelpOverlay overlay;
    private final OnboardingState state;
    private final List<Step> steps = new ArrayList<>();

    private int index = -1;
    private Effect savedEffect;
    private Node activeTarget;
    private boolean active;

    public OnboardingTour(HelpOverlay overlay, OnboardingState state) {
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Adds a step to the end of the tour. Returns {@code this} for chaining. */
    public OnboardingTour addStep(String slug, Node target) {
        steps.add(new Step(slug, target));
        return this;
    }

    /** Returns an immutable snapshot of the configured steps. */
    public List<Step> steps() {
        return List.copyOf(steps);
    }

    /** True while the tour is in progress (between {@link #start} and {@link #finish}). */
    public boolean isActive() {
        return active;
    }

    /** Index of the current step (or -1 if not started / finished). */
    public int currentIndex() {
        return index;
    }

    /**
     * Begins the tour at step 0. Has no effect when no steps are configured.
     * Skips automatically when {@link OnboardingState#isCompleted()} is true,
     * unless {@code forceRun} is true (Help → "Re-run tour" should pass true).
     */
    public void start(boolean forceRun) {
        if (steps.isEmpty()) {
            return;
        }
        if (!forceRun && state.isCompleted()) {
            return;
        }
        active = true;
        index = -1;
        next();
    }

    /** Advances to the next step (or finishes the tour after the last one). */
    public void next() {
        clearHighlight();
        index++;
        if (index >= steps.size()) {
            finish();
            return;
        }
        Step step = steps.get(index);
        if (step.target() != null) {
            savedEffect = step.target().getEffect();
            step.target().setEffect(HIGHLIGHT_EFFECT);
            activeTarget = step.target();
        }
        overlay.showTopic(step.slug());
    }

    /** Cancels the tour. Marks the onboarding flag as completed. */
    public void cancel() {
        clearHighlight();
        active = false;
        index = -1;
        state.markCompleted();
    }

    /** Marks the tour as completed and clears any highlight. */
    public void finish() {
        clearHighlight();
        active = false;
        index = -1;
        state.markCompleted();
    }

    private void clearHighlight() {
        if (activeTarget != null) {
            activeTarget.setEffect(savedEffect);
        }
        activeTarget = null;
        savedEffect = null;
    }

    private static Effect buildHighlight() {
        DropShadow glow = new DropShadow();
        glow.setColor(Color.web("#ffd54f"));
        glow.setRadius(20);
        glow.setSpread(0.5);
        return glow;
    }
}
