package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.plugin.EditorFrame;
import com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession;
import com.benesquivelmusic.daw.app.ui.views.PluginViewContainer;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.beans.value.ChangeListener;
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
 * Story 301 acceptance — Plugin View Design Book §8.2.2 on top of the
 * story-281 invariant: swapping the focused plugin updates the
 * {@link PluginViewContainer}'s content, but the container instance itself
 * is never unmounted or rebuilt.
 *
 * <p>Two fake third-party plugins (different descriptor ids, both relying
 * on the DEFAULT {@code editorFactory()} — the §4.2 backwards-compatible
 * Declarative-over-{@code getParameters()} path) are activated in turn
 * through {@code PluginViewController#onActivateExternalPlugin(DawPlugin)}.
 * The test pins:</p>
 *
 * <ul>
 *   <li>both activations produce {@link EditorFrame} content and the second
 *       frame is a different instance ({@code currentContentProperty}
 *       changed);</li>
 *   <li>the container object identity is unchanged across the swap (strict
 *       reference identity, the {@code WorkshopPluginSwitchTest}
 *       precedent), the swap going through {@code setPluginView} only —
 *       a {@code currentContentProperty} listener registered before the
 *       second activation fires exactly once, with the new frame;</li>
 *   <li>the first session is no longer the active one and the first frame
 *       is no longer the container's content.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class FocusedEditorDoesNotRebuildContainerTest {

    private PluginViewController controller;

    @Test
    void swappingFocusedPluginTwiceKeepsContainerIdentityWhileContentChanges() {
        runOnFxThread(() -> {
            PluginViewContainer container = new PluginViewContainer();
            controller = new PluginViewController(deps(
                    (segments, node) -> container.setPluginView(node)));

            FakeThirdPartyPlugin pluginA =
                    new FakeThirdPartyPlugin("a", "Fake Plugin A");
            FakeThirdPartyPlugin pluginB =
                    new FakeThirdPartyPlugin("b", "Fake Plugin B");

            // ── First activation: plugin A ────────────────────────────────
            controller.onActivateExternalPlugin(pluginA);

            PluginViewContainer containerRef1 = container;
            int containerIdBefore = System.identityHashCode(container);
            Node content1 = container.getCurrentContent();
            PluginEditorSession firstSession =
                    controller.activeEditorSessionForTest();

            assertThat(content1)
                    .as("activating plugin A frames its editor in the container")
                    .isInstanceOf(EditorFrame.class);
            assertThat(firstSession)
                    .as("a live session backs plugin A's editor")
                    .isNotNull();

            // Listen BEFORE the second activation: the swap must surface as
            // exactly one currentContentProperty change (typed property
            // observation — never Event.getSource() identity).
            List<Node> contentChanges = new ArrayList<>();
            ChangeListener<Node> contentListener =
                    (obs, was, now) -> contentChanges.add(now);
            container.currentContentProperty().addListener(contentListener);

            // ── Second activation: plugin B ───────────────────────────────
            controller.onActivateExternalPlugin(pluginB);

            Node content2 = container.getCurrentContent();
            assertThat(content2)
                    .as("activating plugin B frames its editor in the container")
                    .isInstanceOf(EditorFrame.class);
            assertThat(content2)
                    .as("the focused-editor swap replaces the frame instance "
                            + "(currentContentProperty changed)")
                    .isNotSameAs(content1);

            // Container identity — the story-281 invariant under the new frame.
            assertThat(container)
                    .as("the PluginViewContainer instance is the same object "
                            + "across focused-plugin swaps — strict reference identity")
                    .isSameAs(containerRef1);
            assertThat(System.identityHashCode(container))
                    .as("the container identity-hash is unchanged after the swap")
                    .isEqualTo(containerIdBefore);

            assertThat(contentChanges)
                    .as("the swap fires currentContentProperty exactly once "
                            + "— one setPluginView, no unmount/rebuild churn")
                    .hasSize(1);
            assertThat(contentChanges.get(0))
                    .as("the single content change carries the new frame")
                    .isSameAs(content2);

            // Session and content handoff.
            assertThat(controller.activeEditorSessionForTest())
                    .as("the first session is no longer the active one")
                    .isNotNull()
                    .isNotSameAs(firstSession);
            assertThat(container.getCurrentContent())
                    .as("the first frame is no longer the container content")
                    .isNotSameAs(content1);
            assertThat(container.getChildren())
                    .as("the container hosts exactly the new frame — the old "
                            + "frame was swapped out through setPluginView")
                    .containsExactly(content2);

            container.currentContentProperty().removeListener(contentListener);
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

    /** Minimal live {@code Deps}: fixed audio facts and no-op sinks. */
    private static PluginViewController.Deps deps(
            BiConsumer<List<String>, Node> showEditorInWorkshopPane) {
        return new PluginViewController.Deps(
                () -> 48_000.0,
                () -> 512,
                () -> null,
                () -> { },
                () -> { },
                (status, icon) -> { },
                (level, message) -> { },
                showEditorInWorkshopPane,
                () -> null,
                () -> { });
    }

    /**
     * A third-party plugin relying on the DEFAULT {@code editorFactory()}
     * (§4.2 — Declarative over {@code getParameters()} for free). Each
     * instance gets its own descriptor id so A and B are distinct plugins.
     */
    private static final class FakeThirdPartyPlugin implements DawPlugin {

        private final PluginDescriptor descriptor;

        FakeThirdPartyPlugin(String idSuffix, String name) {
            this.descriptor = new PluginDescriptor(
                    "com.test.story301.fake." + idSuffix,
                    name, "1.0.0", "Test Vendor", PluginType.EFFECT);
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void initialize(PluginContext context) {
            // no-op
        }

        @Override
        public void activate() {
            // no-op
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
    }
}
