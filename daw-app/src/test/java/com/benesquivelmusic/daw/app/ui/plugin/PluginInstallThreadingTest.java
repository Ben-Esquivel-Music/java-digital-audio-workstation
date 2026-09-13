package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.BlockingInstallFixture;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.plugin.*;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
final class PluginInstallThreadingTest {
    @TempDir Path directory;

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
