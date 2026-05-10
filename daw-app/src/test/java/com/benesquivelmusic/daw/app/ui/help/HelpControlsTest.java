package com.benesquivelmusic.daw.app.ui.help;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HelpControlsTest {

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
    void setHelpTopicRoundTrips() throws Exception {
        String slug = onFx(() -> {
            Button b = new Button("Play");
            HelpControls.setHelpTopic(b, "transport");
            return HelpControls.getHelpTopic(b).orElse(null);
        });

        assertThat(slug).isEqualTo("transport");
    }

    @Test
    void setHelpTopicNullClearsValue() throws Exception {
        boolean cleared = onFx(() -> {
            Button b = new Button("Play");
            HelpControls.setHelpTopic(b, "transport");
            HelpControls.setHelpTopic(b, null);
            return HelpControls.getHelpTopic(b).isEmpty();
        });

        assertThat(cleared).isTrue();
    }

    @Test
    void findHelpTopicWalksParentChain() throws Exception {
        String slug = onFx(() -> {
            Button child = new Button("Play");
            javafx.scene.layout.VBox parent = new javafx.scene.layout.VBox(child);
            HelpControls.setHelpTopic(parent, "transport");
            // Force a layout pass so the parent reference is real.
            new javafx.scene.Scene(parent);
            return HelpControls.findHelpTopic(child).orElse(null);
        });

        assertThat(slug).isEqualTo("transport");
    }

    @Test
    void getHelpTopicOnUntaggedNodeReturnsEmpty() throws Exception {
        boolean empty = onFx(() -> HelpControls.getHelpTopic(new Button("X")).isEmpty());
        assertThat(empty).isTrue();
    }
}
