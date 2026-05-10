package com.benesquivelmusic.daw.app.ui.help;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class HelpMarkdownRendererTest {

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
    void rendersHeadingsParagraphsAndLists() throws Exception {
        Region rendered = onFx(() -> new HelpMarkdownRenderer().render(
                "# Title\n\nSome paragraph text.\n\n- one\n- two\n"));

        // Make sure the result is a non-empty container.
        assertThat(rendered).isInstanceOf(VBox.class);
        assertThat(((VBox) rendered).getChildren()).isNotEmpty();
    }

    @Test
    void internalLinkInvokesHandlerWithSlug() throws Exception {
        AtomicReference<String> clicked = new AtomicReference<>();
        Hyperlink link = onFx(() -> {
            HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();
            renderer.setLinkHandler(clicked::set);
            TextFlow flow = renderer.inlineFlow("See [the mixer](mixer) for details.");
            // Ensure the renderer placed the link at the expected position.
            new Scene(flow);
            for (var child : flow.getChildren()) {
                if (child instanceof Hyperlink h) {
                    return h;
                }
            }
            return null;
        });
        assertThat(link).isNotNull();
        // Fire the link's onAction.
        onFx(() -> { link.fire(); return null; });
        assertThat(clicked.get()).isEqualTo("mixer");
    }

    @Test
    void externalLinkDoesNotInvokeHandler() throws Exception {
        AtomicReference<String> clicked = new AtomicReference<>();
        Hyperlink link = onFx(() -> {
            HelpMarkdownRenderer renderer = new HelpMarkdownRenderer();
            renderer.setLinkHandler(clicked::set);
            TextFlow flow = renderer.inlineFlow("[OpenJDK](https://openjdk.org/)");
            for (var child : flow.getChildren()) {
                if (child instanceof Hyperlink h) {
                    return h;
                }
            }
            return null;
        });
        assertThat(link).isNotNull();
        onFx(() -> { link.fire(); return null; });
        assertThat(clicked.get()).isNull();
    }

    @Test
    void inlineBoldItalicCodeProduceTextRuns() throws Exception {
        TextFlow flow = onFx(() ->
                new HelpMarkdownRenderer().inlineFlow("This is **bold** and *italic* and `code`."));

        // The flow should split into multiple text runs (not just one big Text).
        long textRuns = flow.getChildren().stream().filter(n -> n instanceof Text).count();
        assertThat(textRuns).isGreaterThan(1);
    }

    @Test
    void unclosedFenceStillRendersWithoutThrowing() throws Exception {
        Region rendered = onFx(() ->
                new HelpMarkdownRenderer().render("# Title\n\n```\nincomplete code"));
        assertThat(rendered).isNotNull();
    }
}
