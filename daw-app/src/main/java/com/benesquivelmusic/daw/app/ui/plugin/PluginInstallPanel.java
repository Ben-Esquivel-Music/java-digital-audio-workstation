package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.DialogDismissibility;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The §6.8 inspect-and-install content for a single dropped / picked JAR (Plugin
 * View Design Book §6.8, §8.4, §8.5.2). Built from a {@link JarInspection}, it
 * renders the manifest path only — story 304 removed the legacy class-name
 * fallback, so a JAR has exactly two outcomes and no class-name input exists
 * anywhere:
 *
 * <ul>
 *   <li><strong>Valid manifest</strong> — a header, the common vendor (or
 *       "Multiple vendors"), one row per declared plugin
 *       ({@code name  vVERSION  TYPE · Category}), a footprint line, and a primary
 *       install button whose label reflects the count ("Install" / "Install both"
 *       / "Install N plugins"). Installing registers <em>every</em> declared
 *       plugin. There is no class-name field anywhere on this path.</li>
 *   <li><strong>Anything else</strong> — no manifest, a manifest that failed to
 *       parse/validate, or an unreadable JAR — is rejected by {@link #showDialog}
 *       via {@code DawgDialog.error(...)} with the case-specific
 *       {@link #rejectionMessage} copy (story 304, §8.5.2); the panel itself is
 *       never constructed for an invalid inspection.</li>
 * </ul>
 */
public final class PluginInstallPanel extends VBox {

    private static final double CONTENT_SPACING = 12;
    private static final double CONTENT_PADDING = 16;
    private static final double ROW_SPACING = 8;
    private static final double ICON_SIZE = 16;

    private final Button primaryButton;
    private final PluginLoader pluginLoader;
    private Runnable onCloseRequest;
    private CompletableFuture<Void> installation = CompletableFuture.completedFuture(null);
    private InstallationAttempt activeInstallation;

    CompletableFuture<Void> installationForTest() { return installation; }

    private record PreparedInstall(ExternalPluginEntry entry, ExternalPluginLoader.LoadResult loaded) { }

    @FunctionalInterface
    interface PluginLoader {
        ExternalPluginLoader.LoadResult load(ExternalPluginEntry entry) throws PluginLoadException;
    }

    private static final class InstallationAttempt {
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        volatile boolean cancelled;
    }

    /**
     * Builds the install content for {@code inspection}.
     *
     * @param inspection  the JAR inspection; must carry a valid manifest bundle
     *                    and must not be {@code null}
     * @param registry    the registry to install into; must not be {@code null}
     * @param onInstalled run after a successful install (e.g. refresh a list);
     *                    must not be {@code null}
     * @throws IllegalArgumentException if {@code inspection} does not carry a
     *                                  valid manifest bundle
     */
    public PluginInstallPanel(JarInspection inspection, PluginRegistry registry, Runnable onInstalled) {
        this(inspection, registry, onInstalled, ExternalPluginLoader::loadWithClassLoader);
    }

    PluginInstallPanel(JarInspection inspection, PluginRegistry registry, Runnable onInstalled,
                       PluginLoader pluginLoader) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(onInstalled, "onInstalled must not be null");
        this.pluginLoader = Objects.requireNonNull(pluginLoader, "pluginLoader must not be null");
        if (!inspection.manifests().isValid()) {
            throw new IllegalArgumentException(
                    "PluginInstallPanel requires a valid manifest bundle. "
                    + "Use PluginInstallPanel.showDialog(...) to reject invalid inspections with a user-facing message.");
        }

        setSpacing(CONTENT_SPACING);
        setPadding(new Insets(CONTENT_PADDING));
        getStyleClass().add("plugin-install-panel");

        this.primaryButton =
                buildManifestPath(inspection, registry, onInstalled, jarName(inspection.jar()));
    }

    /**
     * Wraps a {@code PluginInstallPanel} in a MEDIUM {@link DawgDialog} titled
     * "Install plugin"; the panel's own Cancel / install buttons drive the close.
     * An inspection without a valid manifest bundle never opens the panel: it is
     * rejected with a {@code DawgDialog.error(...)} carrying the case-specific
     * {@link #rejectionMessage} copy (story 304, §8.5.2).
     *
     * @param inspection  the JAR inspection; must not be {@code null}
     * @param registry    the registry to install into; must not be {@code null}
     * @param onInstalled run after a successful install; must not be {@code null}
     */
    public static void showDialog(JarInspection inspection, PluginRegistry registry, Runnable onInstalled) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(onInstalled, "onInstalled must not be null");
        if (!inspection.manifests().isValid()) {
            // story 276 — the same DawgDialog.error(...) chrome idiom as
            // installAll's partial-failure dialog.
            DawgDialog.error("Install plugin", rejectionMessage(inspection)).showAndWait();
            return;
        }
        createDialog(inspection, registry, onInstalled).showAndWait();
    }

    static DawgDialog<Void> createDialog(
            JarInspection inspection, PluginRegistry registry, Runnable onInstalled) {
        return createDialog(inspection, registry, onInstalled, ExternalPluginLoader::loadWithClassLoader);
    }

    static DawgDialog<Void> createDialog(JarInspection inspection, PluginRegistry registry,
                                         Runnable onInstalled, PluginLoader pluginLoader) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(onInstalled, "onInstalled must not be null");
        DawgDialog<Void> dialog = new DawgDialog<>();
        dialog.setTitle("Install plugin");
        dialog.setHeaderText("Install plugin");
        dialog.sized(DawgDialog.Size.MEDIUM);
        PluginInstallPanel panel = new PluginInstallPanel(inspection, registry, onInstalled, pluginLoader);
        panel.setOnCloseRequest(dialog::close);
        dialog.setOnHidden(_ -> panel.cancelInstallation());
        dialog.getDialogPane().setContent(panel);
        DialogDismissibility.installHiddenCancel(dialog);
        dialog.setResultConverter(_ -> null);
        return dialog;
    }

    /** Sets the callback the panel invokes to request its containing dialog close. */
    void setOnCloseRequest(Runnable onCloseRequest) {
        this.onCloseRequest = onCloseRequest;
    }

    /** The primary install button. */
    Button primaryButtonForTest() {
        return primaryButton;
    }

    // ── Manifest path ─────────────────────────────────────────────────────────

    private Button buildManifestPath(JarInspection inspection, PluginRegistry registry,
                                     Runnable onInstalled, String jarName) {
        List<PluginManifest> manifests = inspection.manifests().manifests();

        Label title = new Label("Inspecting " + jarName);
        title.getStyleClass().add("plugin-install-title");

        Label vendor = new Label(vendorLine(manifests));

        VBox rows = new VBox(ROW_SPACING);
        for (PluginManifest manifest : manifests) {
            rows.getChildren().add(manifestRow(manifest));
        }

        Label footprint = new Label(footprintLine(inspection));
        footprint.getStyleClass().add("plugin-manager-info");

        Button install = new Button(installLabel(manifests.size()));
        install.getStyleClass().addAll("dawg-button", "size-default", "primary");
        install.setOnAction(_ -> installAll(inspection.jar(), manifests, registry, onInstalled));

        getChildren().addAll(title, vendor, rows, footprint, footer(install));
        return install;
    }

    private Node manifestRow(PluginManifest manifest) {
        PluginDescriptor d = manifest.descriptor();
        Node icon = PluginIcons.of(d.category(), d.iconHint(), ICON_SIZE);
        Label label = new Label(d.name() + "  v" + d.version()
                + "  " + d.type().name() + " · " + d.category().displayName());
        HBox row = new HBox(ROW_SPACING, icon, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("plugin-install-row");
        return row;
    }

    private void installAll(Path jar, List<PluginManifest> manifests,
                            PluginRegistry registry, Runnable onInstalled) {
        if (!installation.isDone()) return;
        var attempt = new InstallationAttempt();
        activeInstallation = attempt;
        installation = attempt.completion;
        primaryButton.setDisable(true);
        // Virtual threads (JEP 444, final since Java 21) isolate arbitrary constructors from FX.
        Thread.ofVirtual().name("plugin-install-load").start(() -> {
            List<PreparedInstall> prepared = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (PluginManifest manifest : manifests) {
                if (attempt.cancelled) break;
                ExternalPluginEntry entry = new ExternalPluginEntry(jar, manifest.pluginClass());
                try {
                    prepared.add(new PreparedInstall(entry, pluginLoader.load(entry)));
                } catch (PluginLoadException | RuntimeException | Error failure) {
                    failures.add(manifest.pluginClass() + " — " + failure.getMessage());
                }
            }
            if (attempt.cancelled) {
                disposePrepared(prepared, attempt.completion);
            } else {
                FxDispatcher.runOnFx(() -> completeInstallation(attempt, prepared, failures, registry, onInstalled));
            }
        });
    }

    private void completeInstallation(InstallationAttempt attempt,
                                      List<PreparedInstall> prepared, List<String> failures,
                                      PluginRegistry registry, Runnable onInstalled) {
        if (attempt.cancelled) {
            disposePreparedAsync(prepared, attempt.completion);
            return;
        }
        List<PreparedInstall> rejected = new ArrayList<>();
        for (PreparedInstall candidate : prepared) {
            try {
                registry.registerLoaded(candidate.entry(), candidate.loaded());
            } catch (PluginLoadException failure) {
                failures.add(candidate.entry().className() + " — " + failure.getMessage());
                rejected.add(candidate);
            }
        }
        activeInstallation = null;
        primaryButton.setDisable(false);
        try {
            if (!failures.isEmpty()) {
                DawgDialog.error("Install plugin",
                        "Some plugins could not be installed:\n" + String.join("\n", failures))
                        .showAndWait();
            }
            onInstalled.run();
            requestClose();
        } catch (RuntimeException | Error failure) {
            attempt.completion.completeExceptionally(failure);
            throw failure;
        } finally {
            disposePreparedAsync(rejected, attempt.completion);
        }
    }

    private void cancelInstallation() {
        if (activeInstallation != null) activeInstallation.cancelled = true;
    }

    private static void disposePreparedAsync(List<PreparedInstall> prepared, CompletableFuture<Void> completion) {
        if (prepared.isEmpty()) {
            completion.complete(null);
            return;
        }
        // Virtual threads (JEP 444) keep third-party disposal and JAR closing off FX.
        Thread.ofVirtual().name("unpublished-plugin-dispose")
                .start(() -> disposePrepared(prepared, completion));
    }

    private static void disposePrepared(List<PreparedInstall> prepared, CompletableFuture<Void> completion) {
        Throwable firstFailure = null;
        for (PreparedInstall candidate : prepared) {
            try {
                candidate.loaded().plugin().dispose();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            } finally {
                ExternalPluginLoader.closeQuietly(candidate.loaded().classLoader());
            }
        }
        if (firstFailure == null) completion.complete(null);
        else completion.completeExceptionally(firstFailure);
    }

    // ── Rejection copy (no / invalid manifest, unreadable JAR) ────────────────

    /**
     * The §8.5.2 rejection copy for a JAR that cannot be installed, one case per
     * failure mode — preserving story 303's discipline that an I/O failure is
     * never misreported as a missing manifest:
     *
     * <ul>
     *   <li><strong>Unreadable JAR</strong> — a could-not-be-read headline plus
     *       the reader's error lines; no vendor-rebuild copy, because the failure
     *       is I/O, not format era.</li>
     *   <li><strong>Manifest present but invalid</strong> — the reader's
     *       validation errors under an invalid-manifest headline, closing with a
     *       request for a corrected vendor build against the current SDK.</li>
     *   <li><strong>No manifest</strong> — names the missing
     *       {@code META-INF/daw-plugin.json} requirement with the story-304
     *       predates-the-manifest-format copy.</li>
     * </ul>
     *
     * <p>Package-private + {@code static} for tests.</p>
     */
    static String rejectionMessage(JarInspection inspection) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        String jarName = jarName(inspection.jar());
        List<String> errors =
                inspection.manifests() instanceof PluginManifestReader.BundleResult.Invalid invalid
                        ? invalid.errors() : List.of();
        List<String> lines = new ArrayList<>();
        if (!inspection.jarReadable()) {
            lines.add(jarName + " could not be read.");
            lines.addAll(errors);
        } else if (inspection.manifestPresent()) {
            lines.add(jarName + "'s daw-plugin.json manifest could not be read:");
            lines.addAll(errors);
            lines.add("Ask the vendor for a corrected build against the current SDK.");
        } else {
            lines.add(jarName + " has no META-INF/daw-plugin.json manifest, "
                    + "so it cannot be installed.");
            lines.add("This plugin predates the manifest format — "
                    + "ask the vendor to rebuild against the current SDK.");
        }
        return String.join("\n", lines);
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private HBox footer(Button primary) {
        Button cancel = new Button("Cancel");
        cancel.setId("plugin-install-cancel");
        cancel.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        cancel.setOnAction(_ -> {
            cancelInstallation();
            requestClose();
        });
        HBox footer = new HBox(ROW_SPACING, cancel, primary);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("plugin-install-row");
        return footer;
    }

    private void requestClose() {
        if (onCloseRequest != null) {
            onCloseRequest.run();
        }
    }

    private static String jarName(Path jar) {
        Path name = jar.getFileName();
        return name != null ? name.toString() : jar.toString();
    }

    private static String vendorLine(List<PluginManifest> manifests) {
        Set<String> vendors = new LinkedHashSet<>();
        for (PluginManifest manifest : manifests) {
            vendors.add(manifest.descriptor().vendor());
        }
        return vendors.size() == 1 ? "Vendor: " + vendors.iterator().next() : "Multiple vendors";
    }

    private static String footprintLine(JarInspection inspection) {
        return "classes " + inspection.classFiles()
                + " · resources " + inspection.resourceFiles()
                + " · native " + inspection.nativeLibs();
    }

    private static String installLabel(int count) {
        return switch (count) {
            case 1 -> "Install";
            case 2 -> "Install both";
            default -> "Install " + count + " plugins";
        };
    }
}
