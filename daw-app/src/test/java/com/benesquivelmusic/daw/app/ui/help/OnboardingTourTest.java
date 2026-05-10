package com.benesquivelmusic.daw.app.ui.help;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class OnboardingTourTest {

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { ref.set(callable.call()); }
            catch (Exception e) { err.set(e); }
            finally { latch.countDown(); }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) throw err.get();
        return ref.get();
    }

    @Test
    void startWalksThroughStepsAndOpensTopics() throws Exception {
        var dir = Files.createTempDirectory("daw-tour-");
        OnboardingState state = new OnboardingState(dir.resolve("flag"));

        String slugAfterStart = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            HelpOverlay overlay = new HelpOverlay(registry);

            Button transportBtn = new Button("Play");
            Button mixerBtn = new Button("Fader");
            new Scene(new VBox(transportBtn, mixerBtn));

            OnboardingTour tour = new OnboardingTour(overlay, state)
                    .addStep("transport", transportBtn)
                    .addStep("mixer", mixerBtn);
            tour.start(false);
            return overlay.currentSlug();
        });
        assertThat(slugAfterStart).isEqualTo("transport");
    }

    @Test
    void startSkippedWhenAlreadyCompletedAndNotForced() throws Exception {
        var dir = Files.createTempDirectory("daw-tour-");
        OnboardingState state = new OnboardingState(dir.resolve("flag"));
        state.markCompleted();

        boolean active = onFx(() -> {
            HelpOverlay overlay = new HelpOverlay(HelpRegistry.loadDefault());
            OnboardingTour tour = new OnboardingTour(overlay, state)
                    .addStep("transport", null);
            tour.start(false);
            return tour.isActive();
        });
        assertThat(active).isFalse();
    }

    @Test
    void finishMarksCompleted() throws Exception {
        var dir = Files.createTempDirectory("daw-tour-");
        OnboardingState state = new OnboardingState(dir.resolve("flag"));

        boolean completedAfter = onFx(() -> {
            HelpOverlay overlay = new HelpOverlay(HelpRegistry.loadDefault());
            OnboardingTour tour = new OnboardingTour(overlay, state)
                    .addStep("transport", null);
            tour.start(false);
            tour.next(); // advances past the only step → finish
            return state.isCompleted();
        });
        assertThat(completedAfter).isTrue();
    }
}
