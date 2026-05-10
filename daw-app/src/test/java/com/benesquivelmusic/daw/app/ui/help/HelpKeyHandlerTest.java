package com.benesquivelmusic.daw.app.ui.help;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HelpKeyHandlerTest {

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
    void f1OnFocusedControlOpensExpectedTopic() throws Exception {
        String slug = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            HelpOverlay overlay = new HelpOverlay(registry);
            QuickHelpBar bar = new QuickHelpBar(registry);
            HelpKeyHandler handler = new HelpKeyHandler(registry, overlay, bar);

            Button playButton = new Button("Play");
            HelpControls.setHelpTopic(playButton, "transport");
            new Scene(new VBox(playButton));

            handler.openHelpFor(playButton);
            return overlay.currentSlug();
        });

        assertThat(slug).isEqualTo("transport");
    }

    @Test
    void f1WithUnknownSlugFallsBackToIndex() throws Exception {
        String slug = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            HelpOverlay overlay = new HelpOverlay(registry);
            HelpKeyHandler handler = new HelpKeyHandler(registry, overlay, new QuickHelpBar(registry));

            Button broken = new Button("Mystery");
            HelpControls.setHelpTopic(broken, "this-topic-does-not-exist");
            new Scene(new VBox(broken));

            handler.openHelpFor(broken);
            return overlay.currentSlug();
        });

        assertThat(slug).isEqualTo(HelpRegistry.INDEX_SLUG);
    }

    @Test
    void f1WithNoFocusedControlOpensIndex() throws Exception {
        String slug = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            HelpOverlay overlay = new HelpOverlay(registry);
            HelpKeyHandler handler = new HelpKeyHandler(registry, overlay, new QuickHelpBar(registry));

            handler.openHelpFor(null);
            return overlay.currentSlug();
        });

        assertThat(slug).isEqualTo(HelpRegistry.INDEX_SLUG);
    }

    @Test
    void hoveredControlIsUsedWhenFocusHasNoTopic() throws Exception {
        String slug = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            HelpOverlay overlay = new HelpOverlay(registry);
            HelpKeyHandler handler = new HelpKeyHandler(registry, overlay, new QuickHelpBar(registry));

            Button focused = new Button("Untagged");
            Button hovered = new Button("Mixer fader");
            HelpControls.setHelpTopic(hovered, "mixer");
            new Scene(new VBox(focused, hovered));

            handler.setLastHovered(hovered);
            handler.openHelpFor(focused);
            return overlay.currentSlug();
        });

        assertThat(slug).isEqualTo("mixer");
    }

    @Test
    void shiftF1TogglesQuickHelpBar() throws Exception {
        Boolean enabledAfterToggle = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            QuickHelpBar bar = new QuickHelpBar(registry);
            HelpKeyHandler handler = new HelpKeyHandler(registry, new HelpOverlay(registry), bar);

            Scene scene = new Scene(new VBox(bar), 200, 200);
            handler.installOn(scene);

            assertThat(bar.isEnabled()).isFalse();
            // Simulate Shift+F1 by firing the key event through the scene.
            javafx.scene.input.KeyEvent ev = new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", javafx.scene.input.KeyCode.F1,
                    /*shift*/ true, /*ctrl*/ false, /*alt*/ false, /*meta*/ false);
            javafx.event.Event.fireEvent(scene, ev);
            return bar.isEnabled();
        });

        assertThat(enabledAfterToggle).isTrue();
    }
}
