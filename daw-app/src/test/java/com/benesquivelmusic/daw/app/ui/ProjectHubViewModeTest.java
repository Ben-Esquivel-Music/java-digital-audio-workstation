package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectCard;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story-296 {@link ProjectHubView} view-mode regression: the Grid/List toggle
 * must actually change the layout. Because the centre {@code ScrollPane} is
 * {@code fitToWidth}, {@code FlowPane.prefWrapLength} is ignored, so the
 * single-column List layout is forced by binding each card's preferred width to
 * the grid width — selecting List binds it, selecting Grid releases it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectHubViewModeTest {

    @TempDir
    Path tempRoot;

    private FxDispatcher dispatcher;

    @BeforeEach
    void installDispatcher() throws Exception {
        dispatcher = new FxDispatcher();
        FxDispatcher.installDefault(dispatcher);
        runOnFxThread(dispatcher::start);
    }

    @AfterEach
    void disposeDispatcher() throws Exception {
        runOnFxThread(dispatcher::dispose);
        FxDispatcher.installDefault(null);
    }

    @Test
    void listToggleBindsCardWidthAndGridToggleReleasesIt() throws Exception {
        List<Path> recent = List.of(writeProject("Song A"), writeProject("Song B"));

        AtomicReference<ProjectHubView> hubRef = new AtomicReference<>();
        runOnFxThread(() -> hubRef.set(new ProjectHubView(
                recent,
                new ProjectHealthScanner(dispatcher),
                dispatcher,
                p -> { },
                p -> { })));
        ProjectHubView hub = hubRef.get();
        assertThat(hub).isNotNull();

        // Grid is the default view: card widths are free (unbound).
        runOnFxThread(() -> {
            assertThat(hub.recentCards()).isNotEmpty();
            for (ProjectCard card : hub.recentCards()) {
                assertThat(card.prefWidthProperty().isBound())
                        .as("grid mode leaves card width unbound")
                        .isFalse();
            }
        });

        // Switching to List binds each card's preferred width to the grid width
        // (forcing one card per row).
        runOnFxThread(() -> {
            hub.listToggle().setSelected(true);
            for (ProjectCard card : hub.recentCards()) {
                assertThat(card.prefWidthProperty().isBound())
                        .as("list mode binds card width → single column")
                        .isTrue();
            }
        });

        // Switching back to Grid releases the binding and restores the computed
        // tile width.
        runOnFxThread(() -> {
            hub.gridToggle().setSelected(true);
            for (ProjectCard card : hub.recentCards()) {
                assertThat(card.prefWidthProperty().isBound())
                        .as("grid mode releases the list-column binding")
                        .isFalse();
                assertThat(card.getPrefWidth()).isEqualTo(Region.USE_COMPUTED_SIZE);
            }
        });

        for (Thread scan : hub.pendingScans()) {
            scan.join(2_000);
        }
        runOnFxThread(() -> { /* barrier — let posted scan consumers run */ });
    }

    private Path writeProject(String name) throws IOException {
        Path dir = Files.createDirectory(tempRoot.resolve("proj-" + UUID.randomUUID()));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata>
                        <name>%s</name>
                        <created-at>2026-01-01T00:00:00Z</created-at>
                        <last-modified>2026-01-01T00:00:00Z</last-modified>
                    </metadata>
                </daw-project>
                """.formatted(name);
        Files.writeString(dir.resolve("project.daw"), xml);
        return dir;
    }

    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
