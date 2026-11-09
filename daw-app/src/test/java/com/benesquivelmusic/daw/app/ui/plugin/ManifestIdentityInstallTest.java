package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story 334 (studio-engineer ruling E20) — a plugin's identity is its manifest
 * id, so the install panel refuses a plugin whose loaded descriptor disagrees
 * with its manifest, and a bundle that declares one id for more than one
 * class, each with the ruled words; and a manifest whose id is installed
 * already says, before the engineer confirms, which entry the install
 * replaces — which the install then does, the replaced catalogue instance
 * released off the JavaFX application thread (critique J9).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ManifestIdentityInstallTest {

    private static final String DIALOG_TITLE = "Install plugin";

    private final List<String> errorMessages = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> dialogFailure = new AtomicReference<>();
    private final ListChangeListener<Window> dismissErrors = change -> {
        while (change.next()) {
            for (Window window : change.getAddedSubList()) {
                if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                    Platform.runLater(() -> {
                        try {
                            DialogPane pane = (DialogPane) window.getScene().getRoot();
                            Node message = pane.getContent().lookup(".dawg-dialog-message");
                            errorMessages.add(message instanceof Label label ? label.getText()
                                    : "<not an error dialog: " + pane.getContent() + ">");
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

    private PluginRegistry registry;

    @BeforeEach
    void watchForErrorDialogs() {
        registry = new PluginRegistry();
        runOnFxThread(() -> {
            Window.getWindows().addListener(dismissErrors);
            return null;
        });
    }

    @AfterEach
    void stopWatching() {
        runOnFxThread(() -> {
            Window.getWindows().removeListener(dismissErrors);
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage stage && DIALOG_TITLE.equals(stage.getTitle())) {
                    window.hide();
                }
            }
            return null;
        });
        registry.disposeAll();
    }

    @Test
    void aMismatchedManifestIsRefusedWithTheExactText() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")));
        TrackingPlugin loaded = new TrackingPlugin(descriptor("com.example.comp2", "Comp X", "1.2.0"));
        TrackingClassLoader loader = new TrackingClassLoader();
        AtomicInteger loads = new AtomicInteger();

        PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                entry -> {
                    loads.incrementAndGet();
                    return new ExternalPluginLoader.LoadResult(loaded, loader);
                }));
        runOnFxThread(() -> {
            panel.primaryButtonForTest().fire();
            return null;
        });
        panel.installationForTest().get(10, TimeUnit.SECONDS);

        assertThat(dialogFailure).hasNullValue();
        assertThat(loads).as("the plugin was loaded, so its descriptor could be checked").hasValue(1);
        assertThat(errorMessages).singleElement().isEqualTo("Some plugins could not be installed:\n"
                + "comp-x.jar was not installed: CompX reports the id com.example.comp2 and version 1.2.0, but its"
                + " manifest declares com.example.comp 1.2.0. A plugin's manifest and its descriptor must agree.");
        assertThat(runOnFxThread(() -> registry.getEntries())).as("nothing is installed").isEmpty();
        assertThat(loaded.disposals).as("the refused plugin is released").hasValue(1);
        assertThat(loaded.disposedOnFx).isFalse();
        assertThat(loader.closes).as("then its loader closes").hasValue(1);
        assertThat(loader.closedOnFx).isFalse();
    }

    /**
     * Story 334 (the final review's NIT-1, swept to the install panel's
     * release loop): refused plugins whose {@code dispose()} all throw one
     * shared exception instance — as a library's stackless exception is —
     * are every one released with its loader closed, and the installation
     * completes with that exception. {@link Throwable} refuses to suppress an
     * exception into itself, so collecting the second throw into the first
     * would throw out of the loop: the third plugin would stay unreleased and
     * the installation would never complete, so the panel could not install
     * again.
     */
    @Test
    void refusedPluginsThatThrowOneSharedExceptionAsTheyAreReleasedAreAllReleased() throws Exception {
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")),
                new PluginManifest("com.example.Limiter", descriptor("com.example.limiter", "Limiter", "1.0")));
        IllegalStateException shared = new IllegalStateException("the plugin library is already closed");
        Map<String, TrackingPlugin> refused = Map.of(
                "com.example.CompX", refusedThrowing(shared, "com.example.comp2"),
                "com.example.Gate", refusedThrowing(shared, "com.example.gate2"),
                "com.example.Limiter", refusedThrowing(shared, "com.example.limiter2"));
        List<TrackingClassLoader> loaders = new CopyOnWriteArrayList<>();

        PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                entry -> {
                    TrackingClassLoader loader = new TrackingClassLoader();
                    loaders.add(loader);
                    return new ExternalPluginLoader.LoadResult(refused.get(entry.className()), loader);
                }));
        runOnFxThread(() -> {
            panel.primaryButtonForTest().fire();
            return null;
        });
        Throwable completion = catchThrowable(() -> panel.installationForTest().get(10, TimeUnit.SECONDS));

        assertThat(refused.values()).as("every refused plugin is released once")
                .allSatisfy(plugin -> assertThat(plugin.disposals).hasValue(1));
        assertThat(loaders).as("then every loader closes").hasSize(3)
                .allSatisfy(loader -> assertThat(loader.closes).hasValue(1));
        assertThat(completion).as("the installation completes with the plugins' exception")
                .isInstanceOf(ExecutionException.class)
                .hasCauseReference(shared);
        assertThat(shared.getSuppressed()).as("never suppressed into itself").isEmpty();
        assertThat(dialogFailure).hasNullValue();
        assertThat(errorMessages).as("the one refusal dialog").hasSize(1);
    }

    private static TrackingPlugin refusedThrowing(IllegalStateException disposalFailure, String reportedId) {
        return new TrackingPlugin(descriptor(reportedId, "Mismatched", "1.0"), () -> {
            throw disposalFailure;
        });
    }

    @Test
    void aDuplicateIdIsRefusedWithTheExactText() throws Exception {
        JarInspection twoClasses = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.CompY", descriptor("com.example.comp", "Comp Y", "1.2.0")));
        AtomicInteger installed = new AtomicInteger();

        runOnFxThread(() -> {
            PluginInstallPanel.showDialog(twoClasses, registry, installed::incrementAndGet);
            return null;
        });

        assertThat(dialogFailure).hasNullValue();
        assertThat(errorMessages).as("the one error dialog, and no install panel").singleElement()
                .isEqualTo("comp-x.jar was not installed: its manifest declares the id com.example.comp for two"
                        + " plugins (CompX, CompY). Each plugin needs its own id.");
        assertThat(installed).hasValue(0);
        assertThat(runOnFxThread(() -> registry.getEntries())).isEmpty();

        // R11 (d): more than two classes, and one sentence per repeated id, in manifest order.
        JarInspection twoIds = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")),
                new PluginManifest("com.example.CompY", descriptor("com.example.comp", "Comp Y", "1.2.0")),
                new PluginManifest("com.example.Outer$GateTwo", descriptor("com.example.gate", "Gate 2", "1.0")),
                new PluginManifest("com.example.CompZ", descriptor("com.example.comp", "Comp Z", "1.2.0")));
        assertThat(PluginInstallPanel.duplicateIdMessage(twoIds)).contains(
                "comp-x.jar was not installed: its manifest declares the id com.example.comp for 3 plugins"
                        + " (CompX, CompY, CompZ). Each plugin needs its own id.\n"
                        + "comp-x.jar was not installed: its manifest declares the id com.example.gate for two"
                        + " plugins (Gate, GateTwo). Each plugin needs its own id.");
        assertThat(PluginInstallPanel.duplicateIdMessage(inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")))))
                .as("one id per class: nothing to refuse").isEmpty();
    }

    @Test
    void anInstalledIdShowsTheReplacesRow() throws Exception {
        TrackingPlugin installedCatalogue = new TrackingPlugin(descriptor("com.example.comp", "Comp X", "1.1.0"));
        TrackingClassLoader installedLoader = new TrackingClassLoader();
        registry.install(new InstalledPlugin(new ExternalPluginEntry(Path.of("comp-x-1.1.jar"), "com.example.CompX"),
                        new PluginManifest("com.example.CompX", installedCatalogue.getDescriptor()), "comp-x-1.1.jar"),
                new ExternalPluginLoader.LoadResult(installedCatalogue, installedLoader));
        JarInspection inspection = inspection("comp-x.jar",
                new PluginManifest("com.example.CompX", descriptor("com.example.comp", "Comp X", "1.2.0")),
                new PluginManifest("com.example.Gate", descriptor("com.example.gate", "Gate", "1.0")));
        TrackingPlugin newCatalogue = new TrackingPlugin(descriptor("com.example.comp", "Comp X", "1.2.0"));
        TrackingPlugin gate = new TrackingPlugin(descriptor("com.example.gate", "Gate", "1.0"));

        PluginInstallPanel panel = runOnFxThread(() -> new PluginInstallPanel(inspection, registry, () -> { },
                entry -> new ExternalPluginLoader.LoadResult(
                        entry.className().equals("com.example.Gate") ? gate : newCatalogue,
                        new TrackingClassLoader())));

        assertThat(runOnFxThread(() -> panel.lookupAll(".plugin-install-replaces").stream()
                .map(node -> ((Label) node).getText()).toList()))
                .as("only the row of the installed id says what it replaces")
                .containsExactly("Replaces Comp X 1.1.0");

        runOnFxThread(() -> {
            panel.primaryButtonForTest().fire();
            return null;
        });
        panel.installationForTest().get(10, TimeUnit.SECONDS);

        assertThat(errorMessages).isEmpty();
        assertThat(runOnFxThread(() -> registry.installedJar("com.example.comp").map(InstalledPlugin::version)))
                .as("one entry per id: the install replaced it").contains("1.2.0");
        assertThat(runOnFxThread(() -> registry.getEntries())).hasSize(2);
        assertThat(installedCatalogue.disposals).as("the replaced catalogue instance is released").hasValue(1);
        assertThat(installedCatalogue.disposedOnFx).isFalse();
        assertThat(installedLoader.closes).hasValue(1);
        assertThat(installedLoader.closedOnFx).isFalse();
        assertThat(newCatalogue.disposals).as("the installed one is kept").hasValue(0);
    }

    private static JarInspection inspection(String jarName, PluginManifest... manifests) {
        return new JarInspection(Path.of(jarName), true, true,
                new PluginManifestReader.BundleResult.Valid(List.of(manifests)), manifests.length, 0, 0);
    }

    private static PluginDescriptor descriptor(String id, String name, String version) {
        return new PluginDescriptor(id, name, version, "Example Audio", PluginType.EFFECT);
    }

    private static final class TrackingPlugin implements DawPlugin {
        private final PluginDescriptor descriptor;
        private final Runnable lastStepOfDispose;
        final AtomicInteger disposals = new AtomicInteger();
        volatile boolean disposedOnFx;

        TrackingPlugin(PluginDescriptor descriptor) {
            this(descriptor, () -> { });
        }

        /** A plugin whose {@code dispose()} runs {@code lastStepOfDispose} once it has counted the disposal. */
        TrackingPlugin(PluginDescriptor descriptor, Runnable lastStepOfDispose) {
            this.descriptor = descriptor;
            this.lastStepOfDispose = lastStepOfDispose;
        }

        @Override public PluginDescriptor getDescriptor() { return descriptor; }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() {
            disposedOnFx = Platform.isFxApplicationThread();
            disposals.incrementAndGet();
            lastStepOfDispose.run();
        }
    }

    private static final class TrackingClassLoader extends URLClassLoader {
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean closedOnFx;

        TrackingClassLoader() {
            super(new URL[0]);
        }

        @Override public void close() throws IOException {
            closedOnFx = Platform.isFxApplicationThread();
            closes.incrementAndGet();
            super.close();
        }
    }
}
