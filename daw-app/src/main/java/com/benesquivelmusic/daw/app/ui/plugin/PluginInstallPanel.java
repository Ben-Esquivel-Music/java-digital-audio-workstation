package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.plugin.PluginJarScanner.JarInspection;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The §6.8 inspect-and-install content for a single dropped / picked JAR (Plugin
 * View Design Book §6.8, §8.4). Built from a {@link JarInspection}, it presents
 * one of three faces and installs every plugin without the user ever typing or
 * seeing a class name on the manifest path:
 *
 * <ul>
 *   <li><strong>Valid manifest</strong> — a header, the common vendor (or
 *       "Multiple vendors"), one row per declared plugin
 *       ({@code name  vVERSION  TYPE · Category}), a footprint line, and a primary
 *       install button whose label reflects the count ("Install" / "Install both"
 *       / "Install N plugins"). Installing registers <em>every</em> declared
 *       plugin. There is no class-name field anywhere on this path.</li>
 *   <li><strong>No manifest</strong> — the de-emphasised legacy fallback: a
 *       class-name {@link TextField} and an "Add" button (story 304 removes this
 *       fallback later).</li>
 *   <li><strong>Manifest present but invalid, or JAR unreadable</strong> — the
 *       reader's error lines above the same de-emphasised class-name fallback,
 *       so an I/O failure is never misreported as a missing manifest.</li>
 * </ul>
 */
public final class PluginInstallPanel extends VBox {

    private static final double CONTENT_SPACING = 12;
    private static final double CONTENT_PADDING = 16;
    private static final double ROW_SPACING = 8;
    private static final double ICON_SIZE = 16;

    private final Button primaryButton;
    private final TextField classNameField;
    private Runnable onCloseRequest;

    /**
     * Builds the install content for {@code inspection}.
     *
     * @param inspection  the JAR inspection; must not be {@code null}
     * @param registry    the registry to install into; must not be {@code null}
     * @param onInstalled run after a successful install (e.g. refresh a list);
     *                    must not be {@code null}
     */
    public PluginInstallPanel(JarInspection inspection, PluginRegistry registry, Runnable onInstalled) {
        Objects.requireNonNull(inspection, "inspection must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(onInstalled, "onInstalled must not be null");

        setSpacing(CONTENT_SPACING);
        setPadding(new Insets(CONTENT_PADDING));
        getStyleClass().add("plugin-install-panel");

        String jarName = jarName(inspection.jar());

        if (inspection.manifests().isValid()) {
            this.classNameField = null;
            this.primaryButton = buildManifestPath(inspection, registry, onInstalled, jarName);
        } else {
            // No manifest, a manifest that failed to parse/validate, or an
            // unreadable JAR: all land on the de-emphasised class-name fallback
            // (story 304 removes it).
            this.classNameField = new TextField();
            this.primaryButton = buildFallbackPath(inspection, registry, onInstalled, jarName);
        }
    }

    /**
     * Wraps a {@code PluginInstallPanel} in a MEDIUM {@link DawgDialog} titled
     * "Install plugin"; the panel's own Cancel / install buttons drive the close.
     *
     * @param inspection  the JAR inspection; must not be {@code null}
     * @param registry    the registry to install into; must not be {@code null}
     * @param onInstalled run after a successful install; must not be {@code null}
     */
    public static void showDialog(JarInspection inspection, PluginRegistry registry, Runnable onInstalled) {
        DawgDialog<Void> dialog = new DawgDialog<>();
        dialog.setTitle("Install plugin");
        dialog.setHeaderText("Install plugin");
        dialog.sized(DawgDialog.Size.MEDIUM);
        PluginInstallPanel panel = new PluginInstallPanel(inspection, registry, onInstalled);
        panel.setOnCloseRequest(dialog::close);
        dialog.getDialogPane().setContent(panel);
        dialog.showAndWait();
    }

    /** Sets the callback the panel invokes to request its containing dialog close. */
    void setOnCloseRequest(Runnable onCloseRequest) {
        this.onCloseRequest = onCloseRequest;
    }

    /** The primary action button (install on the manifest path, Add on the fallback). */
    Button primaryButtonForTest() {
        return primaryButton;
    }

    /** The class-name field, or {@code null} on the manifest (no-typing) path. */
    TextField classNameFieldForTest() {
        return classNameField;
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
        List<String> failures = new ArrayList<>();
        for (PluginManifest manifest : manifests) {
            try {
                registry.register(new ExternalPluginEntry(jar, manifest.pluginClass()));
            } catch (PluginLoadException e) {
                failures.add(manifest.pluginClass() + " — " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            DawgDialog.error("Install plugin",
                    "Some plugins could not be installed:\n" + String.join("\n", failures))
                    .showAndWait();
        }
        onInstalled.run();
        requestClose();
    }

    // ── Fallback (no / invalid manifest) path ─────────────────────────────────

    private Button buildFallbackPath(JarInspection inspection, PluginRegistry registry,
                                     Runnable onInstalled, String jarName) {
        Label title = new Label(jarName);
        title.getStyleClass().add("plugin-install-title");

        getChildren().add(title);

        // Surface the reader's errors both for a manifest that failed to
        // parse/validate AND for a JAR that could not be read at all — only a
        // readable JAR genuinely lacking a manifest gets the "no manifest" copy.
        if (inspection.manifests() instanceof PluginManifestReader.BundleResult.Invalid invalid
                && (inspection.manifestPresent() || !inspection.jarReadable())) {
            for (String error : invalid.errors()) {
                Label errorLabel = new Label(error);
                errorLabel.setWrapText(true);
                errorLabel.getStyleClass().add("plugin-manager-notice");
                getChildren().add(errorLabel);
            }
            getChildren().add(info((inspection.jarReadable()
                    ? "This JAR's manifest could not be read. "
                    : "This JAR could not be read. ")
                    + "Install by class name instead:"));
        } else {
            getChildren().add(info("This JAR has no daw-plugin.json manifest. "
                    + "Enter the plugin's fully-qualified class name to install it:"));
        }

        classNameField.setPromptText("e.g. com.example.MyReverbPlugin");

        Button add = new Button("Add");
        add.getStyleClass().addAll("dawg-button", "size-default", "primary");
        add.setOnAction(_ -> addByClassName(inspection.jar(), registry, onInstalled));

        getChildren().addAll(classNameField, footer(add));
        return add;
    }

    private void addByClassName(Path jar, PluginRegistry registry, Runnable onInstalled) {
        String className = classNameField.getText();
        if (className == null || className.isBlank()) {
            DawgDialog.error("Install plugin",
                    "Please enter the plugin's fully-qualified class name.").showAndWait();
            return;
        }
        try {
            registry.register(new ExternalPluginEntry(jar, className.strip()));
        } catch (PluginLoadException e) {
            DawgDialog.error("Install plugin",
                    "Failed to load plugin:\n" + e.getMessage()).showAndWait();
            return;
        }
        onInstalled.run();
        requestClose();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private HBox footer(Button primary) {
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        cancel.setOnAction(_ -> requestClose());
        HBox footer = new HBox(ROW_SPACING, cancel, primary);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("plugin-install-row");
        return footer;
    }

    private static Label info(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("plugin-manager-info");
        return label;
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
