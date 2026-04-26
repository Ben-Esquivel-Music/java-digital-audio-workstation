package com.benesquivelmusic.daw.app.ui;

import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.scene.control.Button;
import javafx.util.Duration;

import java.util.Objects;

/**
 * Attaches a subtle scale-down-then-spring-back press animation to a
 * group of buttons so clicks feel tactile and immediate.
 *
 * <p>Extracted from {@link AnimationController}. The behaviour is
 * stateless once installed, so the animator can be applied once at
 * scene-graph wiring time.</p>
 *
 * <p>Issue: "Decompose Remaining God-Class Controllers into Focused
 * Services."</p>
 */
final class ButtonPressAnimator {

    private static final Duration PRESS_DOWN_DURATION = Duration.millis(70);
    private static final Duration SPRING_BACK_DURATION = Duration.millis(130);
    private static final double PRESSED_SCALE = 0.90;

    private final Button[] buttons;

    ButtonPressAnimator(Button[] buttons) {
        this.buttons = Objects.requireNonNull(buttons, "buttons must not be null");
    }

    /** Installs press / release scale animations on every wired button. */
    void install() {
        for (Button btn : buttons) {
            applyPressAnimation(btn);
        }
    }

    private static void applyPressAnimation(Button btn) {
        ScaleTransition pressDown = new ScaleTransition(PRESS_DOWN_DURATION, btn);
        pressDown.setToX(PRESSED_SCALE);
        pressDown.setToY(PRESSED_SCALE);
        pressDown.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition springBack = new ScaleTransition(SPRING_BACK_DURATION, btn);
        springBack.setToX(1.0);
        springBack.setToY(1.0);
        springBack.setInterpolator(Interpolator.EASE_OUT);

        btn.setOnMousePressed(_ -> {
            springBack.stop();
            pressDown.playFromStart();
        });
        btn.setOnMouseReleased(_ -> {
            pressDown.stop();
            springBack.playFromStart();
        });
    }
}
