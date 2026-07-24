package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;

import javafx.application.Platform;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 — JAR inspection runs on a virtual thread and marshals back via the
 * {@link FxDispatcher}; while a scan is in flight a busy row with a spinner shows
 * (never a modal "loading" dialog), the FX thread stays responsive, and on
 * completion the row is gone and the consumer ran on the FX thread.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NonBlockingScanShowsRowSpinnerTest {

    @TempDir
    Path tempDir;

    private FxDispatcher dispatcher;

    @BeforeEach
    void installDispatcher() {
        dispatcher = new FxDispatcher();
        FxDispatcher.installDefault(dispatcher);
        runOnFxThread(() -> {
            dispatcher.start();
            return null;
        });
    }

    @AfterEach
    void disposeDispatcher() {
        runOnFxThread(() -> {
            dispatcher.dispose();
            return null;
        });
        FxDispatcher.installDefault(null);
    }

    @Test
    void busySpinnerShowsDuringScanWhileFxStaysResponsive() throws Exception {
        PluginRegistry registry = new PluginRegistry();
        PluginBrowser browser = runOnFxThread(() -> {
            PluginBrowser b = new PluginBrowser(registry, true, "Test / Insert 1");
            b.setFxDispatcher(dispatcher);
            return b;
        });

        Path jar = tempDir.resolve("blocking.jar"); // path unused — inspector is injected
        CountDownLatch inspectorLatch = new CountDownLatch(1);
        JarInspection canned = new JarInspection(jar, true, false,
                new PluginManifestReader.BundleResult.Invalid(List.of("no manifest")), 0, 0, 0);
        Function<Path, JarInspection> blockingInspector = path -> {
            try {
                inspectorLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return canned;
        };
        PluginJarScanner scanner = new PluginJarScanner(dispatcher, blockingInspector);

        AtomicBoolean consumerRanOnFx = new AtomicBoolean(false);
        CountDownLatch consumerDone = new CountDownLatch(1);

        Thread scanThread = runOnFxThread(() ->
                browser.runScanForTest(jar, scanner, inspection -> {
                    consumerRanOnFx.set(Platform.isFxApplicationThread());
                    consumerDone.countDown();
                }));

        assertThat(scanThread.isVirtual())
                .as("JAR inspection runs OFF the FX thread on a virtual thread").isTrue();

        // ── During the scan (latch held): busy row visible with a spinner ────
        runOnFxThread(() -> {
            Region busy = browser.busyRowForTest();
            assertThat(busy.isVisible())
                    .as("a busy row is shown while the scan is in flight").isTrue();
            assertThat(busy.getStyleClass()).contains("plugin-row-busy");
            assertThat(PluginNodes.hasProgressIndicator(busy))
                    .as("the busy row carries a spinner").isTrue();
            return null;
        });

        // ── The FX thread stays responsive (a probe completes) ───────────────
        AtomicBoolean probeRan = new AtomicBoolean(false);
        runOnFxThread(() -> {
            probeRan.set(true);
            return null;
        });
        assertThat(probeRan)
                .as("the FX thread is not blocked by the in-flight scan").isTrue();

        // ── Release the scan; the consumer marshals back to the FX thread ────
        inspectorLatch.countDown();
        assertThat(consumerDone.await(5, TimeUnit.SECONDS))
                .as("the completion consumer ran").isTrue();
        scanThread.join(2_000);
        runOnFxThread(() -> null); // FX barrier

        assertThat(consumerRanOnFx)
                .as("the completion consumer ran on the FX thread").isTrue();
        runOnFxThread(() -> {
            assertThat(browser.busyRowForTest().isVisible())
                    .as("the busy row is gone after the scan completes").isFalse();
            return null;
        });
    }
}
