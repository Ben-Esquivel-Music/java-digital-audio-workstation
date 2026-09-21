package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.BlockingInstallFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InitializerErrorInstallFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.InstallFixturePluginC;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.*;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
final class PluginInstallThreadingTest {
    @TempDir Path directory;

    @Test
    void initializerErrorCompletesInstallationRecoversTheButtonAndRetainsSuccessfulLoads() throws Exception {
        var broken = new PluginManifest(InitializerErrorInstallFixture.class.getName(),
                new PluginDescriptor("test.initializer.failure", "Broken initializer", "1", "Test", PluginType.EFFECT));
        Path jar = PluginTestJars.buildJar(directory, "initializer-error.jar",
                List.of(PluginTestJars.manifest(InstallFixturePluginC.class), broken),
                List.of(InstallFixturePluginC.class, InitializerErrorInstallFixture.class));
        var inspection = new PluginJarScanner(null).scanBlocking(jar);
        var registry = new PluginRegistry();
        var completions = new AtomicInteger();
        var failures = new ArrayList<String>();
        var dialogFailure = new AtomicReference<Throwable>();
        ListChangeListener<Window> dismissErrors = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && stage.getTitle().equals("Install plugin")) {
                        Platform.runLater(() -> {
                            try {
                                var pane = (DialogPane) window.getScene().getRoot();
                                failures.add(pane.getContent().lookupAll(".label").stream()
                                        .filter(Label.class::isInstance).map(Label.class::cast)
                                        .map(Label::getText).collect(Collectors.joining("\n")));
                            } catch (Throwable failure) {
                                dialogFailure.compareAndSet(null, failure);
                            } finally {
                                window.hide();
                            }
                        });
                    }
                }
            }
        };
        var panel = runOnFxThread(() -> {
            Window.getWindows().addListener(dismissErrors);
            return new PluginInstallPanel(inspection, registry, completions::incrementAndGet);
        });
        try {
            var first = runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return panel.installationForTest();
            });
            first.get(10, TimeUnit.SECONDS);
            assertThat(dialogFailure).hasNullValue();
            runOnFxThread(() -> {
                assertThat(panel.primaryButtonForTest().isDisabled()).isFalse();
                assertThat(failures).singleElement().asString().contains("installation initializer failed");
                assertThat(registry.getEntries()).singleElement()
                        .satisfies(entry -> assertThat(entry.className()).isEqualTo(InstallFixturePluginC.class.getName()));
                return null;
            });
            var retry = runOnFxThread(() -> {
                panel.primaryButtonForTest().fire();
                return panel.installationForTest();
            });
            assertThat(retry).isNotSameAs(first);
            retry.get(10, TimeUnit.SECONDS);
            assertThat(dialogFailure).hasNullValue();
            assertThat(completions).hasValue(2);
            assertThat(runOnFxThread(() -> panel.primaryButtonForTest().isDisabled())).isFalse();
        } finally {
            runOnFxThread(() -> {
                Window.getWindows().removeListener(dismissErrors);
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && stage.getTitle().equals("Install plugin")) window.hide();
                }
                registry.disposeAll();
                return null;
            });
        }
        assertThat(registry.getEntries()).isEmpty();
        Files.move(jar, directory.resolve("released.jar"));
    }

    @Test
    void aBlockingConstructorNeverBlocksFxAndRegistrationCompletesOnFx() throws Exception {
        BlockingInstallFixture.block = false;
        Path jar = PluginTestJars.buildJar(directory, "blocking.jar",
                List.of(PluginTestJars.manifest(BlockingInstallFixture.class)), List.of(BlockingInstallFixture.class));
        var inspection = new PluginJarScanner(null).scanBlocking(jar);
        var registry = new PluginRegistry();
        var callbackOnFx = new AtomicBoolean();
        BlockingInstallFixture.started = new CountDownLatch(1);
        BlockingInstallFixture.release = new CountDownLatch(1);
        BlockingInstallFixture.constructedOnFx = true;
        BlockingInstallFixture.block = true;
        try {
            var panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry,
                    () -> callbackOnFx.set(Platform.isFxApplicationThread())));
            runOnFxThread(() -> { panel.primaryButtonForTest().fire(); return null; });
            assertThat(BlockingInstallFixture.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runOnFxThread(() -> "FX is responsive")).isEqualTo("FX is responsive");
            assertThat(BlockingInstallFixture.constructedOnFx).isFalse();
            assertThat(registry.getEntries()).isEmpty();
            BlockingInstallFixture.release.countDown();
            panel.installationForTest().get(10, TimeUnit.SECONDS);
            assertThat(registry.getEntries()).hasSize(1);
            assertThat(callbackOnFx).isTrue();
        } finally {
            BlockingInstallFixture.block = false;
            BlockingInstallFixture.release.countDown();
            registry.disposeAll();
        }
    }

}
