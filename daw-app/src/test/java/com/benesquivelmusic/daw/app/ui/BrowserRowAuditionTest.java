package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.BrowserPanel.BrowserSection;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 275 — clicking a row's audition button drives the injected
 * {@link SampleAuditioner} with the row's {@link Path}; clicking again
 * stops it. A fake auditioner keeps the test deterministic and opens no
 * audio device.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BrowserRowAuditionTest {

    /** Records calls; never touches an audio device. */
    private static final class FakeAuditioner implements SampleAuditioner {
        volatile Path lastPlayed;
        volatile boolean playing;
        volatile int stopCalls;

        @Override public void play(Path file) {
            lastPlayed = file;
            playing = true;
        }
        @Override public void stop() {
            stopCalls++;
            playing = false;
        }
        @Override public boolean isPlaying() { return playing; }
        @Override public void setOnPlaybackFinished(Runnable callback) { }
    }

    private <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.call());
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) {
            throw err.get();
        }
        return ref.get();
    }

    @Test
    void rowAuditionButtonTogglesTheInjectedAuditioner() throws Exception {
        FakeAuditioner fake = new FakeAuditioner();

        // Locate the audition button on the single audio row, fire it.
        Button auditionButton = onFx(() -> {
            BrowserPanel panel = new BrowserPanel();
            panel.setSampleAuditioner(fake);
            panel.selectSection(BrowserSection.SAMPLES);
            panel.addSamples(List.of("kick.wav"));

            StackPane root = new StackPane(panel);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 600);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            // Force a raster pass so the ListView realizes its cells.
            root.snapshot(null, null);
            root.applyCss();
            root.layout();

            Set<Node> buttons = panel.getSamplesListView()
                    .lookupAll(".browser-audition-button");
            return buttons.stream()
                    .filter(n -> n instanceof Button)
                    .map(n -> (Button) n)
                    .filter(Node::isVisible)
                    .findFirst()
                    .orElse(null);
        });

        assertThat(auditionButton)
                .as("realized audio row exposes an audition button")
                .isNotNull();

        // First click → play(kick.wav), isPlaying() == true.
        onFx(() -> {
            auditionButton.fire();
            return null;
        });
        assertThat(fake.lastPlayed).isEqualTo(Path.of("kick.wav"));
        assertThat(fake.isPlaying()).isTrue();

        // Second click on the same row → stop(), isPlaying() == false.
        onFx(() -> {
            auditionButton.fire();
            return null;
        });
        assertThat(fake.stopCalls).isGreaterThanOrEqualTo(1);
        assertThat(fake.isPlaying()).isFalse();
    }
}
