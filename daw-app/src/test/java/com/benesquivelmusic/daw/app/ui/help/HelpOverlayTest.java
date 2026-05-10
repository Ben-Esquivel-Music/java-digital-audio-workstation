package com.benesquivelmusic.daw.app.ui.help;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HelpOverlayTest {

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { ref.set(callable.call()); }
            catch (Throwable e) { err.set(e); }
            finally { latch.countDown(); }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread runnable must complete within 5 s")
                .isTrue();
        if (err.get() != null) {
            if (err.get() instanceof Exception ex) throw ex;
            throw new RuntimeException(err.get());
        }
        return ref.get();
    }

    @Test
    void showTopicUpdatesCurrentSlug() throws Exception {
        String slug = onFx(() -> {
            HelpOverlay overlay = new HelpOverlay(HelpRegistry.loadDefault());
            overlay.showTopic("mixer");
            return overlay.currentSlug();
        });
        assertThat(slug).isEqualTo("mixer");
    }

    @Test
    void unknownSlugFallsBackToIndex() throws Exception {
        String slug = onFx(() -> {
            HelpOverlay overlay = new HelpOverlay(HelpRegistry.loadDefault());
            overlay.showTopic("nope-no-such-thing");
            return overlay.currentSlug();
        });
        assertThat(slug).isEqualTo(HelpRegistry.INDEX_SLUG);
    }

    @Test
    void breadcrumbAlwaysIncludesIndex() throws Exception {
        List<String> labels = onFx(() -> {
            HelpOverlay overlay = new HelpOverlay(HelpRegistry.loadDefault());
            overlay.showTopic("transport");
            overlay.showTopic("mixer");
            return overlay.testBreadcrumbLabels();
        });
        assertThat(labels.get(0)).isEqualTo("Index");
        assertThat(labels).contains("Mixer");
    }

    @Test
    void searchPopulatesResultsList() throws Exception {
        int count = onFx(() -> {
            HelpOverlay overlay = new HelpOverlay(HelpRegistry.loadDefault());
            overlay.testSearch("Transport");
            return overlay.testResults().size();
        });
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void quickHelpBarShowsTopicTitleForTaggedNode() throws Exception {
        String hint = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            QuickHelpBar bar = new QuickHelpBar(registry);
            bar.setEnabled(true);

            Button mixerFader = new Button("-12dB");
            HelpControls.setHelpTopic(mixerFader, "mixer");
            new Scene(new VBox(mixerFader, bar));

            bar.updateForNode(mixerFader);
            return bar.testHintText();
        });
        assertThat(hint).contains("Mixer").contains("F1");
    }

    @Test
    void quickHelpBarFallsBackToControlIdWhenUntagged() throws Exception {
        String hint = onFx(() -> {
            HelpRegistry registry = HelpRegistry.loadDefault();
            QuickHelpBar bar = new QuickHelpBar(registry);
            bar.setEnabled(true);

            Button untagged = new Button("Untagged");
            untagged.setId("randomControl");
            new Scene(new VBox(untagged, bar));

            bar.updateForNode(untagged);
            return bar.testHintText();
        });
        assertThat(hint).contains("randomControl");
    }
}
