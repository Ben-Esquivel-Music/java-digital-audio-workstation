package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.EditorFrame;
import com.benesquivelmusic.daw.app.ui.plugin.ParameterGridPanel;
import com.benesquivelmusic.daw.app.ui.views.PluginViewContainer;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.Node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 301 acceptance — Plugin View Design Book §8.2.1 <em>"third-party
 * plugins now have editors"</em>.
 *
 * <p>A fake third-party {@link DawPlugin} (its descriptor id is NOT in
 * {@code PluginViewController}'s legacy built-in {@code switch}) overrides
 * {@code editorFactory()} to return a
 * {@link PluginEditorFactory.Declarative} over its own parameter list.
 * Activating it through
 * {@code PluginViewController#onActivateExternalPlugin(DawPlugin)} must:</p>
 *
 * <ul>
 *   <li>build an {@link EditorFrame} (the standard §6.1–§6.6 host chrome)
 *       whose body is the host-generated {@link ParameterGridPanel}
 *       (§6.2), and place it in the Workshop right pane's stable-identity
 *       {@link PluginViewContainer} via the {@code showEditorInWorkshopPane}
 *       dep (§8.2.2);</li>
 *   <li>initialise the plugin exactly once (identity-keyed
 *       initialise-once) while every activation calls {@code activate()};</li>
 *   <li>report the §6.1 breadcrumb segments — vendor and plugin name —
 *       alongside the framed editor;</li>
 *   <li>leave a live contract-editor session behind
 *       ({@code activeEditorSessionForTest()} non-null).</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ThirdPartyPluginGetsEditorTest {

    private PluginViewController controller;

    @Test
    void activatingThirdPartyPluginBuildsEditorFrameWithParameterGridInContainer() {
        runOnFxThread(() -> {
            PluginViewContainer container = new PluginViewContainer();
            List<List<String>> recordedSegments = new ArrayList<>();
            List<String> notifications = new ArrayList<>();
            controller = new PluginViewController(deps(
                    (segments, node) -> {
                        recordedSegments.add(segments);
                        container.setPluginView(node);
                    },
                    notifications));
            FakeThirdPartyPlugin fake = new FakeThirdPartyPlugin("editor");

            controller.onActivateExternalPlugin(fake);

            // onActivateExternalPlugin swallows RuntimeExceptions into the
            // notification sink — an empty log proves the happy path ran.
            assertThat(notifications)
                    .as("activation must not degrade to the error-notification path")
                    .isEmpty();

            Node content = container.getCurrentContent();
            assertThat(content)
                    .as("the host frames the third-party editor in the standard "
                            + "EditorFrame chrome and sets it on the container (§8.2.1)")
                    .isNotNull()
                    .isInstanceOf(EditorFrame.class);
            EditorFrame frame = (EditorFrame) content;
            assertThat(frame.getBody())
                    .as("a Declarative factory yields the host-generated "
                            + "parameter grid as the frame body (§6.2)")
                    .isInstanceOf(ParameterGridPanel.class);

            assertThat(fake.initializeCalls())
                    .as("first activation initialises the plugin exactly once")
                    .isEqualTo(1);
            assertThat(fake.activateCalls())
                    .as("activation calls activate()")
                    .isEqualTo(1);

            assertThat(recordedSegments)
                    .as("the framed editor is handed to the Workshop pane once per activation")
                    .hasSize(1);
            assertThat(recordedSegments.get(0))
                    .as("the §6.1 breadcrumb segments carry vendor and plugin name")
                    .contains("Test Vendor", "Fake Editor Plugin");

            assertThat(controller.activeEditorSessionForTest())
                    .as("a live contract-editor session backs the framed editor")
                    .isNotNull();

            // Second activation of the SAME instance: initialise-once
            // (identity-keyed), activation and editor opening repeat.
            controller.onActivateExternalPlugin(fake);
            assertThat(fake.initializeCalls())
                    .as("a second activation must NOT re-initialise the plugin")
                    .isEqualTo(1);
            assertThat(fake.activateCalls())
                    .as("every activation calls activate()")
                    .isEqualTo(2);
            assertThat(controller.activeEditorSessionForTest())
                    .as("a live session remains after re-activation")
                    .isNotNull();
            assertThat(notifications)
                    .as("re-activation must not degrade to the error-notification path")
                    .isEmpty();
            return null;
        });
    }

    @AfterEach
    void disposeController() {
        if (controller != null) {
            runOnFxThread(() -> {
                controller.dispose();
                return null;
            });
            controller = null;
        }
    }

    // ── Harness ───────────────────────────────────────────────────────────

    /**
     * Minimal live {@code Deps}: fixed audio facts, no-op sinks, the
     * test-provided Workshop-pane consumer, and a notification recorder so a
     * swallowed activation failure is still visible to assertions.
     */
    private static PluginViewController.Deps deps(
            BiConsumer<List<String>, Node> showEditorInWorkshopPane,
            List<String> notificationLog) {
        return new PluginViewController.Deps(
                () -> 48_000.0,
                () -> 512,
                () -> null,
                () -> { },
                () -> { },
                (status, icon) -> { },
                (level, message) -> notificationLog.add(level + ": " + message),
                showEditorInWorkshopPane,
                () -> null,
                () -> { });
    }

    /**
     * The story's third-party shape: an id no built-in switch arm knows,
     * two continuous parameters, and — per the story text — an explicit
     * {@code editorFactory()} override returning
     * {@link PluginEditorFactory.Declarative}.
     */
    private static final class FakeThirdPartyPlugin implements DawPlugin {

        private final PluginDescriptor descriptor;
        private int initializeCalls;
        private int activateCalls;

        FakeThirdPartyPlugin(String idSuffix) {
            this.descriptor = new PluginDescriptor(
                    "com.test.story301.fake." + idSuffix,
                    "Fake Editor Plugin", "1.0.0", "Test Vendor",
                    PluginType.EFFECT);
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void initialize(PluginContext context) {
            initializeCalls++;
        }

        @Override
        public void activate() {
            activateCalls++;
        }

        @Override
        public void deactivate() {
            // no-op
        }

        @Override
        public void dispose() {
            // no-op
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of(
                    new PluginParameter(1, "Gain", -24.0, 24.0, 0.0),
                    new PluginParameter(2, "Mix", 0.0, 1.0, 1.0));
        }

        /** Story 301 §8.2.1 — explicitly Declarative over the parameter list. */
        @Override
        public PluginEditorFactory editorFactory() {
            return new PluginEditorFactory.Declarative(
                    getParameters(), EditorHints.compact());
        }

        int initializeCalls() {
            return initializeCalls;
        }

        int activateCalls() {
            return activateCalls;
        }
    }
}
